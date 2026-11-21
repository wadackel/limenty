package dev.wadackel.limenty.core.common.log

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide log dispatcher.
 *
 * A global object is used deliberately: logging call sites live in hot paths (per-keystroke,
 * per-IME-callback) where threading a logger through every constructor would distort the
 * design of the very subsystems the logs exist to explain.
 */
object TermLog {
    private class Registration(val sink: LogSink, val minLevel: LogLevel)

    private val sinks = CopyOnWriteArrayList<Registration>()
    private val sequence = AtomicLong(0)

    /**
     * The floor below which nothing is even formatted. Sinks narrow further on their own, so this
     * stays at the most detailed level any sink wants — otherwise a quiet logcat would also empty
     * the crash-report buffer, which is the one log a real-device failure leaves behind.
     */
    @Volatile
    var minLevel: LogLevel = LogLevel.INFO

    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    fun addSink(sink: LogSink, minLevel: LogLevel = LogLevel.VERBOSE) {
        if (sinks.any { it.sink === sink }) return
        sinks.add(Registration(sink, minLevel))
    }

    fun removeSink(sink: LogSink) {
        sinks.removeAll { it.sink === sink }
    }

    fun clearSinks() {
        sinks.clear()
    }

    fun isLoggable(level: LogLevel): Boolean = level.atLeast(minLevel) && sinks.any { level.atLeast(it.minLevel) }

    fun logger(tag: String): Logger = Logger(tag)

    internal fun dispatch(
        level: LogLevel,
        tag: String,
        event: String,
        fields: List<Pair<String, String>>,
        throwable: Throwable?,
    ) {
        if (!level.atLeast(minLevel)) return
        if (sinks.isEmpty()) return
        val record = LogRecord(
            sequence = sequence.incrementAndGet(),
            timestampMillis = clock(),
            thread = Thread.currentThread().name,
            level = level,
            tag = tag,
            event = event,
            fields = fields,
            throwable = throwable,
        )
        for (registration in sinks) {
            if (!level.atLeast(registration.minLevel)) continue
            runCatching { registration.sink.emit(record) }
        }
    }
}
