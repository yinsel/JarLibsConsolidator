package org.le1a.jarlibsconsolidator

import java.io.DataInputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Collect loose bytecode without loading or executing any of the project's classes. */
internal object ClassFileCollector {
    data class ClassFile(val source: Path, val relativePath: Path, val sourceRoot: Path)
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
                        // Keep distinct module/output roots separate, even when they contain the same class.
                        var sourceRoot = file.parent
                        if (file.endsWith(relative)) {
                            sourceRoot = file
                            repeat(relative.nameCount) { sourceRoot = sourceRoot.parent }
                        }
                        classes.add(ClassFile(file, relative, sourceRoot))
                    } catch (_: IOException) {
                        skipped.add(file)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                checkCanceled()
                skipped.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return ScanResult(classes.sortedBy { it.source.toString() }, skipped)
    }

    /** Returns the exact classpath roots to register with OrderRootType.CLASSES. */
    fun copy(classes: List<ClassFile>, output: Path, checkCanceled: () -> Unit = {}): List<Path> {
        val roots = mutableListOf<Path>()
        val rootsBySource = linkedMapOf<Path, MutableList<Path>>()
        for (entry in classes) {
            checkCanceled()
            val candidates = rootsBySource.getOrPut(entry.sourceRoot) { mutableListOf() }
            val root = candidates.firstOrNull { !Files.exists(it.resolve(entry.relativePath)) }
                ?: output.resolve("root-${roots.size + 1}").also {
                    roots.add(it)
                    candidates.add(it)
                }
            val target = root.resolve(entry.relativePath)
            Files.createDirectories(target.parent)
            // Class names must never be renamed: their binary names are encoded in the bytecode.
            Files.copy(entry.source, target)
        }
        return roots
    }

    // JVMS 4.1/4.4: read this_class through the constant pool, independently of classfile version.
    private fun readInternalName(file: Path): String = DataInputStream(Files.newInputStream(file).buffered()).use { input ->
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
        name
    }
}
