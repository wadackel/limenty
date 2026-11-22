package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regressions for defects that only surface in the sequences real applications emit, or in the
 * device transitions a foldable puts the terminal through — the cases a screen-level test suite
 * reaches last.
 */
class TerminalRegressionTest {
    private val responses = mutableListOf<String>()
    private val observer = object : TerminalObserver {
        override fun respond(bytes: ByteArray) {
            responses += String(bytes, Charsets.US_ASCII)
        }
    }
    private val terminal = TerminalEmulator(columns = 20, rows = 6, observer = observer)

    private val e = "\u001B"

    private fun write(text: String) = terminal.write(text)

    private fun row(index: Int) = terminal.buffer.screenRow(index).text().trimEnd()

    // --- private parameter space must not reach the public dispatch table ---

    /**
     * What this pins is that the sequence is not read as an SGR, which is a different claim from
     * what it once was. The private space used to be discarded wholesale and this test passed
     * because nothing happened at all; the request is acted on now, and the pen still has to come
     * through it untouched. See ModifyOtherKeysModeTest for what the request does.
     */
    @Test
    fun `nvim modifyOtherKeys is taken as a mode, not as a colour`() {
        write("$e[>4;2m")
        write("a")

        assertThat(terminal.pen).isEqualTo(Pen.Default)
        assertThat(terminal.buffer.screenRow(0).flagsAt(0)).isEqualTo(0)
        assertThat(terminal.modes.keys.modifyOtherKeys).isEqualTo(2)
    }

    @Test
    fun `a secondary device attributes query is not answered with the primary reply`() {
        write("$e[>c")
        assertThat(responses).isEmpty()
    }

    @Test
    fun `a primary device attributes query is still answered`() {
        write("$e[c")
        assertThat(responses).hasSize(1)
    }

    @Test
    fun `other private markers are ignored rather than misread`() {
        write("$e[=1;2c")
        write("$e[<1m")
        write("x")

        assertThat(terminal.pen).isEqualTo(Pen.Default)
        assertThat(row(0)).isEqualTo("x")
    }

    // --- combining marks on wide characters ---

    @Test
    fun `a voiced mark after a kana attaches to the kana itself`() {
        // macOS hands out decomposed filenames, so an `ls` listing arrives in this form.
        write("\u304B\u3099")

        assertThat(row(0)).isEqualTo("\u304B\u3099")
    }

    @Test
    fun `a mark on a wide character survives a copy of the line`() {
        write("a\u304B\u3099b")
        assertThat(row(0)).isEqualTo("a\u304B\u3099b")
    }

    @Test
    fun `a mark after an ascii character still attaches to it`() {
        write("e\u0301")
        assertThat(row(0)).isEqualTo("e\u0301")
    }

    @Test
    fun `inserting characters keeps marks elsewhere on the line`() {
        write("e\u0301xyz")
        write("$e[5G$e[2@")

        assertThat(terminal.buffer.screenRow(0).text()).startsWith("e\u0301")
    }

    // --- shrink and grow ---

    @Test
    fun `folding with the cursor near the top keeps the lines below it`() {
        repeat(6) { write("line$it\r\n") }
        write("$e[1;1H")

        terminal.resize(20, 3)

        val visible = terminal.screenText() + terminal.buffer.snapshotText(-terminal.buffer.scrollbackSize, 0)
        assertThat(visible).contains("line4")
    }

    @Test
    fun `a fold and unfold round trip preserves the content`() {
        repeat(5) { write("line$it\r\n") }
        val before = allText()

        terminal.resize(20, 3)
        terminal.resize(20, 6)

        assertThat(allText()).isEqualTo(before)
    }

    @Test
    fun `repeated fold cycles do not erode the content`() {
        repeat(5) { write("line$it\r\n") }
        val before = allText()

        repeat(8) {
            terminal.resize(20, 3)
            terminal.resize(20, 6)
        }

        assertThat(allText()).isEqualTo(before)
    }

    @Test
    fun `resizing inside the alternate screen preserves the shell screen underneath`() {
        write("shell prompt here\r\n")
        write("second line")
        write("$e[?1049h")
        write("full screen application")

        terminal.resize(20, 3)
        terminal.resize(20, 6)
        write("$e[?1049l")

        assertThat(allText()).contains("shell prompt here")
        assertThat(allText()).contains("second line")
    }

