package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.KeyEncodingModes
import org.junit.Test

class TerminalKeyEncoderTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    private fun ByteArray.readable() = joinToString(" ") { "%02x".format(it) }

    private fun assertEncodes(actual: ByteArray, expected: ByteArray) {
        assertThat(actual.readable()).isEqualTo(expected.readable())
    }

    private fun esc(body: String) = bytes(0x1B) + body.toByteArray(Charsets.US_ASCII)

    // --- text ---

    @Test
    fun `committed text is encoded as UTF-8`() {
        assertEncodes(TerminalKeyEncoder.encodeText("日本語"), "日本語".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `plain ascii character passes through`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('a'.code), bytes(0x61))
    }

    @Test
    fun `astral plane characters survive as a surrogate pair`() {
        val emoji = 0x1F600
        assertEncodes(TerminalKeyEncoder.encodeCodePoint(emoji), "😀".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `shift held with a letter sends the capital`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('a'.code, Modifiers.Shift), bytes(0x41))
    }

    @Test
    fun `alt and shift with a letter send escape and the capital`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('a'.code, Modifiers.Alt + Modifiers.Shift), bytes(0x1B, 0x41))
    }

    // --- control characters ---

    @Test
    fun `ctrl c sends ETX`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('c'.code, Modifiers.Ctrl), bytes(0x03))
    }

    @Test
    fun `ctrl h sends BS`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('h'.code, Modifiers.Ctrl), bytes(0x08))
    }

    @Test
    fun `ctrl l sends FF`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('l'.code, Modifiers.Ctrl), bytes(0x0C))
    }

    /**
     * The byte is also XOFF, which is the whole reason this one is worth naming.
     *
     * A terminal that sends it has done its job; whether it survives depends on the pty's flow
     * control, which is why `SshTransport` asks for a pty with IXON off. The arithmetic below is
     * already covered by the c/h/l cases — what is recorded here is which byte the tmux prefix
     * this app is used with actually is.
     */
    @Test
    fun `ctrl s sends DC3, which is also XOFF`() {
        assertThat(TerminalKeyEncoder.encodeCodePoint('s'.code, Modifiers.Ctrl).toList())
            .isEqualTo(listOf<Byte>(0x13))
    }

    @Test
    fun `ctrl bracket sends ESC`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('['.code, Modifiers.Ctrl), bytes(0x1B))
    }

    @Test
    fun `ctrl is case insensitive`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('C'.code, Modifiers.Ctrl), bytes(0x03))
    }

    @Test
    fun `ctrl space sends NUL`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Space, Modifiers.Ctrl), bytes(0x00))
    }

    @Test
    fun `ctrl on a character with no control meaning sends the character`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('あ'.code, Modifiers.Ctrl), "あ".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `alt x is prefixed with ESC`() {
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('x'.code, Modifiers.Alt), bytes(0x1B, 0x78))
    }

    @Test
    fun `alt can combine with ctrl`() {
        assertEncodes(
            TerminalKeyEncoder.encodeCodePoint('c'.code, Modifiers.Alt + Modifiers.Ctrl),
            bytes(0x1B, 0x03),
        )
    }

    @Test
    fun `alt sending escape can be turned off`() {
        val modes = KeyEncodingModes(altSendsEscape = false)
        assertEncodes(TerminalKeyEncoder.encodeCodePoint('x'.code, Modifiers.Alt, modes), bytes(0x78))
    }

    // --- named keys ---

    @Test
    fun `enter sends CR`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Enter), bytes(0x0D))
    }

    @Test
    fun `escape sends ESC`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Escape), bytes(0x1B))
    }

    @Test
    fun `tab sends HT and shift tab sends back-tab`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Tab), bytes(0x09))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Tab, Modifiers.Shift), esc("[Z"))
    }

    @Test
    fun `backspace sends DEL by default`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Backspace), bytes(0x7F))
    }

    @Test
    fun `ctrl backspace sends the other erase character`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Backspace, Modifiers.Ctrl), bytes(0x08))
    }

    @Test
    fun `backspace honours DECBKM`() {
        val modes = KeyEncodingModes(backspaceSendsBackspace = true)
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Backspace, modes = modes), bytes(0x08))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Backspace, Modifiers.Ctrl, modes), bytes(0x7F))
    }

    @Test
    fun `delete is distinct from backspace`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Delete), esc("[3~"))
    }

    // --- cursor keys ---

    @Test
    fun `cursor keys use CSI in normal mode`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowUp), esc("[A"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowDown), esc("[B"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowRight), esc("[C"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowLeft), esc("[D"))
    }

    @Test
    fun `cursor keys use SS3 in application mode`() {
        val modes = KeyEncodingModes(applicationCursorKeys = true)
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowUp, modes = modes), esc("OA"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowLeft, modes = modes), esc("OD"))
    }

    @Test
    fun `a modified cursor key falls back to CSI even in application mode`() {
        val modes = KeyEncodingModes(applicationCursorKeys = true)
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowUp, Modifiers.Ctrl, modes), esc("[1;5A"))
    }

    @Test
    fun `xterm modifier parameters follow the shift alt ctrl meta order`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowRight, Modifiers.Shift), esc("[1;2C"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowRight, Modifiers.Alt), esc("[1;3C"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.ArrowRight, Modifiers.Ctrl), esc("[1;5C"))
        assertEncodes(
            TerminalKeyEncoder.encode(TerminalKey.ArrowRight, Modifiers.Ctrl + Modifiers.Shift),
            esc("[1;6C"),
        )
    }

    @Test
    fun `home and end use CSI H and CSI F`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Home), esc("[H"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.End), esc("[F"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Home, Modifiers.Ctrl), esc("[1;5H"))
    }

    @Test
    fun `paging keys use tilde sequences`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.PageUp), esc("[5~"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.PageDown), esc("[6~"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.Insert), esc("[2~"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.PageUp, Modifiers.Shift), esc("[5;2~"))
    }

    // --- function keys ---

    @Test
    fun `F1 to F4 use SS3`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.F1), esc("OP"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.F2), esc("OQ"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.F3), esc("OR"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.F4), esc("OS"))
    }

    @Test
    fun `F5 to F12 use tilde sequences with the xterm numbering gaps`() {
        val expected = mapOf(
            TerminalKey.F5 to 15,
            TerminalKey.F6 to 17,
            TerminalKey.F7 to 18,
            TerminalKey.F8 to 19,
            TerminalKey.F9 to 20,
            TerminalKey.F10 to 21,
            TerminalKey.F11 to 23,
            TerminalKey.F12 to 24,
        )
        expected.forEach { (key, number) ->
            assertEncodes(TerminalKeyEncoder.encode(key), esc("[$number~"))
        }
    }

    @Test
    fun `modified function keys carry the modifier parameter`() {
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.F1, Modifiers.Shift), esc("[1;2P"))
        assertEncodes(TerminalKeyEncoder.encode(TerminalKey.F5, Modifiers.Ctrl), esc("[15;5~"))
    }

    @Test
    fun `every key encodes to a non-empty sequence under every single modifier`() {
        val modifiers = listOf(Modifiers.None, Modifiers.Shift, Modifiers.Alt, Modifiers.Ctrl, Modifiers.Meta)
        for (key in TerminalKey.entries) {
            for (modifier in modifiers) {
                val encoded = TerminalKeyEncoder.encode(key, modifier)
                assertThat(encoded.isNotEmpty()).isTrue()
            }
        }
    }
}
