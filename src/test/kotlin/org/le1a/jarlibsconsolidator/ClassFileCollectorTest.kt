package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ClassFileCollectorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun compile(project: Path, output: String, name: String, source: String): Path {
        val sourceDir = temporary.newFolder().toPath()
        val javaFile = sourceDir.resolve("$name.java")
        Files.writeString(javaFile, source)
        val destination = project.resolve(output)
        Files.createDirectories(destination)
        val javac = checkNotNull(System.getProperty("test.javac")) { "Run tests through Gradle to select the fixture JDK" }
        val diagnostics = sourceDir.resolve("javac.log")
        val process = ProcessBuilder(javac, "--release", "17", "-encoding", "UTF-8", "-d", destination.toString(), javaFile.toString())
            .redirectErrorStream(true).redirectOutput(diagnostics.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("javac timed out")
        }
        assertEquals(Files.readString(diagnostics), 0, process.exitValue())
        return destination
    }

    @Test fun `class only project produces loadable package inner and default classes`() {
        val project = temporary.newFolder().toPath()
        compile(project, "target/classes", "Example", """
            package demo;
            public class Example {
                public static final long BIG = 1234567890123L;
                public static final double PI = 3.14159;
                public static class Inner {}
                public Runnable task() { return () -> System.out.println("hello"); }
            }
        """.trimIndent())
        compile(project, "target/classes", "DefaultClass", "public class DefaultClass {}")
        val scan = ClassFileCollector.scan(project)
        assertEquals(3, scan.classes.size)
        assertTrue(scan.skipped.isEmpty())
        val roots = ClassFileCollector.copy(scan.classes, project.resolve("all-in-one/classes"))
        assertEquals(1, roots.size)
        URLClassLoader(roots.map { it.toUri().toURL() }.toTypedArray(), null).use { loader ->
            for (name in listOf("demo.Example", "demo.Example\$Inner", "DefaultClass")) {
                assertEquals(name, loader.loadClass(name).name)
            }
        }
    }

    @Test fun `flattened and renamed class files recover their binary names`() {
        val project = temporary.newFolder().toPath()
        val output = compile(project, "original", "Hello", "package demo.nested; public class Hello {}")
        Files.move(output.resolve("demo/nested/Hello.class"), project.resolve("renamed.CLASS"))
        val scan = ClassFileCollector.scan(project)
        val roots = ClassFileCollector.copy(scan.classes, project.resolve("all-in-one/classes"))
        assertEquals(1, scan.classes.size)
        assertTrue(Files.exists(roots.single().resolve("demo/nested/Hello.class")))
        URLClassLoader(arrayOf(roots.single().toUri().toURL()), null).use {
            assertEquals("demo.nested.Hello", it.loadClass("demo.nested.Hello").name)
        }
    }

    @Test fun `same binary name from different modules remains in separate roots`() {
        val project = temporary.newFolder().toPath()
        for ((directory, value) in listOf("a/target/classes" to 1, "b/build/classes/java/main" to 2)) {
            compile(project, directory, "Same", "package demo; public class Same { public static int value() { return $value; } }")
        }
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, project.resolve("all-in-one/classes"))
        assertEquals(2, roots.size)
        val values = roots.map { root ->
            URLClassLoader(arrayOf(root.toUri().toURL()), null).use {
                it.loadClass("demo.Same").getMethod("value").invoke(null)
            }
        }
        assertEquals(setOf(1, 2), values.toSet())
    }

    @Test fun `duplicate bytecode in one folder is not overwritten or renamed`() {
        val project = temporary.newFolder().toPath()
        val output = compile(project, "flat", "Same", "public class Same {}")
        Files.copy(output.resolve("Same.class"), output.resolve("copy.class"))
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, project.resolve("all-in-one/classes"))
        assertEquals(2, roots.size)
        roots.forEach { assertTrue(Files.exists(it.resolve("Same.class"))) }
    }

    @Test fun `scan includes build and hidden output but excludes previous collection and symlinks`() {
        val project = temporary.newFolder().toPath()
        val output = compile(project, "out/production", "Hidden", "public class Hidden {}")
        for (dir in listOf("build/classes", "target/classes", ".cache/classes", "node_modules/classes", "all-in-one/classes/root-1", ".git")) {
            Files.createDirectories(project.resolve(dir))
            Files.copy(output.resolve("Hidden.class"), project.resolve("$dir/Hidden.class"))
        }
        Files.createSymbolicLink(project.resolve("loop"), project)
        Files.createSymbolicLink(project.resolve("linked.class"), output.resolve("Hidden.class"))
        assertEquals(5, ClassFileCollector.scan(project).classes.size)
    }

    @Test fun `unreadable headers and unsafe binary paths are skipped`() {
        val project = temporary.newFolder().toPath()
        Files.writeString(project.resolve("invalid.class"), "not bytecode")
        Files.write(project.resolve("truncated.class"), byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        // Minimal header with a malicious this_class path: never permit copying outside the output root.
        DataOutputStream(Files.newOutputStream(project.resolve("unsafe.class"))).use {
            it.writeInt(0xCAFEBABE.toInt()); it.writeShort(0); it.writeShort(61)
            it.writeShort(3)
            it.writeByte(1); it.writeUTF("../Escape")
            it.writeByte(7); it.writeShort(1)
            it.writeShort(1); it.writeShort(2)
        }
        val scan = ClassFileCollector.scan(project)
        assertTrue(scan.classes.isEmpty())
        assertEquals(3, scan.skipped.size)
    }

    @Test fun `unicode class names are preserved`() {
        val project = temporary.newFolder().toPath()
        compile(project, "classes", "示例", "package 示例包; public class 示例 {}")
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, project.resolve("all-in-one/classes"))
        URLClassLoader(arrayOf(roots.single().toUri().toURL()), null).use {
            assertEquals("示例包.示例", it.loadClass("示例包.示例").name)
        }
    }

    @Test fun `cancellation propagates from scanning and copying`() {
        val project = temporary.newFolder().toPath()
        compile(project, "classes", "Example", "public class Example {}")
        assertThrows(CancellationException::class.java) {
            ClassFileCollector.scan(project) { throw CancellationException() }
        }
        val scan = ClassFileCollector.scan(project)
        val output = project.resolve("all-in-one/classes")
        assertThrows(CancellationException::class.java) {
            ClassFileCollector.copy(scan.classes, output) { throw CancellationException() }
        }
        assertFalse(Files.exists(output))
    }
}
