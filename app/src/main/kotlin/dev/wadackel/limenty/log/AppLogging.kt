package dev.wadackel.limenty.log

import android.content.Context
import dev.wadackel.limenty.BuildConfig
import dev.wadackel.limenty.core.common.log.LogFormatter
import dev.wadackel.limenty.core.common.log.LogLevel
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.RingBufferLogSink
import dev.wadackel.limenty.core.common.log.TermLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Owns the process-wide log sinks.
 *
 * The in-memory ring buffer is kept in release builds too: a failure reported from a real
 * device is usually the only chance to see the event sequence that produced it.
 */
object AppLogging {
    val recent = RingBufferLogSink()

    private val timestampFormat =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    @Volatile
    private var diagnostics: DiagnosticsStore? = null

    fun install(context: Context) {
        // The ring buffer keeps the input/IME trace even in release, because that trace is the
        // whole point of the crash report; logcat stays at INFO there so day-to-day output is
        // not flooded by per-keystroke records.
        TermLog.minLevel = if (BuildConfig.VERBOSE_LOGGING) LogLevel.VERBOSE else LogLevel.DEBUG
        TermLog.addSink(
            LogcatSink(),
            minLevel = if (BuildConfig.VERBOSE_LOGGING) LogLevel.VERBOSE else LogLevel.INFO,
        )
        TermLog.addSink(recent, minLevel = LogLevel.VERBOSE)

        val store = DiagnosticsStore(context)
        diagnostics = store
        installCrashHandler(store)
    }

    /**
     * Writes the current buffer to disk on demand; returns the report path for the caller to show.
     *
     * The crash handler calls [DiagnosticsStore.write] directly instead: it runs on a process that
     * is already going down, where there is nothing left to suspend onto.
     */
    suspend fun dumpToDisk(reason: String): String? = withContext(Dispatchers.IO) {
        diagnostics?.write(reason)?.absolutePath
    }

    /** Renders the ring buffer as a plain-text report suitable for sharing or `adb pull`. */
    fun dumpRecent(): String = recent.snapshot().joinToString(separator = "\n") { record ->
        buildString {
            append(record.sequence).append(' ')
            append(timestampFormat.format(Instant.ofEpochMilli(record.timestampMillis)))
            append(' ').append(record.level.short)
            append(" [").append(record.thread).append("] ")
            append(record.tag).append(": ").append(LogFormatter.formatMessage(record))
            record.throwable?.let { append(" | ").append(it.toString()) }
        }
    }

    /**
     * Chains rather than replaces: the platform handler is what actually terminates the process
     * and produces the tombstone, and swallowing it would turn a crash into a hang.
     */
    private fun installCrashHandler(store: DiagnosticsStore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                TermLog.logger(LogTags.LIFECYCLE).e(throwable, "app.crash", "thread" to thread.name)
                store.write("crash", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
