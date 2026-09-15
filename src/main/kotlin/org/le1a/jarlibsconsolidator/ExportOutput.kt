package org.le1a.jarlibsconsolidator

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal enum class ExportFormat(private val label: String) {
    DIRECTORY("文件夹（默认）"), ZIP("ZIP 压缩包");
    override fun toString() = label

    fun suggest(parent: Path, name: String): Path {
        val extension = if (this == ZIP) ".zip" else ""
        var candidate = parent.resolve(name + extension)
        var suffix = 2
        while (Files.exists(candidate, NOFOLLOW_LINKS)) candidate = parent.resolve("$name-${suffix++}$extension")
        return candidate
    }
}

internal interface ExportOutput : AutoCloseable {
    fun source(relative: String, text: String, checkCanceled: () -> Unit)
    fun copy(relative: String, open: () -> InputStream, checkCanceled: () -> Unit)
    fun appendReports(root: Path) {}
    override fun close() {}
}
