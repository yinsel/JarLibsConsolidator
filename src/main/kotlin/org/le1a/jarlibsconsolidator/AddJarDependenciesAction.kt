package org.le1a.jarlibsconsolidator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.JarFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 一键添加 JAR 和 class 依赖的 Action
 * 兼容多个IDEA版本 (243.x - 251.x+)
 */
class AddJarDependenciesAction : AnAction() {

    // 版本检测：2025.1 对应 build 251
    private val isNewThreadingModel: Boolean by lazy {
        val buildNumber = ApplicationInfo.getInstance().build.baselineVersion
        buildNumber >= 251 // 2025.1及以上版本使用新的线程模型
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 获取项目根目录
        val basePath = project.basePath ?: run {
            showError(project, "无法获取项目根目录")
            return
        }

        val allInOneDir = File(basePath, "all-in-one")

        PluginDiagnostics.info("Add dependencies requested: project=$basePath, target=$allInOneDir, IDE=${ApplicationInfo.getInstance().build}")

        // 检查all-in-one文件夹是否已存在
        if (allInOneDir.exists()) {
            val result = Messages.showYesNoDialog(
                project,
                "all-in-one文件夹已存在，是否删除并重新创建？\n" +
                        "点击'是'将删除现有文件夹及其内容\n" +
                        "点击'否'将取消操作",
                "文件夹已存在",
                "删除并重新创建",
                "取消",
                Messages.getQuestionIcon()
            )

            if (result != Messages.YES) {
                return // 用户选择取消
            }

            // 删除现有文件夹
            try {
                if (!allInOneDir.deleteRecursively()) {
                    throw RuntimeException("部分文件无法删除，请检查文件权限或占用情况")
                }
            } catch (e: Exception) {
                reportFailure(project, "无法删除现有文件夹: $allInOneDir", e)
                return
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "正在收集 JAR 和 class 依赖...", true) {
            override fun run(indicator: ProgressIndicator) {
                var phase = "扫描依赖"
                val started = System.nanoTime()
                try {
                    indicator.text = "正在扫描 JAR 和 class 文件..."
                    indicator.fraction = 0.1

                    // 扫描jar文件
                    val jarFiles = findJarFiles(File(basePath), indicator)
                    val classScan = ClassFileCollector.scan(Path.of(basePath)) { indicator.checkCanceled() }

                    PluginDiagnostics.info("Dependency scan complete: jars=${jarFiles.size}, classes=${classScan.classes.size}, skipped=${classScan.skipped.size}")

                    if (jarFiles.isEmpty() && classScan.classes.isEmpty()) {
                        showInfo(project, "未找到可添加的 JAR 或 class 文件" +
                                if (classScan.skipped.isEmpty()) "" else "\n跳过 ${classScan.skipped.size} 个无法读取的文件或目录")
                        return
                    }

                    phase = "创建输出目录"
                    indicator.text = "正在创建all-in-one目录..."
                    indicator.fraction = 0.3

                    // 创建目标目录
                    if (!allInOneDir.mkdirs()) {
                        throw RuntimeException("无法创建all-in-one目录")
                    }

                    indicator.text = "正在复制 JAR 和 class 文件..."
                    indicator.fraction = 0.5

                    phase = "复制 JAR 和 class"
                    // 复制文件
                    copyJarFiles(jarFiles, allInOneDir, indicator)
                    val classRoots = ClassFileCollector.copy(classScan.classes, allInOneDir.toPath().resolve("classes")) {
                        indicator.checkCanceled()
                    }
                    indicator.checkCanceled()

                    indicator.text = "正在添加到项目库..."
                    indicator.fraction = 0.8

                    phase = "注册依赖库"
                    val onSuccess = {
                        PluginDiagnostics.info("Add dependencies complete: target=$allInOneDir, roots=${classRoots.size}, elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                        showSuccess(project, jarFiles.size, classScan.classes.size, classScan.skipped.size)
                    }
                    // 只有库和模块依赖提交完成后才显示成功。
                    if (isNewThreadingModel) {
                        addDirectoryToLibrary_New(project, allInOneDir, classRoots, onSuccess)
                    } else {
                        addDirectoryToLibrary_Old(project, allInOneDir, classRoots, onSuccess)
                    }

                    indicator.fraction = 1.0

                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(project, "操作失败：阶段=$phase，项目=$basePath，目标=$allInOneDir", e)
                }
            }
        })
    }

    /**
     * 递归查找所有jar文件
     */
    private fun findJarFiles(directory: File, indicator: ProgressIndicator): List<File> {
        val jarFiles = mutableListOf<File>()

        fun searchDirectory(dir: File) {
            indicator.checkCanceled()

            try {
                val children = dir.listFiles() ?: throw java.io.IOException("无法列出目录: $dir")
                children.forEach { file ->
                    indicator.checkCanceled()

                    when {
                        file.isDirectory -> {
                            // 跳过常见的不需要搜索的目录，提高性能
                            if (!Files.isSymbolicLink(file.toPath()) && !shouldSkipDirectory(file.name)) {
                                searchDirectory(file)
                            }
                        }
                        file.isFile && !Files.isSymbolicLink(file.toPath()) && file.name.endsWith(".jar", ignoreCase = true) -> {
                            jarFiles.add(file)
                            indicator.text2 = "发现: ${file.name}"
                        }
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                PluginDiagnostics.rethrowCancellation(e)
                PluginDiagnostics.warn("JAR scan skipped directory: $dir", e)
            }
        }

        searchDirectory(directory)
        return jarFiles
    }

    /**
     * 判断是否应该跳过某些目录以提高性能
     */
    private fun shouldSkipDirectory(dirName: String): Boolean {
        return dirName.startsWith(".") ||
                dirName == "node_modules" ||
                dirName == "all-in-one" ||
                dirName == "target" ||
                dirName == "build" ||
                dirName == ".gradle" ||
                dirName == ".mvn"
    }

    /**
     * 复制jar文件，添加重名处理
     */
    private fun copyJarFiles(jarFiles: List<File>, targetDir: File, indicator: ProgressIndicator) {
        val nameCount = mutableMapOf<String, Int>()

        jarFiles.forEachIndexed { index, jarFile ->
            indicator.checkCanceled()

            var targetFile = File(targetDir, jarFile.name)
            try {
                // 处理重名文件
                var targetName = jarFile.name
                val baseName = jarFile.nameWithoutExtension
                val extension = jarFile.extension

                if (nameCount.containsKey(targetName)) {
                    val count = nameCount[targetName]!! + 1
                    nameCount[targetName] = count
                    targetName = "${baseName}_$count.$extension"
                } else {
                    nameCount[targetName] = 1
                }

                targetFile = File(targetDir, targetName)
                PluginDiagnostics.debug { "Copy JAR: source=$jarFile, target=$targetFile" }
                Files.copy(jarFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                val progress = 0.5 + (index + 1).toDouble() / jarFiles.size * 0.3
                indicator.fraction = progress
                indicator.text2 = "复制: ${jarFile.name} (${index + 1}/${jarFiles.size})"

            } catch (e: Exception) {
                PluginDiagnostics.rethrowCancellation(e)
                throw java.io.IOException("复制 JAR 失败: source=$jarFile, target=$targetFile", e)
            }
        }
    }

    /**
     * 2025.1+ 版本 (251+) 的库添加方法
     * 使用新的线程模型
     */
    private fun addDirectoryToLibrary_New(project: Project, allInOneDir: File, classRoots: List<Path>, onSuccess: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                WriteAction.run<RuntimeException> {
                    performLibraryOperations(project, allInOneDir, classRoots)
                }
                onSuccess()
            } catch (e: Exception) {
                reportFailure(project, "添加到项目库失败：target=$allInOneDir", e)
            }
        }
    }

    /**
     * 2024.3及以下版本 (243及以下) 的库添加方法
     * 使用旧的线程模型
     */
    private fun addDirectoryToLibrary_Old(project: Project, allInOneDir: File, classRoots: List<Path>, onSuccess: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            ApplicationManager.getApplication().runWriteAction {
                try {
                    performLibraryOperations(project, allInOneDir, classRoots)
                } catch (e: Exception) {
                    PluginDiagnostics.rethrowCancellation(e)
                    throw RuntimeException("添加到项目库失败：target=$allInOneDir", e)
                }
            }
            onSuccess()
        }
    }

    /**
     * 核心库操作逻辑，两个版本共用
     */
    private fun performLibraryOperations(project: Project, allInOneDir: File, classRoots: List<Path>) {
        PluginDiagnostics.info("Register library: target=$allInOneDir, classRoots=${classRoots.size}")
        // 刷新文件系统
        LocalFileSystem.getInstance().refresh(false)
        val allInOneVirtualDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(allInOneDir)
            ?: throw RuntimeException("无法找到all-in-one目录")
        allInOneVirtualDir.refresh(false, true)

        // 获取项目级别库表
        val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // 安全地删除同名库
        val existingLibrary = projectLibraryTable.getLibraryByName("all-in-one")
        existingLibrary?.let { lib ->
            // 先从所有模块中移除引用
            removeLibraryFromAllModules(project, "all-in-one")
            // 然后删除库
            projectLibraryTable.removeLibrary(lib)
        }

        // 创建新库
        val library = projectLibraryTable.createLibrary("all-in-one")
        val libraryModel = library.modifiableModel

        try {
            // 为每个jar文件添加jar root
            val jarChildren = allInOneVirtualDir.children
                ?.filter { !it.isDirectory && it.extension?.equals("jar", true) == true }
                ?: emptyList()

            if (jarChildren.isEmpty() && classRoots.isEmpty()) {
                throw RuntimeException("all-in-one目录中没有找到 JAR 或 class 文件")
            }

            jarChildren.forEach { vf ->
                try {
                    val jarRoot = JarFileSystem.getInstance().refreshAndFindFileByPath("${vf.path}!/")
                    if (jarRoot == null) PluginDiagnostics.warn("Cannot resolve JAR library root: ${vf.path}")
                    jarRoot?.let {
                        PluginDiagnostics.debug { "Register JAR root: ${it.url}" }
                        libraryModel.addRoot(it, OrderRootType.CLASSES)
                    }
                } catch (e: Exception) {
                    // 记录但不中断，继续处理其他jar文件
                    PluginDiagnostics.rethrowCancellation(e)
                    PluginDiagnostics.warn("Cannot register JAR library root: ${vf.path}", e)
                }
            }

            classRoots.forEach { path ->
                val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
                    ?: throw RuntimeException("无法找到 class 库根目录: $path")
                PluginDiagnostics.debug { "Register class root: $path" }
                libraryModel.addRoot(root, OrderRootType.CLASSES)
            }

            libraryModel.commit()

        } catch (e: Exception) {
            libraryModel.dispose()
            throw e
        }
        // A committed library model must not be disposed if attaching it to a module fails.
        addLibraryToAllModules(project, library)
    }

    /**
     * 从所有模块中移除指定名称的库
     */
    private fun removeLibraryFromAllModules(project: Project, libraryName: String) {
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleModel = ModuleRootManager.getInstance(module).modifiableModel
            try {
                val toRemove = moduleModel.orderEntries
                    .filterIsInstance<LibraryOrderEntry>()
                    .filter { it.library?.name == libraryName }

                toRemove.forEach { moduleModel.removeOrderEntry(it) }
                moduleModel.commit()
            } catch (e: Exception) {
                moduleModel.dispose()
                PluginDiagnostics.rethrowCancellation(e)
                PluginDiagnostics.warn("Cannot remove library $libraryName from module ${module.name}", e)
            }
        }
    }

