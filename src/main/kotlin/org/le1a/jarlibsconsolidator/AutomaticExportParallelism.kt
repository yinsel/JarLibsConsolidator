package org.le1a.jarlibsconsolidator

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Resolve optional engine capabilities once, then sample resources at most once per second. */
internal class AutomaticExportParallelism(decompiler: ClassDecompiler) {
    private val hint = decompiler.parallelismHint()
    private var sampledAt = 0L
    private var cached = 1
    @Synchronized fun current(): Int {
        val now = System.nanoTime()
        if (sampledAt == 0L || now - sampledAt >= TimeUnit.SECONDS.toNanos(1)) {
            val snapshot = ExportResources.snapshot()
            val engineLimit = hint?.invoke()
            val next = ExportResources.limit(snapshot, engineLimit)
            if (sampledAt == 0L || next != cached) PluginDiagnostics.info(
                "Automatic export scheduling: limit=$next, engineHint=$engineLimit, cpus=${snapshot.cpus}, " +
                    "freeMiB=${snapshot.free / ExportResources.MIB}, usedHeapMiB=${snapshot.heapUsed / ExportResources.MIB}")
            cached = next
            sampledAt = now
        }
        return cached
    }
}

internal object ExportResources {
    const val MIB = 1024L * 1024
    data class Snapshot(val cpus: Int, val total: Long, val free: Long, val heapMax: Long, val heapUsed: Long)
    fun snapshot(): Snapshot {
        val runtime = Runtime.getRuntime()
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val memory = try { (os?.totalMemorySize ?: -1L) to (os?.freeMemorySize ?: -1L) }
        catch (_: UnsupportedOperationException) { -1L to -1L }
        return Snapshot(runtime.availableProcessors(), memory.first, memory.second,
            runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory())
    }

    fun limit(s: Snapshot, engineHint: Int? = null): Int {
        val hint = engineHint?.takeIf { it > 0 }
        // Engines with a hint account for their own heap; allow space here for text/IO.
        // Stock IDEA has no hint and executes inside the IDE, so budget more per task.
        val cost = (if (hint == null) 256 else 64) * MIB
        val reserve = maxOf(512 * MIB, s.heapMax / 4)
        val heapJobs = ((s.heapMax - s.heapUsed - reserve) / cost).coerceAtLeast(1)
        val physicalJobs = if (s.total > 0 && s.free >= 0)
            ((s.free.coerceAtMost(s.total) - maxOf(1024 * MIB, s.total / 10)) / cost).coerceAtLeast(1) else 1
        return minOf((s.cpus - 1).coerceAtLeast(1).toLong(), heapJobs, physicalJobs,
            (hint ?: Int.MAX_VALUE).toLong()).toInt()
    }
}
