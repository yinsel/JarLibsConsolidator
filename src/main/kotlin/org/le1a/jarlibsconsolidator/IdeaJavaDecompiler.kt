package org.le1a.jarlibsconsolidator

import org.jetbrains.java.decompiler.main.CancellationManager
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Manifest

/** Uses the engine supplied by the installed IDEA Java Bytecode Decompiler plugin. */
internal class IdeaJavaDecompiler(
    private val cache: DecompilationCache? = sessionCache,
    private val engine: IdeaDecompilationAttempt = BundledIdeaDecompilationAttempt
) : ClassDecompiler {
    override fun decompile(file: Path, internalName: String, checkCanceled: () -> Unit): DecompiledClass {
        checkCanceled()
        val bytecode = Files.readAllBytes(file)
        val key = cache?.key(internalName, bytecode)
        if (key != null) cache?.get(key, bytecode)?.let { checkCanceled(); return it }
        val result = try {
            engine.decompile(file, internalName, bytecode, true, checkCanceled)
        } catch (first: IOException) {
            checkCanceled()
            PluginDiagnostics.debug("Retry IDEA decompiler without generic signatures: class=$internalName", first)
            val recovered = try {
                engine.decompile(file, internalName, bytecode, false, checkCanceled)
            } catch (second: IOException) {
                second.addSuppressed(first)
                throw second
            }
            recovered.copy(warnings = listOf(
                "已关闭泛型签名重建后恢复导出（仍使用 IDEA 内置引擎）；泛型类型信息可能退化为原始类型。首次失败：${first.message}"
            ) + recovered.warnings)
        }
        checkCanceled()
        // Warning-bearing fallback results are intentionally not cached as clean successes.
        if (key != null) cache?.put(key, bytecode, result)
        return result
    }

    companion object { private val sessionCache = DecompilationCache() }
}

/** Separate retry policy from the installed engine so version-specific failures can be replayed. */
internal fun interface IdeaDecompilationAttempt {
    fun decompile(file: Path, internalName: String, bytecode: ByteArray,
                  genericSignatures: Boolean, checkCanceled: () -> Unit): DecompiledClass
}

internal object BundledIdeaDecompilationAttempt : IdeaDecompilationAttempt {
    override fun decompile(
        file: Path, internalName: String, bytecode: ByteArray,
        genericSignatures: Boolean, checkCanceled: () -> Unit
    ): DecompiledClass {
        checkCanceled()
        var source: String? = null
        val warnings = mutableListOf<String>()
        var engineFailure: Throwable? = null
        val saver = object : IResultSaver {
            override fun saveClassFile(path: String?, qualifiedName: String?, entryName: String?, content: String?, mapping: IntArray?) {
                if (qualifiedName == internalName) source = content
            }
            override fun saveFolder(path: String?) {}
            override fun copyFile(source: String?, path: String?, entryName: String?) {}
            override fun createArchive(path: String?, archiveName: String?, manifest: Manifest?) {}
            override fun saveDirEntry(path: String?, archiveName: String?, entryName: String?) {}
            override fun copyEntry(source: String?, path: String?, archiveName: String?, entry: String?) {}
            override fun saveClassEntry(path: String?, archiveName: String?, qualifiedName: String?, entryName: String?, content: String?) {}
            override fun closeArchive(path: String?, archiveName: String?) {}
        }
        val logger = object : IFernflowerLogger() {
            override fun writeMessage(message: String, severity: Severity) {
                if (severity >= Severity.WARN) warnings.add(message)
            }
            override fun writeMessage(message: String, severity: Severity, t: Throwable) {
                // Fernflower may catch failures (including cancellation) and report them here.
                if (t is CancellationManager.CanceledException) throw t
                PluginDiagnostics.rethrowCancellation(t)
                if (severity >= Severity.WARN && engineFailure == null) engineFailure = t
                writeMessage("$message: ${t.javaClass.name}: ${t.message}", severity)
            }
        }
        val cancellation = object : CancellationManager {
            override fun checkCanceled() {
                try { checkCanceled.invoke() } catch (e: RuntimeException) { throw CancellationManager.CanceledException(e) }
            }
            override fun startMethod(className: String?, methodName: String?) { checkCanceled() }
            override fun finishMethod(className: String?, methodName: String?) { checkCanceled() }
        }
        val provider = IBytecodeProvider { externalPath, internalPath ->
            checkCanceled()
            if (internalPath != null || Path.of(externalPath).toAbsolutePath().normalize() != file.toAbsolutePath().normalize()) {
                throw IOException("不允许读取未选择的类：$externalPath")
            }
            bytecode
        }
        val options = mapOf<String, Any>(
            // Independent files preserve exact class filters, including blacklisted inner classes.
            IFernflowerPreferences.DECOMPILE_INNER to "0",
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to if (genericSignatures) "1" else "0",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "0",
            IFernflowerPreferences.NEW_LINE_SEPARATOR to "1",
            IFernflowerPreferences.INDENT_STRING to "    "
        )
        try {
            val engine = BaseDecompiler(provider, saver, options, logger, cancellation)
            engine.addSource(file.toFile())
            engine.decompileContext()
        }
        catch (e: CancellationManager.CanceledException) { throw (e.cause as? RuntimeException ?: e) }
        catch (e: Exception) {
            PluginDiagnostics.rethrowCancellation(e)
            throw IOException("IDEA 反编译失败: class=$internalName, genericSignatures=$genericSignatures", e)
        }
        finally { org.jetbrains.java.decompiler.main.DecompilerContext.setCurrentContext(null) }
        checkCanceled()
        val text = source?.takeIf { it.isNotBlank() }
            ?: throw IOException("IDEA 反编译器没有生成 $internalName 的源码（genericSignatures=$genericSignatures）：${warnings.joinToString()}", engineFailure)
        return DecompiledClass(text, warnings.toList())
    }

}
