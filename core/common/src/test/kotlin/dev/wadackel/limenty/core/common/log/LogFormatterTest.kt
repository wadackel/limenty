package dev.wadackel.limenty.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogFormatterTest {
    private fun record(event: String, vararg fields: Pair<String, String>) = LogRecord(0L, 0L, "test", LogLevel.INFO, "Tag", event, fields.toList())

    @Test
    fun `event without fields renders bare`() {
        assertThat(LogFormatter.formatMessage(record("connect.start"))).isEqualTo("connect.start")
    }

    @Test
    fun `fields render as key value pairs`() {
        val message = LogFormatter.formatMessage(
            record("connect.start", "host" to "macbook", "port" to "22"),
        )
        assertThat(message).isEqualTo("connect.start host=macbook port=22")
    }

    @Test
    fun `values containing whitespace are quoted`() {
        val message = LogFormatter.formatMessage(record("error", "reason" to "connection timed out"))
        assertThat(message).isEqualTo("""error reason="connection timed out"""")
    }

    @Test
    fun `empty value is quoted so the pair stays parseable`() {
        assertThat(LogFormatter.formatMessage(record("e", "k" to ""))).isEqualTo("""e k=""""")
    }

    @Test
    fun `control characters are escaped`() {
        val message = LogFormatter.formatMessage(record("e", "k" to "a\nb\tc"))
        assertThat(message).isEqualTo("""e k="a\nb\tc"""")
    }

    @Test
    fun `escape sequences in a value are escaped rather than emitted raw`() {
        val message = LogFormatter.formatMessage(record("out", "seq" to "\u001B[1;5A"))
        assertThat(message).isEqualTo("""out seq="\x1b[1;5A"""")
        assertThat(message).doesNotContain("\u001B")
    }

    @Test
    fun `other C0 controls are escaped too`() {
        assertThat(LogFormatter.formatMessage(record("e", "k" to "\u0000\u0007\u007F")))
            .isEqualTo("""e k="\x00\x07\x7f"""")
    }

    @Test
    fun `values containing the separator are quoted so parsing stays unambiguous`() {
        assertThat(LogFormatter.formatMessage(record("e", "k" to "a=b")))
            .isEqualTo("""e k="a=b"""")
    }

    @Test
    fun `backslashes are escaped`() {
        assertThat(LogFormatter.formatMessage(record("e", "k" to """a\b""")))
            .isEqualTo("""e k="a\\b"""")
    }

    @Test
    fun `byte arrays render as a length only`() {
        assertThat(LogFormatter.formatValue(ByteArray(7))).isEqualTo("bytes[7]")
    }

    @Test
    fun `null renders as null`() {
        assertThat(LogFormatter.formatValue(null)).isEqualTo("null")
    }
}
