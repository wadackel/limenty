package dev.wadackel.limenty.log

import android.util.Log
import dev.wadackel.limenty.core.common.log.LogFormatter
import dev.wadackel.limenty.core.common.log.LogLevel
import dev.wadackel.limenty.core.common.log.LogRecord
import dev.wadackel.limenty.core.common.log.LogSink

/**
 * Bridges [dev.wadackel.limenty.core.common.log.TermLog] onto logcat.
 *
 * Every tag is prefixed so a single `adb logcat -s 'Limenty.*'` captures the whole app
 * without also matching framework tags that happen to share a subsystem name.
 */
class LogcatSink(private val tagPrefix: String = TAG_PREFIX) : LogSink {
    override fun emit(record: LogRecord) {
        val tag = tagPrefix + record.tag
        val message = LogFormatter.formatMessage(record)
        when (record.level) {
            LogLevel.VERBOSE -> Log.v(tag, message, record.throwable)
            LogLevel.DEBUG -> Log.d(tag, message, record.throwable)
            LogLevel.INFO -> Log.i(tag, message, record.throwable)
            LogLevel.WARN -> Log.w(tag, message, record.throwable)
            LogLevel.ERROR -> Log.e(tag, message, record.throwable)
        }
    }

    companion object {
        const val TAG_PREFIX = "Limenty."
    }
}
