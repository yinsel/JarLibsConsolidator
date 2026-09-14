package org.le1a.jarlibsconsolidator

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.compiled.ClassFileDecompilers
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeIdeaDecompilerTest {
    companion object { @BeforeClass @JvmStatic fun startIdea() { TestApplicationManager.getInstance() } }
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Path {
        val directory = temporary.newFolder().toPath()
        val source = Files.writeString(directory.resolve("Outer.java"), """
            package demo;
            public class Outer {
                public java.util.List<String> names() { return java.util.Collections.emptyList(); }
                public static long shift(long x) { x >>= 2; return x; }
                public static class Inner { public int answer() { return 42; } }
            }
        """.trimIndent())
        val classes = Files.createDirectories(directory.resolve("classes"))
        val log = directory.resolve("javac.log")
        val process = ProcessBuilder(System.getProperty("test.javac"), "--release", "17", "-g", "-d", classes.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("javac timed out") }
        assertEquals(Files.readString(log), 0, process.exitValue())
        return classes
    }

    private fun jar(classes: Path): Path {
        val jar = temporary.newFolder().toPath().resolve("input.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            Files.walk(classes).use { files -> files.filter(Files::isRegularFile).sorted().forEach { file ->
                zip.putNextEntry(ZipEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, zip); zip.closeEntry()
            } }
        }
        return jar
    }

    @Test fun `loose and jar export text equals actual registered native entry including inner classes`() {
        val classes = fixture()
        val jar = jar(classes)
        val root = temporary.newFolder().toPath()
        for ((library, source) in listOf(classes to ClassSource(classes.resolve("demo/Outer.class")),
            jar to ClassSource(jar, "demo/Outer.class"))) {
            val vf = EditorIdeaText.resolve(source)
            val native = ClassFileDecompilers.getInstance().find(vf, ClassFileDecompilers.Light::class.java)
            assertEquals("org.jetbrains.java.decompiler.IdeaDecompiler", native.javaClass.name)
            val expected = native.getText(vf).toString()
            assertTrue(expected, expected.contains("return 42;"))
            val target = ExportDirectory.suggest(root, "export")
            val result = ClassExportService.export(root, listOf(library), target, ExportFilter(), IdeaJavaDecompiler())
            assertEquals(result.failures.toString(), 1, result.exported)
            DirectoryOutput(target.toFile()).use { zip ->
                assertEquals(expected, zip.getInputStream(zip.getEntry("demo/Outer.java")).reader().readText())
            }
        }
    }

    @Test fun `nested jar stays available to the native reader until export completes`() {
        val classes = fixture()
        val inner = jar(classes)
        val root = temporary.newFolder().toPath()
        val fat = root.resolve("fat.jar")
        ZipOutputStream(Files.newOutputStream(fat)).use { zip ->
            zip.putNextEntry(ZipEntry("BOOT-INF/lib/input.jar")); Files.copy(inner, zip); zip.closeEntry()
        }
        val direct = ClassFileDecompilers.getInstance().find(EditorIdeaText.resolve(ClassSource(inner, "demo/Outer.class")),
            ClassFileDecompilers.Light::class.java).getText(EditorIdeaText.resolve(ClassSource(inner, "demo/Outer.class"))).toString()
        val target = ExportDirectory.suggest(root, "export")
        val result = ClassExportService.export(root, listOf(fat), target, ExportFilter(), IdeaJavaDecompiler())
        assertEquals(result.failures.toString(), 1, result.exported)
        DirectoryOutput(target.toFile()).use { zip -> assertEquals(direct, zip.getInputStream(zip.getEntry("demo/Outer.java")).reader().readText()) }
    }

    @Test fun `native partial source is unchanged and CSV records the output file without guessing failed member`() {
        val classes = fixture()
        val root = temporary.newFolder().toPath()
        val target = ExportDirectory.suggest(root, "export")
        val text = "class Outer {\r\n  void broken() {\r\n    // \$FF: Couldn't be decompiled\r\n  }\r\n}\r\n"
        var calls = 0
        val reader = NativeIdeaText { _, _, _ -> calls++; text }
        repeat(2) {
            target.toFile().deleteRecursively()
            val result = ClassExportService.export(root, listOf(classes), target, ExportFilter(), IdeaJavaDecompiler(reader))
            assertEquals(1, result.exported)
            assertEquals(1, result.partiallyExported)
            assertEquals(1, result.decompilationFailed)
            DirectoryOutput(target.toFile()).use { zip ->
                assertEquals(text, zip.getInputStream(zip.getEntry("demo/Outer.java")).reader().readText())
                val csv = zip.getInputStream(zip.getEntry("decompilation-failures.csv")).reader().readText()
                assertTrue(csv, csv.contains("IDEA.NativePartialSource") && csv.contains("第 3 行"))
                assertFalse(csv, csv.contains("demo.Outer\$Inner"))
            }
        }
        assertEquals("Every export calls the native entry; no plugin cache or retry", 2, calls)
    }

    @Test fun `failure-like text inside string literals is not counted but native limits are`() {
        val file = temporary.newFile("One.class").toPath()
        val source = "class One { String text = \"\"\"\n// \$FF: Couldn't be decompiled\n\"\"\";\nvoid broken() {\n// \$FF: Limits for direct nodes are exceeded. Current value: 2, limit: 1\n} }"
        val result = IdeaJavaDecompiler(NativeIdeaText { _, _, _ -> source }).decompile(file, "One") {}
        assertEquals(source, result.source)
        assertEquals(1, result.issues.size)
        assertTrue(result.issues.single().reason.contains("Limits for direct nodes"))
    }

    @Test fun `native exceptions and cancellation are propagated without retry`() {
        val file = temporary.newFile("One.class").toPath()
        for (error in listOf(IOException("native failure"), CancellationException("cancel"))) {
            var calls = 0
            val decompiler = IdeaJavaDecompiler(NativeIdeaText { _, _, _ -> calls++; throw error })
            assertSame(error, assertThrows(error.javaClass) { decompiler.decompile(file, "One") {} })
            assertEquals(1, calls)
        }
    }

    @Test fun `selected snapshots preserve each origin even when outer bytes are identical`() {
        val classes = fixture()
        val first = jar(classes)
        val second = jar(classes)
        val root = temporary.newFolder().toPath()
        val sources = mutableListOf<ClassSource>()
        val reader = NativeIdeaText { source, selected, _ ->
            sources.add(source)
            assertTrue(selected.keys.any { it.container == first })
            assertTrue(selected.keys.any { it.container == second })
            assertTrue(selected.values.all(Files::exists))
            "class Outer {}"
        }
        val result = ClassExportService.export(root, listOf(first, second), root.resolve("export"), ExportFilter(), IdeaJavaDecompiler(reader))
        assertEquals(1, result.exported)
        assertEquals(1, sources.size)
        assertTrue(sources.single().container == first || sources.single().container == second)
    }

    @Test fun `worker callback reaches native progress cancellation`() {
        val classes = fixture()
        var reached = false
        val canceled = com.intellij.openapi.progress.ProcessCanceledException()
        val error = assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            IdeaJavaDecompiler().decompileGroup(listOf(classes.resolve("demo/Outer.class") to "demo/Outer",
                classes.resolve("demo/Outer\$Inner.class") to "demo/Outer\$Inner")) {
                if (ProgressManager.getInstance().progressIndicator != null) { reached = true; throw canceled }
            }
        }
        assertTrue(reached)
        assertSame(canceled, error)
        assertNull(ProgressManager.getInstance().progressIndicator)
    }
}
