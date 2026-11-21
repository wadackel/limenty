package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.RecordingTerminalBackend
import org.junit.Test

/**
 * Regressions for the two ways a composition used to corrupt terminal input: an empty-but-active
 * preedit silently eating keys, and a preedit that stayed live while an unrelated key was passed
 * through to the remote.
 */
class CompositionOwnershipTest {
    private val backend = RecordingTerminalBackend()
    private val buffer = ImeEditBuffer()
    private val arbiter = InputArbiter(buffer)
    private val pipeline = TerminalInputPipeline(sink = backend::write)

    private fun key(key: TerminalKey?, codePoint: Int = 0, modifiers: Modifiers = Modifiers.None) = arbiter.onKey(key, codePoint, modifiers).also { pipeline.send(it.events) }

    // --- empty preedit must not claim ownership ---

    @Test
    fun `erasing the last preedit character ends the composition`() {
        buffer.setComposingText("あ", 1)
        buffer.setComposingText("", 1)

        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `enter still sends CR after the preedit has been emptied`() {
        buffer.setComposingText("あ", 1)
        buffer.setComposingText("", 1)
        key(TerminalKey.Enter)

        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x0D))
    }

    @Test
    fun `backspace still erases after the preedit has been emptied`() {
        buffer.setComposingText("あ", 1)
        buffer.setComposingText("", 1)
        key(TerminalKey.Backspace)

        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x7F))
    }

    @Test
    fun `escape still reaches the terminal after the preedit has been emptied`() {
        buffer.setComposingText("あ", 1)
        buffer.setComposingText("", 1)
        key(TerminalKey.Escape)

        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x1B))
    }

    @Test
    fun `deleting the whole preedit ends the composition`() {
        buffer.setComposingText("にほんご", 1)
        buffer.deleteSurroundingText(4, 0)

        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `an empty composing region ends the composition`() {
        buffer.setComposingText("にほんご", 1)
        buffer.setComposingRegion(2, 2)

        assertThat(buffer.composing.active).isFalse()
    }

    // --- a live preedit is never left behind when a key passes through ---

    @Test
    fun `tab during composition drops the preedit instead of typing under it`() {
        buffer.setComposingText("にほんご", 1)
        val decision = key(TerminalKey.Tab)

        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionInterrupted)
        assertThat(buffer.composing.active).isFalse()
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x09))
    }

    @Test
    fun `an alt chord during composition drops the preedit`() {
        buffer.setComposingText("にほんご", 1)
        val decision = key(null, 'x'.code, Modifiers.Alt)

        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionInterrupted)
        assertThat(buffer.composing.active).isFalse()
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x1B, 0x78))
    }

    @Test
    fun `a stray printable key during composition drops the preedit`() {
        buffer.setComposingText("にほんご", 1)
        val decision = key(null, 'a'.code)

        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionInterrupted)
        assertThat(buffer.composing.active).isFalse()
        assertThat(backend.text()).isEqualTo("a")
    }

    @Test
    fun `function keys during composition drop the preedit`() {
        buffer.setComposingText("にほんご", 1)
        key(TerminalKey.F5)

        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `vertical arrows during composition drop the preedit and move the remote cursor`() {
        buffer.setComposingText("にほんご", 1)
        key(TerminalKey.ArrowUp)

        assertThat(buffer.composing.active).isFalse()
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
    }

    @Test
    fun `no key can both leave a live preedit and send bytes`() {
        val keys: List<TerminalKey?> = TerminalKey.entries + listOf(null)
        val modifierSets = listOf(Modifiers.None, Modifiers.Shift, Modifiers.Alt, Modifiers.Ctrl)

        for (candidate in keys) {
            for (modifiers in modifierSets) {
                val freshBuffer = ImeEditBuffer()
                val freshArbiter = InputArbiter(freshBuffer)
                val freshBackend = RecordingTerminalBackend()
                val freshPipeline = TerminalInputPipeline(sink = freshBackend::write)
                freshBuffer.setComposingText("にほんご", 1)

                val decision = freshArbiter.onKey(candidate, if (candidate == null) 'a'.code else 0, modifiers)
                freshPipeline.send(decision.events)

                if (freshBackend.size() > 0) {
                    assertThat(freshBuffer.composing.active).isFalse()
                }
            }
        }
    }

    // --- editing keys keep owning the preedit ---

    @Test
    fun `unmodified editing keys still edit the preedit`() {
        buffer.setComposingText("にほんご", 1)
        key(TerminalKey.Backspace)

        assertThat(buffer.composing.text).isEqualTo("にほん")
        assertThat(backend.size()).isEqualTo(0)
    }

    // --- code point aware deletion ---

    @Test
    fun `deleting one code point removes a whole surrogate pair`() {
        buffer.setComposingText("あ😀", 1)
        buffer.deleteSurroundingTextInCodePoints(1, 0)

        assertThat(buffer.composing.text).isEqualTo("あ")
    }

    @Test
    fun `code point deletion clamps to what the preedit holds`() {
        buffer.setComposingText("😀😀", 1)
        buffer.deleteSurroundingTextInCodePoints(9, 9)

        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `code point deletion outside a composition erases once per code point`() {
        val events = buffer.deleteSurroundingTextInCodePoints(2, 0)
        assertThat(events).hasSize(2)
    }
}
