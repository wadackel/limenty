package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SGR 5 is an attribute the terminal supports, and a terminal that parses it but never shows it
 * is indistinguishable from one that dropped it.
 */
class BlinkAttributeTest {
    private val terminal = TerminalEmulator(columns = 20, rows = 4)
    private val e = "\u001B"

    @Test
    fun `SGR 5 marks the cell as blinking`() {
        terminal.write("$e[5mblink")

        assertThat(terminal.buffer.screenRow(0).flagsAt(0) and CellFlags.BLINK).isNotEqualTo(0)
    }

    @Test
    fun `SGR 6 is treated as blink too`() {
        terminal.write("$e[6mfast")

        assertThat(terminal.buffer.screenRow(0).flagsAt(0) and CellFlags.BLINK).isNotEqualTo(0)
    }

    @Test
    fun `SGR 25 turns blinking off`() {
        terminal.write("$e[5ma$e[25mb")

        val row = terminal.buffer.screenRow(0)
        assertThat(row.flagsAt(0) and CellFlags.BLINK).isNotEqualTo(0)
        assertThat(row.flagsAt(1) and CellFlags.BLINK).isEqualTo(0)
    }

    @Test
    fun `a reset clears blinking`() {
        terminal.write("$e[5ma$e[0mb")

        assertThat(terminal.buffer.screenRow(0).flagsAt(1) and CellFlags.BLINK).isEqualTo(0)
    }

    @Test
    fun `blinking does not disturb the other attributes`() {
        terminal.write("$e[1;5;4ma")

        val flags = terminal.buffer.screenRow(0).flagsAt(0)
        assertThat(flags and CellFlags.BOLD).isNotEqualTo(0)
        assertThat(flags and CellFlags.BLINK).isNotEqualTo(0)
        assertThat(flags and CellFlags.UNDERLINE).isNotEqualTo(0)
    }

    @Test
    fun `blinking is described for the log`() {
        assertThat(CellFlags.describe(CellFlags.BLINK)).contains("blink")
    }
}
