package org.le1a.jarlibsconsolidator

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

/** Collect loose bytecode without loading or executing any of the project's classes. */
internal object ClassFileCollector {
    data class ClassFile(val source: Path, val relativePath: Path, val sourceRoot: Path, val projectRoot: Path)
    data class ScanResult(val classes: List<ClassFile>, val skipped: List<Path>)

    fun scan(project: Path, checkCanceled: () -> Unit = {}): ScanResult {
        val base = project.toAbsolutePath().normalize()
        val classes = mutableListOf<ClassFile>()
        val skipped = mutableListOf<Path>()
        // Do not follow symlinks: avoid cycles and files outside the project.
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCanceled()
                return if (dir != base && dir.fileName.toString() in setOf(".git", ".hg", ".svn", "all-in-one")) {
                    FileVisitResult.SKIP_SUBTREE
                } else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCanceled()
                if (attrs.isRegularFile && file.fileName.toString().endsWith(".class", ignoreCase = true)) {
                    try {
                        val relative = Path.of(readInternalName(file) + ".class")
                        // Retain the original output folder for naming genuinely conflicting versions.
                        var sourceRoot = file.parent
                        if (file.endsWith(relative)) {
                            sourceRoot = file
                            repeat(relative.nameCount) { sourceRoot = sourceRoot.parent }
                        }
                        if (!sourceRoot.startsWith(base)) sourceRoot = file.parent
                        classes.add(ClassFile(file, relative, sourceRoot, base))
                    } catch (error: IOException) {
                        PluginDiagnostics.debug("Skipped class: $file", error)
                        skipped.add(file)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                checkCanceled()
                PluginDiagnostics.debug("Cannot visit class scan path: $file", exc)
                skipped.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        if (skipped.isNotEmpty()) PluginDiagnostics.warn("Class scan skipped ${skipped.size} files/directories under $base; enable DEBUG for paths and exceptions")
        return ScanResult(classes.sortedBy { it.source.toString() }, skipped)
    }

    /** Merge unique bytecode into output; keep different versions in sibling, source-named roots. */
    fun copy(classes: List<ClassFile>, output: Path, checkCanceled: () -> Unit = {}): List<Path> {
        val conflictDirectory = output.resolveSibling("${output.fileName}-conflicts")
        val roots = linkedSetOf<Path>()
        val rootsBySource = linkedMapOf<Path, MutableList<Path>>()
        val usedNames = mutableSetOf("sources.tsv")
        val conflictSources = mutableListOf("library_root\tclass_path\tsource_file")

        fun safeName(value: String): String = value.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .trim('.', '_').ifEmpty { "project" }

        fun conflictRoot(entry: ClassFile): Path {
            val candidates = rootsBySource.getOrPut(entry.sourceRoot) { mutableListOf() }
            candidates.firstOrNull { !Files.exists(it.resolve(entry.relativePath)) }?.let { return it }
            val relativeSource = entry.projectRoot.relativize(entry.sourceRoot)
            val folder = if (relativeSource.toString().isEmpty()) entry.projectRoot.fileName.toString()
                else relativeSource.joinToString("__") { it.toString() }
            // A renamed class can have another binary version in the very same source folder.
            val label = safeName(folder + if (candidates.isEmpty()) "" else "__from-${entry.source.fileName}")
            val origin = entry.projectRoot.relativize(entry.source).toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            var shortLabel = label
            while (shortLabel.toByteArray(Charsets.UTF_8).size > 120) {
                shortLabel = shortLabel.substring(0, shortLabel.offsetByCodePoints(shortLabel.length, -1))
            }
            var name = shortLabel
            if (shortLabel != label || name.lowercase(Locale.ROOT) in usedNames) {
                name = "$shortLabel--${digest.take(12)}"
            }
            if (!usedNames.add(name.lowercase(Locale.ROOT))) {
                name = "$shortLabel--$digest"
                check(usedNames.add(name.lowercase(Locale.ROOT))) { "Conflicting source directory names: $origin" }
            }
            return conflictDirectory.resolve(name).also { candidates.add(it) }
        }

        // Sorting makes both representative selection and root ordering independent of traversal order.
        // Binary names are case-sensitive even when the host Path implementation is not.
        val groups = classes.sortedBy { it.source.toString() }.groupBy { entry ->
            entry.relativePath.joinToString("/") { it.toString() }
        }
        for (entries in groups.values) {
            checkCanceled()
            val versions = mutableListOf<ClassFile>()
            for (entry in entries) {
                checkCanceled()
                val identical = versions.any {
                    checkCanceled()
                    PluginDiagnostics.io({ "比较 class 失败: first=${it.source}, second=${entry.source}" }) {
                        Files.mismatch(it.source, entry.source) == -1L
                    }
                }
                if (!identical) versions.add(entry)
            }
            for (entry in versions) {
                checkCanceled()
                var root = if (versions.size == 1) output else conflictRoot(entry)
                var target = root.resolve(entry.relativePath)
                PluginDiagnostics.debug { "Copy class: name=${entry.relativePath}, source=${entry.source}, target=$target, versions=${versions.size}" }
                PluginDiagnostics.io({ "复制 class 失败: source=${entry.source}, target=$target" }) {
                    Files.createDirectories(target.parent)
                    try {
                        // The copy itself detects collisions, including case aliases and late arrivals.
                        Files.copy(entry.source, target)
                    } catch (collision: FileAlreadyExistsException) {
                        checkCanceled()
                        // Do not follow an unexpected link or interpret a directory as bytecode.
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw collision
                        if (Files.mismatch(entry.source, target) == -1L) {
                            PluginDiagnostics.debug { "Reuse identical existing class: source=${entry.source}, target=$target" }
                        } else {
                            // Preserve the existing file and register its root as well as the new one.
                            // Never rename an existing file: another task/process may own it.
                            roots.add(root)
                            val existing = target
                            root = conflictRoot(entry)
                            target = root.resolve(entry.relativePath)
                            PluginDiagnostics.warn("Class target collision: source=${entry.source}, existing=$existing, preservedAs=$target", collision)
                            checkCanceled()
                            Files.createDirectories(target.parent)
                            Files.copy(entry.source, target)
                        }
                    }
                }
                roots.add(root)
                if (root != output) {
                    conflictSources.add(listOf(root.fileName.toString(), entry.relativePath.toString(),
                        entry.projectRoot.relativize(entry.source).toString()).joinToString("\t") {
                        it.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
                    })
                }
            }
        }
        if (conflictSources.size > 1) Files.write(conflictDirectory.resolve("sources.tsv"), conflictSources)
        return listOf(output).filter { it in roots } + roots.filter { it != output }
    }

    // JVMS 4.1/4.4: read this_class through the constant pool, independently of classfile version.
    internal fun readInternalName(file: Path): String = readInternalName(Files.newInputStream(file))

    internal fun readInternalName(stream: InputStream): String = DataInputStream(stream.buffered()).use { readInternalName(it) }

    /** Leaves the caller's stream open, so export can rewind the buffered header without reopening the entry. */
    internal fun readInternalName(input: DataInputStream): String {
        if (input.readInt() != 0xCAFEBABE.toInt()) throw IOException("Invalid class magic")
        input.readUnsignedShort() // minor_version
        input.readUnsignedShort() // major_version
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classNames = IntArray(count)
        var index = 1
        while (index < count) {
            when (input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                7 -> classNames[index] = input.readUnsignedShort()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                5, 6 -> { input.readLong(); index++ }
                8, 16, 19, 20 -> input.readUnsignedShort()
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> throw IOException("Unknown constant pool tag")
            }
            index++
        }
        input.readUnsignedShort() // access_flags
        val thisClass = input.readUnsignedShort()
        val nameIndex = classNames.getOrNull(thisClass) ?: throw IOException("Invalid this_class")
        val name = utf8.getOrNull(nameIndex) ?: throw IOException("Invalid class name")
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            name.any { it in "\\:;[\u0000" }) {
            throw IOException("Unsafe class name")
        }
        return name
    }
}
