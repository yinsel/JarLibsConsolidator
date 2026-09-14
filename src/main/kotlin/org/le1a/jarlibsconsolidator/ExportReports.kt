package org.le1a.jarlibsconsolidator

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

/** Stream diagnostics too: retain only a short preview for the completion dialog. */
internal class ExportReports(private val root: Path) : AutoCloseable {
    private val report = Files.newBufferedWriter(root.resolve("export-report.txt"), Charsets.UTF_8)
    private val sources = Files.newBufferedWriter(root.resolve("export-sources.tsv"), Charsets.UTF_8)
    private var csv: BufferedWriter? = null
    val preview = mutableListOf<String>()
    var failureCount = 0; private set
    var decompilationFailed = 0; private set

    init {
        report.write("开始导出。完成、失败或取消状态记录在文件末尾。\n")
        sources.write("file_entry\tsource\n")
        report.flush(); sources.flush()
    }

    fun warning(message: String) {
        failureCount++
        if (preview.size < 20) preview.add(message)
        report.write(message); report.newLine(); report.flush()
    }

    fun source(entry: String, origin: String) {
        sources.write(tsv(entry)); sources.write("\t"); sources.write(tsv(origin)); sources.newLine()
        sources.flush()
    }

    fun failedClass(name: String, origin: String, digest: String, entry: String, type: String, reason: String) {
        val writer = csv ?: Files.newBufferedWriter(root.resolve("decompilation-failures.csv"), Charsets.UTF_8).also {
            csv = it
            it.write("\uFEFF")
            row(it, listOf("完整类名", "来源文件或JAR条目", "字节码SHA-256", "计划导出路径", "异常类型", "失败原因"))
        }
        row(writer, listOf(name.replace('/', '.'), origin, digest, entry, type, reason))
        writer.flush()
        decompilationFailed++
    }

    fun finish(summary: String) { report.write("\n$summary\n"); report.flush() }

    override fun close() {
        // Close every writer even when flushing one of them fails.
        try { report.close() } finally { try { sources.close() } finally { csv?.close() } }
    }

    private fun row(writer: BufferedWriter, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) writer.write(",")
            writer.write(csvCell(value))
        }
        writer.write("\r\n")
    }

    private fun csvCell(value: String): String {
        val first = value.trimStart().firstOrNull()
        val safe = if (first in listOf('=', '+', '-', '@') || value.startsWith("\t") || value.startsWith("\r")) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun tsv(text: String): String = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
}
