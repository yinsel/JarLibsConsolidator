package org.le1a.jarlibsconsolidator

import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Read JVM relationships, never guess nesting from a dollar sign in a legal top-level name. */
internal object ClassNesting {
    fun parent(file: Path): String? = DataInputStream(Files.newInputStream(file).buffered()).use { input ->
        if (input.readInt() != 0xCAFEBABE.toInt()) throw IOException("Invalid class magic")
        input.readInt()
        val count = input.readUnsignedShort()
        val strings = arrayOfNulls<String>(count)
        val classes = IntArray(count)
        var i = 1
        while (i < count) {
            when (input.readUnsignedByte()) {
                1 -> strings[i] = input.readUTF()
                7 -> classes[i] = input.readUnsignedShort()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                8, 16, 19, 20 -> input.readUnsignedShort()
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> throw IOException("Unknown constant pool tag")
            }
            i++
        }
        fun className(index: Int): String? = classes.getOrNull(index)?.let { strings.getOrNull(it) }
        input.readUnsignedShort()
        val self = input.readUnsignedShort()
        input.readUnsignedShort()
        repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
        fun skipAttribute() {
            input.readUnsignedShort()
            input.skipNBytes(Integer.toUnsignedLong(input.readInt()))
        }
        repeat(2) { // fields and methods, without reading method bodies into memory
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(6)
                repeat(input.readUnsignedShort()) { skipAttribute() }
            }
        }
        var parent: String? = null
        repeat(input.readUnsignedShort()) {
            val attr = strings.getOrNull(input.readUnsignedShort())
            val size = Integer.toUnsignedLong(input.readInt())
            when (attr) {
                "EnclosingMethod" -> {
                    if (size != 4L) throw IOException("Invalid EnclosingMethod")
                    parent = className(input.readUnsignedShort())
                    input.readUnsignedShort()
                }
                "InnerClasses" -> {
                    val entries = input.readUnsignedShort()
                    if (size != 2L + entries * 8L) throw IOException("Invalid InnerClasses")
                    repeat(entries) {
                        val inner = input.readUnsignedShort()
                        val outer = input.readUnsignedShort()
                        input.readInt()
                        if (inner == self && outer != 0) parent = className(outer)
                    }
                }
                else -> input.skipNBytes(size)
            }
        }
        parent
    }
}
