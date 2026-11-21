package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The cases where a cell number and a string index disagree. ASCII is here as the control: it is
 * the only text for which the two happened to line up, which is why the bug survived so long.
 */
class RowSelectionTest {
    private fun row(text: String, columns: Int = 20): TerminalRow {
        val terminal = TerminalEmulator(columns, 4)
        terminal.write(text.toByteArray(Charsets.UTF_8))
        return terminal.buffer.screenRow(0)
    }

    @Test
    fun `a range over ASCII cells copies exactly those cells`() {
        val row = row("alpha beta")
        assertThat(RowSelection.textIn(row, 0, 4)).isEqualTo("alpha")
        assertThat(RowSelection.textIn(row, 6, 9)).isEqualTo("beta")
    }

    @Test
    fun `a range over a double-width run stops where the run does`() {
        val row = row("日本語 abc")
        assertThat(RowSelection.textIn(row, 0, 5)).isEqualTo("日本語")
        assertThat(RowSelection.textIn(row, 7, 9)).isEqualTo("abc")
    }

    @Test
    fun `a range starting on the right half of a wide character keeps the whole character`() {
        assertThat(RowSelection.textIn(row("日本語"), 1, 3)).isEqualTo("日本")
    }

    @Test
    fun `a range ending on the left half of a wide character keeps the whole character`() {
        assertThat(RowSelection.textIn(row("日本語"), 0, 2)).isEqualTo("日本")
    }

    @Test
    fun `an emoji occupies two cells and copies as one character`() {
        val row = row("a😀b")
        assertThat(RowSelection.textIn(row, 1, 2)).isEqualTo("😀")
        assertThat(RowSelection.textIn(row, 0, 3)).isEqualTo("a😀b")
    }

    @Test
    fun `combining marks travel with the cell they are attached to`() {
        // Decomposed rather than precomposed: this is the shape macOS hands out in a directory
        // listing, and the one that makes a cell hold more code units than it has columns.
        val row = row("a\u0301bc")
        assertThat(RowSelection.textIn(row, 0, 0)).isEqualTo("a\u0301")
        assertThat(RowSelection.textIn(row, 1, 2)).isEqualTo("bc")
    }

    @Test
    fun `trimming drops the row's padding`() {
        assertThat(RowSelection.textIn(row("hi"), 0, 19, trimTrailingBlanks = true)).isEqualTo("hi")
    }

    @Test
    fun `trimming keeps a wide character sitting at the end of the text`() {
        assertThat(RowSelection.textIn(row("あ"), 0, 19, trimTrailingBlanks = true)).isEqualTo("あ")
    }

    @Test
    fun `a range beyond the row's last cell is empty`() {
        assertThat(RowSelection.textIn(row("hi", columns = 4), 4, 8)).isEmpty()
    }

    /** Scrollback trims a blank line to nothing at all, and a selection can still run through it. */
    @Test
    fun `a row trimmed to nothing has no text and no word in it`() {
        val row = TerminalRow(0)

        assertThat(RowSelection.textIn(row, 0, 0)).isEmpty()
        assertThat(RowSelection.wordAt(row, 0)).isNull()
    }

    @Test
    fun `a word on a double-width run covers both halves of every character`() {
        val row = row("日本語 abc")
        assertThat(RowSelection.wordAt(row, 3)).isEqualTo(0..5)
        assertThat(RowSelection.wordAt(row, 8)).isEqualTo(7..9)
    }

    /**
     * A full-width space is two cells wide, and its right half stores a space like any other
     * trailing half. Read as content, it became the first character of the word after it.
     */
    @Test
    fun `a full-width space is not part of the word that follows it`() {
        val row = row("\u3042\u3000\u3044")

        assertThat(RowSelection.wordAt(row, 4)).isEqualTo(4..5)
        assertThat(RowSelection.textIn(row, 4, 5)).isEqualTo("\u3044")
    }

    @Test
    fun `there is no word under a blank cell`() {
        assertThat(RowSelection.wordAt(row("日本語 abc"), 6)).isNull()
    }

    @Test
    fun `a word and the text of its range describe the same characters`() {
        val row = row("日本語 abc")
        val word = RowSelection.wordAt(row, 1)!!
        assertThat(RowSelection.textIn(row, word.first, word.last)).isEqualTo("日本語")
    }
}
