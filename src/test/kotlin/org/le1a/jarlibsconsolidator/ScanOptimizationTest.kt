package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ScanOptimizationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun bytecode(): ByteArray = javaClass.getResourceAsStream("/org/le1a/jarlibsconsolidator/ScanOptimizationTest.class")!!.use { it.readBytes() }

    @Test fun `single stream snapshot preserves every byte including buffered read ahead`() {
        val data = bytecode() + ByteArray(32_000) { (it % 251).toByte() }
        val target = temporary.root.toPath().resolve("1.class")
        val result = ClassSnapshot.read(ByteArrayInputStream(data), target, ExportFilter(), {})
        assertEquals("org/le1a/jarlibsconsolidator/ScanOptimizationTest", result?.name)
        assertArrayEquals(data, Files.readAllBytes(target))
    }

    @Test fun `rejected classes do not read bodies or create snapshots`() {
        val data = bytecode() + ByteArray(2_000_000)
        var count = 0
        val input = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int = super.read(buffer, off, len).also { if (it > 0) count += it }
        }
        val target = temporary.root.toPath().resolve("1.class")
        assertNull(ClassSnapshot.read(input, target, ExportFilter("unmatched.package.*"), {}))
        assertTrue("Only the header should be read: $count/${data.size}", count < data.size / 2)
        assertFalse(Files.exists(target))
    }

    @Test fun `header reading remains cancellable before snapshot creation`() {
        val target = temporary.root.toPath().resolve("1.class")
        assertThrows(CancellationException::class.java) {
            ClassSnapshot.read(ByteArrayInputStream(bytecode()), target, ExportFilter(), { throw CancellationException() })
        }
        assertFalse(Files.exists(target))
    }

    @Test fun `progress rendering is throttled without rendering suppressed messages`() {
        var now = 0L
        var renders = 0
        val messages = mutableListOf<String>()
        val progress = ScanProgress({ text, _ -> messages.add(text) }, { now })
        repeat(10_000) { progress.update { renders++; "scan" } }
        assertEquals(1, renders)
        now = 100_000_000L
        progress.update { renders++; "next" }
        assertEquals(listOf("scan", "next"), messages)
    }

    @Test fun `package cache never reuses a class keyword decision for siblings`() {
        val filter = ExportFilter("Service", "Secret")
        repeat(3) {
            assertTrue(filter.accepts("demo/UserService"))
            assertFalse(filter.accepts("demo/Other"))
            assertFalse(filter.accepts("demo/SecretService"))
        }
        repeat(2100) { filter.accepts("pkg$it/UserService") }
        assertFalse(filter.accepts("demo/SecretService"))
        assertTrue(filter.accepts("demo/UserService"))
    }
}
