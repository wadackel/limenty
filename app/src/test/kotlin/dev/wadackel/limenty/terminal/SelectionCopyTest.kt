package dev.wadackel.limenty.terminal

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.offlineSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

/**
 * What the view puts on the clipboard, against what the same cells show on screen.
 *
 * Driven through [TerminalView.selectedText] rather than through [RowSelection] directly: the
 * disagreement this covers was in the step between them, where a cell number was handed to a
 * string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
// Sizing the view through layout needs a measured cell, which only real text metrics give.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionCopyTest {
    private fun view(output: String): TerminalView {
        val session = offlineSession(COLUMNS, ROWS)
        session.emulator.write(output.toByteArray(Charsets.UTF_8))
        return TerminalView(ApplicationProvider.getApplicationContext<Context>()).apply {
            this.session = session
        }
    }

    private fun TerminalView.copyOf(startColumn: Int, endColumn: Int): String {
        selection = SelectionRange(0, startColumn, 0, endColumn)
        return selectedText()
    }

    @Test
    fun `selecting the double-width cells of a mixed line copies only those characters`() {
        val view = view("日本語 abc")

        assertThat(view.copyOf(0, 5)).isEqualTo("日本語")
        assertThat(view.copyOf(7, 9)).isEqualTo("abc")
    }

    @Test
    fun `selecting ASCII copies the same cells it did before`() {
        val view = view("alpha beta")

        assertThat(view.copyOf(0, 4)).isEqualTo("alpha")
        assertThat(view.copyOf(6, 9)).isEqualTo("beta")
    }

    @Test
    fun `a selection that stops inside a wide character still copies it whole`() {
        assertThat(view("日本語").copyOf(0, 2)).isEqualTo("日本")
    }

    @Test
    fun `a selection across a wrapped line copies it as one line`() {
        // COLUMNS wide with no newline, so the terminal wraps it and copy has to join it back.
        val view = view("あ".repeat(COLUMNS / 2) + "tail")
        view.selection = SelectionRange(0, 0, 1, 3)

        assertThat(view.selectedText()).isEqualTo("あ".repeat(COLUMNS / 2) + "tail")
    }

    @Test
    fun `a selection over a combining mark copies the mark with its character`() {
        // Decomposed rather than precomposed: this is the shape macOS hands out in a
        // directory listing, and the one that makes a cell hold more code units than columns.
        val view = view("a\u0301bc")

        assertThat(view.copyOf(0, 0)).isEqualTo("a\u0301")
        assertThat(view.copyOf(0, 2)).isEqualTo("a\u0301bc")
    }

    @Test
    fun `an emoji selected by its cells copies once`() {
        assertThat(view("a😀b").copyOf(1, 2)).isEqualTo("😀")
    }

    // --- after a resize: the keyboard opening or closing, a fold, a rotation ---

    /** Sizes the view to the grid through a real layout pass, the one path to a resize. */
    private fun TerminalView.layOut(columns: Int, rows: Int) {
        // Half a cell over, so rounding in the measured cell cannot land one short.
        layout(0, 0, ceil(metrics.cellWidth * (columns + 0.5f)).toInt(), ceil(metrics.cellHeight * (rows + 0.5f)).toInt())
        assertThat(session!!.emulator.columns to session!!.emulator.rows).isEqualTo(columns to rows)
    }

    private fun TerminalView.select(line: Int, startColumn: Int, endColumn: Int): MutableList<SelectionRange?> {
        layOut(COLUMNS, ROWS)
        selection = SelectionRange(line, startColumn, line, endColumn, scrolledAt = session!!.linesScrolled())
        return mutableListOf<SelectionRange?>().also { changes -> onSelectionChanged = { changes += it } }
    }

    /** Ends on the link above an empty prompt row; the numbers above it are in scrollback. */
    private val linkAfterHistory = (1..40).joinToString("") { "$it\r\n" } + "https://example.com\r\n"

    @Test
    fun `a selection still copies its text after a taller screen pulls history back`() {
        val view = view(linkAfterHistory)
        val changes = view.select(ROWS - 2, 0, 18)
        assertThat(view.selectedText()).isEqualTo("https://example.com")

        view.layOut(COLUMNS, ROWS + 10)

        assertThat(view.selectedText()).isEqualTo("https://example.com")
        assertThat(view.selectedLink()).isEqualTo("https://example.com")
        assertThat(changes).isEmpty()
    }

    @Test
    fun `a selection still copies its text after a shorter screen and back`() {
        val view = view(linkAfterHistory)
        val changes = view.select(ROWS - 2, 0, 18)

        view.layOut(COLUMNS, ROWS - 5)
        view.layOut(COLUMNS, ROWS)

        assertThat(view.selectedText()).isEqualTo("https://example.com")
        assertThat(changes).isEmpty()
    }

    @Test
    fun `a selection whose text a narrower screen cut off is dropped`() {
        val view = view("https://example.com/a/long/path\r\n")
        val changes = view.select(0, 0, 30)

        view.layOut(20, ROWS)

        assertThat(view.selection).isNull()
        assertThat(changes).containsExactly(null)
    }

    @Test
    fun `a selection a narrower screen leaves whole is kept`() {
        val view = view("https://example.com/a/long/path\r\n")
        val changes = view.select(0, 0, 18)

        view.layOut(20, ROWS)

        assertThat(view.selectedText()).isEqualTo("https://example.com")
        assertThat(changes).isEmpty()
    }

    @Test
    fun `a kept selection that ran into blanks ends inside a narrower screen`() {
        val view = view("https://example.com\r\n")
        val changes = view.select(0, 0, 30)

        view.layOut(20, ROWS)

        assertThat(view.selectedText()).isEqualTo("https://example.com")
        assertThat(view.selection?.endColumn).isEqualTo(19)
        assertThat(changes).isEmpty()
    }

    @Test
    fun `a selection starting in blanks a narrower screen would pull onto text is dropped`() {
        val view = view("a".repeat(25) + "\r\nbbbbbb\r\n")
        val changes = view.select(0, 0, 0)
        view.selection = SelectionRange(0, 30, 1, 5, scrolledAt = view.session!!.linesScrolled())
        assertThat(view.selectedText()).isEqualTo("\nbbbbbb")

        view.layOut(20, ROWS)

        assertThat(view.selection).isNull()
        assertThat(changes).containsExactly(null)
    }

    private companion object {
        const val COLUMNS = 40
        const val ROWS = 12
    }
}
