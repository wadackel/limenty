package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImeEditBufferTest {
    private val buffer = ImeEditBuffer()

    private fun textOf(events: List<TerminalInputEvent>) = events.filterIsInstance<TerminalInputEvent.Text>().joinToString("") { it.text }

    // --- nothing leaves the app while composing ---

    @Test
    fun `a full japanese conversion emits nothing until commit`() {
        val duringComposition = buildList {
            addAll(buffer.setComposingText("n", 1))
            addAll(buffer.setComposingText("に", 1))
            addAll(buffer.setComposingText("にほ", 1))
            addAll(buffer.setComposingText("にほん", 1))
            addAll(buffer.setComposingText("にほんご", 1))
            addAll(buffer.setComposingText("日本語", 1))
        }

        assertThat(duringComposition).isEmpty()
        assertThat(buffer.composing.text).isEqualTo("日本語")
        assertThat(buffer.composing.active).isTrue()

        val committed = buffer.commitText("日本語", 1)

        assertThat(textOf(committed)).isEqualTo("日本語")
        assertThat(buffer.composing.active).isFalse()
        assertThat(buffer.composing.text).isEmpty()
    }

    @Test
    fun `candidate changes during conversion emit nothing`() {
        buffer.setComposingText("きしゃ", 1)
        val events = buildList {
            addAll(buffer.setComposingText("汽車", 1))
            addAll(buffer.setComposingText("記者", 1))
            addAll(buffer.setComposingText("貴社", 1))
        }
        assertThat(events).isEmpty()
        assertThat(buffer.composing.text).isEqualTo("貴社")
    }

    @Test
    fun `cancelling a conversion sends nothing`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.discardComposing()).isEmpty()
        assertThat(buffer.composing.active).isFalse()
        assertThat(buffer.composing.text).isEmpty()
    }

    @Test
    fun `an empty commit sends nothing`() {
        buffer.setComposingText("あ", 1)
        assertThat(buffer.commitText("", 1)).isEmpty()
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `direct commit without composition sends the text`() {
        assertThat(textOf(buffer.commitText("ls -la", 1))).isEqualTo("ls -la")
    }

    // --- cursor placement ---

    @Test
    fun `newCursorPosition 1 puts the caret after the preedit`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.composing.cursor).isEqualTo(4)
    }

    @Test
    fun `newCursorPosition 0 puts the caret before the preedit`() {
        buffer.setComposingText("にほんご", 0)
        assertThat(buffer.composing.cursor).isEqualTo(0)
    }

    @Test
    fun `a caret beyond the preedit is clamped`() {
        buffer.setComposingText("ab", 9)
        assertThat(buffer.composing.cursor).isEqualTo(2)
        buffer.setComposingText("ab", -9)
        assertThat(buffer.composing.cursor).isEqualTo(0)
    }

    @Test
    fun `setSelection moves the caret inside the preedit`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.setSelection(2, 2)).isEmpty()
        assertThat(buffer.composing.cursor).isEqualTo(2)
    }

    @Test
    fun `setSelection outside a composition is ignored`() {
        assertThat(buffer.setSelection(0, 5)).isEmpty()
        assertThat(buffer.composing.active).isFalse()
    }

    // --- revision ---

    @Test
    fun `revision advances on every change so stale frames are detectable`() {
        val start = buffer.composing.revision
        buffer.setComposingText("に", 1)
        val afterCompose = buffer.composing.revision
        buffer.commitText("に", 1)
        val afterCommit = buffer.composing.revision

        assertThat(afterCompose).isGreaterThan(start)
        assertThat(afterCommit).isGreaterThan(afterCompose)
    }

    @Test
    fun `reset on an already inactive buffer does not churn the revision`() {
        val revision = buffer.composing.revision
        buffer.reset()
        assertThat(buffer.composing.revision).isEqualTo(revision)
    }

    // --- deletion ---

    @Test
    fun `backspace inside a composition edits the preedit and sends nothing`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.deleteSurroundingText(1, 0)).isEmpty()
        assertThat(buffer.composing.text).isEqualTo("にほん")
        assertThat(buffer.composing.cursor).isEqualTo(3)
    }

    @Test
    fun `backspace outside a composition becomes a terminal erase key`() {
        val events = buffer.deleteSurroundingText(1, 0)
        assertThat(events).containsExactly(TerminalInputEvent.Key(TerminalKey.Backspace))
    }

    @Test
    fun `forward delete outside a composition becomes the delete key`() {
        val events = buffer.deleteSurroundingText(0, 1)
        assertThat(events).containsExactly(TerminalInputEvent.Key(TerminalKey.Delete))
    }

    @Test
    fun `a multi character delete outside a composition repeats the key`() {
        val events = buffer.deleteSurroundingText(3, 0)
        assertThat(events).hasSize(3)
        assertThat(events.all { it == TerminalInputEvent.Key(TerminalKey.Backspace) }).isTrue()
    }

    @Test
    fun `deleting more than the preedit holds clamps instead of throwing`() {
        buffer.setComposingText("あ", 1)
        assertThat(buffer.deleteSurroundingText(10, 10)).isEmpty()
        assertThat(buffer.composing.text).isEmpty()
    }

    @Test
    fun `negative delete lengths are ignored`() {
        assertThat(buffer.deleteSurroundingText(-1, -1)).isEmpty()
    }

    // --- ending a composition ---

    @Test
    fun `a key the user pressed to confirm the candidate sends it`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(textOf(buffer.commitComposing())).isEqualTo("にほんご")
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `ending without a composition sends nothing`() {
        assertThat(buffer.endComposing()).isEmpty()
    }

    @Test
    fun `a key the composition cannot own throws the preedit away`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.discardComposing()).isEmpty()
        assertThat(buffer.composing.active).isFalse()
    }

    /**
     * Gboard's shape while resolving romaji at speed, taken from a device trace: it commits the
     * converted word and parks the leftover consonant as a fresh composition. Withholding that
     * consonant cost the next syllable its onset — 「の」 arrived as 「お」.
     */
    @Test
    fun `a leftover consonant the IME parks is kept for the syllable it completes`() {
        buffer.setComposingText("とくてい", 1)
        val committed = buffer.commitText("特定", 1)
        buffer.setComposingText("ｎ", 1)
        val parked = buffer.endComposing()

        assertThat(textOf(committed) + textOf(parked)).isEqualTo("特定ｎ")
    }

    @Test
    fun `commit followed by the IME ending the composition does not duplicate the text`() {
        buffer.setComposingText("日本語", 1)
        val committed = buffer.commitText("日本語", 1)
        val ended = buffer.endComposing()

        assertThat(textOf(committed) + textOf(ended)).isEqualTo("日本語")
    }

    // --- surrounding text ---

    @Test
    fun `the IME only ever sees the preedit as surrounding text`() {
        buffer.commitText("previously sent to the remote", 1)
        assertThat(buffer.textBeforeCursor(64)).isEmpty()

        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.textBeforeCursor(2)).isEqualTo("んご")
        assertThat(buffer.textAfterCursor(2)).isEmpty()
    }

    @Test
    fun `surrounding text queries clamp to the preedit`() {
        buffer.setComposingText("abc", 0)
        assertThat(buffer.textBeforeCursor(10)).isEmpty()
        assertThat(buffer.textAfterCursor(10)).isEqualTo("abc")
        assertThat(buffer.textBeforeCursor(0)).isEmpty()
    }

    // --- committed line breaks, discovered by tracing Gboard with TYPE_TEXT_FLAG_MULTI_LINE ---

    @Test
    fun `a committed newline becomes an Enter key rather than LF text`() {
        val events = buffer.commitText("\n", 1)
        assertThat(events).containsExactly(TerminalInputEvent.Key(TerminalKey.Enter))
    }

    @Test
    fun `text around a newline is split into segments and an Enter`() {
        val events = buffer.commitText("ls\n-la", 1)
        assertThat(events).containsExactly(
            TerminalInputEvent.Text("ls"),
            TerminalInputEvent.Key(TerminalKey.Enter),
            TerminalInputEvent.Text("-la"),
        ).inOrder()
    }

    @Test
    fun `CRLF counts as one Enter`() {
        assertThat(buffer.commitText("a\r\nb", 1)).containsExactly(
            TerminalInputEvent.Text("a"),
            TerminalInputEvent.Key(TerminalKey.Enter),
            TerminalInputEvent.Text("b"),
        ).inOrder()
    }

    @Test
    fun `a bare CR is also an Enter`() {
        assertThat(buffer.commitText("\r", 1)).containsExactly(TerminalInputEvent.Key(TerminalKey.Enter))
    }

    @Test
    fun `consecutive newlines produce one Enter each`() {
        assertThat(buffer.commitText("\n\n\n", 1)).hasSize(3)
    }

    @Test
    fun `text without a line break is still a single event`() {
        assertThat(buffer.commitText("ls -la", 1)).containsExactly(TerminalInputEvent.Text("ls -la"))
    }

    @Test
    fun `confirming a composition that ends in a newline also yields an Enter`() {
        buffer.setComposingText("abc\n", 1)
        assertThat(buffer.commitComposing()).containsExactly(
            TerminalInputEvent.Text("abc"),
            TerminalInputEvent.Key(TerminalKey.Enter),
        ).inOrder()
    }

    @Test
    fun `setComposingRegion narrows the preedit without emitting`() {
        buffer.setComposingText("にほんご", 1)
        assertThat(buffer.setComposingRegion(0, 2)).isEmpty()
        assertThat(buffer.composing.text).isEqualTo("にほ")
    }
}
