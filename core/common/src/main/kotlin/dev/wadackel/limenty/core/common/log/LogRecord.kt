package dev.wadackel.limenty.core.common.log

data class LogRecord(
    /**
     * Monotonic within the process. Wall-clock milliseconds collide freely across a burst of
     * keystrokes or IME callbacks, and the order of those callbacks is usually the thing under
     * investigation, so ordering cannot rest on the timestamp.
     */
    val sequence: Long,
    val timestampMillis: Long,
    /** Which thread produced the record — IME and rendering bugs are frequently threading bugs. */
    val thread: String,
    val level: LogLevel,
    val tag: String,
    val event: String,
    val fields: List<Pair<String, String>> = emptyList(),
    val throwable: Throwable? = null,
)
