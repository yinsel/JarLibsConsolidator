package org.le1a.jarlibsconsolidator

import java.security.MessageDigest
import java.util.HexFormat

/** Session-only LRU. Includes input bytes in the budget and verifies them on a hash hit. */
internal class DecompilationCache(private val maxBytes: Long = 32L * 1024 * 1024, private val maxEntries: Int = 4096) {
    private data class Entry(val input: ByteArray, val result: DecompiledClass, val weight: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0L

    fun key(name: String, input: ByteArray): String = name + ":" +
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input))

    @Synchronized fun get(key: String, input: ByteArray): DecompiledClass? =
        entries[key]?.takeIf { it.input.contentEquals(input) }?.result

    @Synchronized fun put(key: String, input: ByteArray, result: DecompiledClass) {
        // Do not preserve partial/failed decompilations or warnings between attempts.
        if (result.source.isBlank() || result.warnings.isNotEmpty() || result.issues.isNotEmpty()) return
        val weight = input.size.toLong() + result.source.length * 2L + key.length * 2L + 256L
        if (weight > maxBytes || maxEntries <= 0) return
        entries.remove(key)?.let { bytes -= it.weight }
        entries[key] = Entry(input.copyOf(), result, weight)
        bytes += weight
        val oldest = entries.entries.iterator()
        while (bytes > maxBytes || entries.size > maxEntries) {
            bytes -= oldest.next().value.weight
            oldest.remove()
        }
    }
}
