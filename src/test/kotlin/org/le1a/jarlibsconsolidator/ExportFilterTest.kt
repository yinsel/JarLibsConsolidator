package org.le1a.jarlibsconsolidator

import org.junit.Assert.*
import org.junit.Test

class ExportFilterTest {
    @Test fun `empty rules allow all including default package`() {
        assertTrue(ExportFilter().accepts("DefaultClass"))
        assertTrue(ExportFilter().accepts("com/example/Service"))
    }

    @Test fun `qualified package wildcard includes parent and descendants`() {
        val filter = ExportFilter("com.example.*")
        assertTrue(filter.accepts("com/example/Service"))
        assertTrue(filter.accepts("com/example/deep/Service"))
        assertFalse(filter.accepts("com/examples/Service"))
        assertFalse(filter.accepts("org/com/example/Service"))
    }

    @Test fun `custom wildcards match any package segment in the specified direction`() {
        val contains = ExportFilter("*example*")
        assertTrue(contains.accepts("org/myexampletools/api/Service"))
        val prefix = ExportFilter("*example")
        assertTrue(prefix.accepts("org/exampletools/api/Service"))
        assertFalse(prefix.accepts("org/myexample/api/Service"))
        val suffix = ExportFilter("example*")
        assertTrue(suffix.accepts("org/myexample/api/Service"))
        assertFalse(suffix.accepts("org/exampletools/api/Service"))
        assertFalse(contains.accepts("org/ex/ample/Service"))
        assertFalse(contains.accepts("org/api/exampleService"))
    }

    @Test fun `blacklist takes precedence and accepts multiple separators`() {
        val filter = ExportFilter("com.example.*\norg.tools.*，net.api.*", "internal,Secret\n*test*")
        assertTrue(filter.accepts("net/api/Service"))
        assertFalse(filter.accepts("com/example/internal/Service"))
        assertFalse(filter.accepts("org/tools/SecretService"))
        assertFalse(filter.accepts("net/api/mytests/Service"))
    }

    @Test fun `keywords include class names while package literals are exact`() {
        assertTrue(ExportFilter("Service").accepts("demo/UserService"))
        assertTrue(ExportFilter("example").accepts("org/myexample/api/Thing"))
        assertFalse(ExportFilter("service").accepts("demo/UserService"))
        assertTrue(ExportFilter("com.example").accepts("com/example/Thing"))
        assertFalse(ExportFilter("com.example").accepts("com/example/sub/Thing"))
    }

    @Test fun `general glob characters never become regular expressions`() {
        assertTrue(ExportFilter("e?ample").accepts("org/example/Thing"))
        assertFalse(ExportFilter("e?ample").accepts("org/exxample/Thing"))
        assertTrue(ExportFilter("*").accepts("DefaultClass"))
        assertFalse(ExportFilter("", "*").accepts("DefaultClass"))
        assertFalse(ExportFilter("com.(example).*").accepts("com/example/Thing"))
    }
}
