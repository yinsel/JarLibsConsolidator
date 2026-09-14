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
                assertTrue(pipeline.next().issues.isEmpty())
            } finally { releaseSecond.countDown() }
            repeat(39) { pipeline.next() }
        }
        assertEquals(40L, Files.list(output.root).use { it.count() })
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

    @Test fun `default native concurrency reserves IDE heap and never exceeds two workers`() {
        val mib = 1024L * 1024
        assertEquals(1, BatchParallelDecompiler.parallelismFor(1024 * mib, 16))
        assertEquals(2, BatchParallelDecompiler.parallelismFor(2048 * mib, 16))
        assertEquals(2, BatchParallelDecompiler.parallelismFor(32768 * mib, 64))
        assertEquals(1, BatchParallelDecompiler.parallelismFor(4096 * mib, 1))
    }
}
