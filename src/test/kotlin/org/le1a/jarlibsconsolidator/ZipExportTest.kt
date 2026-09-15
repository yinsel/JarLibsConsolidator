package org.le1a.jarlibsconsolidator

import com.intellij.testFramework.TestApplicationManager
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class ZipExportTest {
    companion object { @BeforeClass @JvmStatic fun startIdea() { TestApplicationManager.getInstance() } }
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Path {
        val root = temporary.newFolder().toPath()
        val source = Files.writeString(root.resolve("A.java"),
            "package demo; public class A { public int value() { return 42; } } class B {} class C {}")
        val log = root.resolve("javac.log")
        val process = ProcessBuilder(System.getProperty("test.javac"), "--release", "17", "-d", root.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(Files.readString(log), 0, process.exitValue())
        return root.resolve("demo")
    }

    private fun contents(zip: Path) = ZipFile(zip.toFile()).use { archive ->
        archive.entries().asSequence().associate { it.name to archive.getInputStream(it).use { input -> input.readBytes() } }
    }

    @Test fun `class and parallel java zip exports match directory including filters and failure CSV`() {
        val classes = fixture()
        val root = temporary.newFolder().toPath()
        for (java in listOf(false, true)) {
            val engine = if (!java) null else ClassDecompiler { _, name, _ ->
                if (name == "demo/B") throw IOException("sample failure")
                DecompiledClass("package demo; class A { String text = \"中文😀\"; }")
            }
            val directory = root.resolve("folder-$java")
            val zip = root.resolve("output-$java.zip")
            val filter = ExportFilter(blacklist = "C")
            val a = ClassExportService.export(root, listOf(classes), directory, filter, engine, parallelism = 2, batchSize = 1)
            val b = ClassExportService.export(root, listOf(classes), zip, filter, engine, parallelism = 2, batchSize = 1, format = ExportFormat.ZIP)
            assertEquals(a, b)
            val actual = contents(zip)
            val expected = Files.walk(directory).use { stream -> stream.filter(Files::isRegularFile).toList()
                .associate { directory.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) } }
            assertEquals(expected.keys, actual.keys)
            // Completion order is intentionally unconstrained; compare report rows as sets.
            for ((name, bytes) in expected) {
                if (name == "export-sources.tsv") assertEquals(bytes.toString(Charsets.UTF_8).lines().toSet(), actual.getValue(name).toString(Charsets.UTF_8).lines().toSet())
                else assertArrayEquals(name, bytes, actual.getValue(name))
            }
            assertEquals(java, "decompilation-failures.csv" in actual)
            assertFalse(actual.keys.any { it.endsWith("C.class") || it.endsWith("C.java") })
        }
    }

    @Test fun `success count excludes partial source and failed decompilation`() {
        val classes = fixture()
        val root = temporary.newFolder().toPath()
        for (format in ExportFormat.values()) {
            val target = format.suggest(root, "counts")
            val engine = ClassDecompiler { _, name, _ ->
                when (name) {
                    "demo/B" -> DecompiledClass("class B { /* partial */ }", issues = listOf(
                        DecompilationIssue("demo/B", "IDEA.NativePartialSource", "sample partial source")))
                    "demo/C" -> throw IOException("sample failure")
                    else -> DecompiledClass("class A {}")
                }
            }
            val result = ClassExportService.export(root, listOf(classes), target, ExportFilter(), engine, format = format)
            assertEquals(2, result.exported)
            assertEquals(1, result.successfullyExported)
            assertEquals(1, result.partiallyExported)
            assertEquals(2, result.decompilationFailed)
        }
    }

    @Test fun `cancellation finalizes completed entries and includes incomplete report`() {
        val classes = fixture()
        val root = temporary.newFolder().toPath()
        val zip = root.resolve("canceled.zip")
        val engine = ClassDecompiler { _, name, _ ->
            if (name == "demo/B") throw CancellationException("test cancellation")
            DecompiledClass("class A {}")
        }
        assertThrows(CancellationException::class.java) {
            ClassExportService.export(root, listOf(classes), zip, ExportFilter(), engine, parallelism = 1, format = ExportFormat.ZIP)
        }
        val entries = contents(zip)
        assertEquals("class A {}", entries.getValue("demo/A.java").toString(Charsets.UTF_8))
        assertFalse("demo/B.java" in entries)
        assertTrue(entries.getValue("export-report.txt").toString(Charsets.UTF_8).contains("导出未完成"))
    }

    @Test fun `entry staging keeps canceled data out of zip and cleans temporary files`() {
        val root = temporary.newFolder().toPath()
        val work = temporary.newFolder().toPath()
        val zip = root.resolve("output.zip")
        ExportZip(zip, work).use { output ->
            val text = "x".repeat(8191) + "😀中文\r\n"
            output.source("中文/A.java", text) {}
            var checks = 0
            assertThrows(CancellationException::class.java) {
                output.source("Canceled.java", "x".repeat(40000)) { if (++checks == 3) throw CancellationException() }
            }
            assertThrows(IllegalArgumentException::class.java) { output.source("../escape.java", "bad") {} }
            assertEquals(0L, Files.list(work).use { it.count() })
        }
        assertEquals(setOf("中文/A.java"), contents(zip).keys)
        assertEquals("x".repeat(8191) + "😀中文\r\n", contents(zip).getValue("中文/A.java").toString(Charsets.UTF_8))
        val original = Files.readAllBytes(zip)
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { ExportZip(zip, work) }
        assertArrayEquals(original, Files.readAllBytes(zip))
        assertEquals(root.resolve("output-2.zip"), ExportFormat.ZIP.suggest(root, "output"))
        assertEquals(ExportFormat.DIRECTORY, ExportFormat.values().first())
    }

    @Test fun `concurrent workers write complete zip entries without retaining staged sources`() {
        val work = temporary.newFolder().toPath()
        val zip = temporary.newFolder().toPath().resolve("parallel.zip")
        val groups = (0 until 100).map { listOf(Path.of("unused") to "C$it") }
        ExportZip(zip, work).use { output ->
            BatchParallelDecompiler(groups, ClassDecompiler { _, name, _ -> DecompiledClass("class $name {}") },
                4, {}, batchSize = 1, writeSource = { index, text, check -> output.source("C$index.java", text, check) }).use { pipeline ->
                repeat(100) { assertNull(pipeline.next().error) }
            }
            assertEquals(0L, Files.list(work).use { it.count() })
        }
        val entries = contents(zip)
        assertEquals(100, entries.size)
        repeat(100) { assertEquals("class C$it {}", entries.getValue("C$it.java").toString(Charsets.UTF_8)) }
    }
}
