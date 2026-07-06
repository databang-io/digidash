package io.databang.digidash.core.logging

import android.content.Context
import java.io.File
import java.io.FileWriter

/** A recorded log file on disk. */
data class LogFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/**
 * Owns the app's private log directory. No storage permission needed: files
 * live in the app's filesDir and are shared out via FileProvider when the user
 * asks. Logs are local only — never uploaded.
 */
class LogRepository(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun newLogFile(compactTimestamp: String): File =
        File(dir, CsvLogger.fileName(compactTimestamp))

    fun openWriter(file: File): FileWriter = FileWriter(file, false)

    fun list(): List<LogFile> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            .orEmpty()
            .map { LogFile(it.name, it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedMillis }

    fun delete(logFile: LogFile): Boolean =
        runCatching { File(logFile.path).delete() }.getOrDefault(false)
}
