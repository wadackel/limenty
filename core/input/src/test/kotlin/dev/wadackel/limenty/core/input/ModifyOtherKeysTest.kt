package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.KeyEncodingModes
import org.junit.Test

/**
 * Reporting a modifier on a key whose legacy encoding throws it away.
 *
 * The reported form exists because Shift+Enter and Enter were the same byte, so an editor could
 * not tell them apart. The gate exists because the reported form is not something a shell can be
 * handed unasked: measured against zsh, `CSI 27;2;13~` at a prompt produces a bell and a mangled
 * line rather than a command, so every case here has a partner at level 0.
 */
class ModifyOtherKeysTest {
    private val asked = KeyEncodingModes(modifyOtherKeys = 2)
    private val never = KeyEncodingModes.Default

    private fun bytes(key: TerminalKey, modifiers: Modifiers, modes: KeyEncodingModes) = TerminalKeyEncoder.encode(key, modifiers, modes).toList()

    private fun reported(code: Int, parameter: Int) = "\u001B[27;$parameter;$code~".toByteArray(Charsets.US_ASCII).toList()

    /**
     * Shift and Enter is the one combination with somewhere to go before anybody asks.
     *
     * Applications settled on CR to submit and LF to insert a line long before modifyOtherKeys, so
     * the plain byte already means the right thing to them, and being a plain byte it needs
     * nothing from tmux — measured through a pty, LF arrives as LF with extended keys on, off, and
     * with no tmux at all, where the reported form collapses back to CR unless tmux was told.
     */
    @Test
    fun `shift and enter is a newline, and the reported form when one is wanted`() {
        assertThat(bytes(TerminalKey.Enter, Modifiers.None, never)).isEqualTo(listOf<Byte>(0x0D))
        assertThat(bytes(TerminalKey.Enter, Modifiers.Shift, never)).isEqualTo(listOf<Byte>(0x0A))
        assertThat(bytes(TerminalKey.Enter, Modifiers.Shift, asked)).isEqualTo(reported(13, 2))
    }

    @Test
    fun `the modifier rides in the parameter, as it does for the cursor keys`() {
        assertThat(bytes(TerminalKey.Enter, Modifiers.Ctrl, asked)).isEqualTo(reported(13, 5))
        assertThat(bytes(TerminalKey.Enter, Modifiers.Alt, asked)).isEqualTo(reported(13, 3))
        assertThat(bytes(TerminalKey.Escape, Modifiers.Shift, asked)).isEqualTo(reported(27, 2))
        assertThat(bytes(TerminalKey.Space, Modifiers.Ctrl, asked)).isEqualTo(reported(32, 5))
        assertThat(bytes(TerminalKey.Tab, Modifiers.Ctrl, asked)).isEqualTo(reported(9, 5))
        assertThat(bytes(TerminalKey.Backspace, Modifiers.Shift, asked)).isEqualTo(reported(127, 2))
    }

    /**
     * The most important test here.
     *
     * A prompt that never asked has to keep receiving exactly what it received before, or the fix
     * for one editor becomes a bell on every command line.
     */
    @Test
    fun `a key nobody asked about keeps its legacy bytes`() {
        val keys = listOf(
            TerminalKey.Enter,
            TerminalKey.Escape,
            TerminalKey.Space,
            TerminalKey.Tab,
            TerminalKey.Backspace,
        )
        val held = listOf(Modifiers.None, Modifiers.Shift, Modifiers.Ctrl, Modifiers.Alt)
        keys.forEach { key ->
            held.forEach { modifiers ->
                assertThat(bytes(key, modifiers, never)).isEqualTo(legacy(key, modifiers))
            }
            // Nothing held is nothing to report, whoever asked.
            assertThat(bytes(key, Modifiers.None, asked)).isEqualTo(bytes(key, Modifiers.None, never))
        }
        assertThat(bytes(TerminalKey.Enter, Modifiers.None, asked)).isEqualTo(listOf<Byte>(0x0D))
    }

    /** What each of these sent before any of this existed, written out rather than derived. */
    private fun legacy(key: TerminalKey, modifiers: Modifiers): List<Byte> {
        val esc = 0x1B.toByte()
        val base: List<Byte> = when (key) {
            TerminalKey.Enter -> if (modifiers.shift) listOf(0x0A) else listOf(0x0D)
            TerminalKey.Escape -> listOf(esc)
            TerminalKey.Space -> if (modifiers.ctrl) listOf(0x00) else listOf(0x20)
            TerminalKey.Tab ->
                if (modifiers.shift) return listOf(esc, '['.code.toByte(), 'Z'.code.toByte()) else listOf(0x09)
            TerminalKey.Backspace -> if (modifiers.ctrl) listOf(0x08) else listOf(0x7F)
            else -> error("not one of the keys this covers")
        }
        return if (modifiers.alt) listOf(esc) + base else base
    }

    /** Level 1 asks only about keys with no legacy form, so these keep theirs. */
    @Test
    fun `level one leaves these keys alone`() {
        val level1 = KeyEncodingModes(modifyOtherKeys = 1)
        assertThat(bytes(TerminalKey.Enter, Modifiers.Shift, level1))
            .isEqualTo(bytes(TerminalKey.Enter, Modifiers.Shift, never))
        assertThat(bytes(TerminalKey.Escape, Modifiers.Ctrl, level1))
            .isEqualTo(bytes(TerminalKey.Escape, Modifiers.Ctrl, never))
    }

    /** Back-tab is a key, not Tab with Shift held, and stays one however keys are reported. */
    @Test
    fun `shift and tab stay back-tab`() {
        val backTab = "\u001B[Z".toByteArray(Charsets.US_ASCII).toList()
        assertThat(bytes(TerminalKey.Tab, Modifiers.Shift, never)).isEqualTo(backTab)
        assertThat(bytes(TerminalKey.Tab, Modifiers.Shift, asked)).isEqualTo(backTab)
    }

    /** Cursor and function keys belong to other resources and keep the encoding they had. */
    @Test
    fun `the cursor keys are not touched by this`() {
        assertThat(bytes(TerminalKey.ArrowUp, Modifiers.Shift, asked))
            .isEqualTo(bytes(TerminalKey.ArrowUp, Modifiers.Shift, never))
        assertThat(bytes(TerminalKey.PageUp, Modifiers.Ctrl, asked))
            .isEqualTo(bytes(TerminalKey.PageUp, Modifiers.Ctrl, never))
        assertThat(bytes(TerminalKey.F5, Modifiers.Shift, asked))
            .isEqualTo(bytes(TerminalKey.F5, Modifiers.Shift, never))
    }
}
