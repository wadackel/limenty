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

/**
 * What the view puts on the clipboard, against what the same cells show on screen.
 *
 * Driven through [TerminalView.selectedText] rather than through [RowSelection] directly: the
 * disagreement this covers was in the step between them, where a cell number was handed to a
 * string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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

    private companion object {
        const val COLUMNS = 40
        const val ROWS = 12
    }
}
