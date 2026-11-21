package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What one cell may be made to hold.
 *
 * Zero-width marks do not advance the cursor, so a remote can direct any number of them at the
 * same cell. The ceiling is what keeps that from turning a stream of bytes into a growing array
 * copied once per byte; the cases below are the sequences it must not cut.
 */
class CombiningMarksTest {
    private fun emulator() = TerminalEmulator(80, 24)

    @Test
    fun `a cell keeps a bounded number of combining marks`() {
        val terminal = emulator()

        terminal.write(("a" + "\u0301".repeat(16000)).toByteArray(Charsets.UTF_8))

        assertThat(terminal.buffer.screenRow(0).combiningAt(0)!!.size).isAtMost(TerminalRow.MAX_COMBINING)
    }

    /**
     * The bound has to survive the boundaries the transport draws, since SSH hands output over in
     * chunks and the cell outlives any one of them.
     */
    @Test
    fun `the bound holds when the marks arrive in separate chunks`() {
        val terminal = emulator()

        terminal.write("a".toByteArray(Charsets.UTF_8))
        repeat(200) { terminal.write("\u0301".repeat(50).toByteArray(Charsets.UTF_8)) }

        assertThat(terminal.buffer.screenRow(0).combiningAt(0)!!.size).isAtMost(TerminalRow.MAX_COMBINING)
    }

    @Test
    fun `an accent written as a separate mark still reads back as one character`() {
        val terminal = emulator()

        terminal.write("a\u0301bc".toByteArray(Charsets.UTF_8))

        assertThat(terminal.buffer.screenRow(0).text(0, 3)).isEqualTo("a\u0301bc")
    }

    /**
     * A subdivision flag is the longest sequence real text puts on one cell: the base, five tag
     * characters spelling the region, and the cancel tag. All of them are zero width, so all of
     * them land on the same cell — and dropping the cancel tag alone would leave a flag no font
     * can resolve.
     */
    @Test
    fun `a subdivision flag keeps every one of its tag characters`() {
        // Written by code point rather than pasted: the tags are invisible in an editor, and
        // one silently lost in a copy would make this pass for the wrong reason.
        val scotland = buildString {
            appendCodePoint(0x1F3F4)
            intArrayOf(0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F).forEach(::appendCodePoint)
        }
        val terminal = emulator()

        terminal.write(scotland.toByteArray(Charsets.UTF_8))

        assertThat(terminal.buffer.screenRow(0).combiningAt(0)!!.size).isEqualTo(6)
        assertThat(terminal.buffer.screenRow(0).text(0, 2)).isEqualTo(scotland)
    }
}
