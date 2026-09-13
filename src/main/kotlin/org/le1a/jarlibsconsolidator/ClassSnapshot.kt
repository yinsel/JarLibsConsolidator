package org.le1a.jarlibsconsolidator

import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Parse once, then replay the buffered header and stream the remaining bytes into a flat snapshot. */
internal object ClassSnapshot {
    private const val MAX_BYTES = 64 * 1024 * 1024
    data class Accepted(val name: String, val digest: String)

    fun read(input: InputStream, target: Path, filter: ExportFilter, checkCanceled: () -> Unit): Accepted? {
        var total = 0L
        val bounded = object : FilterInputStream(input) {
            private fun account(count: Int): Int {
                checkCanceled()
                if (count > 0) total += count
                if (total > MAX_BYTES) throw IOException("class 文件超过 64 MiB")
                return count
            }
            override fun read(): Int {
                checkCanceled()
                val value = `in`.read()
                account(if (value < 0) 0 else 1)
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkCanceled()
                return account(`in`.read(buffer, offset, length))
            }
        }
        bounded.buffered().use { stream ->
            stream.mark(MAX_BYTES + 1)
            val name = ClassFileCollector.readInternalName(DataInputStream(stream))
            if (!filter.accepts(name)) return null
            stream.reset()
            // Release the mark after replay; a large method body need not stay buffered.
            stream.mark(0)
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCanceled()
                    val size = stream.read(buffer)
                    if (size < 0) break
                    hash.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                }
            }
            return Accepted(name, HexFormat.of().formatHex(hash.digest()))
        }
    }
}
