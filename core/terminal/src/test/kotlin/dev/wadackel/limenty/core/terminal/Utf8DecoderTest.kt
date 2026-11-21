package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Utf8DecoderTest {
    private val decoder = Utf8Decoder()

    private fun decodeAll(bytes: ByteArray): List<Int> = buildList { bytes.forEach { byte -> decoder.decode(byte.toInt(), ::add) } }

    private fun decodeAll(text: String): List<Int> = decodeAll(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `ascii passes through`() {
        assertThat(decodeAll("ls -la")).isEqualTo("ls -la".map { it.code })
    }

    @Test
    fun `japanese decodes to its code points`() {
        assertThat(decodeAll("日本語")).containsExactly(0x65E5, 0x672C, 0x8A9E).inOrder()
    }

    @Test
    fun `astral plane characters decode to a single code point`() {
        assertThat(decodeAll("😀")).containsExactly(0x1F600)
    }

    @Test
    fun `a character split across two writes still decodes once`() {
        val bytes = "語".toByteArray(Charsets.UTF_8)
        val first = decodeAll(bytes.copyOfRange(0, 2))
        val second = decodeAll(bytes.copyOfRange(2, 3))

        assertThat(first).isEmpty()
        assertThat(second).containsExactly(0x8A9E)
    }

    @Test
    fun `a character split byte by byte still decodes once`() {
        val bytes = "😀".toByteArray(Charsets.UTF_8)
        assertThat(decodeAll(bytes)).containsExactly(0x1F600)
    }

    @Test
    fun `a lone continuation byte becomes the replacement character`() {
        assertThat(decodeAll(byteArrayOf(0x80.toByte()))).containsExactly(Utf8Decoder.REPLACEMENT)
    }

    @Test
    fun `an overlong encoding is rejected`() {
        // 0xC0 0xAF would be an overlong '/', historically a security problem.
        val emitted = decodeAll(byteArrayOf(0xC0.toByte(), 0xAF.toByte()))
        assertThat(emitted).doesNotContain('/'.code)
    }

    @Test
    fun `a surrogate encoded in utf-8 is rejected`() {
        val emitted = decodeAll(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        assertThat(emitted).containsExactly(Utf8Decoder.REPLACEMENT)
    }

    @Test
    fun `a truncated sequence yields one replacement and does not swallow what follows`() {
        assertThat(decodeAll(byteArrayOf(0xE6.toByte(), 0x41)))
            .containsExactly(Utf8Decoder.REPLACEMENT, 'A'.code).inOrder()
    }

    @Test
    fun `a truncated sequence followed by a new lead byte recovers`() {
        val bytes = byteArrayOf(0xE6.toByte()) + "語".toByteArray(Charsets.UTF_8)
        assertThat(decodeAll(bytes)).containsExactly(Utf8Decoder.REPLACEMENT, 0x8A9E).inOrder()
    }

    @Test
    fun `reset drops a partial sequence`() {
        decodeAll(byteArrayOf(0xE6.toByte()))
        decoder.reset()
        assertThat(decodeAll(byteArrayOf(0x41))).containsExactly('A'.code)
    }

    @Test
    fun `mixed ascii and multibyte text round-trips`() {
        val text = "user@macbook ~/日本語 $ echo 😀"
        val decoded = decodeAll(text)
        val rebuilt = buildString { decoded.forEach { appendCodePoint(it) } }
        assertThat(rebuilt).isEqualTo(text)
    }
}
