package dev.wadackel.limenty.core.common.log

fun interface LogSink {
    fun emit(record: LogRecord)
}
