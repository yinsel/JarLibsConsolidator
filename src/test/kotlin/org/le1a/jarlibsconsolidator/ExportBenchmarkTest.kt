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
import java.util.zip.ZipOutputStream

/** Opt-in, paired end-to-end benchmark; timings are observations, never flaky pass/fail thresholds. */
class ExportBenchmarkTest {
    companion object {
        @org.junit.BeforeClass @JvmStatic fun startIdea() { com.intellij.testFramework.TestApplicationManager.getInstance() }
    }
    @get:Rule val temporary = TemporaryFolder()
    private data class Sample(val millis: Long, val digest: String, val exported: Int, val warnings: Int)

    @Test fun `compare ordered families with forty-family parallel batches`() {
        assumeTrue(java.lang.Boolean.getBoolean("benchmark.exports"))
        val project = temporary.newFolder().toPath()
        val input = fixtureFatJar(800)
        val target = project.resolve("batch")
        val previousWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        val batchWorkers = BatchParallelDecompiler.defaultParallelism()
        val modes = listOf("serial", "previous", "batch-same-workers", "batch-native")
        val times = modes.associateWith { mutableListOf<Long>() }
        var expected: String? = null
        for (round in 0..3) for (offset in modes.indices) {
            val mode = modes[(round + offset) % modes.size]
            target.toFile().deleteRecursively()
            val start = System.nanoTime()
            val result = ClassExportService.export(project, listOf(input), target, ExportFilter(),
                IdeaJavaDecompiler(),
                parallelism = if (mode == "serial") 1 else if (mode == "previous" || mode == "batch-same-workers") previousWorkers else batchWorkers,
                batchSize = if (mode == "previous") 1 else 40)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertEquals(result.failures.toString(), 0, result.decompilationFailed)
            assertEquals(800, result.exported)
            val digest = MessageDigest.getInstance("SHA-256")
            DirectoryOutput(target.toFile()).use { zip ->
                zip.entries().asSequence().filter { it.name != "export-report.txt" }.forEach { entry ->
                    digest.update(entry.name.toByteArray(Charsets.UTF_8))
                    digest.update(zip.getInputStream(entry).use { it.readBytes() })
                }
            }
            val actual = HexFormat.of().formatHex(digest.digest())
            if (expected == null) expected = actual else assertEquals("$mode source/order mismatch", expected, actual)
            if (round > 0) times.getValue(mode).add(elapsed)
        }
        times.forEach { (mode, samples) -> println("BATCH_BENCHMARK mode=$mode medianMs=${samples.sorted()[1]} inputClasses=1600 families=800 oldWorkers=$previousWorkers batchWorkers=$batchWorkers") }
    }

    private fun fixtureFatJar(count: Int = 160): Path {
        val folder = temporary.newFolder().toPath()
        val sources = Files.createDirectories(folder.resolve("sources"))
        val classes = Files.createDirectories(folder.resolve("classes"))
        for (i in 0 until count) Files.writeString(sources.resolve("Service$i.java"), """
            package benchmark.services;
            public class Service$i {
                public int calculate(int n) { int sum = $i; for (int k=0; k<n; k++) { switch(k % 3) { case 0: sum += k; break; case 1: sum -= k; break; default: sum ^= k; } } return sum; }
                public String format(java.util.List<String> values) { StringBuilder out = new StringBuilder(); for(String v : values) { if(v != null) out.append(v.trim()).append(':'); } return out.toString(); }
                public static class Inner { public int value() { return $i; } }
            }
        """.trimIndent())
        val args = mutableListOf(System.getProperty("test.javac"), "--release", "17", "-encoding", "UTF-8", "-d", classes.toString())
        Files.list(sources).use { it.sorted().forEach { source -> args.add(source.toString()) } }
        val log = folder.resolve("compile.log")
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("fixture compilation timed out") }
        assertEquals(Files.readString(log), 0, process.exitValue())
        val library = folder.resolve("services.jar")
        ZipOutputStream(Files.newOutputStream(library)).use { zip ->
            Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).sorted().forEach { file ->
                zip.putNextEntry(ZipEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, zip); zip.closeEntry()
            } }
        }
        val fat = folder.resolve("application.jar")
        ZipOutputStream(Files.newOutputStream(fat)).use { zip ->
            zip.putNextEntry(ZipEntry("BOOT-INF/lib/services.jar")); Files.copy(library, zip); zip.closeEntry()
        }
        return fat
    }
}
