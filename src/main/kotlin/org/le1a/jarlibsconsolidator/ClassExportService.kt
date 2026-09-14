package org.le1a.jarlibsconsolidator

import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile

internal data class DecompilationIssue(val internalName: String?, val errorType: String, val reason: String)
internal data class DecompiledClass(val source: String, val warnings: List<String> = emptyList(),
                                   val issues: List<DecompilationIssue> = emptyList())
internal fun interface ClassDecompiler {
    fun decompile(file: Path, internalName: String, checkCanceled: () -> Unit): DecompiledClass
    val mergeInnerClasses: Boolean get() = false
    val requiresOriginalFiles: Boolean get() = false
    fun registerSource(snapshot: Path, source: ClassSource) {}
    fun releaseSources() {}
    fun decompileGroup(inputs: List<Pair<Path, String>>, checkCanceled: () -> Unit): DecompiledClass {
        require(inputs.size == 1)
        return decompile(inputs[0].first, inputs[0].second, checkCanceled)
    }
}

/** IO-only export pipeline, separate from IntelliJ's UI and library discovery. */
internal object ClassExportService {
    data class Result(val discovered: Int, val filtered: Int, val duplicates: Int, val exported: Int,
                      val failures: List<String>, val decompilationFailed: Int = 0, val partiallyExported: Int = 0,
                      val failureCount: Int = failures.size)
    private data class Item(val name: String, val file: Path, val origin: String, val group: String, val digest: String)
    private data class UnitOfWork(val members: List<Item>, val digest: String) { val root get() = members.first() }

