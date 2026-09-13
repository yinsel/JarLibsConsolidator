package org.le1a.jarlibsconsolidator

/** Avoid one IDE progress notification (and path rendering) per file or class. */
internal class ScanProgress(
    private val report: (String, Double) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var previous: Long? = null
    fun update(message: () -> String) {
        val now = nanoTime()
        if (previous == null || now - previous!! >= 100_000_000L) {
            previous = now
            report(message(), 0.1)
        }
    }
}
