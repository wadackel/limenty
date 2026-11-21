package dev.wadackel.limenty.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RingBufferLogSinkTest {
    private fun record(event: String) = LogRecord(0L, 0L, "test", LogLevel.INFO, "Tag", event)

    @Test
    fun `snapshot is empty before any record`() {
        assertThat(RingBufferLogSink(4).snapshot()).isEmpty()
    }

    @Test
    fun `records are returned in insertion order`() {
        val sink = RingBufferLogSink(4)
        listOf("a", "b", "c").forEach { sink.emit(record(it)) }
        assertThat(sink.snapshot().map { it.event }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `oldest records are dropped once capacity is exceeded`() {
        val sink = RingBufferLogSink(3)
        listOf("a", "b", "c", "d", "e").forEach { sink.emit(record(it)) }
        assertThat(sink.snapshot().map { it.event }).containsExactly("c", "d", "e").inOrder()
    }

    @Test
    fun `clear drops everything`() {
        val sink = RingBufferLogSink(3)
        sink.emit(record("a"))
        sink.clear()
        assertThat(sink.snapshot()).isEmpty()
    }

    @Test
    fun `capacity must be positive`() {
        runCatching { RingBufferLogSink(0) }
            .onSuccess { throw AssertionError("expected failure") }
            .onFailure { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }
    }
}