    @Test
    fun `history restored on unfold is not tinted by the current pen`() {
        repeat(8) { write("line$it\r\n") }
        terminal.resize(20, 3)
        write("$e[41m")

        terminal.resize(20, 6)

        val restored = terminal.buffer.screenRow(0)
        assertThat(restored.backgroundAt(restored.columns - 1)).isEqualTo(TerminalColor.Default)
    }

    // --- origin mode and scroll regions ---

    @Test
    fun `origin mode makes positioning relative to the region`() {
        write("$e[2;5r$e[?6h")
        write("$e[1;1H*")

        assertThat(row(1)).isEqualTo("*")
    }

    @Test
    fun `origin mode confines the cursor to the region`() {
        write("$e[2;5r$e[?6h")
        write("$e[99;1Hx")

        assertThat(row(4)).isEqualTo("x")
        assertThat(row(5)).isEmpty()
    }

    @Test
    fun `leaving origin mode restores absolute positioning`() {
        write("$e[2;5r$e[?6h$e[?6l")
        write("$e[1;1H*")

        assertThat(row(0)).isEqualTo("*")
    }

    // --- deferred wrap ---

    @Test
    fun `erasing at the right margin cancels the pending wrap`() {
        write("x".repeat(20))
        write("$e[K")
        write("y")

        assertThat(terminal.cursor.row).isEqualTo(0)
    }

    @Test
    fun `repositioning cancels the pending wrap`() {
        write("x".repeat(20))
        write("$e[1;1H")
        write("y")

        assertThat(terminal.cursor.row).isEqualTo(0)
        assertThat(row(0)).startsWith("y")
    }

    @Test
    fun `backspace at the right margin steps back within the line`() {
        write("x".repeat(20))
        write("\b")
        write("y")

        assertThat(terminal.cursor.row).isEqualTo(0)
        assertThat(row(0)).endsWith("y")
    }

    // --- wide characters at the margin ---

    @Test
    fun `a wide character pushed to the next line leaves the last cell blank and the row wrapped`() {
        write("x".repeat(19))
        write("\u65E5")

        assertThat(terminal.buffer.screenRow(0).wrapped).isTrue()
        assertThat(terminal.buffer.screenRow(0).codePointAt(19)).isEqualTo(' '.code)
        assertThat(row(1)).isEqualTo("\u65E5")
    }

    // --- soft reset ---

    @Test
    fun `a soft reset restores defaults without clearing the screen`() {
        write("keep me")
        write("$e[?1h$e[31m$e[2;4r")

        write("$e[!p")

        assertThat(row(0)).isEqualTo("keep me")
        assertThat(terminal.pen).isEqualTo(Pen.Default)
        assertThat(terminal.modes.keys.applicationCursorKeys).isFalse()
    }

    // --- scrollback identity ---

    @Test
    fun `the scrolled line counter keeps rising past the scrollback cap`() {
        val small = TerminalEmulator(columns = 10, rows = 3, maxScrollback = 4)
        repeat(20) { small.write("line$it\r\n") }

        assertThat(small.buffer.scrollbackSize).isAtMost(4)
        assertThat(small.buffer.totalLinesScrolled).isAtLeast(17L)
    }

    @Test
    fun `a line keeps its address when a taller screen pulls history back above it`() {
        val small = TerminalEmulator(columns = 10, rows = 3)
        repeat(8) { small.write("line$it\r\n") }
        val address = small.buffer.totalLinesScrolled + 1
        val text = small.buffer.line(1)?.text()?.trimEnd()

        small.resize(10, 6)

        val index = (address - small.buffer.totalLinesScrolled).toInt()
        assertThat(small.buffer.line(index)?.text()?.trimEnd()).isEqualTo(text)
    }

    @Test
    fun `a line keeps its address across a shorter screen and back`() {
        val small = TerminalEmulator(columns = 10, rows = 6)
        repeat(8) { small.write("line$it\r\n") }
        val address = small.buffer.totalLinesScrolled + 4
        val text = small.buffer.line(4)?.text()?.trimEnd()

        small.resize(10, 3)
        small.resize(10, 6)

        val index = (address - small.buffer.totalLinesScrolled).toInt()
        assertThat(small.buffer.line(index)?.text()?.trimEnd()).isEqualTo(text)
    }

    private fun allText(): String {
        val from = -terminal.buffer.scrollbackSize
        return terminal.buffer.snapshotText(from, terminal.rows).trim()
    }
}
