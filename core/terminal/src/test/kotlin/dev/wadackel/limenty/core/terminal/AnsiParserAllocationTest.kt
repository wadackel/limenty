package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import com.sun.management.ThreadMXBean
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * How the parser's memory use grows with the size of a sequence it is going to throw away.
 *
 * The caps are not only about the final size: without them the buffer behind an unterminated
 * string grows with the input, so a remote sending a longer one costs proportionally more. The
 * other parser tests show the caps hold; this one shows that holding them makes the cost stop
 * following the input.
 */
class AnsiParserAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean

    /** ESC, written out so the test source stays free of raw control characters. */
    private val e = "\u001B"

    private fun oscOf(bodyLength: Int) = ("$e]0;" + "x".repeat(bodyLength) + "$e\\").toByteArray(Charsets.UTF_8)

    /** A DCS whose intermediate run is the part that grows; it shares the parser's capped helper. */
    private fun dcsIntermediatesOf(runLength: Int) = ("${e}P" + " ".repeat(runLength) + "q#0$e\\").toByteArray(Charsets.UTF_8)

    /**
     * Bytes allocated by feeding [input], measured on this thread.
     *
     * The parser and the input are built outside the window, so what is left is the parsing.
     */
    private fun allocatedWhileParsing(input: ByteArray): Long {
        val parser = AnsiParser(RecordingAnsiHandler())
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        parser.feed(input)
        return threads.getThreadAllocatedBytes(id) - before
    }

    private fun assertCostDoesNotFollowTheInput(shortRun: Int = SHORT_RUN, of: (Int) -> ByteArray) {
        val short = of(shortRun)
        val long = of(shortRun * GROWTH)
        // The first runs pass through an unwarmed interpreter and allocate far more than the rest.
        repeat(WARMUPS) {
            allocatedWhileParsing(short)
            allocatedWhileParsing(long)
        }

        val forShort = allocatedWhileParsing(short)
        val forLong = allocatedWhileParsing(long)

        // Well inside the eightfold growth of the input, and well outside the noise between two
        // runs of the same size.
        assertThat(forLong).isLessThan(forShort * 2)
    }

    @Test
    fun `an oversized string body costs the same however much more of it arrives`() {
        assertCostDoesNotFollowTheInput(OSC_SHORT_RUN, ::oscOf)
    }

    @Test
    fun `an unbounded run of DCS intermediates costs the same however much more arrives`() {
        assertCostDoesNotFollowTheInput(of = ::dcsIntermediatesOf)
    }

    private companion object {
        /** Beyond the parser's caps, so both inputs are in the region the caps govern. */
        const val SHORT_RUN = 20_000

        /** An OSC is capped far higher, since a clipboard copy travels in one. */
        const val OSC_SHORT_RUN = 512 * 1024 + 20_000
        const val GROWTH = 8
        const val WARMUPS = 3
    }
}
