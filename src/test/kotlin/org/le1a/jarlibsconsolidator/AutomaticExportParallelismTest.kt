package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutomaticExportParallelismTest {
    private val mib = ExportResources.MIB
    private fun machine(cpus: Int = 16, free: Long = 24000, heap: Long = 4096, used: Long = 1024) =
        ExportResources.Snapshot(cpus, 32768 * mib, free * mib, heap * mib, used * mib)

    @Test fun `plan respects engine capacity CPU IDE heap and physical memory`() {
        assertEquals(8, ExportResources.limit(machine())) // Stock in-process engine.
        assertEquals(15, ExportResources.limit(machine(), 32))
        assertEquals(3, ExportResources.limit(machine(), 3))
        assertEquals(1, ExportResources.limit(machine(cpus = 1), 32))
        assertEquals(1, ExportResources.limit(machine(free = 0), 32))
        assertEquals(1, ExportResources.limit(machine(used = 3900), 32))
        assertEquals(1, ExportResources.limit(machine().copy(total = -1, free = -1), 32))
        assertEquals(8, ExportResources.limit(machine(), -1))
    }

    class EngineHint { var limit = 3; fun getRecommendedParallelism() = limit }
    @Test fun `optional native capability follows changes and supports stock plugins`() {
        assertNull(EditorIdeaText.optionalParallelismHint(Any()))
        val engine = EngineHint()
        val hint = requireNotNull(EditorIdeaText.optionalParallelismHint(engine))
        assertEquals(3, hint())
        engine.limit = 8
        assertEquals(8, hint())
        engine.limit = 0
        assertNull(hint())
    }

    @Test fun `pressure pauses new native calls then recovery resumes without losing output`() {
        val limit = AtomicInteger(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val peakAfterPressure = AtomicInteger()
        val one = CountDownLatch(1)
        val three = CountDownLatch(3)
        val release = CountDownLatch(1)
        val engine = ClassDecompiler { _, _, _ ->
            val n = active.incrementAndGet()
            peak.updateAndGet { maxOf(n, it) }
            if (calls.incrementAndGet() > 3) peakAfterPressure.updateAndGet { maxOf(n, it) }
            one.countDown(); three.countDown()
            try { check(release.await(10, TimeUnit.SECONDS)); DecompiledClass("class Sample {}") }
            finally { active.decrementAndGet() }
        }
        val groups = (0 until 120).map { listOf(Path.of("unused") to "$it") }
        BatchParallelDecompiler(groups, engine, 3, {}, parallelismLimit = { limit.get() },
            writeSource = { _, _, _ -> }).use { pipeline ->
            try {
                assertTrue(one.await(5, TimeUnit.SECONDS))
                assertFalse(three.await(200, TimeUnit.MILLISECONDS))
                assertEquals(1, peak.get())
                limit.set(3)
                assertTrue(three.await(5, TimeUnit.SECONDS))
                assertEquals(3, peak.get())
                limit.set(1)
                assertEquals(3, active.get()) // Already running tasks are not interrupted.
            } finally { release.countDown() }
            assertEquals((0 until 120).toSet(), (0 until 120).map { pipeline.next().index }.toSet())
        }
        assertEquals(0, active.get())
        assertEquals(1, peakAfterPressure.get())
    }
}
