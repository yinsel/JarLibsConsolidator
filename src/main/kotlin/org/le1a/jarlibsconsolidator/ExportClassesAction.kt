package org.le1a.jarlibsconsolidator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

internal class ExportClassesAction : BaseExportAction(false)

internal abstract class BaseExportAction(private val javaSources: Boolean) : AnAction() {
    protected open fun decompiler(): ClassDecompiler? = null

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = project.basePath?.let(Path::of) ?: return
        val options = ExportOptionsDialog(project, javaSources)
        if (!options.showAndGet()) return
        val defaultName = "${project.name}-${if (javaSources) "java-sources" else "classes"}"
        val format = options.format.selectedItem as ExportFormat
        val location = if (format == ExportFormat.ZIP) "ZIP 内" else "文件夹内"
        val chooser = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "选择导出父目录"
            description = "将在所选目录中创建 $defaultName${if (format == ExportFormat.ZIP) ".zip" else ""}；同名输出已存在时自动添加序号。"
        }
        val initial = LocalFileSystem.getInstance().findFileByIoFile(base.toFile())
        val selected = FileChooser.chooseFile(chooser, project, initial) ?: return
        val target = format.suggest(Path.of(selected.path), defaultName)
        val whitelist = options.whitelist.text
        val blacklist = options.blacklist.text
        val filter = ExportFilter(whitelist, blacklist)
        val concurrencyMode = options.concurrency.selectedIndex
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, if (javaSources) "反编译并导出 JAVA" else "导出 CLASS", true) {
            override fun run(indicator: ProgressIndicator) {
                val started = System.nanoTime()
                try {
                    PluginDiagnostics.info("Export requested: mode=${if (javaSources) "JAVA" else "CLASS"}, project=$base, target=$target")
                    PluginDiagnostics.debug { "Export filters: whitelist=$whitelist, blacklist=$blacklist" }
                    indicator.isIndeterminate = true
                    indicator.text = "正在读取 IDEA 已登记的依赖库…"
                    val libraries = ReadAction.compute<List<Path>, RuntimeException> {
                        if (project.isDisposed) throw ProcessCanceledException()
                        val roots = LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries
                            .flatMap { it.getFiles(OrderRootType.CLASSES).asList() }.toMutableList()
                        for (module in ModuleManager.getInstance(project).modules) {
                            ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrderEntry>().forEach { entry ->
                                entry.library?.getFiles(OrderRootType.CLASSES)?.let { roots.addAll(it) }
                            }
                        }
                        roots.mapNotNull { root ->
                            val local = if (root.fileSystem.protocol == "jar") JarFileSystem.getInstance().getVirtualFileForJar(root) else root
                            local?.takeIf { it.fileSystem.protocol == "file" }?.let { Path.of(it.path) }
                        }.distinct()
                    }
                    PluginDiagnostics.debug { "Export registered libraries: ${libraries.joinToString()}" }
                    val result = ClassExportService.export(base, libraries, target, filter, decompiler(),
                        format = format,
                        parallelism = when (concurrencyMode) {
                            1 -> BatchParallelDecompiler.analyzerParallelism()
                            2 -> 1
                            else -> BatchParallelDecompiler.defaultParallelism()
                        },
                        checkCanceled = { indicator.checkCanceled() },
                        progress = { text, fraction ->
                            indicator.isIndeterminate = fraction < 0.3
                            indicator.text = text
                            if (fraction >= 0.3) indicator.fraction = fraction
                        })
                    PluginDiagnostics.info("Export complete: target=$target, discovered=${result.discovered}, filtered=${result.filtered}, duplicates=${result.duplicates}, exported=${result.exported}, failures=${result.failureCount}, decompilationFailed=${result.decompilationFailed}, elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            val message = "已导出 ${result.exported} 个文件\n扫描 ${result.discovered} 个 class，过滤 ${result.filtered} 个，相同内容去重 ${result.duplicates} 个\n" +
                                    (if (result.partiallyExported > 0) "其中 ${result.partiallyExported} 个为部分源码（存在失败的方法）\n" else "") +
                                    (if (javaSources) "反编译失败：${result.decompilationFailed} 个\n" +
                                        (if (result.decompilationFailed > 0) "失败明细见 $location decompilation-failures.csv\n" else "") else "") +
                                    "全部失败/警告 ${result.failureCount} 项，详细列表见 $location export-report.txt\n\n$target"
                            if (result.failureCount == 0) Messages.showInfoMessage(project, message, "导出完成")
                            else Messages.showWarningDialog(project, message, "导出完成（有失败或警告）")
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    PluginDiagnostics.info("Export canceled; completed files retained: target=$target")
                    throw e
                }
                catch (e: Exception) {
                    PluginDiagnostics.rethrowCancellation(e)
                    PluginDiagnostics.warn("Export failed: project=$base, target=$target, javaSources=$javaSources", e)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) Messages.showErrorDialog(project, PluginDiagnostics.userMessage("导出未完成：$target\n已完成的输出会尽量保留；ZIP 写入损坏时会删除无效压缩包。", e), "导出失败")
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class ExportOptionsDialog(project: Project, private val javaSources: Boolean) : DialogWrapper(project) {
    val format = JComboBox(ExportFormat.values())
    val whitelist = JTextArea(6, 35)
    val blacklist = JTextArea(6, 35)
    val concurrency = JComboBox(arrayOf("自动（根据 CPU 和可用堆内存）",
        "高并发（${BatchParallelDecompiler.analyzerParallelism()} 个任务，CPU × 2）", "低内存（1 个任务）"))

    init {
        title = if (javaSources) "一键反编译并导出 JAVA" else "一键导出 CLASS"
        setOKButtonText("选择导出父目录…")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.add(JLabel("<html>请先一键添加依赖。导出范围：IDEA 已登记的依赖库（含 all-in-one，不含 JDK）。<br>不会扫描未加入依赖库的项目文件；库内嵌套 JAR 仍会读取。<br>多条规则用换行或逗号分隔；区分大小写；黑名单优先。<br>" +
                "普通关键字：包名任意段或类名包含；com.example.*：该包及子包。<br>" +
                "*example*：任意包名段包含；*example：任意段以 example 开头；example*：任意段以 example 结尾。</html>"), BorderLayout.NORTH)
        val fields = JPanel(GridLayout(1, 2, 12, 0))
        fields.add(JPanel(BorderLayout()).apply { add(JLabel("白名单（留空表示全部）"), BorderLayout.NORTH); add(JScrollPane(whitelist)) })
        fields.add(JPanel(BorderLayout()).apply { add(JLabel("黑名单（命中即排除）"), BorderLayout.NORTH); add(JScrollPane(blacklist)) })
        panel.add(fields, BorderLayout.CENTER)
        val outputOptions = JPanel(GridLayout(0, 1, 0, 8))
        outputOptions.add(JPanel(BorderLayout(8, 4)).apply {
            add(JLabel("输出格式"), BorderLayout.WEST)
            add(format, BorderLayout.CENTER)
        })
        if (javaSources) outputOptions.add(JPanel(BorderLayout(8, 4)).apply {
            add(JLabel("反编译并发"), BorderLayout.WEST)
            add(concurrency, BorderLayout.CENTER)
            add(JLabel("每批 40 个类族，完成即写入；提高并发会增加反编译期间的内存使用。"), BorderLayout.SOUTH)
        })
        panel.add(outputOptions, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(780, if (javaSources) 390 else 330)
        return panel
    }
}