    fun export(
        project: Path,
        libraries: List<Path>,
        target: Path,
        filter: ExportFilter,
        decompiler: ClassDecompiler? = null,
        checkCanceled: () -> Unit = {},
        progress: (String, Double) -> Unit = { _, _ -> },
        parallelism: Int = BatchParallelDecompiler.defaultParallelism(),
        batchSize: Int = 40
    ): Result {
        require(libraries.isNotEmpty()) { "没有已登记的依赖库，请先执行“一键添加依赖”后再导出。" }
        val base = project.toAbsolutePath().normalize()
        val destination = target.toAbsolutePath().normalize()
        checkCanceled()
        val output = ExportDirectory(destination)
        val work = Files.createTempDirectory("jarlibs-export-")
        val reports = try { ExportReports(destination) } catch (e: Exception) { work.toFile().deleteRecursively(); throw e }
        val written = java.util.concurrent.atomic.AtomicInteger()
        var discovered = 0
        var filtered = 0
        var duplicates = 0
        var exported = 0
        var partiallyExported = 0
        val items = mutableListOf<Item>()
        val seenFiles = mutableSetOf<Path>()
        val seenDirectories = mutableSetOf<Path>()
        val versions = mutableMapOf<String, MutableList<Item>>()
        val scanProgress = ScanProgress(progress)

        fun sourceLabel(path: Path): String = if (path.startsWith(base)) base.relativize(path).toString().replace('\\', '/')
            else "external/" + path.toList().takeLast(4).joinToString("/")

        var loggedFailures = 0
        fun failure(origin: String, e: Exception): String {
            checkCanceled()
            PluginDiagnostics.rethrowCancellation(e)
            val reason = PluginDiagnostics.describe(e)
            reports.warning("$origin: $reason")
            // Bound default stack logging on damaged archives; DEBUG retains every failure.
            if (loggedFailures++ < 20) PluginDiagnostics.warn("Export item failed: origin=$origin, target=$destination", e)
            else PluginDiagnostics.debug("Export item failed: origin=$origin, target=$destination", e)
            return reason
        }

        fun collect(origin: String, group: String, source: ClassSource, open: () -> InputStream) {
            checkCanceled()
            discovered++
            scanProgress.update { "扫描依赖库：已扫描 $discovered 个 class，已过滤 $filtered 个；$origin" }
            try {
                val staged = work.resolve("$discovered.class")
                val accepted = ClassSnapshot.read(open(), staged, filter, checkCanceled)
                if (accepted == null) { filtered++; return }
                val (name, digest) = accepted
                decompiler?.registerSource(staged, source)
                val existing = versions.getOrPut(name) { mutableListOf() }
                if (existing.any { it.digest == digest && Files.mismatch(it.file, staged) == -1L }) {
                    duplicates++
                    if (decompiler?.mergeInnerClasses == true) {
                        // Retain this origin's snapshot: identical outers can have different native siblings.
                        items.add(Item(name, staged, origin, group, digest))
                    }
                    if (decompiler?.mergeInnerClasses != true) Files.delete(staged)
                } else {
                    Item(name, staged, origin, group, digest).also { existing.add(it); items.add(it) }
                }
            } catch (e: Exception) { failure(origin, e) }
        }

        fun scanJar(path: Path, label: String = sourceLabel(path), depth: Int = 0) {
            try {
                ZipFile(path.toFile()).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        checkCanceled()
                        val entry = entries.nextElement()
                        if (!entry.isDirectory && entry.name.endsWith(".class", true)) {
                            collect("$label!/${entry.name}", label, ClassSource(path, entry.name)) { zip.getInputStream(entry) }
                        } else if (!entry.isDirectory && entry.name.endsWith(".jar", true)) {
                            val origin = "$label!/${entry.name}"
                            if (depth >= 8) { reports.warning("$origin: 嵌套 JAR 超过 8 层"); continue }
                            val nested = Files.createTempFile(work, "nested-", ".jar")
                            try {
                                zip.getInputStream(entry).use { input -> Files.newOutputStream(nested).use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0L
                                    while (true) {
                                        checkCanceled()
                                        scanProgress.update { "读取依赖库中的嵌套 JAR：$origin" }
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        total += size
                                        if (total > 512L * 1024 * 1024) throw IOException("嵌套 JAR 超过 512 MiB")
                                        output.write(buffer, 0, size)
                                    }
                                } }
                                scanJar(nested, origin, depth + 1)
                            } catch (e: Exception) { failure(origin, e) }
                            finally { if (decompiler?.requiresOriginalFiles != true) Files.deleteIfExists(nested) }
                        }
                    }
                }
            } catch (e: Exception) { failure(label, e) }
        }

        fun scanFile(path: Path, knownRegular: Boolean = false) {
            checkCanceled()
            val filename = path.fileName.toString()
            val isClass = filename.endsWith(".class", true)
            val isJar = filename.endsWith(".jar", true)
            if ((!isClass && !isJar) || !seenFiles.add(path)) return
            if (!knownRegular && Files.isSymbolicLink(path)) return
            when {
                isClass -> collect(sourceLabel(path), sourceLabel(path.parent), ClassSource(path)) { Files.newInputStream(path) }
                isJar -> scanJar(path)
            }
        }

        fun scanDirectory(root: Path) {
            if (!Files.exists(root)) { reports.warning("${sourceLabel(root)}: 目录不存在"); return }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCanceled()
                    if (dir.startsWith(destination)) return FileVisitResult.SKIP_SUBTREE
                    scanProgress.update { "遍历依赖库：${sourceLabel(dir)}；已扫描 $discovered 个 class，已过滤 $filtered 个" }
                    if (dir != root && dir.fileName.toString() in setOf(".git", ".hg", ".svn")) return FileVisitResult.SKIP_SUBTREE
                    return if (seenDirectories.add(dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) scanFile(file, knownRegular = true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failure(sourceLabel(file), exc)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        try {
            PluginDiagnostics.debug { "Export staging: work=$work, target=$destination, parallelism=${if (decompiler == null) 0 else parallelism}" }
            for (library in libraries.map { it.toAbsolutePath().normalize() }.distinct().sorted()) {
                checkCanceled()
                if (Files.isSymbolicLink(library)) continue
                try {
                    if (Files.isDirectory(library)) scanDirectory(library) else scanFile(library)
                } catch (e: Exception) { failure(sourceLabel(library), e) }
            }
            checkCanceled()
            progress("依赖库扫描完成：$discovered 个 class，过滤 $filtered 个，待导出 ${items.size} 个", 0.3)
            PluginDiagnostics.info("Export scan complete: discovered=$discovered, filtered=$filtered, duplicates=$duplicates, selected=${items.size}")
            val ordered = exportUnits(items, decompiler?.mergeInnerClasses == true, checkCanceled)
            // Portable collision grouping also isolates names differing only by case on Windows/macOS.
            val outputVersions = ordered.groupingBy { it.root.name.lowercase(java.util.Locale.ROOT) }.eachCount()
            val entries = ordered.map { unit ->
                val item = unit.root
                val extension = if (decompiler == null) "class" else "java"
                val prefix = if (outputVersions.getValue(item.name.lowercase(java.util.Locale.ROOT)) == 1) "" else {
                    val label = item.group.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(50).ifEmpty { "project" }
                    "classes-conflicts/$label--${unit.digest.take(16)}/"
                }
                "$prefix${item.name}.$extension"
            }
            check(entries.distinct().size == entries.size) { "重复的导出文件路径" }
            val processing = decompiler?.let {
                BatchParallelDecompiler(ordered.map { unit -> unit.members.map { member -> member.file to member.name } },
                    it, parallelism, checkCanceled, batchSize) { index, source, check ->
                    output.source(entries[index], source, check)
                    written.incrementAndGet()
                }
            }
            if (processing != null) PluginDiagnostics.info("Export scheduler: families=${ordered.size}, batchSize=$batchSize, workers=${processing.workers}, completionOrder=true")
            processing.use {
                for (position in ordered.indices) {
                    // Completion carries its own identity: reports never associate an out-of-order
                    // result or failure with the class that happened to be submitted first.
                    val completion = processing?.next()
                    val index = completion?.index ?: position
                    val unit = ordered[index]
                    val item = unit.root
                    val entryName = entries[index]
                    checkCanceled()
                    progress("${if (decompiler == null) "复制" else "反编译并写入"}：${item.name}（已落盘 ${written.get()} 个文件）", 0.3 + 0.65 * position / ordered.size.coerceAtLeast(1))
                    val result = try { completion?.error?.let { throw it }; completion?.result }
                    catch (e: ExportWriteException) { throw e }
                    catch (e: Exception) {
                        val reason = failure(item.origin, e)
                        unit.members.forEach { member -> reports.failedClass(member.name, member.origin,
                            member.digest, entryName, e.javaClass.name, reason) }
                        continue
                    }
                    if (result == null) {
                        output.copy(entryName, { Files.newInputStream(item.file) }, checkCanceled)
                        written.incrementAndGet()
                    } else {
                        result.warnings.forEach { reports.warning("${item.origin}: $it") }
                        if (result.issues.isNotEmpty()) {
                            partiallyExported++
                            for (member in unit.members) {
                                val issues = result.issues.filter { issue -> issue.internalName == member.name ||
                                    unit.members.none { it.name == issue.internalName } }
                                if (issues.isEmpty()) continue
                                val reason = issues.joinToString("\n") { it.reason }
                                reports.warning("${member.origin}: 部分源码，存在失败的方法：$reason")
                                reports.failedClass(member.name, member.origin, member.digest, entryName,
                                    issues.map { it.errorType }.distinct().joinToString("; "), reason)
                            }
                        }
                    }
                    exported++
                    unit.members.forEach { reports.source(entryName, it.origin) }
                }
            }
            checkCanceled()
            reports.finish("状态：导出完成\n扫描 class: $discovered\n已过滤: $filtered\n相同内容去重: $duplicates\n已导出文件: $exported\n失败/警告: ${reports.failureCount}\n" +
                if (decompiler == null) "" else "反编译失败: ${reports.decompilationFailed}\n已保留部分源码文件: $partiallyExported（包含失败的方法，不代表完整成功）\n反编译入口：IDEA 原生 Java Bytecode Decompiler；源码逐文件落盘，未缓存已完成源码。\n")
            progress("导出完成：$destination", 1.0)
            if (reports.failureCount > 0) PluginDiagnostics.warn("Export completed with ${reports.failureCount} failures/warnings: target=$destination; see export-report.txt")
            return Result(discovered, filtered, duplicates, exported, reports.preview.toList(), reports.decompilationFailed, partiallyExported, reports.failureCount)
        } catch (e: Exception) {
            try { reports.finish("状态：导出未完成（失败或取消）\n已落盘文件: ${written.get()}\n已完成文件保留；来源和失败报告可能尚未完整汇总。\n原因：${PluginDiagnostics.describe(e)}") } catch (reportError: Exception) { e.addSuppressed(reportError) }
            throw e
        } finally {
            try { decompiler?.releaseSources() } finally {
                try { reports.close() } finally { work.toFile().deleteRecursively() }
            }
        }
    }

    private fun exportUnits(items: List<Item>, merge: Boolean, checkCanceled: () -> Unit): List<UnitOfWork> {
        if (!merge) return items.sortedWith(compareBy<Item> { it.name }.thenBy { it.origin })
            .map { UnitOfWork(listOf(it), it.digest) }
        val units = mutableListOf<UnitOfWork>()
        val parents = mutableMapOf<Path, String?>()
        // The containing directory/JAR entry directory isolates multi-release and shaded copies too.
        for (scope in items.groupBy { it.origin.substringBeforeLast('/', "") }.values) {
            val byName = scope.groupBy { it.name }
            val buckets = linkedMapOf<Item, MutableList<Item>>()
            for (item in scope) {
                checkCanceled()
                var root = item
                val visited = mutableSetOf(root.name)
                while (true) {
                    if (!parents.containsKey(root.file)) parents[root.file] = try { ClassNesting.parent(root.file) }
                        catch (e: IOException) { null } // The actual engine will report malformed bytecode.
                    val parent = parents[root.file] ?: break
                    val next = byName[parent]?.singleOrNull() ?: break
                    if (!visited.add(next.name)) break
                    root = next
                }
                buckets.getOrPut(root) { mutableListOf() }.add(item)
            }
            for ((root, members) in buckets) {
                val sorted = listOf(root) + members.filter { it !== root }.sortedBy { it.name }
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                sorted.forEach { hash.update((it.name + ":" + it.digest + "\n").toByteArray(Charsets.UTF_8)) }
                units.add(UnitOfWork(sorted, if (sorted.size == 1) root.digest else java.util.HexFormat.of().formatHex(hash.digest())))
            }
        }
        // Deduplicate whole families: identical outer bytes can have different inner implementations.
        val seen = mutableMapOf<Pair<String, String>, MutableList<UnitOfWork>>()
        return units.sortedWith(compareBy<UnitOfWork> { it.root.name }.thenBy { it.root.origin }).filter { unit ->
            checkCanceled()
            val previous = seen.getOrPut(unit.root.name to unit.digest) { mutableListOf() }
            val duplicate = previous.any { old -> old.members.size == unit.members.size &&
                old.members.zip(unit.members).all { (a, b) -> a.name == b.name && Files.mismatch(a.file, b.file) == -1L } }
            if (!duplicate) previous.add(unit)
            !duplicate
        }
    }

}
