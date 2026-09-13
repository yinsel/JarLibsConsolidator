package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DecompilerOptimizationTest {
    @Test fun `cache validates bytecode and name and obeys LRU entry limit`() {
        val cache = DecompilationCache(maxEntries = 2)
        val a = byteArrayOf(1); val b = byteArrayOf(2); val c = byteArrayOf(3)
        val ka = cache.key("Same", a); val kb = cache.key("Same", b); val kc = cache.key("Other", c)
        assertNotEquals(ka, kb)
        assertNotEquals(ka, cache.key("Other", a))
        cache.put(ka, a, DecompiledClass("one")); cache.put(kb, b, DecompiledClass("two"))
        assertEquals("one", cache.get(ka, a)?.source)
        assertNull(cache.get(ka, b)) // Even a forced digest/key collision cannot return another version.
        cache.put(kc, c, DecompiledClass("three"))
        assertNull(cache.get(kb, b))
        assertEquals("one", cache.get(ka, a)?.source)
    }

    @Test fun `cache enforces byte budget and never retains warnings or blank sources`() {
        val cache = DecompilationCache(maxBytes = 700)
        val input = byteArrayOf(1)
        cache.put("a", input, DecompiledClass("a".repeat(80)))
        cache.put("b", input, DecompiledClass("b".repeat(80)))
        assertNull(cache.get("a", input)); assertNotNull(cache.get("b", input))
        cache.put("large", input, DecompiledClass("x".repeat(1000)))
        cache.put("warning", input, DecompiledClass("source", listOf("partial")))
        cache.put("blank", input, DecompiledClass(" "))
        assertNull(cache.get("large", input)); assertNull(cache.get("warning", input)); assertNull(cache.get("blank", input))
    }

    @Test fun `parallel scheduler bounds active work and preserves submission order`() {
        val started = CountDownLatch(2)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val decompiler = ClassDecompiler { _, name, _ ->
            calls.incrementAndGet()
            val count = active.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf)
            try {
                started.countDown()
                check(started.await(5, TimeUnit.SECONDS)) { "Workers did not run concurrently" }
                DecompiledClass(name)
            } finally { active.decrementAndGet() }
        }
        val inputs = (0..19).map { Path.of("unused") to "Class$it" }
        OrderedParallelDecompiler(inputs, decompiler, 2, {}).use { pipeline ->
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get()) // No unbounded queue of completed sources.
            inputs.forEach { assertEquals(it.second, pipeline.next().source) }
        }
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun `cancellation joins running workers and does not schedule remaining classes`() {
        val started = CountDownLatch(2)
        val canceled = AtomicBoolean()
        val active = AtomicInteger()
        val calls = AtomicInteger()
        val decompiler = ClassDecompiler { _, _, check ->
            active.incrementAndGet(); calls.incrementAndGet(); started.countDown()
            try {
                while (!Thread.currentThread().isInterrupted) { check(); Thread.yield() }
                throw CancellationException()
            }
            finally { active.decrementAndGet() }
        }
        OrderedParallelDecompiler((0..9).map { Path.of("unused") to "Class$it" }, decompiler, 2,
            { if (canceled.get()) throw CancellationException() }).use { pipeline ->
            assertTrue(started.await(5, TimeUnit.SECONDS))
            canceled.set(true)
            assertThrows(CancellationException::class.java) { pipeline.next() }
        }
        assertEquals(0, active.get())
        assertEquals(2, calls.get())
    }
}
