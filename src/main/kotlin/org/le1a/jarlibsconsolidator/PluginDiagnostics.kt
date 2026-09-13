package org.le1a.jarlibsconsolidator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** A stable category for IDEA's Debug Log Settings; never log bytecode or source contents. */
internal object PluginDiagnostics {
    private val log by lazy { Logger.getInstance("#org.le1a.jarlibsconsolidator") }

    fun info(message: String) = log.info(message)
    fun warn(message: String, cause: Throwable? = null) = log.warn(message, cause)
    fun debug(message: () -> String) { if (log.isDebugEnabled) log.debug(message()) }
    fun debug(message: String, cause: Throwable) { if (log.isDebugEnabled) log.debug(message, cause) }

    fun rethrowCancellation(error: Throwable) {
        for (cause in causes(error)) {
            if (cause is ProcessCanceledException) throw cause
            if (cause is CancellationException) throw cause
        }
    }

    fun describe(error: Throwable): String = causes(error).joinToString("\n原因：") {
        "${it.javaClass.name}: ${it.message ?: "（无详细信息）"}"
    }

    fun userMessage(operation: String, error: Throwable): String =
        "$operation\n${describe(error)}\n\n完整异常堆栈已记录到 idea.log（Help → Show Log in Explorer）。"

    /** Retain both paths and the original filesystem exception, without changing copy semantics. */
    fun <T> io(context: () -> String, operation: () -> T): T = try { operation() }
        catch (error: IOException) { throw IOException("${context()}\n${error.javaClass.name}: ${error.message}", error) }

    private fun causes(error: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            result.add(current)
            current = current.cause
        }
        return result
    }
}
