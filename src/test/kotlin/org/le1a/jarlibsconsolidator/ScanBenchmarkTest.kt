package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ScanBenchmarkTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Sample(val scanMs: Long, val totalMs: Long, val updates: Int, val count: Int, val digest: String)

    @Test fun `measure registered library scanning against frozen version 1_5_1`() {
        assumeTrue(java.lang.Boolean.getBoolean("benchmark.exports"))
        val folder = temporary.newFolder().toPath()
        val project = Files.createDirectories(folder.resolve("project"))
        val noise = Files.createDirectories(project.resolve("unrelated"))
        repeat(12_000) { Files.writeString(noise.resolve("file$it.txt"), "not a dependency") }
        val classes = createFixture(folder)
        val regular = folder.resolve("registered.jar")
        ZipOutputStream(Files.newOutputStream(regular)).use { zip ->
            classes.forEach { (name, data) -> zip.putNextEntry(ZipEntry("$name.class")); zip.write(data); zip.closeEntry() }
        }
        val fat = folder.resolve("fat.jar")
        ZipOutputStream(Files.newOutputStream(fat)).use { zip ->
            zip.putNextEntry(ZipEntry("BOOT-INF/lib/registered.jar")); Files.copy(regular, zip); zip.closeEntry()
        }
        val output = project.resolve("export.zip")
        println("SCAN_BENCHMARK environment cpus=${Runtime.getRuntime().availableProcessors()} java=${System.getProperty("java.version")} classes=${classes.size} unrelatedFiles=12000")
        for ((dataset, root) in listOf("jar" to regular, "nested-fatjar" to fat)) {
            for ((selection, white) in listOf("all" to "", "one-percent" to "benchmark.allowed.*")) {
                fun run(legacy: Boolean): Sample {
                    output.toFile().deleteRecursively()
                    val start = System.nanoTime()
                    var scanFinished = 0L
                    var updates = 0
                    val progress: (String, Double) -> Unit = { _, fraction ->
                        if (fraction < 0.3) updates++
                        if (fraction >= 0.3 && scanFinished == 0L) scanFinished = System.nanoTime()
                    }
                    val count = if (legacy) {
                        val result = PreScanOptimizationExport.export(project, listOf(root), output, PreScanOptimizationFilter(white), progress = progress)
                        assertTrue(result.failures.toString(), result.failures.isEmpty()); result.exported
                    } else {
                        val result = ClassExportService.export(project, listOf(root), output, ExportFilter(white), progress = progress)
                        assertTrue(result.failures.toString(), result.failures.isEmpty()); result.exported
                    }
                    val total = (System.nanoTime() - start) / 1_000_000
                    val hash = MessageDigest.getInstance("SHA-256")
                    val contents = if (legacy) ZipFile(output.toFile()).use { zip ->
                        zip.entries().asSequence().filter { it.name.endsWith(".class") }.associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } }
                    } else DirectoryOutput(output.toFile()).use { directory ->
                        directory.entries().asSequence().filter { it.name.endsWith(".class") }.associate { it.name to directory.getInputStream(it).use { stream -> stream.readBytes() } }
                    }
                    contents.toSortedMap().forEach { (name, bytes) -> hash.update(name.toByteArray(Charsets.UTF_8)); hash.update(bytes) }
                    return Sample((scanFinished - start) / 1_000_000, total, updates, count, HexFormat.of().formatHex(hash.digest()))
                }
                val expected = run(true)
                assertEquals(if (selection == "all") 4000 else 40, expected.count)
                assertEquals(expected.digest, run(false).digest)
                val samples = mapOf(true to mutableListOf<Sample>(), false to mutableListOf<Sample>())
                repeat(3) { round ->
                    for (legacy in if (round % 2 == 0) listOf(true, false) else listOf(false, true)) {
                        val sample = run(legacy)
                        assertEquals(expected.digest, sample.digest)
                        samples.getValue(legacy).add(sample)
                        println("SCAN_BENCHMARK sample dataset=$dataset filter=$selection mode=${if (legacy) "1.5.1" else "1.5.2"} round=${round + 1} scanMs=${sample.scanMs} totalMs=${sample.totalMs} scanUpdates=${sample.updates} exported=${sample.count}")
                    }
                }
                for ((legacy, values) in samples) println("SCAN_BENCHMARK median dataset=$dataset filter=$selection mode=${if (legacy) "1.5.1" else "1.5.2"} scanMs=${values.map { it.scanMs }.sorted()[1]} totalMs=${values.map { it.totalMs }.sorted()[1]} scanUpdates=${values.map { it.updates }.sorted()[1]}")
            }
        }
    }

    private fun createFixture(folder: Path): List<Pair<String, ByteArray>> {
        val source = folder.resolve("C000000.java")
        Files.writeString(source, """
            package benchmark.allowed;
            public class C000000 {
                public String format(java.util.List<String> values) { StringBuilder out = new StringBuilder(); for(String v : values) { if(v != null) out.append(v.trim()).append(':'); } return out.toString(); }
                public int count(int n) { int x = 0; for(int i = 0; i < n; i++) { if(i % 3 == 0) x += i; else x -= i; } return x; }
            }
        """.trimIndent())
        val compiled = Files.createDirectories(folder.resolve("compiled"))
        val log = folder.resolve("compile.log")
        val process = ProcessBuilder(System.getProperty("test.javac"), "--release", "17", "-d", compiled.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Fixture compilation timed out") }
        assertEquals(Files.readString(log), 0, process.exitValue())
        val template = Files.readAllBytes(compiled.resolve("benchmark/allowed/C000000.class"))
        fun replace(bytes: ByteArray, old: String, replacement: String) {
            val needle = old.toByteArray(); val value = replacement.toByteArray()
            require(needle.size == value.size)
            for (offset in 0..bytes.size - needle.size) if (needle.indices.all { bytes[offset + it] == needle[it] }) {
                value.copyInto(bytes, offset)
            }
        }
        return (0 until 4000).map { index ->
            val name = "C" + index.toString().padStart(6, '0')
            val pkg = if (index % 100 == 0) "allowed" else "blocked"
            val bytes = template.copyOf()
            replace(bytes, "C000000", name); replace(bytes, "allowed", pkg)
            "benchmark/$pkg/$name" to bytes
        }
    }
}
