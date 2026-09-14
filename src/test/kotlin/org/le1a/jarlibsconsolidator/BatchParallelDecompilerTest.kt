package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BatchParallelDecompilerTest {
    private fun inputs(count: Int) = (0 until count).map { listOf(Path.of("unused") to "$it") }

    @Test fun `forty item batches run concurrently and preserve ordered output despite item failures`() {
        val started = CountDownLatch(2)
        val threads = Collections.synchronizedMap(mutableMapOf<Int, String>())
        val decompiler = ClassDecompiler { _, name, _ ->
            val index = name.toInt()
            threads[index] = Thread.currentThread().name + ":" + Thread.currentThread().id
            if (index == 0 || index == 40) { started.countDown(); check(started.await(5, TimeUnit.SECONDS)) }
            if (index == 13 || index == 41) throw IOException("broken-$index")
            DecompiledClass("source-$index", if (index == 17) listOf("warning") else emptyList())
        }
        BatchParallelDecompiler(inputs(83), decompiler, 2, {}, memoryBudget = 1).use { pipeline ->
            for (i in 0 until 83) {
                if (i == 13 || i == 41) assertEquals("broken-$i", assertThrows(IOException::class.java) { pipeline.next() }.message)
                else {
                    val result = pipeline.next()
                    assertEquals("source-$i", result.source)
                    assertEquals(if (i == 17) listOf("warning") else emptyList<String>(), result.warnings)
                }
            }
        }
        assertEquals(1, (0 until 40).map { threads[it] }.toSet().size)
        assertEquals(1, (40 until 80).map { threads[it] }.toSet().size)
        assertNotEquals(threads[0], threads[40])
    }

    @Test fun `cancellation stops both active batches and prevents later items from starting`() {
        val started = CountDownLatch(2)
        val cancel = AtomicBoolean()
        val active = AtomicInteger()
        val calls = AtomicInteger()
        val decompiler = ClassDecompiler { _, _, check ->
            active.incrementAndGet(); calls.incrementAndGet(); started.countDown()
            try { while (true) { check(); Thread.yield() }; @Suppress("UNREACHABLE_CODE") DecompiledClass("") }
            finally { active.decrementAndGet() }
        }
        BatchParallelDecompiler(inputs(200), decompiler, 2,
            { if (cancel.get()) throw CancellationException() }).use { pipeline ->
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cancel.set(true)
            assertThrows(CancellationException::class.java) { pipeline.next() }
        }
        assertEquals(2, calls.get())
        assertEquals(0, active.get())
    }
}
