package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamingExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun groups(count: Int) = (0 until count).map { listOf(Path.of("unused") to "$it") }

    @Test fun `first file is visible before next class completes or coordinator consumes a batch`() {
        val output = ExportDirectory(temporary.newFolder().toPath().resolve("sources"))
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val engine = ClassDecompiler { _, name, _ ->
            if (name == "1") { secondStarted.countDown(); check(releaseSecond.await(5, TimeUnit.SECONDS)) }
            DecompiledClass("class C$name {}")
        }
        BatchParallelDecompiler(groups(40), engine, 1, {}, writeSource = { i, source, check ->
            output.source("C$i.java", source, check)
        }).use { pipeline ->
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                assertEquals("class C0 {}", Files.readString(output.root.resolve("C0.java")))
                assertFalse(Files.exists(output.root.resolve("C1.java")))
                assertTrue(pipeline.next().result!!.issues.isEmpty())
            } finally { releaseSecond.countDown() }
            repeat(39) { pipeline.next() }
        }
        assertEquals(40L, Files.list(output.root).use { it.count() })
    }

    @Test fun `worker claims a third batch and reports progress while first batch is blocked`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val laterWritten = CountDownLatch(80)
        val output = ExportDirectory(temporary.newFolder().toPath().resolve("sources"))
        val engine = ClassDecompiler { _, name, _ ->
            if (name == "0") { firstStarted.countDown(); check(releaseFirst.await(5, TimeUnit.SECONDS)) }
            DecompiledClass("class C$name {}")
        }
        BatchParallelDecompiler(groups(120), engine, 2, {}, writeSource = { i, source, check ->
            output.source("C$i.java", source, check)
            if (i >= 40) laterWritten.countDown()
        }).use { pipeline ->
            try {
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                assertTrue("Later batch must not stall behind the first class", laterWritten.await(5, TimeUnit.SECONDS))
                assertEquals("class C119 {}", Files.readString(output.root.resolve("C119.java")))
                repeat(80) { assertTrue(pipeline.next().index >= 40) }
                assertFalse(Files.exists(output.root.resolve("C0.java")))
            } finally { releaseFirst.countDown() }
            repeat(40) { pipeline.next() }
        }
    }

    @Test fun `cancel while writing removes partial file and retains previous complete source`() {
        val output = ExportDirectory(temporary.newFolder().toPath().resolve("sources"))
        output.source("demo/Good.java", "complete") {}
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            output.source("demo/Canceled.java", "x".repeat(40000)) { if (++chunks == 2) throw CancellationException() }
        }
        assertEquals("complete", Files.readString(output.root.resolve("demo/Good.java")))
        assertFalse(Files.exists(output.root.resolve("demo/Canceled.java")))
        Files.walk(output.root).use { paths -> assertFalse(paths.anyMatch { it.toString().endsWith(".part") }) }
    }

    @Test fun `output preserves UTF8 and refuses overwrites traversal and symlink parents`() {
        val parent = temporary.newFolder().toPath()
        val output = ExportDirectory(parent.resolve("project-java-sources"))
        val text = "x".repeat(8191) + "😀中文\r\n"
        output.source("demo/One.java", text) {}
        assertEquals(text, Files.readString(output.root.resolve("demo/One.java")))
        assertThrows(ExportWriteException::class.java) { output.source("demo/One.java", "replacement") {} }
        assertEquals(text, Files.readString(output.root.resolve("demo/One.java")))
        assertThrows(IllegalArgumentException::class.java) { output.source("../escape.java", "bad") {} }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { ExportDirectory(output.root) }
        assertEquals(parent.resolve("project-java-sources-2"), ExportDirectory.suggest(parent, "project-java-sources"))
        val external = Files.createDirectory(parent.resolve("external"))
        Files.createSymbolicLink(output.root.resolve("linked"), external)
        assertThrows(ExportWriteException::class.java) { output.source("linked/Bad.java", "bad") {} }
        assertFalse(Files.exists(external.resolve("Bad.java")))
    }

    @Test fun `output failure from later batch promptly stops earlier active native work`() {
        val bothStarted = CountDownLatch(2)
        val active = AtomicInteger()
        val engine = ClassDecompiler { _, name, check ->
            active.incrementAndGet(); bothStarted.countDown()
            try {
                check(bothStarted.await(5, TimeUnit.SECONDS))
                if (name == "0") {
                    while (true) { check(); Thread.yield() }
                }
                DecompiledClass("source")
            } finally { active.decrementAndGet() }
        }
        val error = ExportWriteException("disk full", IOException("disk full"))
        BatchParallelDecompiler(groups(80), engine, 2, {}, writeSource = { _, _, _ -> throw error }).use { pipeline ->
            assertSame(error, assertThrows(ExportWriteException::class.java) { pipeline.next() })
        }
        assertEquals(0, active.get())
    }

    @Test fun `failure CSV streams all rows while dialog preview stays bounded`() {
        val root = temporary.newFolder().toPath()
        ExportReports(root).use { reports ->
            repeat(100) { i ->
                reports.warning("failed-$i")
                reports.failedClass("demo/C$i", "input.jar!/demo/C$i.class", "digest-$i", "demo/C$i.java", "IOException", "failed-$i")
            }
            assertEquals(20, reports.preview.size)
            assertEquals(100, reports.failureCount)
            assertEquals(100, reports.decompilationFailed)
            assertEquals(101, Files.readAllLines(root.resolve("decompilation-failures.csv")).size)
        }
    }

    @Test fun `resource plan controls active workers and retains batch identity`() {
        val mib = ExportResources.MIB
        val resourcePlan = ExportResources.limit(ExportResources.Snapshot(16, 32768 * mib, 24000 * mib, 4096 * mib, 1024 * mib), 8)
        assertEquals(8, resourcePlan)
        for (requested in listOf(3, resourcePlan)) {
            val ready = CountDownLatch(requested)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val engine = ClassDecompiler { _, _, _ ->
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                ready.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)); DecompiledClass("class Sample {}") }
                finally { active.decrementAndGet() }
            }
            BatchParallelDecompiler(groups(320), engine, requested, {}, writeSource = { _, _, _ -> }).use { pipeline ->
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    assertEquals(requested, pipeline.workers)
                    assertEquals(requested, peak.get())
                } finally { release.countDown() }
                val indices = (0 until 320).map { pipeline.next().index }
                assertEquals((0 until 320).toSet(), indices.toSet())
            }
            assertEquals(0, active.get())
        }
    }

    @Test fun `automatic concurrency uses CPU times two ceiling and available heap budget`() {
        val mib = 1024L * 1024
        assertEquals(2, BatchParallelDecompiler.parallelismFor(1024 * mib, 16))
        assertEquals(6, BatchParallelDecompiler.parallelismFor(2048 * mib, 16))
        assertEquals(96, BatchParallelDecompiler.parallelismFor(32768 * mib, 64))
        assertEquals(2, BatchParallelDecompiler.parallelismFor(4096 * mib, 1))
        assertEquals(4, BatchParallelDecompiler.parallelismFor(2048 * mib, 16, 512 * mib))
        assertEquals(1, BatchParallelDecompiler.parallelismFor(2048 * mib, 16, 1800 * mib))
        assertEquals(1, BatchParallelDecompiler.parallelismFor(256 * mib, 16))
        assertEquals(32, BatchParallelDecompiler.analyzerParallelism(16))
    }
}
