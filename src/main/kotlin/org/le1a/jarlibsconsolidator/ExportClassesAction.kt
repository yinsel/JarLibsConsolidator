package org.le1a.jarlibsconsolidator

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
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
        val defaultName = "${project.name}-${if (javaSources) "java-sources" else "classes"}.zip"
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("保存导出 ZIP", "选择 ZIP 文件的保存位置", "zip"), project)
        val initial = LocalFileSystem.getInstance().findFileByIoFile(base.toFile())
        val selected = saver.save(initial, defaultName)?.file ?: return
        val target = (if (selected.name.endsWith(".zip", true)) selected else java.io.File(selected.parentFile, selected.name + ".zip")).toPath()
        if (Files.exists(target) && Messages.showYesNoDialog(project, "文件已存在，是否替换？\n$target", "确认覆盖", Messages.getQuestionIcon()) != Messages.YES) return
        val filter = ExportFilter(options.whitelist.text, options.blacklist.text)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, if (javaSources) "反编译并导出 JAVA" else "导出 CLASS", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val libraries = ReadAction.compute<List<Path>, RuntimeException> {
                        if (project.isDisposed) throw ProcessCanceledException()
                        val roots = LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries
                            .flatMap { it.getFiles(OrderRootType.CLASSES).asList() }.toMutableList()
                        for (module in ModuleManager.getInstance(project).modules) {
                            roots.addAll(ModuleRootManager.getInstance(module).orderEntries().recursively().withoutSdk().classes().roots)
                        }
                        roots.mapNotNull { root ->
                            val local = if (root.fileSystem.protocol == "jar") JarFileSystem.getInstance().getVirtualFileForJar(root) else root
                            local?.takeIf { it.fileSystem.protocol == "file" }?.let { Path.of(it.path) }
                        }.distinct()
                    }
                    val result = ClassExportService.export(base, libraries, target, filter, decompiler(),
                        checkCanceled = { indicator.checkCanceled() },
                        progress = { text, fraction -> indicator.text = text; indicator.fraction = fraction })
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            val message = "已导出 ${result.exported} 个文件\n扫描 ${result.discovered} 个 class，过滤 ${result.filtered} 个，相同内容去重 ${result.duplicates} 个\n" +
                                    "失败/警告 ${result.failures.size} 项，详细列表见 ZIP 内 export-report.txt\n\n$target"
                            if (result.failures.isEmpty()) Messages.showInfoMessage(project, message, "导出完成")
                            else Messages.showWarningDialog(project, message, "导出完成（有失败或警告）")
                        }
                    }
                } catch (e: ProcessCanceledException) { throw e }
                catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "导出失败")
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class ExportOptionsDialog(project: Project, javaSources: Boolean) : DialogWrapper(project) {
    val whitelist = JTextArea(6, 35)
    val blacklist = JTextArea(6, 35)

    init {
        title = if (javaSources) "一键反编译并导出 JAVA" else "一键导出 CLASS"
        setOKButtonText("选择 ZIP 保存位置…")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.add(JLabel("<html>范围：项目 class/JAR 及已配置的依赖库（不含 JDK）。<br>多条规则用换行或逗号分隔；区分大小写；黑名单优先。<br>" +
                "普通关键字：包名任意段或类名包含；com.example.*：该包及子包。<br>" +
                "*example*：任意包名段包含；*example：任意段以 example 开头；example*：任意段以 example 结尾。</html>"), BorderLayout.NORTH)
        val fields = JPanel(GridLayout(1, 2, 12, 0))
        fields.add(JPanel(BorderLayout()).apply { add(JLabel("白名单（留空表示全部）"), BorderLayout.NORTH); add(JScrollPane(whitelist)) })
        fields.add(JPanel(BorderLayout()).apply { add(JLabel("黑名单（命中即排除）"), BorderLayout.NORTH); add(JScrollPane(blacklist)) })
        panel.add(fields, BorderLayout.CENTER)
        panel.preferredSize = Dimension(780, 280)
        return panel
    }
}
