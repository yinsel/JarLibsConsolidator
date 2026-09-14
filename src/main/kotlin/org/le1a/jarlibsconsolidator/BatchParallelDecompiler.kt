package org.le1a.jarlibsconsolidator

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface DecompilationPipeline : AutoCloseable {
    fun next(): DecompiledClass
}

/** Parallel batches, sequential families within each batch; one caller writes the ordered ZIP. */
internal class BatchParallelDecompiler(
    private val groups: List<List<Pair<Path, String>>>,
    private val decompiler: ClassDecompiler,
    parallelism: Int,
    private val checkCanceled: () -> Unit,
    private val batchSize: Int = 40,
    private val memoryBudget: Long = 32L * 1024 * 1024
) : DecompilationPipeline {
    private data class Outcome(var source: String? = null, val file: Path? = null,
                               val warnings: List<String> = emptyList(), val error: Exception? = null,
                               val issues: List<DecompilationIssue> = emptyList(),
                               val weight: Long = 0)
    init { require(batchSize > 0) }
    private val stopped = AtomicBoolean()
    private val retained = AtomicLong()
    private val staging = Files.createTempDirectory("jarlibs-source-batches-")
    private val workers = minOf(parallelism.coerceAtLeast(1),
        ((groups.size.toLong() + batchSize.coerceAtLeast(1) - 1) / batchSize.coerceAtLeast(1)).toInt().coerceAtLeast(1))
    private val pool = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "JarLibs-decompiler-batch").apply { isDaemon = true }
    }
    private val pending = ArrayDeque<Future<List<Outcome>>>()
    private var submitted = 0
    private var current: List<Outcome>? = null
    private var offset = 0

    init {
        repeat(workers) { submit() }
    }

    private fun checkWorkerCanceled() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        checkCanceled()
    }

    private fun retain(result: DecompiledClass): Outcome {
        val weight = result.source.length * 2L + 128L
        val total = retained.addAndGet(weight)
        if (total <= memoryBudget) return Outcome(source = result.source, warnings = result.warnings, issues = result.issues, weight = weight)
        retained.addAndGet(-weight)
        // Bound completed source retention independently of CPU count and batch count.
        val file = Files.createTempFile(staging, "source-", ".txt")
        Files.writeString(file, result.source)
        return Outcome(file = file, warnings = result.warnings, issues = result.issues)
    }

    private fun submit() {
        if (submitted >= groups.size || stopped.get()) return
        val start = submitted
        val end = minOf(groups.size.toLong(), start.toLong() + batchSize).toInt()
        submitted = end
        pending.addLast(pool.submit<List<Outcome>> {
            (start until end).map { index ->
                checkWorkerCanceled()
                try { retain(decompiler.decompileGroup(groups[index], ::checkWorkerCanceled)) }
                catch (e: Exception) {
                    PluginDiagnostics.rethrowCancellation(e)
                    checkWorkerCanceled()
                    Outcome(error = e)
                }
            }
        })
    }

    override fun next(): DecompiledClass {
        checkCanceled()
        if (current == null) {
            val future = pending.first
            while (true) {
                checkCanceled()
                try { current = future.get(50, TimeUnit.MILLISECONDS); break }
                catch (_: TimeoutException) { /* Keep IDEA cancellation responsive. */ }
                catch (e: ExecutionException) { throw (e.cause ?: e) }
                catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("反编译导出被中断").apply { initCause(e) }
                }
            }
            pending.removeFirst()
            offset = 0
        }
        val batch = current!!
        val item = batch[offset++]
        try {
            item.error?.let { throw it }
            val source = item.source ?: Files.readString(item.file!!)
            return DecompiledClass(source, item.warnings, item.issues)
        } finally {
            item.source = null
            retained.addAndGet(-item.weight)
            item.file?.let { Files.deleteIfExists(it) }
            if (offset == batch.size) {
                current = null
                checkCanceled()
                submit()
            }
        }
    }

    override fun close() {
        stopped.set(true)
        pending.forEach { it.cancel(true) }
        pool.shutdownNow()
        var interrupted = false
        while (!pool.isTerminated) {
            try { pool.awaitTermination(50, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        current = null
        pending.clear()
        staging.toFile().deleteRecursively()
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        fun defaultParallelism(): Int {
            val runtime = Runtime.getRuntime()
            // A conservative concurrency guard for high-core IDEs with a small configured heap.
            // This is a scheduling allowance, not a hard limit on an engine's allocations.
            val heapAllowance = (runtime.maxMemory() / (128L * 1024 * 1024)).coerceAtLeast(1)
            return minOf(runtime.availableProcessors().toLong() * 2, heapAllowance, Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
