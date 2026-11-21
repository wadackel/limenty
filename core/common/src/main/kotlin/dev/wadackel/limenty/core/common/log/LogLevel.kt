package dev.wadackel.limenty.core.common.log

enum class LogLevel(val priority: Int, val short: Char) {
    VERBOSE(2, 'V'),
    DEBUG(3, 'D'),
    INFO(4, 'I'),
    WARN(5, 'W'),
    ERROR(6, 'E'),
    ;

    fun atLeast(other: LogLevel): Boolean = priority >= other.priority
}