    /**
     * 将库添加到所有模块
     */
    private fun addLibraryToAllModules(project: Project, library: Library) {
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleModel = ModuleRootManager.getInstance(module).modifiableModel
            try {
                // 检查是否已存在
                val exists = moduleModel.orderEntries
                    .filterIsInstance<LibraryOrderEntry>()
                    .any { it.library?.name == "all-in-one" }

                if (!exists) {
                    moduleModel.addLibraryEntry(library)
                }
                moduleModel.commit()
            } catch (e: Exception) {
                moduleModel.dispose()
                PluginDiagnostics.rethrowCancellation(e)
                throw RuntimeException("无法将库添加到模块 ${module.name}", e)
            }
        }
    }

    private fun reportFailure(project: Project, context: String, error: Exception) {
        PluginDiagnostics.rethrowCancellation(error)
        PluginDiagnostics.warn(context, error)
        showError(project, PluginDiagnostics.userMessage(context, error))
    }

    private fun showInfo(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) Messages.showInfoMessage(project, message, "提示")
        }
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) Messages.showErrorDialog(project, message, "错误")
        }
    }

    private fun showSuccess(project: Project, jarCount: Int, classCount: Int, skippedCount: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val versionInfo = if (isNewThreadingModel) "2025.1+" else "2024.3-"
            Messages.showInfoMessage(
                project,
                "成功处理了 $jarCount 个 JAR 文件和 $classCount 个 class 文件\n依赖已添加为库并关联所有模块" +
                        (if (skippedCount == 0) "" else "\n跳过 $skippedCount 个无法读取的文件或目录") +
                        "\n(兼容模式: $versionInfo)",
                "操作完成"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
