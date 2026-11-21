package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The mode an application turns on to be told about modified keys, and every way it goes off again.
 *
 * The reset paths carry the weight here. The mode changes what the keyboard sends, so a level left
 * standing after the application that asked for it is gone does not spoil that application — it
 * spoils the shell underneath, on every command line, until something happens to clear it. nvim
 * suspending on Ctrl+Z is not an exotic case; it is Tuesday.
 */
class ModifyOtherKeysModeTest {
    private val e = "\u001B"
    private val responses = mutableListOf<String>()
    private val terminal = TerminalEmulator(
        rows = 4,
        columns = 20,
        observer = object : TerminalObserver {
            override fun respond(bytes: ByteArray) {
                responses += String(bytes, Charsets.US_ASCII)
            }
        },
    )

    private fun write(text: String) = terminal.write(text.toByteArray(Charsets.UTF_8))
    private val level get() = terminal.modes.keys.modifyOtherKeys

    @Test
    fun `nobody has asked, so nothing is reported`() {
        assertThat(level).isEqualTo(0)
    }

    @Test
    fun `an application asks for it`() {
        write("$e[>4;2m")
        assertThat(level).isEqualTo(2)
    }

    @Test
    fun `and hands it back on the way out`() {
        write("$e[>4;2m")
        write("$e[>4;0m")
        assertThat(level).isEqualTo(0)
    }

    /** `CSI > 4 m` with no value is the same retreat written shorter, and xterm accepts both. */
    @Test
    fun `an omitted level is a reset`() {
        write("$e[>4;2m")
        write("$e[>4m")
        assertThat(level).isEqualTo(0)
    }

    @Test
    fun `a soft reset clears it`() {
        write("$e[>4;2m")
        write("$e[!p")
        assertThat(level).isEqualTo(0)
    }

    @Test
    fun `a full reset clears it`() {
        write("$e[>4;2m")
        write("${e}c")
        assertThat(level).isEqualTo(0)
    }

    /** nvim asks what the level is before it trusts a set of it. */
    @Test
    fun `the level can be queried`() {
        write("$e[?4m")
        assertThat(responses).containsExactly("$e[>4;0m")

        responses.clear()
        write("$e[>4;2m")
        write("$e[?4m")
        assertThat(responses).containsExactly("$e[>4;2m")
    }

    /** A resource this terminal has no opinion on is left alone rather than answered wrongly. */
    @Test
    fun `another modifier resource is neither set nor answered`() {
        write("$e[>1;2m")
        write("$e[?1m")
        assertThat(level).isEqualTo(0)
        assertThat(responses).isEmpty()
    }

    /**
     * The reason the private parameter space was discarded wholesale before this existed: read as
     * an SGR, `CSI > 4 ; 2 m` sets bold and a colour and every character after it comes out wrong.
     */
    @Test
    fun `the request is not read as a colour`() {
        write("$e[>4;2m")
        write("a")

        assertThat(terminal.pen).isEqualTo(Pen.Default)
        assertThat(terminal.buffer.screenRow(0).flagsAt(0)).isEqualTo(0)
    }
}
