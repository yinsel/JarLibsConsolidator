package org.le1a.jarlibsconsolidator

import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class ExportWriteException(message: String, cause: Throwable) : IOException(message, cause)

/** Own a newly created directory; never merge into or replace an existing export. */
internal class ExportDirectory(val root: Path) : ExportOutput {
    init { Files.createDirectory(root) }

    override fun source(relative: String, text: String, checkCanceled: () -> Unit) = write(relative) { temp ->
        Files.newBufferedWriter(temp, Charsets.UTF_8).use { writer ->
            var offset = 0
            while (offset < text.length) {
                checkCanceled()
                val size = minOf(8192, text.length - offset)
                writer.write(text, offset, size)
                offset += size
            }
        }
    }

    override fun copy(relative: String, open: () -> InputStream, checkCanceled: () -> Unit) = write(relative) { temp ->
        open().use { input -> Files.newOutputStream(temp).use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCanceled()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
        } }
    }

    private fun write(relative: String, body: (Path) -> Unit) {
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root) && target != root) { "非法导出路径：$relative" }
        var temp: Path? = null
        try {
            var parent = root
            for (segment in root.relativize(target.parent)) {
                parent = parent.resolve(segment)
                try { Files.createDirectory(parent) }
                catch (e: FileAlreadyExistsException) { if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw e }
            }
            if (Files.exists(target, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
            temp = Files.createTempFile(target.parent, ".jarlibs-writing-", ".part")
            body(temp)
            // Same-directory rename exposes only a fully written file; no whole-export staging or reread.
            Files.move(temp, target)
            temp = null
        } catch (e: Exception) {
            PluginDiagnostics.rethrowCancellation(e)
            throw ExportWriteException("写入导出文件失败：$target", e)
        } finally { temp?.let { Files.deleteIfExists(it) } }
    }

    companion object {
        fun suggest(parent: Path, name: String): Path {
            var candidate = parent.resolve(name)
            var suffix = 2
            while (Files.exists(candidate, NOFOLLOW_LINKS)) candidate = parent.resolve("$name-${suffix++}")
            return candidate
        }
    }
}
