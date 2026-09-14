package org.le1a.jarlibsconsolidator

import com.intellij.lang.java.lexer.JavaLexer
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.JavaTokenType
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.compiled.ClassFileDecompilers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** An original loose class or an entry in an original (possibly staged nested) JAR. */
internal data class ClassSource(val container: Path, val entry: String? = null)

internal fun interface NativeIdeaText {
    fun read(source: ClassSource, selected: Map<ClassSource, Path>, checkCanceled: () -> Unit): String
}

/** Delegate to the registered editor decompiler, without options, retries, bytecode edits or a source cache. */
internal class IdeaJavaDecompiler(private val nativeText: NativeIdeaText = EditorIdeaText) : ClassDecompiler {
    override val mergeInnerClasses = true
    override val requiresOriginalFiles = true
    private val sources = mutableMapOf<Path, ClassSource>()
    private val selected = mutableMapOf<ClassSource, Path>()

    override fun registerSource(snapshot: Path, source: ClassSource) {
        sources[snapshot] = source
        selected[source] = snapshot
    }

    override fun releaseSources() { sources.clear(); selected.clear() }

    override fun decompile(file: Path, internalName: String, checkCanceled: () -> Unit): DecompiledClass =
        decompileGroup(listOf(file to internalName), checkCanceled)

    override fun decompileGroup(inputs: List<Pair<Path, String>>, checkCanceled: () -> Unit): DecompiledClass {
        checkCanceled()
        val root = inputs.first()
        val source = sources[root.first] ?: ClassSource(root.first)
        // Direct callers select only their supplied files; exports register all accepted originals before scheduling.
        val allowed = if (sources.isEmpty()) inputs.associate { ClassSource(it.first) to it.first } else selected
        val text = nativeText.read(source, allowed, checkCanceled)
        checkCanceled()
        if (text.isBlank()) throw IOException("IDEA 原生反编译入口未返回源码：${root.second}")
        // IDEA logs method failures but still returns partial source. Keep its text byte-for-byte.
        // The public editor API does not expose method diagnostics: do not invent exception types or attribution.
        val issues = mutableListOf<DecompilationIssue>()
        val lexer = JavaLexer(LanguageLevel.HIGHEST)
        lexer.start(text)
        var line = 1
        var position = 0
        while (lexer.tokenType != null) {
            checkCanceled()
            if (lexer.tokenType == JavaTokenType.END_OF_LINE_COMMENT) {
                val comment = text.substring(lexer.tokenStart, lexer.tokenEnd)
                if (comment.startsWith("// \$FF:") &&
                    (comment.contains("Couldn't be decompiled", ignoreCase = true) ||
                        comment.contains("Limits for ") && comment.contains(" are exceeded."))) {
                    while (position < lexer.tokenStart) { if (text[position++] == '\n') line++ }
                    issues.add(DecompilationIssue(root.second, "IDEA.NativePartialSource",
                        "IDEA 原生源码第 $line 行：$comment；属于该源码文件，具体失败类/方法及异常堆栈见 idea.log。"))
                }
            }
            lexer.advance()
        }
        return DecompiledClass(text, issues = issues)
    }
}

/** This is the same Light.getText entry called by IDEA for Java class editor text. */
internal object EditorIdeaText : NativeIdeaText {
    override fun read(source: ClassSource, selected: Map<ClassSource, Path>, checkCanceled: () -> Unit): String {
        val app = ApplicationManager.getApplication()
        check(!app.isDispatchThread && !app.isWriteAccessAllowed) { "反编译必须在后台线程执行" }
        if (!app.isUnitTestMode && !PropertiesComponent.getInstance().isValueSet("decompiler.legal.notice.accepted")) {
            throw IOException("请先在 IDEA 中打开任意 Java class，并通过 IDEA 自带的反编译提示后再导出。")
        }
        checkCanceled()
        val file = resolve(source)
        val prefix = file.nameWithoutExtension + "$"
        val context = listOf(file) + file.parent.children.filter {
            it.name.startsWith(prefix) && it.fileType === JavaClassFileType.INSTANCE
        }
        // Check the exact siblings the native decompiler will load. Never silently include filtered content.
        for (member in context) {
            checkCanceled()
            val location = if (source.entry == null) ClassSource(Path.of(member.path)) else
                ClassSource(source.container, source.entry.substringBeforeLast('/', "").let {
                    if (it.isEmpty()) member.name else "$it/${member.name}"
                })
            val snapshot = selected[location] ?: throw IOException(
                "IDEA 原生反编译会同时读取未选中的类 ${member.name}；已跳过整个源码文件以保持过滤条件，请同时选择该类族。")
            if (!member.contentsToByteArray().contentEquals(Files.readAllBytes(snapshot))) {
                throw IOException("扫描后 class 内容已变化或 IDEA 文件缓存尚未刷新，请刷新依赖库后重试：${member.url}")
            }
        }
        val native = ClassFileDecompilers.getInstance().find(file, ClassFileDecompilers.Light::class.java)
        if (native == null || native.javaClass.name != "org.jetbrains.java.decompiler.IdeaDecompiler") {
            throw IOException("此 class 的编辑器反编译入口不是 IDEA 自带的 Java Bytecode Decompiler，请检查已启用的反编译插件。")
        }
        var text: String? = null
        // Native cancellation uses a thread-local ProgressIndicator. Each worker gets its own indicator.
        val delegate = ProgressIndicatorBase()
        val indicator = object : ProgressIndicator by delegate {
            override fun checkCanceled() { checkCanceled.invoke(); delegate.checkCanceled() }
        }
        ProgressManager.getInstance().runProcess(Runnable { text = native.getText(file).toString() }, indicator)
        checkCanceled()
        return text ?: throw IOException("IDEA 原生反编译没有返回结果：${file.url}")
    }

    internal fun resolve(source: ClassSource): VirtualFile {
        val local = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source.container)
            ?: throw IOException("IDEA 无法读取源文件：${source.container}")
        local.refresh(false, false)
        if (source.entry == null) return local
        return JarFileSystem.getInstance().getJarRootForLocalFile(local)?.findFileByRelativePath(source.entry)
            ?: throw IOException("IDEA 无法读取 JAR 条目：${source.container}!/${source.entry}")
    }
}
