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
        val junit = Path.of(org.junit.Test::class.java.protectionDomain.codeSource.location.toURI())
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
                        IdeaJavaDecompiler(if (mode == "cached") cache else DecompilationCache()), parallelism = workers)
                    count = result.exported; warnings = result.failures.size
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                // Compare every Java file and the source mapping; ZIP timestamps are deliberately excluded.
                val digest = MessageDigest.getInstance("SHA-256")
                ZipFile(destination.toFile()).use { zip ->
                    zip.entries().asSequence().filter { it.name != "export-report.txt" }.sortedBy { it.name }.forEach { entry ->
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

    private fun fixtureFatJar(): Path {
        val folder = temporary.newFolder().toPath()
        val sources = Files.createDirectories(folder.resolve("sources"))
        val classes = Files.createDirectories(folder.resolve("classes"))
        for (i in 0 until 160) Files.writeString(sources.resolve("Service$i.java"), """
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
