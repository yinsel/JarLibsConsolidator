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

    @Test fun `inner classes merge into outer source and blacklisted inner content cannot leak`() {
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
        assertEquals(result.failures.toString(), 1, result.exported)
        assertEquals(1, result.filtered)
        val sources = entries(target).filterKeys { it.endsWith(".java") }
        assertTrue(sources.containsKey("demo/Outer.java"))
        assertFalse(sources.containsKey("demo/Outer\$Visible.java"))
        assertTrue(sources.getValue("demo/Outer.java").toString(Charsets.UTF_8).contains("return 7;"))
        assertFalse(sources.values.any { "SHOULD_NOT_EXPORT" in it.toString(Charsets.UTF_8) })
    }

    @Test fun `families retain different inner versions even when outer bytes are identical`() {
        val project = temporary.newFolder().toPath()
        fun fixture(value: Int) = compile("Outer", "package demo; public class Outer { public static class Inner { public int value() { return $value; } } }")
        val a = fixture(11)
        val b = fixture(22)
        assertArrayEquals(Files.readAllBytes(a.resolve("demo/Outer.class")), Files.readAllBytes(b.resolve("demo/Outer.class")))
        val first = jar(project.resolve("first.jar"), a)
        val second = jar(project.resolve("second.jar"), b)
        val copy = Files.copy(first, project.resolve("copy.jar"))
        val target = project.resolve("sources.zip")
        val cache = DecompilationCache()
        val result = ClassExportService.export(project, listOf(first, second, copy), target, ExportFilter(), IdeaJavaDecompiler(cache), parallelism = 2)
        assertEquals(result.failures.toString(), 2, result.exported)
        val sources = entries(target).filterKeys { it.endsWith(".java") }
        assertTrue(sources.keys.all { it.startsWith("classes-conflicts/") && it.endsWith("demo/Outer.java") })
        assertTrue(sources.values.any { "return 11;" in it.toString(Charsets.UTF_8) })
        assertTrue(sources.values.any { "return 22;" in it.toString(Charsets.UTF_8) })
        // A warm full-family result must not leak an inner class after the filter changes.
        val filtered = ClassExportService.export(project, listOf(first), target, ExportFilter("", "Inner"), IdeaJavaDecompiler(cache))
        assertEquals(1, filtered.exported)
        assertFalse(entries(target).getValue("demo/Outer.java").toString(Charsets.UTF_8).contains("return 11;"))
    }

    @Test fun `anonymous and local classes merge and generated outer source recompiles`() {
        val project = temporary.newFolder().toPath()
        val classes = compile("Outer", """
            package demo;
            public class Outer {
                public Runnable task() { return new Runnable() { public void run() { System.out.println("anonymous-body"); } }; }
                public int local() { class Local { int value() { return 43; } } return new Local().value(); }
                public class Member { public int value() { return 44; } }
            }
        """.trimIndent())
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, listOf(classes), target, ExportFilter(), IdeaJavaDecompiler(null))
        assertEquals(result.failures.toString(), 1, result.exported)
        assertEquals(0, result.decompilationFailed)
        val source = entries(target).getValue("demo/Outer.java").toString(Charsets.UTF_8)
        assertTrue(source, "anonymous-body" in source && "return 43;" in source && "return 44;" in source)
        assertTrue(Files.exists(compile("Outer", source).resolve("demo/Outer.class")))
    }

    @Test fun `failed family writes a CSV row for every selected member`() {
        val project = temporary.newFolder().toPath()
        val classes = compile("Outer", "package demo; public class Outer { public static class Inner {} }")
        val failing = object : ClassDecompiler {
            override val mergeInnerClasses = true
            override fun decompile(file: Path, internalName: String, checkCanceled: () -> Unit): DecompiledClass = throw IOException("failed")
            override fun decompileGroup(inputs: List<Pair<Path, String>>, checkCanceled: () -> Unit): DecompiledClass = throw IOException("family failed")
        }
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, listOf(classes), target, ExportFilter(), failing)
        assertEquals(0, result.exported)
        assertEquals(2, result.decompilationFailed)
        val csv = entries(target).getValue("decompilation-failures.csv").toString(Charsets.UTF_8)
        assertTrue(csv, csv.contains("demo.Outer\$Inner") && csv.contains("demo.Outer"))
        assertEquals(2, csv.lineSequence().count { it.contains("family failed") })
    }

    @Test fun `dollar in a top level class name does not cause accidental merging`() {
        val project = temporary.newFolder().toPath()
        val outer = compile("Outer", "package demo; public class Outer {}")
        val separate = compile("Outer\$Standalone", "package demo; public class Outer\$Standalone { public int value() { return 8; } }")
        Files.copy(separate.resolve("demo/Outer\$Standalone.class"), outer.resolve("demo/Outer\$Standalone.class"))
        val result = ClassExportService.export(project, listOf(outer), project.resolve("sources.zip"), ExportFilter(), IdeaJavaDecompiler(null))
        assertEquals(result.failures.toString(), 2, result.exported)
    }

    private fun genericInnerFixture(value: Int = 42): Path = compile("GenericOuter", """
        package demo;
        import java.util.List;
        import java.util.concurrent.atomic.AtomicBoolean;
        public class GenericOuter {
            public String outerSecret() { return "OUTER_SHOULD_NOT_EXPORT"; }
            public class Task {
                private final List<String> names;
                public Task(String a, String b, String c, AtomicBoolean d, List<String> names, int e, Long f) {
                    this.names = names;
                }
                public int value() { return $value; }
                public int size() { return names.size(); }
            }
            public static class Hidden { public String secret() { return "HIDDEN_SHOULD_NOT_EXPORT"; } }
        }
    """.trimIndent())

    // The build baseline (IDEA 2024.1) tolerates this signature. Replay the newer engine's
    // reported failure, then run the real bundled engine with the actual fallback options.
    private fun replayGenericFailure(): IdeaDecompilationAttempt = IdeaDecompilationAttempt { file, name, bytes, generic, check ->
        if (generic) throw IOException("Inconsistent generic signature in method <init>: Index 7 out of bounds for length 7",
            IndexOutOfBoundsException("Index 7 out of bounds for length 7"))
        BundledIdeaDecompilationAttempt.decompile(file, name, bytes, generic, check)
    }

    @Test fun `reported generic failure retries real engine without leaking filtered classes`() {
        val project = temporary.newFolder().toPath()
        val classes = genericInnerFixture()
        val name = "demo/GenericOuter\$Task"
        val input = classes.resolve("$name.class")
        val normal = IdeaJavaDecompiler(null).decompile(input, name) {}
        assertTrue(normal.source, normal.source.contains("return 42;"))
        val cache = DecompilationCache()
        val engine = IdeaJavaDecompiler(cache, replayGenericFailure())
        val result = engine.decompile(input, name) {}
        assertTrue(result.source, result.source.contains("return 42;"))
        assertTrue(result.source, result.source.contains("names.size()"))
        assertTrue(result.warnings.toString(), result.warnings.any { it.contains("关闭泛型签名") })
        val bytecode = Files.readAllBytes(input)
        assertNull(cache.get(cache.key(name, bytecode), bytecode))
        val dependency = jar(project.resolve("generic.jar"), classes)
        val target = project.resolve("sources.zip")
        val exported = ClassExportService.export(project, listOf(dependency), target, ExportFilter("Task", "Hidden"), engine)
        val files = entries(target)
        assertEquals(1, exported.exported)
        assertEquals(0, exported.decompilationFailed)
        assertFalse(files.containsKey("decompilation-failures.csv"))
        assertEquals(setOf("$name.java"), files.keys.filter { it.endsWith(".java") }.toSet())
        assertTrue(files.getValue("export-report.txt").toString(Charsets.UTF_8).contains("关闭泛型签名"))
        assertFalse(files.getValue("$name.java").toString(Charsets.UTF_8).contains("SHOULD_NOT_EXPORT"))
    }

    @Test fun `parallel generic fallback retains distinct versions of the same inner class`() {
        val project = temporary.newFolder().toPath()
        val libraries = listOf(jar(project.resolve("first.jar"), genericInnerFixture(41)),
            jar(project.resolve("second.jar"), genericInnerFixture(42)))
        val target = project.resolve("sources.zip")
        val result = ClassExportService.export(project, libraries, target, ExportFilter("Task"), IdeaJavaDecompiler(null, replayGenericFailure()), parallelism = 2)
        val sources = entries(target).filterKeys { it.endsWith(".java") }
        assertEquals(result.failures.toString(), 2, result.exported)
        assertTrue(sources.keys.all { it.startsWith("classes-conflicts/") })
        assertTrue(sources.values.any { "return 41;" in it.toString(Charsets.UTF_8) })
        assertTrue(sources.values.any { "return 42;" in it.toString(Charsets.UTF_8) })
    }

    @Test fun `cancellation inside fallback engine propagates and clears thread context`() {
        val classes = genericInnerFixture()
        val name = "demo/GenericOuter\$Task"
        val canceled = CancellationException("stop fallback")
        val error = assertThrows(CancellationException::class.java) {
            IdeaJavaDecompiler(null, replayGenericFailure()).decompile(classes.resolve("$name.class"), name) {
                if (org.jetbrains.java.decompiler.main.DecompilerContext.getCurrentContext() != null &&
                    !org.jetbrains.java.decompiler.main.DecompilerContext.getOption(
                        org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES)) throw canceled
            }
        }
        assertSame(canceled, error)
        assertNull(org.jetbrains.java.decompiler.main.DecompilerContext.getCurrentContext())
    }

    @Test fun `successful generic decompilation uses one attempt and preserves type arguments`() {
        val classes = compile("Generic", "package demo; public class Generic { public java.util.List<String> names() { return java.util.Collections.emptyList(); } }")
        var attempts = 0
        val engine = IdeaDecompilationAttempt { file, name, bytes, generic, check ->
            attempts++
            assertTrue(generic)
            BundledIdeaDecompilationAttempt.decompile(file, name, bytes, generic, check)
        }
        val result = IdeaJavaDecompiler(null, engine).decompile(classes.resolve("demo/Generic.class"), "demo/Generic") {}
        assertEquals(1, attempts)
        assertTrue(result.source, result.source.contains("List<String>"))
    }

    @Test fun `both failed attempts retain diagnostic causes and never cache empty source`() {
        val file = temporary.newFile("Broken.class").toPath()
        Files.write(file, byteArrayOf(0, 1, 2))
        val cache = DecompilationCache()
        val error = assertThrows(IOException::class.java) { IdeaJavaDecompiler(cache).decompile(file, "Broken") {} }
        assertEquals(1, error.suppressed.size)
        assertTrue(error.message, error.message!!.contains("genericSignatures=false"))
        assertTrue(error.suppressed.single().message!!.contains("genericSignatures=true"))
        val bytes = Files.readAllBytes(file)
        assertNull(cache.get(cache.key("Broken", bytes), bytes))
        assertNull(org.jetbrains.java.decompiler.main.DecompilerContext.getCurrentContext())
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

    @Test fun `failure CSV counts only final decompilation failures and preserves quoted multiline reasons`() {
        val project = temporary.newFolder().toPath()
        val good = jar(project.resolve("good.jar"), compile("Good", "package demo; public class Good {}"))
        val bad = jar(project.resolve("=bad,quoted.jar"), compile("Bad", "package demo; public class Bad {}"))
        val corrupt = Files.writeString(project.resolve("broken.class"), "invalid bytecode")
        val target = project.resolve("export.zip")
        val engine = ClassDecompiler { _, name, _ ->
            if (name.endsWith("Bad")) throw IOException("失败, \"引号\"\n第二行", IllegalStateException("root cause"))
            DecompiledClass("package demo; public class Good {}", listOf("warning only"))
        }
        val result = ClassExportService.export(project, listOf(good, bad, corrupt), target, ExportFilter(), engine)
        val files = entries(target)
        assertEquals(1, result.exported)
        assertEquals(1, result.decompilationFailed)
        assertEquals(3, result.failures.size)
        val csv = files.getValue("decompilation-failures.csv")
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), csv.take(3).toByteArray())
        val text = csv.toString(Charsets.UTF_8)
        assertTrue(text, text.contains("\"demo.Bad\",\"'=bad,quoted.jar!/demo/Bad.class\""))
        assertTrue(text, text.contains("失败, \"\"引号\"\"\n第二行"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: root cause"))
        assertFalse(text.contains("demo.Good"))
        assertFalse(text.contains("broken.class"))
        assertTrue(files.getValue("export-report.txt").toString(Charsets.UTF_8).contains("反编译失败: 1"))
        // CLASS export never creates a decompilation-failure report, even with scan failures.
        val classResult = ClassExportService.export(project, listOf(good, corrupt), target, ExportFilter())
        assertEquals(0, classResult.decompilationFailed)
        assertFalse(entries(target).containsKey("decompilation-failures.csv"))
    }

    @Test fun `failure CSV retains every failed bytecode version of the same class`() {
        val project = temporary.newFolder().toPath()
        val libraries = listOf(jar(project.resolve("a.jar"), compile("Same", "public class Same { public int n() { return 1; } }")),
            jar(project.resolve("b.jar"), compile("Same", "public class Same { public int n() { return 2; } }")))
        val target = project.resolve("export.zip")
        val result = ClassExportService.export(project, libraries, target, ExportFilter(),
            ClassDecompiler { _, _, _ -> throw IOException("failed") }, parallelism = 2)
        assertEquals(2, result.decompilationFailed)
        assertEquals(0, result.exported)
        val csv = entries(target).getValue("decompilation-failures.csv").toString(Charsets.UTF_8)
        assertEquals(3, csv.trimEnd().split("\r\n").size)
        assertTrue(csv.contains("a.jar!/Same.class")); assertTrue(csv.contains("b.jar!/Same.class"))
        assertEquals(2, Regex("classes-conflicts/").findAll(csv).count())
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
