package org.le1a.jarlibsconsolidator

import java.io.IOException
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Completed work contains diagnostics only; source text never enters a queue or Future. */
internal data class WrittenClass(val warnings: List<String>, val issues: List<DecompilationIssue>)

/** Write each class immediately; batch queues carry at most one small outcome each. */
internal class BatchParallelDecompiler(
    private val groups: List<List<Pair<Path, String>>>,
    private val decompiler: ClassDecompiler,
    parallelism: Int,
    private val checkCanceled: () -> Unit,
    private val batchSize: Int = 40,
    private val writeSource: (Int, String, () -> Unit) -> Unit
) : AutoCloseable {
    private data class Outcome(val result: WrittenClass? = null, val error: Exception? = null)
    private class Batch(val count: Int) {
        val queue = ArrayBlockingQueue<Outcome>(1)
        lateinit var future: Future<*>
        var consumed = 0
    }
    init { require(batchSize > 0) }
    private val stopped = AtomicBoolean()
    private val fatal = AtomicReference<Throwable?>()
    private val workers = minOf(parallelism.coerceAtLeast(1),
        ((groups.size.toLong() + batchSize - 1) / batchSize).toInt().coerceAtLeast(1))
    private val pool = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "JarLibs-decompiler-batch").apply { isDaemon = true }
    }
    private val pending = ArrayDeque<Batch>()
    private var submitted = 0

    init { repeat(workers) { submit() } }

    private fun checkWorkerCanceled() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        fatal.get()?.let { throw it }
        checkCanceled()
    }

    // Keep this scope separate from queue backpressure, so a blocked worker retains no completed source.
    private fun runOne(index: Int): Outcome {
        checkWorkerCanceled()
        val result = try {
            decompiler.decompileGroup(groups[index], ::checkWorkerCanceled).also {
                if (it.source.isBlank()) throw IOException("反编译器未生成源码")
            }
        } catch (e: Exception) {
            PluginDiagnostics.rethrowCancellation(e)
            checkWorkerCanceled()
            return Outcome(error = e)
        }
        // Disk failures abort the export; don't keep decompiling when the output cannot be written.
        writeSource(index, result.source, ::checkWorkerCanceled)
        return Outcome(WrittenClass(result.warnings, result.issues))
    }

    private fun submit() {
        if (submitted >= groups.size || stopped.get()) return
        val start = submitted
        val end = minOf(groups.size.toLong(), start.toLong() + batchSize).toInt()
        submitted = end
        val batch = Batch(end - start)
        batch.future = pool.submit {
            try { for (index in start until end) {
                val outcome = runOne(index)
                while (!batch.queue.offer(outcome, 50, TimeUnit.MILLISECONDS)) checkWorkerCanceled()
            } } catch (e: Throwable) { fatal.compareAndSet(null, e); throw e }
        }
        pending.addLast(batch)
    }

    fun next(): WrittenClass {
        checkCanceled()
        val batch = pending.first
        while (true) {
            fatal.get()?.let { throw it }
            checkCanceled()
            val outcome = try { batch.queue.poll(50, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("反编译导出被中断").apply { initCause(e) }
            }
            if (outcome != null) {
                batch.consumed++
                if (batch.consumed == batch.count) {
                    pending.removeFirst()
                    checkCanceled()
                    submit()
                }
                outcome.error?.let { throw it }
                return outcome.result!!
            }
            if (batch.future.isDone) {
                try { batch.future.get() }
                catch (e: ExecutionException) { throw (e.cause ?: e) }
                if (batch.queue.isNotEmpty()) continue // An offer can race the timed poll and future completion.
                throw IllegalStateException("反编译任务结束但没有输出结果")
            }
        }
    }

    override fun close() {
        stopped.set(true)
        pending.forEach { it.future.cancel(true) }
        pool.shutdownNow()
        var interrupted = false
        while (!pool.isTerminated) {
            try { pool.awaitTermination(50, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        pending.clear()
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        fun defaultParallelism(): Int = parallelismFor(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())
        internal fun parallelismFor(heap: Long, cpus: Int): Int {
            // Leave heap for IDEA itself; native decompilation still has an unavoidable working set.
            val allowance = ((heap - 512L * 1024 * 1024) / (512L * 1024 * 1024)).coerceAtLeast(1)
            return minOf(2L, cpus.toLong().coerceAtLeast(1), allowance).toInt()
        }
    }
}
