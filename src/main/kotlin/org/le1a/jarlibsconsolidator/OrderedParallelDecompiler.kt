package org.le1a.jarlibsconsolidator

import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded work window; only the calling thread writes ZIP entries, in stable order. */
internal class OrderedParallelDecompiler(
    private val inputs: List<Pair<Path, String>>,
    private val decompiler: ClassDecompiler,
    parallelism: Int,
    private val checkCanceled: () -> Unit,
    private val groups: List<List<Pair<Path, String>>>? = null
) : AutoCloseable {
    private val stopped = AtomicBoolean()
    private val workers = parallelism.coerceIn(1, 4)
    private val pool = if (workers > 1 && inputs.size > 1) Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "JarLibs-decompiler").apply { isDaemon = true }
    } else null
    private val pending = ArrayDeque<Future<DecompiledClass>>()
    private var submitted = 0
    private var consumed = 0

    init { repeat(minOf(workers, inputs.size)) { submit() } }

    private fun checkWorkerCanceled() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        checkCanceled()
    }

    private fun submit() {
        if (pool == null || submitted >= inputs.size) return
        val index = submitted++
        val (path, name) = inputs[index]
        pending.addLast(pool.submit<DecompiledClass> {
            checkWorkerCanceled()
            groups?.let { decompiler.decompileGroup(it[index], ::checkWorkerCanceled) }
                ?: decompiler.decompile(path, name, ::checkWorkerCanceled)
        })
    }

    fun next(): DecompiledClass {
        checkCanceled()
        if (pool == null) {
            val index = consumed++
            val (path, name) = inputs[index]
            return groups?.let { decompiler.decompileGroup(it[index], checkCanceled) }
                ?: decompiler.decompile(path, name, checkCanceled)
        }
        val future = pending.first
        try {
            while (true) {
                checkCanceled()
                try { return future.get(50, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { /* Poll cancellation even when an earlier class is slow. */ }
                catch (e: ExecutionException) { throw (e.cause ?: e) }
                catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("反编译导出被中断").apply { initCause(e) }
                }
            }
        } finally {
            pending.removeFirst()
            consumed++
            if (!future.isDone) future.cancel(true)
            // Cancellation must not start another class.
            if (!stopped.get() && !future.isCancelled) {
                checkCanceled()
                submit()
            }
        }
    }

    override fun close() {
        stopped.set(true)
        pending.forEach { it.cancel(true) }
        pool?.shutdownNow()
        // Engine cancellation is cooperative. Join workers before the caller removes snapshots.
        var interrupted = false
        if (pool != null) while (!pool.isTerminated) {
            try { pool.awaitTermination(50, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        fun defaultParallelism(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}
