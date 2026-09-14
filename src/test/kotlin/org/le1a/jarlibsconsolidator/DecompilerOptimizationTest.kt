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
