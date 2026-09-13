package org.le1a.jarlibsconsolidator

// Frozen 1.5.1 baseline from ed5ae3d8c4252c8e0297ee082db54857a418ed11; benchmark-only.

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** IO-only export pipeline, separate from IntelliJ's UI and library discovery. */
internal object PreScanOptimizationExport {
    data class Result(val discovered: Int, val filtered: Int, val duplicates: Int, val exported: Int, val failures: List<String>)
    private data class Item(val name: String, val file: Path, val origin: String, val group: String, val digest: String)
    private const val MAX_CLASS_BYTES = 64L * 1024 * 1024

    fun export(
        project: Path,
        libraries: List<Path>,
        target: Path,
        filter: PreScanOptimizationFilter,
        decompiler: ClassDecompiler? = null,
        checkCanceled: () -> Unit = {},
        progress: (String, Double) -> Unit = { _, _ -> },
        parallelism: Int = OrderedParallelDecompiler.defaultParallelism()
    ): Result {
        val base = project.toAbsolutePath().normalize()
        val destination = target.toAbsolutePath().normalize()
        val work = Files.createTempDirectory("jarlibs-export-")
        var archive: Path? = null
        var discovered = 0
        var filtered = 0
        var duplicates = 0
        var exported = 0
        val failures = mutableListOf<String>()
        val items = mutableListOf<Item>()
        val seenFiles = mutableSetOf<Path>()
        val seenDirectories = mutableSetOf<Path>()
        val versions = mutableMapOf<String, MutableList<Item>>()

        fun sourceLabel(path: Path): String = if (path.startsWith(base)) base.relativize(path).toString().replace('\\', '/')
            else "external/" + path.toList().takeLast(4).joinToString("/")

        fun failure(origin: String, e: Exception) {
            checkCanceled()
            if (e is CancellationException) throw e
            failures.add("$origin: ${e.message ?: e.javaClass.simpleName}")
        }

        fun collect(origin: String, group: String, open: () -> InputStream) {
            checkCanceled()
            discovered++
            progress("扫描：$origin", 0.1)
            try {
                val name = ClassFileCollector.readInternalName(open())
                if (!filter.accepts(name)) { filtered++; return }
                val staged = work.resolve("input-${discovered}").resolve("$name.class")
                Files.createDirectories(staged.parent)
                val hash = MessageDigest.getInstance("SHA-256")
                open().use { input -> Files.newOutputStream(staged).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        checkCanceled()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_CLASS_BYTES) throw IOException("class 文件超过 64 MiB")
                        hash.update(buffer, 0, size)
                        output.write(buffer, 0, size)
                    }
                } }
                val digest = HexFormat.of().formatHex(hash.digest())
                val existing = versions.getOrPut(name) { mutableListOf() }
                if (existing.any { it.digest == digest && Files.mismatch(it.file, staged) == -1L }) {
                    duplicates++
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

        fun scanFile(path: Path) {
            checkCanceled()
            if (!seenFiles.add(path) || Files.isSymbolicLink(path)) return
            when {
                path.fileName.toString().endsWith(".class", true) -> collect(sourceLabel(path), sourceLabel(path.parent)) { Files.newInputStream(path) }
                path.fileName.toString().endsWith(".jar", true) -> scanJar(path)
            }
        }

        fun scanDirectory(root: Path) {
            if (!Files.exists(root)) { failures.add("${sourceLabel(root)}: 目录不存在"); return }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCanceled()
                    if (dir != root && dir.fileName.toString() in setOf(".git", ".hg", ".svn", "all-in-one")) return FileVisitResult.SKIP_SUBTREE
                    return if (seenDirectories.add(dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) scanFile(file)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failure(sourceLabel(file), exc)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        try {
            scanDirectory(base)
            for (library in libraries.map { it.toAbsolutePath().normalize() }.distinct().sorted()) {
                checkCanceled()
                if (Files.isSymbolicLink(library)) continue
                try {
                    if (Files.isDirectory(library)) scanDirectory(library) else scanFile(library)
                } catch (e: Exception) { failure(sourceLabel(library), e) }
            }
            checkCanceled()
            archive = Files.createTempFile(destination.parent, ".jarlibs-export-", ".zip")
            val report = mutableListOf("zip_entry\tsource")
            val usedEntries = mutableSetOf<String>()
            val ordered = items.sortedWith(compareBy<Item> { it.name }.thenBy { it.origin })
            val processing = decompiler?.let {
                OrderedParallelDecompiler(ordered.map { item -> item.file to item.name }, it, parallelism, checkCanceled)
            }
            processing.use {
                ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                    for ((index, item) in ordered.withIndex()) {
                        checkCanceled()
                        progress(if (decompiler == null) "打包：${item.name}" else "反编译：${item.name}", 0.3 + 0.65 * index / items.size.coerceAtLeast(1))
                        val extension = if (decompiler == null) "class" else "java"
                        val prefix = if (versions.getValue(item.name).size == 1) "" else {
                            val label = item.group.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(50).ifEmpty { "project" }
                            "conflicts/$label--${item.digest.take(16)}/"
                        }
                        val entryName = "$prefix${item.name}.$extension"
                        val source = try {
                            processing?.next()?.also { result ->
                                if (result.source.isBlank()) throw IOException("反编译器未生成源码")
                                result.warnings.forEach { failures.add("${item.origin}: $it") }
                            }?.source
                        } catch (e: Exception) { failure(item.origin, e); continue }
                        checkCanceled()
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
                        report.add("${tsv(entryName)}\t${tsv(item.origin)}")
                    }
                    val summary = "扫描 class: $discovered\n已过滤: $filtered\n相同内容去重: $duplicates\n已导出文件: $exported\n失败/警告: ${failures.size}\n" +
                            (if (decompiler == null) "" else "反编译引擎：IDEA 内置 Fernflower；内部类单独导出。\n") +
                            failures.joinToString("\n")
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
            return Result(discovered, filtered, duplicates, exported, failures)
        } finally {
            archive?.let { Files.deleteIfExists(it) }
            work.toFile().deleteRecursively()
        }
    }

    private fun tsv(text: String): String = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
}
