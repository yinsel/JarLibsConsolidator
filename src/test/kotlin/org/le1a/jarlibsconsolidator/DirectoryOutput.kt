package org.le1a.jarlibsconsolidator

import java.io.File
import java.nio.file.Files
import java.util.Collections

/** Read exported files in deterministic relative-path order; input JAR fixtures remain ZIPs. */
internal class DirectoryOutput(file: File) : AutoCloseable {
    private val root = file.toPath()
    data class Entry(val name: String)
    fun entries() = Collections.enumeration(Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).map { Entry(root.relativize(it).toString().replace('\\', '/')) }
            .toList().sortedBy { it.name }
    })
    fun getEntry(name: String) = Entry(name)
    fun getInputStream(entry: Entry) = Files.newInputStream(root.resolve(entry.name))
    override fun close() {}
}
