package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ParameterIncrementNormalizerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(withSwitch: Boolean = true): Path {
        val folder = temporary.newFolder().toPath()
        val source = folder.resolve("ParameterBranch.java")
        Files.writeString(source, """
            public class ParameterBranch {
                public static int adjust(int value, boolean reset) {
                    int observed = value & 3;
                    if (reset) {
                        value = 0;
                        for (int i = 0; i < observed; i++) value += i;
                        value++;
                    }
                    return value + observed;
                }
                public static int adjust(int value) { value++; return value; }
                public static int decrement(int value) { value--; return value; }
                public int wide(long first, int value, double second, boolean fail) {
                    try {
                        value++;
                        if (fail) throw new IllegalArgumentException();
                        ${if (withSwitch) "switch (value & 3) { case 0: return value; case 1: return value + 8; default: return value - 3; }"
                          else "if ((value & 3) == 0) return value; if ((value & 3) == 1) return value + 8; return value - 3;"}
                    } catch (IllegalArgumentException e) { return value ^ 7; }
                }
                public static int localOnly(int value) { int local = value; local++; return local; }
                public static int larger(int value) { value += 2; return value; }
            }
        """.trimIndent())
        val log = folder.resolve("javac.log")
        val process = ProcessBuilder(System.getProperty("test.javac"), "--release", "17", "-g", source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        assertTrue("javac timed out", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(Files.readString(log), 0, process.exitValue())
        return folder.resolve("ParameterBranch.class")
    }

    private fun load(bytes: ByteArray): Class<*> = object : ClassLoader(javaClass.classLoader) {
        fun define() = defineClass(null, bytes, 0, bytes.size)
    }.define()

    @Test fun `parameter increment rewrites preserve overflow branches frames and exception handlers`() {
        val file = fixture()
        val original = Files.readAllBytes(file)
        val normalized = ParameterIncrementNormalizer.normalize(original,
            setOf("adjust(IZ)I", "adjust(I)I", "decrement(I)I", "wide(JIDZ)I"), Runnable {})!!
        assertArrayEquals(original, Files.readAllBytes(file))
        assertFalse(original.contentEquals(normalized))
        val before = load(original)
        val after = load(normalized)
        val a = before.getConstructor().newInstance()
        val b = after.getConstructor().newInstance()
        val values = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -2, -1, 0, 1, 2, Int.MAX_VALUE - 1, Int.MAX_VALUE) +
            List(1000) { java.util.Random(it.toLong()).nextInt() }
        for (value in values) {
            for (name in listOf("adjust", "decrement")) {
                assertEquals(before.getMethod(name, Int::class.javaPrimitiveType).invoke(null, value),
                    after.getMethod(name, Int::class.javaPrimitiveType).invoke(null, value))
            }
            for (flag in listOf(false, true)) {
                assertEquals(before.getMethod("adjust", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(null, value, flag),
                    after.getMethod("adjust", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(null, value, flag))
                val types = arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                assertEquals(before.getMethod("wide", *types).invoke(a, 11L, value, 2.5, flag),
                    after.getMethod("wide", *types).invoke(b, 11L, value, 2.5, flag))
            }
        }
    }

    @Test fun `no rewrite without an exact failed method and eligible parameter increment`() {
        val original = Files.readAllBytes(fixture())
        for (methods in listOf(emptySet(), setOf("missing(I)I"), setOf("localOnly(I)I"), setOf("larger(I)I"), setOf("adjust(J)I"))) {
            assertNull(ParameterIncrementNormalizer.normalize(original, methods, Runnable {}))
        }
        val normalized = ParameterIncrementNormalizer.normalize(original, setOf("decrement(I)I"), Runnable {})!!
        assertNull("Unselected overload must not be normalized", ParameterIncrementNormalizer.normalize(normalized, setOf("decrement(I)I"), Runnable {}))
        assertNotNull(ParameterIncrementNormalizer.normalize(normalized, setOf("adjust(I)I"), Runnable {}))
    }

    @Test fun `normalization propagates cancellation without modifying input`() {
        val original = Files.readAllBytes(fixture())
        val copy = original.copyOf()
        val canceled = CancellationException("stop")
        try {
            ParameterIncrementNormalizer.normalize(original, setOf("adjust(I)I"), Runnable { throw canceled })
            fail("cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(canceled, actual) }
        assertArrayEquals(copy, original)
    }

    @Test fun `bundled engine exports a conditional parameter increment with its real body`() {
        // The 2024 baseline has an unrelated invalid switch-arrow rendering bug.
        // Switch offsets/frames remain covered by the bytecode-equivalence test above.
        val file = fixture(withSwitch = false)
        val original = Files.readAllBytes(file)
        val result = IdeaJavaDecompiler(null).decompile(file, "ParameterBranch") {}
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertFalse(result.source.contains("Couldn't be decompiled"))
        assertTrue(result.source.contains("observed"))
        assertArrayEquals(original, Files.readAllBytes(file))
        // Execute only this synthetic fixture, never user-supplied bytecode.
        val source = file.resolveSibling("ParameterBranch.java")
        Files.writeString(source, result.source)
        val output = Files.createDirectories(file.parent.resolve("roundtrip"))
        val log = output.resolve("javac.log")
        val process = ProcessBuilder(System.getProperty("test.javac"), "--release", "17", "-d", output.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(Files.readString(log), 0, process.exitValue())
        val before = load(original).getMethod("adjust", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val after = load(Files.readAllBytes(output.resolve("ParameterBranch.class")))
            .getMethod("adjust", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        for (value in listOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0, 1, 2, 3, 100)) {
            for (flag in listOf(false, true)) assertEquals(before.invoke(null, value, flag), after.invoke(null, value, flag))
        }
    }
}
