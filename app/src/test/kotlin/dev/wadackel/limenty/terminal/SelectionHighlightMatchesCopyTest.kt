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
 * The cells drawn as selected, against the text handed to the clipboard.
 *
 * Two different pieces of code answer "is this part of the selection": the renderer asks
 * [SelectionRange.contains] per cell, and the copy path asks the row for the text between two
 * columns. Nothing until now compared their answers, so the one place they deliberately disagree
 * was as unrecorded as an accidental disagreement would have been.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SelectionHighlightMatchesCopyTest {
    private fun view(output: String): TerminalView {
        val session = offlineSession(COLUMNS, ROWS)
        session.emulator.write(output.toByteArray(Charsets.UTF_8))
        return TerminalView(ApplicationProvider.getApplicationContext<Context>()).apply {
            this.session = session
        }
    }

    private fun TerminalView.copyOf(range: SelectionRange): String {
        selection = range
        return selectedText()
    }

    /** The cells the renderer would draw with the selection background, on the top row. */
    private fun SelectionRange.highlightedColumns() = (0 until COLUMNS).filter { contains(0, it) }

    @Test
    fun `an ascii selection highlights exactly the cells it copies`() {
        val view = view("alpha beta")
        val range = SelectionRange(0, 0, 0, 4)

        val copied = view.copyOf(range)

        assertThat(copied).isEqualTo("alpha")
        assertThat(range.highlightedColumns()).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    /**
     * The one deliberate disagreement. A wide character occupies two cells, and a selection that
     * ends on the first of them copies the whole character — anything else would put half a
     * glyph on the clipboard — while the highlight still stops where the finger did.
     */
    @Test
    fun `a selection ending inside a wide character copies more than it highlights`() {
        val view = view("日本語")
        // 日 covers columns 0 and 1, 本 covers 2 and 3; the selection ends on the first cell of 本.
        val range = SelectionRange(0, 0, 0, 2)

        val copied = view.copyOf(range)

        assertThat(copied).isEqualTo("日本")
        assertThat(range.highlightedColumns()).containsExactly(0, 1, 2).inOrder()
        assertThat(range.contains(0, 3)).isFalse()
    }

    private companion object {
        const val COLUMNS = 20
        const val ROWS = 4
    }
}
