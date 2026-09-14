package org.le1a.jarlibsconsolidator

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class DecompiledClass(val source: String, val warnings: List<String> = emptyList())
internal fun interface ClassDecompiler {
    fun decompile(file: Path, internalName: String, checkCanceled: () -> Unit): DecompiledClass
    val mergeInnerClasses: Boolean get() = false
    fun decompileGroup(inputs: List<Pair<Path, String>>, checkCanceled: () -> Unit): DecompiledClass {
        require(inputs.size == 1)
        return decompile(inputs[0].first, inputs[0].second, checkCanceled)
    }
}

/** IO-only export pipeline, separate from IntelliJ's UI and library discovery. */
internal object ClassExportService {
    data class Result(val discovered: Int, val filtered: Int, val duplicates: Int, val exported: Int,
                      val failures: List<String>, val decompilationFailed: Int = 0)
    private data class DecompilationFailure(val item: Item, val entryName: String, val errorType: String, val reason: String)
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
        val work = Files.createTempDirectory("jarlibs-export-")
        var archive: Path? = null
        var discovered = 0
        var filtered = 0
        var duplicates = 0
        var exported = 0
        val failures = mutableListOf<String>()
        val decompilationFailures = mutableListOf<DecompilationFailure>()
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
            failures.add("$origin: $reason")
            // Bound default stack logging on damaged archives; DEBUG retains every failure.
            if (loggedFailures++ < 20) PluginDiagnostics.warn("Export item failed: origin=$origin, target=$destination", e)
            else PluginDiagnostics.debug("Export item failed: origin=$origin, target=$destination", e)
            return reason
        }

        fun collect(origin: String, group: String, open: () -> InputStream) {
            checkCanceled()
            discovered++
            scanProgress.update { "扫描依赖库：已扫描 $discovered 个 class，已过滤 $filtered 个；$origin" }
            try {
                val staged = work.resolve("$discovered.class")
                val accepted = ClassSnapshot.read(open(), staged, filter, checkCanceled)
                if (accepted == null) { filtered++; return }
                val (name, digest) = accepted
                val existing = versions.getOrPut(name) { mutableListOf() }
                if (existing.any { it.digest == digest && Files.mismatch(it.file, staged) == -1L }) {
                    duplicates++
                    if (decompiler?.mergeInnerClasses == true) {
                        val shared = existing.first { it.digest == digest && Files.mismatch(it.file, staged) == -1L }
                        items.add(Item(name, shared.file, origin, group, digest))
                    }
                    Files.delete(staged)
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
                            collect("$label!/${entry.name}", label) { zip.getInputStream(entry) }
                        } else if (!entry.isDirectory && entry.name.endsWith(".jar", true)) {
                            val origin = "$label!/${entry.name}"
                            if (depth >= 8) { failures.add("$origin: 嵌套 JAR 超过 8 层"); continue }
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
                            finally { Files.deleteIfExists(nested) }
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
                isClass -> collect(sourceLabel(path), sourceLabel(path.parent)) { Files.newInputStream(path) }
                isJar -> scanJar(path)
            }
        }

        fun scanDirectory(root: Path) {
            if (!Files.exists(root)) { failures.add("${sourceLabel(root)}: 目录不存在"); return }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCanceled()
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
            archive = Files.createTempFile(destination.parent, ".jarlibs-export-", ".zip")
            val report = mutableListOf("zip_entry\tsource")
            val usedEntries = mutableSetOf<String>()
            val ordered = exportUnits(items, decompiler?.mergeInnerClasses == true, checkCanceled)
            val outputVersions = ordered.groupingBy { it.root.name }.eachCount()
            val processing: DecompilationPipeline? = decompiler?.let {
                val groups = ordered.map { unit -> unit.members.map { member -> member.file to member.name } }
                if (batchSize == 1) OrderedParallelDecompiler(ordered.map { unit -> unit.root.file to unit.root.name },
                    it, parallelism, checkCanceled, groups)
                else BatchParallelDecompiler(groups, it, parallelism, checkCanceled, batchSize)
            }
            processing.use {
                ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                    for ((index, unit) in ordered.withIndex()) {
                        val item = unit.root
                        checkCanceled()
                        progress(if (decompiler == null) "打包：${item.name}" else "反编译：${item.name}", 0.3 + 0.65 * index / ordered.size.coerceAtLeast(1))
                        val extension = if (decompiler == null) "class" else "java"
                        val prefix = if (outputVersions.getValue(item.name) == 1) "" else {
                            val label = item.group.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(50).ifEmpty { "project" }
                            "classes-conflicts/$label--${unit.digest.take(16)}/"
                        }
                        val entryName = "$prefix${item.name}.$extension"
                        val source = try {
                            processing?.next()?.also { result ->
                                if (result.source.isBlank()) throw IOException("反编译器未生成源码")
                                result.warnings.forEach { failures.add("${item.origin}: $it") }
                            }?.source
                        } catch (e: Exception) {
                            val reason = failure(item.origin, e) // Cancellation propagates before being counted.
                            unit.members.forEach { member ->
                                decompilationFailures.add(DecompilationFailure(member, entryName, e.javaClass.name, reason))
                            }
                            continue
                        }
                        checkCanceled()
                        PluginDiagnostics.debug { "Write ZIP entry: origin=${item.origin}, entry=$entryName, target=$destination" }
                        check(usedEntries.add(entryName)) { "重复的 ZIP 条目：$entryName" }
                        // ZIP write errors abort the archive, rather than leaving a corrupt partial entry.
                        zip.putNextEntry(ZipEntry(entryName))
                        if (source == null) Files.newInputStream(item.file).use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                checkCanceled()
                                val size = input.read(buffer)
                                if (size < 0) break
                                zip.write(buffer, 0, size)
                            }
                        } else zip.write(source.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        exported++
                        unit.members.forEach { report.add("${tsv(entryName)}\t${tsv(it.origin)}") }
                    }
                    val summary = "扫描 class: $discovered\n已过滤: $filtered\n相同内容去重: $duplicates\n已导出文件: $exported\n失败/警告: ${failures.size}\n" +
                            (if (decompiler == null) "" else "反编译失败: ${decompilationFailures.size}\n反编译引擎：IDEA 内置 Fernflower；同一来源且命中过滤的内部类合并导出，缺少选中的外部类时单独导出。\n") +
                            failures.joinToString("\n")
                    if (decompilationFailures.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("decompilation-failures.csv"))
                        // UTF-8 BOM helps Excel recognize Chinese text; write rows incrementally.
                        zip.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        fun csvRow(values: List<String>) {
                            checkCanceled()
                            zip.write((values.joinToString(",", transform = ::csvCell) + "\r\n").toByteArray(Charsets.UTF_8))
                        }
                        csvRow(listOf("完整类名", "来源文件或JAR条目", "字节码SHA-256", "计划导出路径", "异常类型", "失败原因"))
                        for ((item, entryName, errorType, reason) in decompilationFailures) {
                            csvRow(listOf(item.name.replace('/', '.'), item.origin, item.digest, entryName,
                                errorType, reason))
                        }
                        zip.closeEntry()
                    }
                    for ((name, text) in listOf("export-report.txt" to summary, "export-sources.tsv" to report.joinToString("\n"))) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    }
                }
            }
            checkCanceled()
            try { Files.move(archive, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(archive, destination, StandardCopyOption.REPLACE_EXISTING) }
            archive = null
            progress("导出完成", 1.0)
            if (failures.isNotEmpty()) PluginDiagnostics.warn("Export completed with ${failures.size} failures/warnings: target=$destination; see export-report.txt; first 20 exception stacks at WARN, remaining at DEBUG")
            return Result(discovered, filtered, duplicates, exported, failures, decompilationFailures.size)
        } finally {
            archive?.let { Files.deleteIfExists(it) }
            work.toFile().deleteRecursively()
        }
    }

    private fun exportUnits(items: List<Item>, merge: Boolean, checkCanceled: () -> Unit): List<UnitOfWork> {
        if (!merge) return items.sortedWith(compareBy<Item> { it.name }.thenBy { it.origin })
            .map { UnitOfWork(listOf(it), it.digest) }
        val units = mutableListOf<UnitOfWork>()
        val parents = mutableMapOf<Path, String?>()
        // The containing directory/JAR entry directory isolates multi-release and shaded copies too.
        for (scope in items.groupBy { it.origin.substringBeforeLast('/') }.values) {
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

    private fun csvCell(value: String): String {
        // CSV quoting handles separators/newlines; prefix formula-like data for spreadsheet viewers.
        val first = value.trimStart().firstOrNull()
        val safe = if (first in listOf('=', '+', '-', '@') || value.startsWith("\t") || value.startsWith("\r")) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun tsv(text: String): String = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
}
