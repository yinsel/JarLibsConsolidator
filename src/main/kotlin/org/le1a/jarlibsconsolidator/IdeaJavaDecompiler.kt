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

/** A saver may produce the healthy methods even when Fernflower fails another method. */
internal class PartialDecompilationException(val partial: DecompiledClass, cause: Throwable) :
    IOException("IDEA 仅生成部分源码：${partial.issues.joinToString { it.reason }}", cause)

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
        val result = withRetry(inputs[0].second, checkCanceled) { generic ->
            engine.decompileGroup(inputs, bytes, generic, checkCanceled)
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
        val result = withRetry(internalName, checkCanceled) { generic ->
            engine.decompile(file, internalName, bytecode, generic, checkCanceled)
        }
        checkCanceled()
        // Warning-bearing fallback results are intentionally not cached as clean successes.
        if (key != null) cache?.put(key, bytecode, result)
        return result
    }

    private fun withRetry(name: String, checkCanceled: () -> Unit,
                          attempt: (Boolean) -> DecompiledClass): DecompiledClass {
        try { return attempt(true) }
        catch (first: IOException) {
            checkCanceled()
            PluginDiagnostics.debug("Retry IDEA decompiler without generic signatures: class=$name", first)
            val recovered = try {
                attempt(false)
            } catch (second: IOException) {
                checkCanceled()
                second.addSuppressed(first)
                // Prefer the original attempt on ties, preserving its generic type information.
                val partial = listOfNotNull((first as? PartialDecompilationException)?.partial,
                    (second as? PartialDecompilationException)?.partial)
                    .filter { it.source.isNotBlank() && it.issues.isNotEmpty() }
                    .minByOrNull { it.issues.size } ?: throw second
                PluginDiagnostics.debug("Both attempts incomplete; retaining partial source: class=$name", second)
                return partial.copy(source = "// WARNING: Partial decompilation; failed methods are listed in decompilation-failures.csv.\n" + partial.source,
                    warnings = listOf("两次尝试后仍有方法失败，已保留部分源码；这不是完整成功，失败明细见 decompilation-failures.csv。") + partial.warnings)
            }
            return recovered.copy(warnings = listOf(
                "已关闭泛型签名重建后恢复导出（仍使用 IDEA 内置引擎）；泛型类型信息可能退化为原始类型。首次失败：${first.message}"
            ) + recovered.warnings)
        }
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
                          genericSignatures: Boolean, merge: Boolean, checkCanceled: () -> Unit,
                          allowParameterRetry: Boolean = true): DecompiledClass {
        val internalName = inputs.first().second
        checkCanceled()
        var source: String? = null
        val warnings = mutableListOf<String>()
        val issues = mutableListOf<DecompilationIssue>()
        val parameterRetryMethods = mutableMapOf<String, MutableSet<String>>()
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
                if (severity >= Severity.WARN) {
                    val name = inputs.firstOrNull { (_, candidate) ->
                        message.contains(" in class $candidate couldn't be decompiled") ||
                            message.startsWith("Class $candidate ")
                    }?.second
                    issues.add(DecompilationIssue(name, t.javaClass.name, "$message: ${PluginDiagnostics.describe(t)}"))
                    // Match the actual SSA failure, not arbitrary engine errors or warning text.
                    if (allowParameterRetry && name != null && t is NullPointerException && t.stackTrace.any {
                        it.className == "org.jetbrains.java.decompiler.modules.decompiler.sforms.SSAConstructorSparseEx" &&
                            it.methodName == "processExprent"
                    }) {
                        val method = Regex("^Method (\\S+) (\\([^ ]*\\)[^ ]+) in class ").find(message)
                        if (method != null) parameterRetryMethods.getOrPut(name) { mutableSetOf() }
                            .add(method.groupValues[1] + method.groupValues[2])
                    }
                }
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
        if (engineFailure != null && parameterRetryMethods.isNotEmpty()) {
            try {
                var changed = false
                val normalized = bytes.mapIndexed { index, original ->
                    checkCanceled()
                    ParameterIncrementNormalizer.normalize(original,
                        parameterRetryMethods[inputs[index].second].orEmpty(), Runnable { checkCanceled() })
                        ?.also { changed = true } ?: original
                }
                if (changed) {
                    val recovered = runEngine(inputs, normalized, genericSignatures, merge, checkCanceled, false)
                    return recovered.copy(warnings = listOf(
                        "已通过参数自增兼容处理恢复反编译（仍使用 IDEA 内置引擎）；仅改写内存中的等价指令，原始 class/JAR 未修改。"
                    ) + recovered.warnings)
                }
            } catch (e: Exception) {
                PluginDiagnostics.rethrowCancellation(e)
                checkCanceled()
                // Keep the original source and failure attribution if the bounded retry also fails.
                engineFailure!!.addSuppressed(e)
                PluginDiagnostics.debug("IDEA parameter-increment compatibility retry failed: class=$internalName", e)
            }
        }
        val text = source?.takeIf { it.isNotBlank() }
            ?: throw IOException("IDEA 反编译器没有生成 $internalName 的源码（genericSignatures=$genericSignatures）：${warnings.joinToString()}", engineFailure)
        if (engineFailure != null) throw PartialDecompilationException(DecompiledClass(text, warnings.toList(), issues.toList()), engineFailure!!)
        return DecompiledClass(text, warnings.toList())
    }

}
