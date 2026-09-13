package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ClassExportServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun compile(name: String, source: String): Path {
        val folder = temporary.newFolder().toPath()
        val java = folder.resolve("$name.java")
        Files.writeString(java, source)
        val output = Files.createDirectories(folder.resolve("classes"))
        val log = folder.resolve("javac.log")
        val process = ProcessBuilder(System.getProperty("test.javac"), "--release", "17", "-encoding", "UTF-8", "-d", output.toString(), java.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("javac timed out") }
        assertEquals(Files.readString(log), 0, process.exitValue())
        return output
    }

    private fun jar(target: Path, classes: Path): Path {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            Files.walk(classes).use { stream -> stream.filter(Files::isRegularFile).sorted().forEach { file ->
                zip.putNextEntry(ZipEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, zip); zip.closeEntry()
            } }
        }
        return target
    }

    private fun entries(zip: Path): Map<String, ByteArray> = ZipFile(zip.toFile()).use { archive ->
        archive.entries().asSequence().associate { it.name to archive.getInputStream(it).use { input -> input.readBytes() } }
    }

    @Test fun `class export includes loose files and external dependency jars`() {
        val project = temporary.newFolder().toPath()
        val output = compile("Local", "package demo; public class Local {}")
        val loose = Files.createDirectories(project.resolve("target/classes/demo")).resolve("Local.class")
        Files.copy(output.resolve("demo/Local.class"), loose)
        val dependency = jar(temporary.newFolder().toPath().resolve("dependency.jar"), compile("Service", "package org.exampletools; public class Service {}"))
        val target = project.resolve("classes.zip")
        val result = ClassExportService.export(project, listOf(project.resolve("target/classes"), dependency, dependency), target, ExportFilter())
        val contents = entries(target)
        assertEquals(2, result.exported)
        assertTrue(contents.containsKey("demo/Local.class"))
        assertTrue(contents.containsKey("org/exampletools/Service.class"))
        assertArrayEquals(Files.readAllBytes(loose), contents.getValue("demo/Local.class"))
        assertTrue(contents.getValue("export-sources.tsv").toString(Charsets.UTF_8).contains("dependency.jar!/"))
    }

    @Test fun `jar filters use actual bytecode package and blacklist wins`() {
        val project = temporary.newFolder().toPath()
        val allowed = compile("Service", "package org.exampletools.api; public class Service {}")
        val denied = compile("Secret", "package org.exampletools.internal; public class Secret {}")
        jar(project.resolve("allowed.jar"), allowed)
        jar(project.resolve("denied.jar"), denied)
        val target = project.resolve("export.zip")
        val result = ClassExportService.export(project, listOf(project), target, ExportFilter("*example", "internal"))
        assertEquals(1, result.filtered)
        assertEquals(1, result.exported)
        assertTrue(entries(target).containsKey("org/exampletools/api/Service.class"))
        assertFalse(entries(target).keys.any { "Secret" in it })
    }

    @Test fun `nested dependency jars and untrusted entry paths are safely exported`() {
        val project = temporary.newFolder().toPath()
        val classes = compile("Safe", "package demo; public class Safe {}")
        val nested = jar(temporary.newFolder().toPath().resolve("nested.jar"), classes)
        ZipOutputStream(Files.newOutputStream(project.resolve("app.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry("BOOT-INF/lib/nested.jar")); Files.copy(nested, zip); zip.closeEntry()
            zip.putNextEntry(ZipEntry("../../escaped.class")); Files.copy(classes.resolve("demo/Safe.class"), zip); zip.closeEntry()
        }
        val target = project.resolve("export.zip")
        val result = ClassExportService.export(project, listOf(project), target, ExportFilter())
        assertEquals(1, result.exported)
        assertEquals(1, result.duplicates)
        assertTrue(entries(target).containsKey("demo/Safe.class"))
        assertFalse(entries(target).keys.any { ".." in it })
        assertFalse(Files.exists(project.parent.resolve("escaped.class")))
    }

    @Test fun `different jar versions survive while identical copies are deduplicated`() {
        val project = temporary.newFolder().toPath()
        val a = jar(project.resolve("a.jar"), compile("Same", "package demo; public class Same { public int value() { return 1; } }"))
        jar(project.resolve("b.jar"), compile("Same", "package demo; public class Same { public int value() { return 2; } }"))
        Files.copy(a, project.resolve("copy.jar"))
        val target = project.resolve("export.zip")
        val result = ClassExportService.export(project, listOf(project), target, ExportFilter())
        assertEquals(2, result.exported)
        assertEquals(1, result.duplicates)
        val classEntries = entries(target).keys.filter { it.endsWith(".class") }
        assertEquals(2, classEntries.size)
        assertTrue(classEntries.all { it.startsWith("classes-conflicts/") && it.endsWith("demo/Same.class") })
    }

    @Test fun `IDEA bundled decompiler emits method bodies from jar bytecode`() {
        val project = temporary.newFolder().toPath()
        jar(project.resolve("input.jar"), compile("Answer", "package demo; public class Answer { public static int answer() { return 42; } }"))
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, listOf(project), target, ExportFilter(), IdeaJavaDecompiler())
        assertEquals(result.failures.toString(), 1, result.exported)
        val source = entries(target).getValue("demo/Answer.java").toString(Charsets.UTF_8)
        assertTrue(source, source.contains("package demo;"))
        assertTrue(source, source.contains("return 42;"))
        // Recompile the actual decompiled source, rather than accepting a signature-only stub.
        assertTrue(Files.exists(compile("Answer", source).resolve("demo/Answer.class")))
    }

    @Test fun `Java export preserves different bytecode versions with source jar names`() {
        val project = temporary.newFolder().toPath()
        val first = jar(project.resolve("first.jar"), compile("Same", "package demo; public class Same { public int value() { return 1; } }"))
        jar(project.resolve("second.jar"), compile("Same", "package demo; public class Same { public int value() { return 2; } }"))
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, listOf(first, project.resolve("second.jar")), target, ExportFilter(), IdeaJavaDecompiler())
        assertEquals(result.failures.toString(), 2, result.exported)
        val sources = entries(target).filterKeys { it.endsWith(".java") }
        assertEquals(2, sources.size)
        assertTrue(sources.any { (name, bytes) -> name.startsWith("classes-conflicts/first.jar--") && "return 1;" in bytes.toString(Charsets.UTF_8) })
        assertTrue(sources.any { (name, bytes) -> name.startsWith("classes-conflicts/second.jar--") && "return 2;" in bytes.toString(Charsets.UTF_8) })
    }

    @Test fun `inner classes export separately and blacklisted inner content cannot leak`() {
        val project = temporary.newFolder().toPath()
        jar(project.resolve("input.jar"), compile("Outer", """
            package demo;
            public class Outer {
                public static class Visible { public int value() { return 7; } }
                public static class Hidden { public String secret() { return "SHOULD_NOT_EXPORT"; } }
            }
        """.trimIndent()))
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, listOf(project), target, ExportFilter("", "Hidden"), IdeaJavaDecompiler())
        assertEquals(result.failures.toString(), 2, result.exported)
        assertEquals(1, result.filtered)
        val sources = entries(target).filterKeys { it.endsWith(".java") }
        assertTrue(sources.containsKey("demo/Outer.java"))
        assertTrue(sources.containsKey("demo/Outer\$Visible.java"))
        assertFalse(sources.values.any { "SHOULD_NOT_EXPORT" in it.toString(Charsets.UTF_8) })
    }

    @Test fun `corrupt class and individual decompile failures are reported without corrupting zip`() {
        val project = temporary.newFolder().toPath()
        jar(project.resolve("a.jar"), compile("Good", "package demo; public class Good {}"))
        jar(project.resolve("b.jar"), compile("Bad", "package demo; public class Bad {}"))
        Files.writeString(project.resolve("broken.class"), "invalid")
        val decompiler = ClassDecompiler { _, name, _ ->
            if (name.endsWith("Bad")) throw IOException("test decompilation failure")
            DecompiledClass("package demo; public class Good {}")
        }
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, listOf(project), target, ExportFilter(), decompiler)
        assertEquals(1, result.exported)
        assertEquals(2, result.failures.size)
        assertTrue(result.failures.any { it.contains("java.io.IOException: test decompilation failure") })
        assertTrue(entries(target).getValue("export-report.txt").toString(Charsets.UTF_8).contains("test decompilation failure"))
    }

    @Test fun `wrapped IDEA cancellation aborts export and preserves destination`() {
        val project = temporary.newFolder().toPath()
        val dependency = jar(project.resolve("dependency.jar"), compile("Example", "public class Example {}"))
        val target = Files.writeString(project.resolve("export.zip"), "existing destination")
        val canceled = com.intellij.openapi.progress.ProcessCanceledException()
        val decompiler = ClassDecompiler { _, _, _ -> throw IOException("engine wrapper", canceled) }
        val error = assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            ClassExportService.export(project, listOf(dependency), target, ExportFilter(), decompiler)
        }
        assertSame(canceled, error)
        assertEquals("existing destination", Files.readString(target))
        Files.list(project).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".jarlibs-export-") }) }
    }

    @Test fun `cancellation preserves existing destination and cleans partial zip`() {
        val project = temporary.newFolder().toPath()
        jar(project.resolve("a.jar"), compile("Good", "package demo; public class Good {}"))
        val target = project.resolve("sources.zip")
        Files.writeString(target, "original")
        assertThrows(CancellationException::class.java) {
            ClassExportService.export(project, listOf(project), target, ExportFilter(),
                ClassDecompiler { _, _, _ -> throw CancellationException() })
        }
        assertEquals("original", Files.readString(target))
        Files.list(project).use { files -> assertFalse(files.anyMatch { it.fileName.toString().startsWith(".jarlibs-export-") }) }
    }

    @Test fun `parallel cancellation preserves destination and joins decompiler workers`() {
        val project = temporary.newFolder().toPath()
        jar(project.resolve("a.jar"), compile("Outer", "package demo; public class Outer { public static class Inner {} }"))
        val target = project.resolve("sources.zip")
        Files.writeString(target, "original")
        val started = java.util.concurrent.CountDownLatch(2)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val cancel = java.util.concurrent.atomic.AtomicBoolean()
        val decompiler = ClassDecompiler { _, _, checkCanceled ->
            active.incrementAndGet(); started.countDown()
            try {
                check(started.await(5, TimeUnit.SECONDS))
                cancel.set(true)
                checkCanceled()
                throw AssertionError("Cancellation was ignored")
            } finally { active.decrementAndGet() }
        }
        assertThrows(CancellationException::class.java) {
            ClassExportService.export(project, listOf(project), target, ExportFilter(), decompiler,
                checkCanceled = { if (cancel.get()) throw CancellationException() }, parallelism = 2)
        }
        assertEquals(0, active.get())
        assertEquals("original", Files.readString(target))
        Files.list(project).use { files -> assertFalse(files.anyMatch { it.fileName.toString().startsWith(".jarlibs-export-") }) }
    }

    @Test fun `cached exports honor changed filters and replaced bytecode`() {
        val project = temporary.newFolder().toPath()
        val library = project.resolve("input.jar")
        jar(library, compile("Same", "package demo; public class Same { public int value() { return 1; } }"))
        val target = project.resolve("sources.zip")
        val decompiler = IdeaJavaDecompiler(DecompilationCache())
        ClassExportService.export(project, listOf(project), target, ExportFilter(), decompiler)
        assertTrue("return 1;" in entries(target).getValue("demo/Same.java").toString(Charsets.UTF_8))
        val filtered = ClassExportService.export(project, listOf(project), target, ExportFilter("", "Same"), decompiler)
        assertEquals(0, filtered.exported)
        assertFalse(entries(target).keys.any { it.endsWith(".java") })
        jar(library, compile("Same", "package demo; public class Same { public int value() { return 2; } }"))
        ClassExportService.export(project, listOf(project), target, ExportFilter(), decompiler)
        assertTrue("return 2;" in entries(target).getValue("demo/Same.java").toString(Charsets.UTF_8))
    }

    @Test fun `only registered library roots are exported even when project classes match`() {
        val project = temporary.newFolder().toPath()
        val registered = jar(project.resolve("all-in-one/registered.jar"), compile("Wanted", "package demo; public class Wanted {}"))
        jar(project.resolve("unregistered.jar"), compile("Unwanted", "package demo; public class Unwanted {}"))
        val loose = compile("Loose", "package demo; public class Loose {}")
        Files.copy(loose.resolve("demo/Loose.class"), project.resolve("Loose.class"))
        val output = project.resolve("export.zip")
        val result = ClassExportService.export(project, listOf(registered), output, ExportFilter("demo.*"))
        assertEquals(1, result.discovered)
        assertEquals(setOf("demo/Wanted.class"), entries(output).keys.filter { it.endsWith(".class") }.toSet())
        ClassExportService.export(project, listOf(registered), output, ExportFilter("demo.*"), IdeaJavaDecompiler())
        assertEquals(setOf("demo/Wanted.java"), entries(output).keys.filter { it.endsWith(".java") }.toSet())
    }

    @Test fun `missing library configuration asks user to add dependencies and preserves target`() {
        val project = temporary.newFolder().toPath()
        val output = project.resolve("export.zip")
        Files.writeString(output, "original")
        val error = assertThrows(IllegalArgumentException::class.java) {
            ClassExportService.export(project, emptyList(), output, ExportFilter())
        }
        assertTrue(error.message!!.contains("一键添加依赖"))
        assertEquals("original", Files.readString(output))
    }
}
