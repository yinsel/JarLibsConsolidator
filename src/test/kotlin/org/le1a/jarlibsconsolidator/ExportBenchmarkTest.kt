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

/** Opt-in, paired end-to-end benchmark; timings are observations, never flaky pass/fail thresholds. */
class ExportBenchmarkTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Sample(val millis: Long, val digest: String, val exported: Int, val warnings: Int)

    @Test fun `compare frozen serial baseline with cold parallel and warm cache exports`() {
        assumeTrue(java.lang.Boolean.getBoolean("benchmark.exports"))
        // IntelliJ's test classloader does not expose CodeSource locations reliably.
        val junit = Path.of(System.getProperty("benchmark.junitJar"))
        assertTrue("Benchmark requires the real JUnit dependency JAR", Files.isRegularFile(junit))
        val fatJar = fixtureFatJar()
        val workers = OrderedParallelDecompiler.defaultParallelism()
        println("BENCHMARK environment cpus=${Runtime.getRuntime().availableProcessors()} workers=$workers java=${System.getProperty("java.version")} heapMiB=${Runtime.getRuntime().maxMemory() / 1024 / 1024}")
        for ((label, input) in listOf("junit" to junit, "nested-fatjar" to fatJar)) {
            val project = temporary.newFolder().toPath()
            val destination = project.resolve("export.zip")
            val cache = DecompilationCache()
            val times = linkedMapOf("legacy" to mutableListOf<Long>(), "parallel-cold" to mutableListOf<Long>(), "cached" to mutableListOf<Long>())
            fun run(mode: String): Sample {
                val start = System.nanoTime()
                val count: Int
                val warnings: Int
                if (mode == "legacy") {
                    val result = LegacySerialExport.export(project, listOf(input), destination, ExportFilter(), LegacyIdeaJavaDecompiler())
                    count = result.exported; warnings = result.failures.size
                } else {
                    val result = ClassExportService.export(project, listOf(input), destination, ExportFilter(),
                        IdeaJavaDecompiler(if (mode == "cached") cache else DecompilationCache(), mergeInnerClasses = false), parallelism = workers, batchSize = 1)
                    count = result.exported; warnings = result.failures.size
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                // Compare every Java file and the source mapping; ZIP timestamps are deliberately excluded.
                val digest = MessageDigest.getInstance("SHA-256")
                ZipFile(destination.toFile()).use { zip ->
                    zip.entries().asSequence().filter { it.name != "export-report.txt" && it.name != "decompilation-failures.csv" }.sortedBy { it.name }.forEach { entry ->
                        digest.update(entry.name.toByteArray(Charsets.UTF_8))
                        digest.update(zip.getInputStream(entry).use { it.readBytes() })
                    }
                }
                return Sample(elapsed, HexFormat.of().formatHex(digest.digest()), count, warnings)
            }
            // Warm all paths, load engine classes/JIT and populate only the repeat-export cache.
            val expected = run("legacy")
            assertTrue(expected.exported > 100)
            for (mode in listOf("parallel-cold", "cached")) {
                val sample = run(mode)
                assertEquals("$label $mode output mismatch", expected.digest, sample.digest)
                assertEquals(expected.exported, sample.exported)
                assertEquals(expected.warnings, sample.warnings)
            }
            val modes = times.keys.toList()
            for (round in 0..2) for (offset in modes.indices) {
                val mode = modes[(round + offset) % modes.size]
                val sample = run(mode)
                assertEquals("$label $mode output mismatch", expected.digest, sample.digest)
                assertEquals(expected.exported, sample.exported)
                assertEquals(expected.warnings, sample.warnings)
                times.getValue(mode).add(sample.millis)
                println("BENCHMARK sample dataset=$label mode=$mode round=${round + 1} ms=${sample.millis} classes=${sample.exported} warnings=${sample.warnings} digest=${sample.digest}")
            }
            for ((mode, values) in times) println("BENCHMARK median dataset=$label mode=$mode ms=${values.sorted()[1]} classes=${expected.exported}")
        }
    }

    @Test fun `measure family export cold and warm with all inner method bodies retained`() {
        assumeTrue(java.lang.Boolean.getBoolean("benchmark.exports"))
        val input = fixtureFatJar()
        val project = temporary.newFolder().toPath()
        val target = project.resolve("families.zip")
        val warm = IdeaJavaDecompiler(DecompilationCache())
        val times = linkedMapOf("individual" to mutableListOf<Long>(), "family-cold" to mutableListOf<Long>(), "family-cached" to mutableListOf<Long>())
        for (round in 0..3) for (mode in times.keys) {
            val decompiler = when (mode) {
                "individual" -> IdeaJavaDecompiler(null, mergeInnerClasses = false)
                "family-cold" -> IdeaJavaDecompiler(null)
                else -> warm
            }
            val start = System.nanoTime()
            val result = ClassExportService.export(project, listOf(input), target, ExportFilter(), decompiler,
                parallelism = OrderedParallelDecompiler.defaultParallelism(), batchSize = 1)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertEquals(result.failures.toString(), 0, result.decompilationFailed)
            assertEquals(if (mode == "individual") 320 else 160, result.exported)
            ZipFile(target.toFile()).use { zip ->
                for (i in 0 until 160) {
                    val outer = zip.getInputStream(zip.getEntry("benchmark/services/Service$i.java")).reader().readText()
                    assertTrue(outer, outer.contains("calculate(int "))
                    assertTrue(outer, outer.contains("StringBuilder"))
                    val inner = if (mode == "individual") zip.getInputStream(zip.getEntry("benchmark/services/Service$i\$Inner.java")).reader().readText() else outer
                    assertTrue(inner, inner.contains("return $i;"))
                }
                assertEquals(321, zip.getInputStream(zip.getEntry("export-sources.tsv")).reader().readText().lines().size)
            }
            if (round > 0) times.getValue(mode).add(elapsed)
        }
        times.forEach { (mode, samples) -> println("FAMILY_BENCHMARK mode=$mode medianMs=${samples.sorted()[1]} inputClasses=320") }
    }

    @Test fun `compare ordered families with forty-family parallel batches`() {
        assumeTrue(java.lang.Boolean.getBoolean("benchmark.exports"))
        val project = temporary.newFolder().toPath()
        val input = fixtureFatJar(800)
        val target = project.resolve("batch.zip")
        val previousWorkers = OrderedParallelDecompiler.defaultParallelism()
        val batchWorkers = BatchParallelDecompiler.defaultParallelism()
        val cache = DecompilationCache()
        val modes = listOf("previous", "batch-same-workers", "batch-cold", "batch-cached")
        val times = modes.associateWith { mutableListOf<Long>() }
        var expected: String? = null
        for (round in 0..3) for (offset in modes.indices) {
            val mode = modes[(round + offset) % modes.size]
            val start = System.nanoTime()
            val result = ClassExportService.export(project, listOf(input), target, ExportFilter(),
                IdeaJavaDecompiler(if (mode == "batch-cached") cache else null),
                parallelism = if (mode == "previous" || mode == "batch-same-workers") previousWorkers else batchWorkers,
                batchSize = if (mode == "previous") 1 else 40)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertEquals(result.failures.toString(), 0, result.decompilationFailed)
            assertEquals(800, result.exported)
            val digest = MessageDigest.getInstance("SHA-256")
            ZipFile(target.toFile()).use { zip ->
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
