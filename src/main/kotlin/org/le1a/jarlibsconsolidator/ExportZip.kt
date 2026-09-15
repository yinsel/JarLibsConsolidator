package org.le1a.jarlibsconsolidator

import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** At most one staged file per active worker; source strings never enter a result queue. */
internal class ExportZip(private val target: Path, private val staging: Path) : ExportOutput {
    private val lock = ReentrantLock()
    private var broken = false
    private val zip: ZipOutputStream
    init {
        Files.createFile(target) // Reserve exclusively: never truncate an existing export.
        zip = try {
            // File streams are not interruptible channels: worker shutdown cannot truncate
            // an entry already being committed. Cancellation is checked between entries.
            ZipOutputStream(FileOutputStream(target.toFile()).buffered(), Charsets.UTF_8)
                .apply { setLevel(Deflater.BEST_SPEED) }
        } catch (e: Exception) { Files.deleteIfExists(target); throw e }
    }

    override fun source(relative: String, text: String, checkCanceled: () -> Unit) = staged(relative, checkCanceled) { path ->
        Files.newBufferedWriter(path, Charsets.UTF_8).use { writer ->
            var offset = 0
            while (offset < text.length) {
                checkCanceled()
                val size = minOf(8192, text.length - offset)
                writer.write(text, offset, size)
                offset += size
            }
        }
    }

    override fun copy(relative: String, open: () -> InputStream, checkCanceled: () -> Unit) = staged(relative, checkCanceled) { path ->
        open().use { input -> Files.newOutputStream(path).use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCanceled()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
        } }
    }

    private fun staged(relative: String, checkCanceled: () -> Unit, write: (Path) -> Unit) {
        require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative && ':' !in relative &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "非法导出路径：$relative" }
        var temp: Path? = null
        try {
            checkCanceled()
            temp = Files.createTempFile(staging, "zip-entry-", ".part")
            write(temp)
            while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) checkCanceled()
            try {
                checkCanceled()
                check(!broken) { "ZIP 写入已失败" }
                try {
                    zip.putNextEntry(ZipEntry(relative))
                    // Finish this fully staged entry before honoring cancellation again. Interrupting
                    // an entry midway would leave a truncated class/source inside an otherwise valid ZIP.
                    FileInputStream(temp.toFile()).use { it.copyTo(zip, 8192) }
                    zip.closeEntry()
                } catch (e: Exception) { broken = true; throw e }
            } finally { lock.unlock() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("ZIP 导出被中断").apply { initCause(e) }
        } catch (e: Exception) {
            PluginDiagnostics.rethrowCancellation(e)
            throw ExportWriteException("写入 ZIP 条目失败：$target!/$relative", e)
        } finally { temp?.let { Files.deleteIfExists(it) } }
    }

    override fun appendReports(root: Path) {
        // Workers have been joined; finalize diagnostics even after cancellation.
        if (broken) return
        Files.list(root).use { paths -> paths.sorted().forEach { path ->
            copy(path.fileName.toString(), { Files.newInputStream(path) }) {}
        } }
    }

    override fun close() {
        try { zip.close() }
        catch (e: IOException) { broken = true; throw ExportWriteException("完成 ZIP 失败：$target", e) }
        finally { if (broken) Files.deleteIfExists(target) }
    }
}
