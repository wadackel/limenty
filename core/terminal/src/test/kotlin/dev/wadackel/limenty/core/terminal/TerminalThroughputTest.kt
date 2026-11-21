package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Throughput floors for the parse-and-screen path.
 *
 * These are not micro-benchmarks; they exist so that a change which reintroduces per-character
 * allocation or an accidental O(n^2) in the buffer fails the build instead of being discovered as
 * a stutter on the device. The floors are set well below what the code currently achieves so they
 * do not turn into flakes on a loaded CI machine.
 */
class TerminalThroughputTest {
    private val e = "\u001B"

    private fun measureMegabytesPerSecond(payload: ByteArray, repeats: Int, terminal: TerminalEmulator): Double {
        // One untimed pass so class loading and JIT warm-up are not attributed to the measurement.
        terminal.write(payload)
        val start = System.nanoTime()
        repeat(repeats) { terminal.write(payload) }
        val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0
        val megabytes = payload.size.toDouble() * repeats / (1024 * 1024)
        return megabytes / elapsedSeconds
    }

    @Test
    fun `plain text sustains a usable rate`() {
        val terminal = TerminalEmulator(columns = 120, rows = 40)
        val payload = ("The quick brown fox jumps over the lazy dog. ".repeat(20) + "\r\n")
            .toByteArray(Charsets.UTF_8)

        val rate = measureMegabytesPerSecond(payload, repeats = 2000, terminal = terminal)

        println("plain text: %.1f MB/s".format(rate))
        assertThat(rate).isGreaterThan(3.0)
    }

    @Test
    fun `heavily coloured output sustains a usable rate`() {
        val terminal = TerminalEmulator(columns = 120, rows = 40)
        val payload = buildString {
            repeat(40) { index ->
                append("$e[38;5;${index % 256}m").append("colour ").append("$e[0m")
            }
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val rate = measureMegabytesPerSecond(payload, repeats = 2000, terminal = terminal)

        println("coloured: %.1f MB/s".format(rate))
        assertThat(rate).isGreaterThan(2.0)
    }

    @Test
    fun `japanese output sustains a usable rate`() {
        val terminal = TerminalEmulator(columns = 120, rows = 40)
        val payload = ("日本語のファイル名とターミナル表示 ".repeat(10) + "\r\n").toByteArray(Charsets.UTF_8)

        val rate = measureMegabytesPerSecond(payload, repeats = 2000, terminal = terminal)

        println("japanese: %.1f MB/s".format(rate))
        assertThat(rate).isGreaterThan(2.0)
    }

    @Test
    fun `a chunked image transfer sustains a usable rate`() {
        val terminal = TerminalEmulator(columns = 120, rows = 40)
        // One 64x64 RGBA image as kitty chunks it: 4096 base64 characters per APC. The same id on
        // every repeat replaces the previous image, so the store's quota never fills up.
        val pixels = ByteArray(64 * 64 * 4) { 0x7F }
        val encoded = java.util.Base64.getEncoder().encodeToString(pixels)
        val payload = buildString {
            encoded.chunked(4096).forEachIndexed { index, chunk ->
                val more = if (index * 4096 + chunk.length < encoded.length) 1 else 0
                if (index == 0) append("$e" + "_Ga=t,f=32,s=64,v=64,i=1,q=2,m=$more;") else append("$e" + "_Gm=$more;")
                append(chunk).append("$e\\")
            }
        }.toByteArray(Charsets.US_ASCII)

        val rate = measureMegabytesPerSecond(payload, repeats = 500, terminal = terminal)

        println("graphics: %.1f MB/s".format(rate))
        // The first implementation measured 43 MB/s alone and 26 MB/s with the whole suite
        // running beside it. The floor is well under both, as the text floors are under theirs,
        // so it fails on a regression rather than on a busy machine.
        assertThat(rate).isGreaterThan(10.0)
    }

    @Test
    fun `scrollback memory stays bounded under sustained output`() {
        val terminal = TerminalEmulator(columns = 120, rows = 40, maxScrollback = 5000)
        val payload = ("x".repeat(100) + "\r\n").toByteArray(Charsets.UTF_8)

        val runtime = Runtime.getRuntime()
        repeat(20_000) { terminal.write(payload) }
        System.gc()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        println("heap after 20k lines: $used MB, scrollback=${terminal.buffer.scrollbackSize}")
        assertThat(terminal.buffer.scrollbackSize).isAtMost(5000)
        assertThat(used).isLessThan(256L)
    }

    @Test
    fun `a resize storm during output does not degrade into quadratic work`() {
        val terminal = TerminalEmulator(columns = 120, rows = 40)
        val payload = ("line of output ".repeat(6) + "\r\n").toByteArray(Charsets.UTF_8)

        val start = System.nanoTime()
        repeat(300) { index ->
            repeat(20) { terminal.write(payload) }
            // Alternates the way folding and unfolding does, while output keeps arriving.
            terminal.resize(if (index % 2 == 0) 80 else 120, if (index % 2 == 0) 24 else 40)
        }
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        println("resize storm: $elapsedMillis ms")
        assertThat(elapsedMillis).isLessThan(20_000L)
    }
}
