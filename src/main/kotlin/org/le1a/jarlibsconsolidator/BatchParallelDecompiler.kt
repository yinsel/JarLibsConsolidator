package org.le1a.jarlibsconsolidator

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Completed work contains diagnostics only; source text never enters a queue or Future. */
internal data class WrittenClass(val warnings: List<String>, val issues: List<DecompilationIssue>)
internal data class CompletedClass(val index: Int, val result: WrittenClass? = null, val error: Exception? = null)

/** Workers claim forty-family batches independently and write each source before reporting completion. */
internal class BatchParallelDecompiler(
    private val groups: List<List<Pair<Path, String>>>,
    private val decompiler: ClassDecompiler,
    parallelism: Int,
    private val checkCanceled: () -> Unit,
    private val batchSize: Int = 40,
    private val writeSource: (Int, String, () -> Unit) -> Unit
) : AutoCloseable {
    init { require(batchSize > 0) }
    private val stopped = AtomicBoolean()
    private val fatal = AtomicReference<Throwable?>()
    private val batchCount = ((groups.size.toLong() + batchSize - 1) / batchSize).toInt()
    internal val workers = minOf(parallelism.coerceAtLeast(1), batchCount.coerceAtLeast(1))
    private val nextBatch = AtomicInteger()
    // Only bounded diagnostics cross threads. A slow family cannot block consuming other families.
    private val completed = ArrayBlockingQueue<CompletedClass>((workers.toLong() * 40).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    private val remainingWorkers = AtomicInteger(workers)
    private val pool = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "JarLibs-decompiler-batch").apply { isDaemon = true }
    }

    init {
        repeat(workers) {
            pool.submit {
                try {
                    while (true) {
                        checkWorkerCanceled()
                        val batch = nextBatch.getAndIncrement()
                        if (batch >= batchCount) break
                        val start = batch * batchSize
                        val end = minOf(groups.size.toLong(), start.toLong() + batchSize).toInt()
                        for (index in start until end) {
                            val outcome = runOne(index)
                            while (!completed.offer(outcome, 50, TimeUnit.MILLISECONDS)) checkWorkerCanceled()
                        }
                    }
                } catch (e: Throwable) {
                    fatal.compareAndSet(null, e)
                } finally {
                    remainingWorkers.decrementAndGet()
                }
            }
        }
    }

    private fun checkWorkerCanceled() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        fatal.get()?.let { throw it }
        checkCanceled()
    }

    // Separate scope: a worker blocked on diagnostics backpressure retains no completed source.
    private fun runOne(index: Int): CompletedClass {
        checkWorkerCanceled()
        val result = try {
            decompiler.decompileGroup(groups[index], ::checkWorkerCanceled).also {
                if (it.source.isBlank()) throw IOException("反编译器未生成源码")
            }
        } catch (e: Exception) {
            PluginDiagnostics.rethrowCancellation(e)
            checkWorkerCanceled()
            return CompletedClass(index, error = e)
        }
        // Disk failures abort all workers rather than treating output failures as bad bytecode.
        writeSource(index, result.source, ::checkWorkerCanceled)
        return CompletedClass(index, WrittenClass(result.warnings, result.issues))
    }

    fun next(): CompletedClass {
        while (true) {
            fatal.get()?.let { throw it }
            checkCanceled()
            val outcome = try { completed.poll(50, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("反编译导出被中断").apply { initCause(e) }
            }
            if (outcome != null) return outcome
            if (remainingWorkers.get() == 0) {
                fatal.get()?.let { throw it }
                if (completed.isNotEmpty()) continue // An offer may race the timed poll.
                throw IllegalStateException("反编译任务结束但没有输出结果")
            }
        }
    }

    override fun close() {
        stopped.set(true)
        pool.shutdownNow()
        var interrupted = false
        while (!pool.isTerminated) {
            try { pool.awaitTermination(50, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        completed.clear()
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        fun analyzerParallelism(cpus: Int = Runtime.getRuntime().availableProcessors()): Int =
            (cpus.toLong().coerceAtLeast(1) * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun defaultParallelism(): Int {
            val runtime = Runtime.getRuntime()
            return parallelismFor(runtime.maxMemory(), runtime.availableProcessors(), runtime.totalMemory() - runtime.freeMemory())
        }

        internal fun parallelismFor(heap: Long, cpus: Int, used: Long = 0): Int {
            // Estimate available headroom without a forced GC. Reserve space for IDEA as well as
            // existing live objects; this is a scheduling estimate, not an engine memory limit.
            val reserve = maxOf(512L * 1024 * 1024, heap / 4)
            val allowance = ((heap - used.coerceAtLeast(0) - reserve) / (256L * 1024 * 1024)).coerceAtLeast(1)
            return minOf(analyzerParallelism(cpus).toLong(), allowance).toInt()
        }
    }
}
