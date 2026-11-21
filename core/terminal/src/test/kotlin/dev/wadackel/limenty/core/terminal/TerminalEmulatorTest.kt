package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalEmulatorTest {
    private val responses = mutableListOf<String>()
    private var title = ""
    private var bells = 0
    private val copied = mutableListOf<String>()

    private val observer = object : TerminalObserver {
        override fun respond(bytes: ByteArray) {
            responses += String(bytes, Charsets.US_ASCII)
        }

        override fun titleChanged(title: String) {
            this@TerminalEmulatorTest.title = title
        }

        override fun bell() {
            bells++
        }

        override fun clipboardWrite(text: String) {
            copied += text
        }
    }

    private val terminal = TerminalEmulator(columns = 20, rows = 5, observer = observer)

    private val e = "\u001B"

    private fun write(text: String) = terminal.write(text)

    private fun row(index: Int): String = terminal.buffer.screenRow(index).text().trimEnd()

    // --- printing and wrapping ---

    @Test
    fun `text lands on the first row`() {
        write("hello")
        assertThat(row(0)).isEqualTo("hello")
        assertThat(terminal.cursor.column).isEqualTo(5)
    }

    @Test
    fun `carriage return and line feed move as a shell expects`() {
        write("one\r\ntwo")
        assertThat(row(0)).isEqualTo("one")
        assertThat(row(1)).isEqualTo("two")
    }

    @Test
    fun `a line exactly filling the row does not wrap until the next character`() {
        write("x".repeat(20))
        assertThat(terminal.cursor.row).isEqualTo(0)
        write("y")
        assertThat(terminal.cursor.row).isEqualTo(1)
        assertThat(row(1)).isEqualTo("y")
    }

    @Test
    fun `wrapped rows are marked so a copy can rejoin them`() {
        write("x".repeat(25))
        assertThat(terminal.buffer.screenRow(0).wrapped).isTrue()
        assertThat(terminal.buffer.screenRow(1).wrapped).isFalse()
    }

    @Test
    fun `autowrap can be turned off`() {
        write("$e[?7l" + "x".repeat(25))
        assertThat(terminal.cursor.row).isEqualTo(0)
        assertThat(row(0)).hasLength(20)
    }

    @Test
    fun `backspace moves left without erasing`() {
        write("abc\b")
        assertThat(terminal.cursor.column).isEqualTo(2)
        assertThat(row(0)).isEqualTo("abc")
    }

    @Test
    fun `tab advances to the next stop`() {
        write("a\tb")
        assertThat(terminal.cursor.column).isEqualTo(9)
        assertThat(row(0)).startsWith("a")
    }

    @Test
    fun `next line from a full row lands on the row below, not two below`() {
        write("${"x".repeat(20)}${e}Ey")
        assertThat(row(1)).isEqualTo("y")
        assertThat(terminal.buffer.screenRow(0).wrapped).isFalse()
    }

    @Test
    fun `repeat copies a wide character whole`() {
        write("あ$e[2b")
        assertThat(row(0)).isEqualTo("あああ")
    }

    @Test
    fun `repeat after a wrap copies what was printed, not what is left of the cursor`() {
        write("${"a".repeat(19)}b$e[b")
        assertThat(row(1)).isEqualTo("b")
    }

    // --- CJK and combining ---

    @Test
    fun `a wide character occupies two cells`() {
        write("日")
        assertThat(terminal.cursor.column).isEqualTo(2)
        assertThat(terminal.buffer.screenRow(0).flagsAt(0) and CellFlags.WIDE_LEADING).isNotEqualTo(0)
        assertThat(terminal.buffer.screenRow(0).flagsAt(1) and CellFlags.WIDE_TRAILING).isNotEqualTo(0)
    }

    @Test
    fun `wide text reads back without the trailing placeholder`() {
        write("日本語")
        assertThat(row(0)).isEqualTo("日本語")
    }

    @Test
    fun `a wide character never straddles the right edge`() {
        write("x".repeat(19) + "日")
        assertThat(row(0).trimEnd()).isEqualTo("x".repeat(19))
        assertThat(row(1)).isEqualTo("日")
    }

    @Test
    fun `overwriting half of a wide character clears the other half`() {
        write("日")
        write("\r")
        write("a")
        assertThat(row(0)).isEqualTo("a")
    }

    @Test
    fun `combining marks attach to the preceding cell`() {
        write("a\u0301")
        assertThat(row(0)).isEqualTo("a\u0301")
        assertThat(terminal.cursor.column).isEqualTo(1)
    }

    @Test
    fun `mixed ascii and cjk keeps its column arithmetic`() {
        write("ab日cd")
        assertThat(terminal.cursor.column).isEqualTo(6)
        assertThat(row(0)).isEqualTo("ab日cd")
    }

    // --- cursor movement ---

    @Test
    fun `absolute positioning is one based`() {
        write("$e[3;5H*")
        assertThat(terminal.cursor.row).isEqualTo(2)
        assertThat(row(2)).isEqualTo("    *")
    }

    @Test
    fun `positioning without parameters homes the cursor`() {
        write("$e[10;10H$e[H")
        assertThat(terminal.cursor.row).isEqualTo(0)
        assertThat(terminal.cursor.column).isEqualTo(0)
    }

    @Test
    fun `relative movement clamps at the edges`() {
        write("$e[100A$e[100D")
        assertThat(terminal.cursor.row).isEqualTo(0)
        assertThat(terminal.cursor.column).isEqualTo(0)
    }

    @Test
    fun `save and restore round-trip the cursor and the pen`() {
        write("$e[3;4H$e[31m$e[s$e[H$e[0m$e[u")
        assertThat(terminal.cursor.row).isEqualTo(2)
        assertThat(terminal.cursor.column).isEqualTo(3)
        assertThat(terminal.pen.foreground).isEqualTo(TerminalColor.indexed(1))
    }

    // --- erase ---

    @Test
    fun `erase to end of line keeps what precedes the cursor`() {
        write("abcdef$e[4G$e[K")
        assertThat(row(0)).isEqualTo("abc")
    }

    @Test
    fun `erase to start of line keeps what follows the cursor`() {
        write("abcdef$e[4G$e[1K")
        assertThat(row(0)).isEqualTo("    ef")
    }

    @Test
    fun `erase display clears the screen without touching scrollback`() {
        repeat(10) { write("line$it\r\n") }
        val history = terminal.buffer.scrollbackSize
        write("$e[2J")
        assertThat(terminal.screenText().trim()).isEmpty()
        assertThat(terminal.buffer.scrollbackSize).isEqualTo(history)
    }

    @Test
    fun `erase display mode three drops the scrollback`() {
        repeat(10) { write("line$it\r\n") }
        write("$e[3J")
        assertThat(terminal.buffer.scrollbackSize).isEqualTo(0)
    }

    @Test
    fun `erase characters blanks in place`() {
        write("abcdef$e[3G$e[2X")
        assertThat(row(0)).isEqualTo("ab  ef")
    }

    // --- insert and delete ---

    @Test
    fun `delete characters pulls the rest of the line left`() {
        write("abcdef$e[3G$e[2P")
        assertThat(row(0)).isEqualTo("abef")
    }

    @Test
    fun `insert characters pushes the rest of the line right`() {
        write("abcdef$e[3G$e[2@")
        assertThat(row(0)).isEqualTo("ab  cdef")
    }

    @Test
    fun `delete lines pulls the rows below up`() {
        write("a\r\nb\r\nc\r\n")
        write("$e[1;1H$e[1M")
        assertThat(row(0)).isEqualTo("b")
        assertThat(row(1)).isEqualTo("c")
    }

    @Test
    fun `insert lines pushes the rows below down`() {
        write("a\r\nb\r\n")
        write("$e[1;1H$e[1L")
        assertThat(row(0)).isEmpty()
        assertThat(row(1)).isEqualTo("a")
    }

    // --- scrolling and scrollback ---

    @Test
    fun `output beyond the last row scrolls and is retained`() {
        repeat(8) { write("line$it\r\n") }
        assertThat(terminal.buffer.scrollbackSize).isAtLeast(4)
        assertThat(terminal.buffer.line(-1)?.text()?.trimEnd()).isEqualTo("line3")
    }

    @Test
    fun `a scrolling region confines the scroll`() {
        write("$e[2;4r")
        write("$e[2;1H")
        repeat(5) { write("x\r\n") }
        assertThat(row(0)).isEmpty()
        assertThat(row(4)).isEmpty()
    }

    @Test
    fun `a region scroll does not pollute the scrollback`() {
        write("$e[2;4r$e[2;1H")
        val before = terminal.buffer.scrollbackSize
        repeat(10) { write("x\r\n") }
        assertThat(terminal.buffer.scrollbackSize).isEqualTo(before)
    }

    @Test
    fun `a region anchored at the top keeps what scrolls off it, as a progress bar below it needs`() {
        write("$e[1;4r$e[4;1H")
        repeat(10) { write("pkg$it\r\n") }
        write("$e[5;1Hprogress")
        assertThat(terminal.buffer.line(-1)?.text()?.trimEnd()).isEqualTo("pkg6")
        assertThat(terminal.buffer.scrollbackSize).isEqualTo(10)
        assertThat(row(4)).isEqualTo("progress")
    }

    @Test
    fun `reverse index scrolls the region down`() {
        write("a\r\nb")
        write("$e[1;1H${e}M")
        assertThat(row(1)).isEqualTo("a")
    }

    // --- SGR ---

    @Test
    fun `basic colours map to palette indices`() {
        write("$e[31;42ma")
        val row = terminal.buffer.screenRow(0)
        assertThat(row.foregroundAt(0)).isEqualTo(TerminalColor.indexed(1))
        assertThat(row.backgroundAt(0)).isEqualTo(TerminalColor.indexed(2))
    }

    @Test
    fun `bright colours map to the upper palette`() {
        write("$e[91ma")
        assertThat(terminal.buffer.screenRow(0).foregroundAt(0)).isEqualTo(TerminalColor.indexed(9))
    }

    @Test
    fun `256 colour selection is applied`() {
        write("$e[38;5;196ma")
        assertThat(terminal.buffer.screenRow(0).foregroundAt(0)).isEqualTo(TerminalColor.indexed(196))
    }

    @Test
    fun `truecolour with semicolons is applied`() {
        write("$e[38;2;10;20;30ma")
        assertThat(terminal.buffer.screenRow(0).foregroundAt(0)).isEqualTo(TerminalColor.rgb(10, 20, 30))
    }

    @Test
    fun `truecolour with colons is applied`() {
        write("$e[38:2:10:20:30ma")
        assertThat(terminal.buffer.screenRow(0).foregroundAt(0)).isEqualTo(TerminalColor.rgb(10, 20, 30))
    }

    @Test
    fun `truecolour with a colour space id is applied`() {
        write("$e[38:2::10:20:30ma")
        assertThat(terminal.buffer.screenRow(0).foregroundAt(0)).isEqualTo(TerminalColor.rgb(10, 20, 30))
    }

    @Test
    fun `attributes accumulate and reset`() {
        write("$e[1;3;4;9ma")
        val flags = terminal.buffer.screenRow(0).flagsAt(0)
        assertThat(flags and CellFlags.BOLD).isNotEqualTo(0)
        assertThat(flags and CellFlags.ITALIC).isNotEqualTo(0)
        assertThat(flags and CellFlags.UNDERLINE).isNotEqualTo(0)
        assertThat(flags and CellFlags.STRIKETHROUGH).isNotEqualTo(0)

        write("$e[0mb")
        assertThat(terminal.buffer.screenRow(0).flagsAt(1)).isEqualTo(0)
    }

    @Test
    fun `individual attributes can be turned off`() {
        write("$e[1;4m$e[24ma")
        val flags = terminal.buffer.screenRow(0).flagsAt(0)
        assertThat(flags and CellFlags.BOLD).isNotEqualTo(0)
        assertThat(flags and CellFlags.UNDERLINE).isEqualTo(0)
    }

    @Test
    fun `an erased cell keeps the current background so themed clears fill the line`() {
        write("$e[44m$e[2J")
        assertThat(terminal.buffer.screenRow(0).backgroundAt(5)).isEqualTo(TerminalColor.indexed(4))
    }

    // --- modes ---

    @Test
    fun `application cursor keys are reported to the input layer`() {
        write("$e[?1h")
        assertThat(terminal.modes.keys.applicationCursorKeys).isTrue()
        write("$e[?1l")
        assertThat(terminal.modes.keys.applicationCursorKeys).isFalse()
    }

    @Test
    fun `bracketed paste is reported`() {
        write("$e[?2004h")
        assertThat(terminal.modes.bracketedPaste).isTrue()
    }

    @Test
    fun `mouse tracking and its encoding are reported`() {
        write("$e[?1002h$e[?1006h")
        assertThat(terminal.modes.mouse).isEqualTo(MouseTracking.ButtonEvent)
        assertThat(terminal.modes.mouseEncoding).isEqualTo(MouseEncoding.Sgr)
    }

    @Test
    fun `cursor visibility follows DECTCEM`() {
        write("$e[?25l")
        assertThat(terminal.cursor.visible).isFalse()
        write("$e[?25h")
        assertThat(terminal.cursor.visible).isTrue()
    }

    @Test
    fun `insert mode shifts existing text right`() {
        write("abc$e[1G$e[4hX")
        assertThat(row(0)).isEqualTo("Xabc")
    }

    // --- alternate screen ---

    @Test
    fun `entering the alternate screen hides the shell output`() {
        write("shell output\r\n")
        write("$e[?1049h")
        assertThat(terminal.screenText().trim()).isEmpty()
        assertThat(terminal.modes.alternateScreen).isTrue()
    }

    @Test
    fun `leaving the alternate screen restores what was there`() {
        write("shell output")
        write("$e[?1049h")
        write("full screen app")
        write("$e[?1049l")
        assertThat(row(0)).isEqualTo("shell output")
        assertThat(terminal.modes.alternateScreen).isFalse()
    }

    @Test
    fun `the alternate screen does not feed the scrollback`() {
        write("$e[?1049h")
        val before = terminal.buffer.scrollbackSize
        repeat(20) { write("x\r\n") }
        assertThat(terminal.buffer.scrollbackSize).isEqualTo(before)
    }

    @Test
    fun `a cursor saved on the alternate screen does not move the one leaving it restores`() {
        write("$e[3;3H$e[?1049h$e[1;1H${e}7ALT${e}8$e[?1049l")
        assertThat(terminal.cursor.row).isEqualTo(2)
        assertThat(terminal.cursor.column).isEqualTo(2)
    }

    @Test
    fun `leaving the alternate screen after the window grew returns to the prompt`() {
        for (index in 0..8) write("l$index\r\n")
        write("$ ")
        write("$e[?1049h")
        terminal.resize(20, 8)
        write("$e[?1049l")
        write("PROMPT")
        assertThat(row(7)).isEqualTo("$ PROMPT")
        assertThat(row(6)).isEqualTo("l8")
    }

    @Test
    fun `a soft reset on the alternate screen still lets the application leave it`() {
        write("MAIN$e[?1049hALT$e[!p")
        assertThat(terminal.modes.alternateScreen).isTrue()
        write("$e[?1049l")
        assertThat(row(0)).isEqualTo("MAIN")
        assertThat(terminal.modes.alternateScreen).isFalse()
    }

    @Test
    fun `restoring the cursor also restores line drawing`() {
        write("$e(0${e}7$e(Bq${e}8q")
        assertThat(row(0)).isEqualTo("─")
    }

    // --- character sets ---

    @Test
    fun `line drawing designated into G0 draws a box instead of letters`() {
        write("$e(0lqqk$e(B\r\nlqqk")
        assertThat(row(0)).isEqualTo("┌──┐")
        assertThat(row(1)).isEqualTo("lqqk")
    }

    @Test
    fun `shift out prints from G1 until shift in`() {
        write("$e)0a\u000Eqx\u000Fq")
        assertThat(row(0)).isEqualTo("a─│q")
    }

    @Test
    fun `line drawing leaves characters outside its range alone`() {
        write("$e(0AZ09$e(B")
        assertThat(row(0)).isEqualTo("AZ09")
    }

    @Test
    fun `a soft reset returns to plain text`() {
        write("$e(0$e[!pq")
        assertThat(row(0)).isEqualTo("q")
    }

    // --- reports ---

    @Test
    fun `a cursor position report answers with the current position`() {
        write("$e[3;7H$e[6n")
        assertThat(responses).containsExactly("$e[3;7R")
    }

    @Test
    fun `a device attributes request is answered`() {
        write("$e[c")
        assertThat(responses).hasSize(1)
        assertThat(responses.single()).startsWith("$e[?")
    }

    // --- OSC ---

    @Test
    fun `the window title is captured`() {
        write("$e]0;~/dev/limenty\u0007")
        assertThat(title).isEqualTo("~/dev/limenty")
    }

    private fun base64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `an OSC 52 copy arrives decoded`() {
        write("$e]52;c;${base64("yank 日本語")}\u0007")
        assertThat(copied).containsExactly("yank 日本語")
    }

    @Test
    fun `an OSC 52 copy larger than a graphics chunk still arrives`() {
        val text = "x".repeat(7500)
        write("$e]52;c;${base64(text)}$e\\")
        assertThat(copied).containsExactly(text)
    }

    @Test
    fun `an OSC 52 payload that is not base64 is dropped`() {
        write("$e]52;c;not*base64\u0007")
        assertThat(copied).isEmpty()
    }

    @Test
    fun `an OSC 52 read of the clipboard is refused`() {
        write("$e]52;c;?\u0007")
        assertThat(copied).isEmpty()
        assertThat(responses).isEmpty()
    }

    @Test
    fun `a bell is reported once`() {
        write("\u0007")
        assertThat(bells).isEqualTo(1)
    }

    // --- resize ---

    @Test
    fun `shrinking the screen keeps the cursor line`() {
        repeat(4) { write("line$it\r\n") }
        terminal.resize(20, 3)
        assertThat(terminal.rows).isEqualTo(3)
        assertThat(terminal.cursor.row).isAtMost(2)
    }

    @Test
    fun `growing the screen pulls lines back from scrollback`() {
        repeat(10) { write("line$it\r\n") }
        val before = terminal.buffer.scrollbackSize
        terminal.resize(20, 8)
        assertThat(terminal.buffer.scrollbackSize).isLessThan(before)
        assertThat(terminal.screenText()).contains("line")
    }

    @Test
    fun `resizing preserves the text already on screen`() {
        write("hello")
        terminal.resize(40, 10)
        assertThat(row(0)).isEqualTo("hello")
        assertThat(terminal.columns).isEqualTo(40)
    }

    @Test
    fun `a narrower screen truncates rather than losing the session`() {
        write("x".repeat(15))
        terminal.resize(10, 5)
        assertThat(terminal.columns).isEqualTo(10)
        assertThat(terminal.cursor.column).isAtMost(9)
    }

    @Test
    fun `a wrapped row loses its wrap when the width changes`() {
        write("x".repeat(25))
        assertThat(terminal.buffer.screenRow(0).wrapped).isTrue()
        terminal.resize(10, 5)
        assertThat(terminal.buffer.screenRow(0).wrapped).isFalse()
    }

    @Test
    fun `a wrapped row brought back from scrollback at the same width stays wrapped`() {
        val small = TerminalEmulator(columns = 20, rows = 3, observer = observer)
        small.write("${"x".repeat(25)}\r\n1\r\n2\r\n3".toByteArray())
        assertThat(small.buffer.line(-2)!!.wrapped).isTrue()
        small.resize(20, 6)
        assertThat(small.buffer.screenRow(0).wrapped).isTrue()
    }

    @Test
    fun `a row wrapped before a blank edge cell comes back from scrollback still wrapped`() {
        val small = TerminalEmulator(columns = 20, rows = 3, observer = observer)
        // The wide character cannot fit in the last cell, so the row wraps with that cell blank.
        small.write("${"x".repeat(19)}日\r\n1\r\n2\r\n3".toByteArray())
        assertThat(small.buffer.line(-2)!!.wrapped).isTrue()
        small.resize(20, 6)
        assertThat(small.buffer.screenRow(0).wrapped).isTrue()
    }

    // --- reset ---

    @Test
    fun `a full reset returns every mode to its default`() {
        write("$e[?1h$e[?2004h$e[31m$e[3;3H")
        terminal.reset()
        assertThat(terminal.modes).isEqualTo(TerminalModes())
        assertThat(terminal.pen).isEqualTo(Pen.Default)
        assertThat(terminal.cursor.row).isEqualTo(0)
        assertThat(terminal.screenText().trim()).isEmpty()
    }

    // --- robustness ---

    @Test
    fun `an unknown sequence does not disturb the text around it`() {
        write("a$e[9999Yb")
        assertThat(row(0)).startsWith("a")
        assertThat(terminal.screenText()).contains("b")
    }

    @Test
    fun `a truncated sequence at the end of a chunk resumes on the next`() {
        terminal.write("$e[3".toByteArray(Charsets.UTF_8))
        terminal.write(";5H*".toByteArray(Charsets.UTF_8))
        assertThat(terminal.cursor.row).isEqualTo(2)
    }
}
