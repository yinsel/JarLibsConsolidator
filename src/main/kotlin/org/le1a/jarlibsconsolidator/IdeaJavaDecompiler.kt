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
    private val engine: IdeaDecompilationAttempt = BundledIdeaDecompilationAttempt,
    override val mergeInnerClasses: Boolean = true
) : ClassDecompiler {

    override fun decompileGroup(inputs: List<Pair<Path, String>>, checkCanceled: () -> Unit): DecompiledClass {
        if (inputs.size == 1) return decompile(inputs[0].first, inputs[0].second, checkCanceled)
        checkCanceled()
        val bytes = inputs.map { (path, _) -> checkCanceled(); Files.readAllBytes(path) }
        // Include every selected member and its name: filter changes and inner-only edits invalidate the cache.
        val cacheInput = if (bytes.sumOf { it.size.toLong() } <= 16L * 1024 * 1024) {
            val buffer = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(buffer).use { out -> inputs.forEachIndexed { index, (_, name) ->
                out.writeUTF(name); out.writeInt(bytes[index].size); out.write(bytes[index])
            } }
            buffer.toByteArray()
        } else null
        val key = cacheInput?.let { cache?.key("family:" + inputs[0].second, it) }
        if (key != null && cacheInput != null) cache?.get(key, cacheInput)?.let { checkCanceled(); return it }
        val result = try {
            engine.decompileGroup(inputs, bytes, true, checkCanceled)
        } catch (first: IOException) {
            checkCanceled()
            PluginDiagnostics.debug("Retry IDEA class family without generic signatures: class=${inputs[0].second}", first)
            val recovered = try { engine.decompileGroup(inputs, bytes, false, checkCanceled) }
            catch (second: IOException) { second.addSuppressed(first); throw second }
            recovered.copy(warnings = listOf("已关闭泛型签名重建后恢复导出；泛型类型信息可能退化为原始类型。首次失败：${first.message}") + recovered.warnings)
        }
        checkCanceled()
        if (key != null && cacheInput != null) cache?.put(key, cacheInput, result)
        return result
    }

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
    fun decompileGroup(inputs: List<Pair<Path, String>>, bytes: List<ByteArray>,
                       genericSignatures: Boolean, checkCanceled: () -> Unit): DecompiledClass =
        BundledIdeaDecompilationAttempt.decompileGroup(inputs, bytes, genericSignatures, checkCanceled)
}

internal object BundledIdeaDecompilationAttempt : IdeaDecompilationAttempt {
    override fun decompile(
        file: Path, internalName: String, bytecode: ByteArray,
        genericSignatures: Boolean, checkCanceled: () -> Unit
    ): DecompiledClass = runEngine(listOf(file to internalName), listOf(bytecode), genericSignatures, false, checkCanceled)

    override fun decompileGroup(inputs: List<Pair<Path, String>>, bytes: List<ByteArray>,
                                genericSignatures: Boolean, checkCanceled: () -> Unit): DecompiledClass =
        runEngine(inputs, bytes, genericSignatures, true, checkCanceled)

    private fun runEngine(inputs: List<Pair<Path, String>>, bytes: List<ByteArray>,
                          genericSignatures: Boolean, merge: Boolean, checkCanceled: () -> Unit): DecompiledClass {
        val internalName = inputs.first().second
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
        val allowed = inputs.mapIndexed { index, (path, _) -> path.toAbsolutePath().normalize() to bytes[index] }.toMap()
        val provider = IBytecodeProvider { externalPath, internalPath ->
            checkCanceled()
            if (internalPath != null) throw IOException("不允许读取未选择的类：$externalPath")
            allowed[Path.of(externalPath).toAbsolutePath().normalize()]
                ?: throw IOException("不允许读取未选择的类：$externalPath")
        }
        val options = mapOf<String, Any>(
            // Only explicitly selected members of this origin/version enter the context.
            IFernflowerPreferences.DECOMPILE_INNER to if (merge) "1" else "0",
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to if (genericSignatures) "1" else "0",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "0",
            IFernflowerPreferences.NEW_LINE_SEPARATOR to "1",
            IFernflowerPreferences.INDENT_STRING to "    "
        )
        try {
            val engine = BaseDecompiler(provider, saver, options, logger, cancellation)
            inputs.forEach { (path, _) -> checkCanceled(); engine.addSource(path.toFile()) }
            engine.decompileContext()
        }
        catch (e: CancellationManager.CanceledException) { throw (e.cause as? RuntimeException ?: e) }
        catch (e: Exception) {
            PluginDiagnostics.rethrowCancellation(e)
            throw IOException("IDEA 反编译失败: class=$internalName, genericSignatures=$genericSignatures", e)
        }
        finally { org.jetbrains.java.decompiler.main.DecompilerContext.setCurrentContext(null) }
        checkCanceled()
        if (engineFailure != null) throw IOException("IDEA 反编译器未完整生成 $internalName 的源码：${warnings.joinToString()}", engineFailure)
        val text = source?.takeIf { it.isNotBlank() }
            ?: throw IOException("IDEA 反编译器没有生成 $internalName 的源码（genericSignatures=$genericSignatures）：${warnings.joinToString()}", engineFailure)
        return DecompiledClass(text, warnings.toList())
    }

}
