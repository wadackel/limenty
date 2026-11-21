package dev.wadackel.limenty.core.common.log

/**
 * Subsystem-scoped logging facade.
 *
 * Every call takes a short `event` name plus structured fields instead of a free-form
 * sentence, so a log line can be matched by a fixed pattern when reconstructing a failure.
 */
class Logger internal constructor(private val tag: String) {
    fun v(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.VERBOSE, event, fields, null)

    fun d(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.DEBUG, event, fields, null)

    fun i(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.INFO, event, fields, null)

    fun w(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.WARN, event, fields, null)

    fun w(throwable: Throwable, event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.WARN, event, fields, throwable)

    fun e(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.ERROR, event, fields, null)

    fun e(throwable: Throwable, event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.ERROR, event, fields, throwable)

    fun isLoggable(level: LogLevel): Boolean = TermLog.isLoggable(level)

    private fun log(
        level: LogLevel,
        event: String,
        fields: Array<out Pair<String, Any?>>,
        throwable: Throwable?,
    ) {
        if (!level.atLeast(TermLog.minLevel)) return
        val rendered = if (fields.isEmpty()) {
            emptyList()
        } else {
            fields.map { (key, value) -> key to LogFormatter.formatValue(value) }
        }
        TermLog.dispatch(level, tag, event, rendered, throwable)
    }
}
