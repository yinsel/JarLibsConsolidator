package org.le1a.jarlibsconsolidator

/** Registered only when IDEA's bundled Java Bytecode Decompiler is enabled. */
internal class ExportJavaAction : BaseExportAction(true) {
    override fun decompiler(): ClassDecompiler = IdeaJavaDecompiler()
}
