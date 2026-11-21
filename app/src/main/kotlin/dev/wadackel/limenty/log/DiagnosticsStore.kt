package dev.wadackel.limenty.log

import android.content.Context
import android.os.Build
import dev.wadackel.limenty.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Persists the in-memory log ring buffer to disk.
 *
 * The buffer is worthless if it only ever lives in the process that crashed, so every report
 * path — an uncaught exception, or the user noticing something is wrong — ends here, and the
 * file survives for `adb` (or a later share action) to collect.
 */
class DiagnosticsStore(context: Context) {
    // Resolved on first use, not here: `filesDir` creates the directory if it is missing, so
    // naming a file under it is a disk read and a disk write on whatever thread built this — and
    // this one is built from `Application.onCreate`.
    private val directory by lazy { File(context.filesDir, DIRECTORY_NAME) }
    private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS").withZone(ZoneId.systemDefault())

    fun write(reason: String, throwable: Throwable? = null): File? = runCatching {
        directory.mkdirs()
        val file = File(directory, "limenty-${fileTimestamp.format(Instant.now())}-$reason.log")
        file.writeText(buildReport(reason, throwable))
        prune()
        file
    }.getOrNull()

    /**
     * Oldest first.
     *
     * Ordered by when each was written rather than by name: the name ends in the reason, so two
     * reports from the same moment sorted alphabetically by what went wrong, which put the newest
     * one in the middle — and then [prune] deleted it.
     */
    fun files(): List<File> = directory.listFiles()?.sortedWith(compareBy({ it.lastModified() }, { it.name })).orEmpty()

    private fun buildReport(reason: String, throwable: Throwable?): String = buildString {
        appendLine("reason=$reason")
        appendLine("app=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        appendLine("device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} fingerprint=${Build.FINGERPRINT}")
        appendLine("capturedAt=${Instant.now()}")
        throwable?.let {
            appendLine("--- throwable ---")
            appendLine(it.stackTraceToString())
        }
        appendLine("--- recent events (${AppLogging.recent.snapshot().size}) ---")
        appendLine(AppLogging.dumpRecent())
    }

    /** Old reports are dropped rather than accumulating: this is device storage, not an archive. */
    private fun prune() {
        val files = files()
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    companion object {
        const val DIRECTORY_NAME = "diagnostics"
        private const val MAX_FILES = 10
    }
}
