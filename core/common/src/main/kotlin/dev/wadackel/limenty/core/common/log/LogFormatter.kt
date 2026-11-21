package dev.wadackel.limenty.core.common.log

object LogFormatter {
    /** Renders `event key=value key="value with spaces"` — greppable and stable. */
    fun formatMessage(record: LogRecord): String {
        if (record.fields.isEmpty()) return record.event
        return buildString {
            append(record.event)
            for ((key, value) in record.fields) {
                append(' ').append(key).append('=').append(quoteIfNeeded(value))
            }
        }
    }

    fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is CharSequence -> value.toString()
        is ByteArray -> "bytes[${value.size}]"
        else -> value.toString()
    }

    /**
     * Quotes anything that would make the `key=value` grammar ambiguous, and escapes control
     * characters. A terminal app routinely logs values carrying ESC and other C0 bytes; emitting
     * those raw would corrupt the surrounding logcat line and any tooling that reads it back.
     */
    private fun quoteIfNeeded(value: String): String {
        val needsQuote = value.isEmpty() || value.any { ch ->
            ch.isWhitespace() || ch == '"' || ch == '=' || ch == '\\' || ch.isISOControl()
        }
        if (!needsQuote) return value
        return buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch.isISOControl() -> append("\\x%02x".format(ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}
