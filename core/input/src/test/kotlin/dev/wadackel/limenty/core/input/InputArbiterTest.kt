package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InputArbiterTest {
    private val buffer = ImeEditBuffer()
    private val arbiter = InputArbiter(buffer)

    private fun compose(text: String) = buffer.setComposingText(text, 1)

    // --- no composition: the terminal owns everything ---

    @Test
    fun `named keys reach the terminal untouched`() {
        val decision = arbiter.onKey(TerminalKey.Escape)
        assertThat(decision.events).containsExactly(TerminalInputEvent.Key(TerminalKey.Escape, Modifiers.None))
        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.PassThrough)
        assertThat(decision.handled).isTrue()
    }

    @Test
    fun `a printable key becomes text`() {
        assertThat(arbiter.onKey(null, 'a'.code).events)
            .containsExactly(TerminalInputEvent.Text("a"))
    }

    @Test
    fun `ctrl and alt chords keep their modifiers`() {
        assertThat(arbiter.onKey(null, 'c'.code, Modifiers.Ctrl).events)
            .containsExactly(TerminalInputEvent.Chord('c'.code, Modifiers.Ctrl))
        assertThat(arbiter.onKey(null, 'x'.code, Modifiers.Alt).events)
            .containsExactly(TerminalInputEvent.Chord('x'.code, Modifiers.Alt))
    }

    @Test
    fun `shifted characters arrive already resolved and stay plain text`() {
        assertThat(arbiter.onKey(null, 'A'.code, Modifiers.Shift).events)
            .containsExactly(TerminalInputEvent.Text("A"))
    }

    @Test
    fun `a key with neither identity nor character is left to the framework`() {
        val decision = arbiter.onKey(null, 0)
        assertThat(decision.handled).isFalse()
        assertThat(decision.events).isEmpty()
    }

    // --- composition ownership ---

    @Test
    fun `esc aborts the composition and sends nothing`() {
        compose("にほんご")
        val decision = arbiter.onKey(TerminalKey.Escape)

        assertThat(decision.events).isEmpty()
        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionCancelled)
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `enter confirms the composition and does not also send a newline`() {
        compose("日本語")
        val decision = arbiter.onKey(TerminalKey.Enter)

        assertThat(decision.events).containsExactly(TerminalInputEvent.Text("日本語"))
        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionCommitted)
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `enter after the composition ends sends CR as usual`() {
        compose("日本語")
        arbiter.onKey(TerminalKey.Enter)
        val decision = arbiter.onKey(TerminalKey.Enter)

        assertThat(decision.events).containsExactly(TerminalInputEvent.Key(TerminalKey.Enter, Modifiers.None))
    }

    @Test
    fun `backspace edits the preedit instead of erasing on the remote`() {
        compose("にほんご")
        val decision = arbiter.onKey(TerminalKey.Backspace)

        assertThat(decision.events).isEmpty()
        assertThat(buffer.composing.text).isEqualTo("にほん")
        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionEdited)
    }

    @Test
    fun `horizontal arrows move the caret inside the preedit`() {
        compose("にほんご")
        assertThat(arbiter.onKey(TerminalKey.ArrowLeft).events).isEmpty()
        assertThat(buffer.composing.cursor).isEqualTo(3)
        assertThat(arbiter.onKey(TerminalKey.ArrowRight).events).isEmpty()
        assertThat(buffer.composing.cursor).isEqualTo(4)
    }

    @Test
    fun `home and end jump to the preedit boundaries`() {
        compose("にほんご")
        arbiter.onKey(TerminalKey.Home)
        assertThat(buffer.composing.cursor).isEqualTo(0)
        arbiter.onKey(TerminalKey.End)
        assertThat(buffer.composing.cursor).isEqualTo(4)
    }

    @Test
    fun `keys the composition cannot own drop the preedit and reach the terminal`() {
        compose("にほんご")
        val decision = arbiter.onKey(TerminalKey.ArrowUp)

        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionInterrupted)
        assertThat(decision.events).containsExactly(TerminalInputEvent.Key(TerminalKey.ArrowUp, Modifiers.None))
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `ctrl c interrupts the composition and reaches the terminal`() {
        compose("にほんご")
        val decision = arbiter.onKey(null, 'c'.code, Modifiers.Ctrl)

        assertThat(decision.events).containsExactly(TerminalInputEvent.Chord('c'.code, Modifiers.Ctrl))
        assertThat(decision.reason).isEqualTo(KeyDecision.Reason.CompositionInterrupted)
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `an interrupted composition is discarded rather than committed`() {
        compose("にほんご")
        val decision = arbiter.onKey(null, 'c'.code, Modifiers.Ctrl)

        assertThat(decision.events.filterIsInstance<TerminalInputEvent.Text>()).isEmpty()
    }

    @Test
    fun `ctrl on a named key also interrupts`() {
        compose("にほんご")
        val decision = arbiter.onKey(TerminalKey.ArrowUp, modifiers = Modifiers.Ctrl)

        assertThat(decision.events)
            .containsExactly(TerminalInputEvent.Key(TerminalKey.ArrowUp, Modifiers.Ctrl))
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `no arbitration path can emit the preedit as text without confirming it`() {
        val keys = TerminalKey.entries + listOf(null)
        val modifierSets = listOf(Modifiers.None, Modifiers.Shift, Modifiers.Alt, Modifiers.Ctrl)

        for (key in keys) {
            for (modifiers in modifierSets) {
                val freshBuffer = ImeEditBuffer()
                val freshArbiter = InputArbiter(freshBuffer)
                freshBuffer.setComposingText("にほんご", 1)

                val decision = freshArbiter.onKey(key, if (key == null) 'a'.code else 0, modifiers)
                val emitted = decision.events.filterIsInstance<TerminalInputEvent.Text>().map { it.text }

                // Only an explicit confirmation may produce the preedit as text.
                if (decision.reason != KeyDecision.Reason.CompositionCommitted) {
                    assertThat(emitted).doesNotContain("にほんご")
                }
            }
        }
    }
}
