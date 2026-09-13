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
internal class IdeaJavaDecompiler : ClassDecompiler {
    override fun decompile(file: Path, internalName: String, checkCanceled: () -> Unit): DecompiledClass {
        checkCanceled()
        var source: String? = null
        val warnings = mutableListOf<String>()
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
                writeMessage("$message: ${t.message ?: t.javaClass.simpleName}", severity)
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
            Files.readAllBytes(file)
        }
        val options = mapOf<String, Any>(
            // Independent files preserve exact class filters, including blacklisted inner classes.
            IFernflowerPreferences.DECOMPILE_INNER to "0",
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to "1",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "0",
            IFernflowerPreferences.NEW_LINE_SEPARATOR to "1",
            IFernflowerPreferences.INDENT_STRING to "    "
        )
        val engine = BaseDecompiler(provider, saver, options, logger, cancellation)
        engine.addSource(file.toFile())
        try { engine.decompileContext() }
        catch (e: CancellationManager.CanceledException) { throw (e.cause as? RuntimeException ?: e) }
        checkCanceled()
        return DecompiledClass(source ?: throw IOException("IDEA 反编译器没有生成 $internalName 的源码：${warnings.joinToString()}"), warnings)
    }
}
