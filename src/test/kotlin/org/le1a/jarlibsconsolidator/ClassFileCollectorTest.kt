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
        assertEquals(project.resolve("all-in-one/classes"), roots.single())
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
        assertEquals(setOf("a__target__classes", "b__build__classes__java__main"), roots.map { it.fileName.toString() }.toSet())
        roots.forEach { assertEquals(project.resolve("all-in-one/classes-conflicts"), it.parent) }
        assertFalse(Files.exists(project.resolve("all-in-one/classes/demo/Same.class")))
        val sourceIndex = Files.readString(project.resolve("all-in-one/classes-conflicts/sources.tsv"))
        assertTrue(sourceIndex.contains("a/target/classes/demo/Same.class"))
        assertTrue(sourceIndex.contains("b/build/classes/java/main/demo/Same.class"))
        val values = roots.map { root ->
            URLClassLoader(arrayOf(root.toUri().toURL()), null).use {
                it.loadClass("demo.Same").getMethod("value").invoke(null)
            }
        }
        assertEquals(setOf(1, 2), values.toSet())
    }

    @Test fun `identical bytecode with different filenames is deduplicated`() {
        val project = temporary.newFolder().toPath()
        val output = compile(project, "flat", "Same", "public class Same {}")
        Files.copy(output.resolve("Same.class"), output.resolve("copy.class"))
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, project.resolve("all-in-one/classes"))
        assertEquals(listOf(project.resolve("all-in-one/classes")), roots)
        assertTrue(Files.exists(roots.single().resolve("Same.class")))
        assertFalse(Files.exists(project.resolve("all-in-one/classes-conflicts")))
    }

    @Test fun `nonconflicting classes from multiple modules share one classes root`() {
        val project = temporary.newFolder().toPath()
        compile(project, "a/target/classes", "First", "package demo; public class First {}")
        compile(project, "b/build/classes", "Second", "package other; public class Second {}")
        val output = project.resolve("all-in-one/classes")
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, output)
        assertEquals(listOf(output), roots)
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use {
            assertEquals("demo.First", it.loadClass("demo.First").name)
            assertEquals("other.Second", it.loadClass("other.Second").name)
        }
    }

    @Test fun `identical bytecode from different folders only produces one copy`() {
        val project = temporary.newFolder().toPath()
        val original = compile(project, "a/classes", "Same", "package demo; public class Same {}")
        val duplicate = project.resolve("b/classes/demo/Same.class")
        Files.createDirectories(duplicate.parent)
        Files.copy(original.resolve("demo/Same.class"), duplicate)
        val output = project.resolve("all-in-one/classes")
        assertEquals(listOf(output), ClassFileCollector.copy(ClassFileCollector.scan(project).classes, output))
        assertEquals(-1L, Files.mismatch(original.resolve("demo/Same.class"), output.resolve("demo/Same.class")))
    }

    @Test fun `mixed collection deduplicates versions and isolates only conflicting names`() {
        val project = temporary.newFolder().toPath()
        val first = compile(project, "a/classes", "Same", "package demo; public class Same { public static int value() { return 1; } }")
        compile(project, "b/classes", "Same", "package demo; public class Same { public static int value() { return 2; } }")
        compile(project, "b/classes", "Unique", "package demo; public class Unique {}")
        val duplicate = project.resolve("c/classes/demo/Same.class")
        Files.createDirectories(duplicate.parent)
        Files.copy(first.resolve("demo/Same.class"), duplicate)
        val output = project.resolve("all-in-one/classes")
        val scan = ClassFileCollector.scan(project)
        // Reverse the input to prove representative selection is independent of caller ordering.
        val roots = ClassFileCollector.copy(scan.classes.reversed(), output)
        assertEquals(3, roots.size)
        assertEquals(output, roots.first())
        assertTrue(Files.exists(output.resolve("demo/Unique.class")))
        assertFalse(Files.exists(output.resolve("demo/Same.class")))
        assertEquals(listOf("a__classes", "b__classes"), roots.drop(1).map { it.fileName.toString() })
        assertFalse(Files.exists(project.resolve("all-in-one/classes-conflicts/c__classes")))
        roots.drop(1).forEach { assertFalse(Files.exists(it.resolve("demo/Unique.class"))) }
    }

    @Test fun `different versions in the same flat folder retain source filenames in root names`() {
        val project = temporary.newFolder().toPath()
        val first = compile(project, "first", "Same", "public class Same { public static int value() { return 1; } }")
        val second = compile(project, "second", "Same", "public class Same { public static int value() { return 2; } }")
        val flat = Files.createDirectories(project.resolve("flat"))
        Files.move(first.resolve("Same.class"), flat.resolve("original.class"))
        Files.move(second.resolve("Same.class"), flat.resolve("renamed.class"))
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, project.resolve("all-in-one/classes"))
        assertEquals(listOf("flat", "flat__from-renamed.class"), roots.map { it.fileName.toString() })
        val values = roots.map { root ->
            URLClassLoader(arrayOf(root.toUri().toURL()), null).use {
                it.loadClass("Same").getMethod("value").invoke(null)
            }
        }
        assertEquals(listOf(1, 2), values)
    }

    @Test fun `ambiguous flattened folder names are disambiguated without losing either version`() {
        val project = temporary.newFolder().toPath()
        compile(project, "a/b", "Same", "public class Same { public static int value() { return 1; } }")
        compile(project, "a__b", "Same", "public class Same { public static int value() { return 2; } }")
        val roots = ClassFileCollector.copy(ClassFileCollector.scan(project).classes, project.resolve("all-in-one/classes"))
        assertEquals(2, roots.distinct().size)
        assertEquals("a__b", roots[0].fileName.toString())
        assertTrue(roots[1].fileName.toString().startsWith("a__b--"))
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

    @Test fun `existing different target is preserved and incoming class uses source named conflict root`() {
        val project = temporary.newFolder().toPath()
        val incoming = compile(project, "libs/classes/sign", "AuthCallBackController",
            "package com.qiyuesuo.callback; public class AuthCallBackController { public static int value() { return 2; } }")
        val existing = compile(project, "previous", "AuthCallBackController",
            "package com.qiyuesuo.callback; public class AuthCallBackController { public static int value() { return 1; } }")
        val relative = Path.of("com/qiyuesuo/callback/AuthCallBackController.class")
        val output = project.resolve("all-in-one/classes")
        Files.createDirectories(output.resolve(relative).parent)
        Files.copy(existing.resolve(relative), output.resolve(relative))
        val entry = ClassFileCollector.ClassFile(incoming.resolve(relative), relative, incoming, project)
        val roots = ClassFileCollector.copy(listOf(entry), output)
        assertEquals(listOf(output, project.resolve("all-in-one/classes-conflicts/libs__classes__sign")), roots)
        assertArrayEquals(Files.readAllBytes(existing.resolve(relative)), Files.readAllBytes(output.resolve(relative)))
        assertArrayEquals(Files.readAllBytes(incoming.resolve(relative)), Files.readAllBytes(roots[1].resolve(relative)))
        assertTrue(Files.readString(project.resolve("all-in-one/classes-conflicts/sources.tsv")).contains("libs__classes__sign"))
        val values = roots.map { root -> URLClassLoader(arrayOf(root.toUri().toURL()), null).use {
            it.loadClass("com.qiyuesuo.callback.AuthCallBackController").getMethod("value").invoke(null)
        } }
        assertEquals(listOf(1, 2), values)
    }

    @Test fun `repeated copy reuses identical existing class without creating conflict roots`() {
        val project = temporary.newFolder().toPath()
        compile(project, "libs", "Example", "package demo; public class Example {}")
        val classes = ClassFileCollector.scan(project).classes
        val output = project.resolve("all-in-one/classes")
        assertEquals(listOf(output), ClassFileCollector.copy(classes, output))
        assertEquals(listOf(output), ClassFileCollector.copy(classes, output))
        assertFalse(Files.exists(project.resolve("all-in-one/classes-conflicts")))
    }

    @Test fun `case distinct binary names retain both original bytecodes`() {
        val project = temporary.newFolder().toPath()
        val first = compile(project, "first", "AuthCallbackController",
            "package demo; public class AuthCallbackController {}")
        val second = compile(project, "second", "AuthCallBackController",
            "package demo; public class AuthCallBackController {}")
        val classes = ClassFileCollector.scan(project).classes
        val roots = ClassFileCollector.copy(classes, project.resolve("all-in-one/classes"))
        // Works on either case-sensitive or case-insensitive disks; compare actual bytecode names.
        val copied = roots.flatMap { root -> Files.walk(root).use { files ->
            files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.toList()
        } }.associateBy { ClassFileCollector.readInternalName(it) }
        assertEquals(setOf("demo/AuthCallbackController", "demo/AuthCallBackController"), copied.keys)
        assertArrayEquals(Files.readAllBytes(first.resolve("demo/AuthCallbackController.class")),
            Files.readAllBytes(copied.getValue("demo/AuthCallbackController")))
        assertArrayEquals(Files.readAllBytes(second.resolve("demo/AuthCallBackController.class")),
            Files.readAllBytes(copied.getValue("demo/AuthCallBackController")))
    }

    @Test fun `missing source failure retains both paths and original filesystem cause`() {
        val project = temporary.newFolder().toPath()
        val source = Files.write(project.resolve("input.class"), byteArrayOf(1, 2, 3))
        val output = project.resolve("all-in-one/classes")
        val target = output.resolve("demo/Existing.class")
        Files.createDirectories(target.parent)
        Files.write(target, byteArrayOf(9))
        Files.delete(source)
        val entry = ClassFileCollector.ClassFile(source, Path.of("demo/Existing.class"), project, project)
        val error = assertThrows(java.io.IOException::class.java) {
            ClassFileCollector.copy(listOf(entry), output)
        }
        assertTrue(error.message!!.contains(source.toString()))
        assertTrue(error.message!!.contains(target.toString()))
        assertTrue(error.cause is java.nio.file.NoSuchFileException)
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(target))
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
