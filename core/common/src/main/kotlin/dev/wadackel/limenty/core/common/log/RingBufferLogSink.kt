package dev.wadackel.limenty.core.common.log

/**
 * Keeps the most recent records in memory so a failure can be inspected after the fact
 * without a logcat attached — the usual situation when the bug is reported from a device.
 */
class RingBufferLogSink(private val capacity: Int = DEFAULT_CAPACITY) : LogSink {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val buffer = arrayOfNulls<LogRecord>(capacity)
    private var writeIndex = 0
    private var size = 0

    override fun emit(record: LogRecord) {
        synchronized(lock) {
            buffer[writeIndex] = record
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    fun snapshot(): List<LogRecord> = synchronized(lock) {
        val start = (writeIndex - size + capacity) % capacity
        List(size) { offset -> buffer[(start + offset) % capacity]!! }
    }

    fun clear() {
        synchronized(lock) {
            buffer.fill(null)
            writeIndex = 0
            size = 0
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2048
    }
}
