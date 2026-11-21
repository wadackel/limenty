package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnsiParserTest {
    private val handler = RecordingAnsiHandler()
    private val parser = AnsiParser(handler)

    /** ESC, written out so the test source stays free of raw control characters. */
    private val e = "\u001B"

    private fun feed(text: String) = parser.feed(text.toByteArray(Charsets.UTF_8))

    private fun feedBytes(vararg bytes: Int) = parser.feed(bytes.map { it.toByte() }.toByteArray())

    // --- printable text ---

    @Test
    fun `plain text is printed`() {
        feed("hello")
        assertThat(handler.printed).isEqualTo("hello")
    }

    @Test
    fun `japanese text survives the parser`() {
        feed("日本語")
        assertThat(handler.printed).isEqualTo("日本語")
    }

    @Test
    fun `multibyte characters split across feeds are printed once`() {
        val bytes = "語".toByteArray(Charsets.UTF_8)
        parser.feed(bytes, 0, 2)
        parser.feed(bytes, 2, 1)
        assertThat(handler.printed).isEqualTo("語")
    }

    @Test
    fun `control characters are executed not printed`() {
        feed("a\r\nb")
        assertThat(handler.events).containsExactly("print:97", "exec:0d", "exec:0a", "print:98").inOrder()
    }

    // --- CSI ---

    @Test
    fun `a bare CSI final is dispatched with no parameters`() {
        feed("$e[H")
        assertThat(handler.events).containsExactly("csi::H")
    }

    @Test
    fun `parameters are collected in order`() {
        feed("$e[12;34H")
        assertThat(handler.events).containsExactly("csi:12,34:H")
    }

    @Test
    fun `an omitted parameter is distinguishable from zero`() {
        feed("$e[;5H")
        assertThat(handler.events).containsExactly("csi:-1,5:H")
    }

    @Test
    fun `private markers are reported separately`() {
        feed("$e[?1049h")
        assertThat(handler.events).containsExactly("csi:?1049:h")
    }

    @Test
    fun `intermediates are reported separately`() {
        feed("$e[4 q")
        assertThat(handler.events).containsExactly("csi:4/ :q")
    }

    @Test
    fun `sub parameters split on colon are preserved`() {
        feed("$e[38:2:255:100:0m")
        assertThat(handler.events).hasSize(1)
        assertThat(handler.events.single()).endsWith(":m")
    }

    @Test
    fun `a huge parameter is clamped rather than overflowing`() {
        feed("$e[99999999999999H")
        assertThat(handler.events).containsExactly("csi:65535:H")
    }

    @Test
    fun `too many parameters do not overflow the buffer`() {
        feed("$e[" + (1..80).joinToString(";") + "m")
        assertThat(handler.events).hasSize(1)
    }

    @Test
    fun `control characters inside a sequence are executed immediately`() {
        feed("$e[1\r2H")
        assertThat(handler.events).containsExactly("exec:0d", "csi:12:H").inOrder()
    }

    // --- ESC ---

    @Test
    fun `a two byte escape is dispatched`() {
        feed("${e}M")
        assertThat(handler.events).containsExactly("esc:M")
    }

    @Test
    fun `charset selection carries its intermediate`() {
        feed("$e(B")
        assertThat(handler.events).containsExactly("esc:(B")
    }

    // --- OSC ---

    @Test
    fun `a BEL terminated OSC is dispatched`() {
        feed("$e]0;my title\u0007")
        assertThat(handler.events).containsExactly("osc:0:my title")
    }

    @Test
    fun `a seven bit ST terminated OSC is dispatched`() {
        feed("$e]0;my title$e\\")
        assertThat(handler.events).containsExactly("osc:0:my title")
    }

    @Test
    fun `an OSC body keeps its UTF-8 content`() {
        feed("$e]0;~/日本語$e\\")
        assertThat(handler.events).containsExactly("osc:0:~/日本語")
    }

    @Test
    fun `an OSC body may itself contain semicolons`() {
        feed("$e]8;;https://example.com/a;b$e\\")
        assertThat(handler.events).containsExactly("osc:8:;https://example.com/a;b")
    }

    @Test
    fun `an escape that is not a terminator abandons the OSC and starts a new sequence`() {
        feed("$e]0;title$e[H")
        assertThat(handler.events).containsExactly("csi::H")
    }

    @Test
    fun `an unbounded OSC is discarded rather than buffered forever`() {
        feed("$e]0;" + "x".repeat(512 * 1024 + 1) + "$e\\")
        assertThat(handler.events).isEmpty()
    }

    // --- resource limits ---

    /** Captures what the recording handler flattens away, since the bound is on exactly that. */
    private class SubParams : AnsiHandler {
        var last: List<IntArray> = emptyList()

        override fun print(codePoint: Int) = Unit

        override fun execute(control: Int) = Unit

        override fun csi(params: IntArray, subParams: List<IntArray>, intermediates: String, private: Char?, final: Char) {
            last = subParams
        }

        override fun esc(intermediates: String, final: Char) = Unit

        override fun osc(identifier: Int, body: String) = Unit

        override fun dcs(params: IntArray, intermediates: String, body: String) = Unit

        override fun apc(body: ByteArray, truncated: Boolean) = Unit
    }

    @Test
    fun `a colour written with sub parameters keeps every one of them`() {
        val captured = SubParams()
        AnsiParser(captured).feed("$e[38:2:255:100:0m".toByteArray(Charsets.UTF_8))
        assertThat(captured.last.single().asList()).containsExactly(38, 2, 255, 100, 0).inOrder()
    }

    @Test
    fun `an unterminated run of sub parameters does not grow without limit`() {
        val captured = SubParams()
        val bounded = AnsiParser(captured)
        bounded.feed(("$e[" + ":".repeat(262144)).toByteArray(Charsets.UTF_8))
        bounded.feed("m".toByteArray(Charsets.UTF_8))
        assertThat(captured.last.single().size).isAtMost(16)
    }

    @Test
    fun `an unterminated run of CSI intermediates does not grow without limit`() {
        feed("$e[" + " ".repeat(262144) + "q")
        assertThat(handler.events).containsExactly("csi:/  :q")
    }

    @Test
    fun `an unterminated run of escape intermediates does not grow without limit`() {
        feed(e + " ".repeat(262144) + "A")
        assertThat(handler.events).containsExactly("esc:  A")
    }

    @Test
    fun `the two intermediates a real escape sequence uses both survive`() {
        feed(e + "\$(A")
        assertThat(handler.events).containsExactly("esc:\$(A")
    }

    @Test
    fun `text after a discarded run of intermediates is still printed`() {
        feed("$e[" + ":".repeat(9000) + "m" + "ok")
        assertThat(handler.printed).isEqualTo("ok")
    }

    // --- recovery ---

    @Test
    fun `CAN aborts a sequence in flight`() {
        feed("$e[12")
        feedBytes(0x18)
        feed("A")
        assertThat(handler.printed).isEqualTo("A")
        assertThat(handler.events.none { it.startsWith("csi") }).isTrue()
    }

    @Test
    fun `a new escape abandons an unfinished one`() {
        feed("$e[12$e[H")
        assertThat(handler.events).containsExactly("csi::H")
    }

    @Test
    fun `state does not leak between sequences`() {
        feed("$e[?25l")
        feed("$e[H")
        assertThat(handler.events).containsExactly("csi:?25:l", "csi::H").inOrder()
    }

    @Test
    fun `a DCS is consumed without disturbing the text around it`() {
        feed("a${e}Pq#0;2;0;0;0$e\\b")
        assertThat(handler.printed).isEqualTo("ab")
    }

    /**
     * The cap discards the body but leaves the parser inside the string, so the byte that is
     * supposed to get it out has to still work after the discarding has started.
     */
    @Test
    fun `CAN gets the parser out of a string that has already hit its cap`() {
        feed("$e]0;" + "x".repeat(20000))
        feedBytes(0x18)
        feed("$e[H" + "ok")

        assertThat(handler.printed).isEqualTo("ok")
        assertThat(handler.events.filterNot { it.startsWith("print") }).containsExactly("csi::H")
    }

    @Test
    fun `an escape gets the parser out of a string that has already hit its cap`() {
        feed("$e]0;" + "x".repeat(20000))
        feed("$e[H" + "ok")

        assertThat(handler.printed).isEqualTo("ok")
        assertThat(handler.events.filterNot { it.startsWith("print") }).containsExactly("csi::H")
    }

    @Test
    fun `a CSI after an APC that hit its cap is still read`() {
        feed(e + "_" + "x".repeat(20000))
        feedBytes(0x18)
        feed("$e[H")

        assertThat(handler.events).containsExactly("csi::H")
    }

    /**
     * The intermediate path into a DCS body, which shares the capped helper with CSI and ESC but
     * had no test reaching it.
     */
    @Test
    fun `a DCS reached through intermediate bytes is consumed like any other`() {
        feed("a${e}P" + " ".repeat(262144) + "q#0;2;0;0;0$e\\b")

        assertThat(handler.printed).isEqualTo("ab")
    }

    @Test
    fun `a DCS with parameters and an intermediate is consumed whole`() {
        feed("a${e}P1;2 q#0$e\\b")

        assertThat(handler.printed).isEqualTo("ab")
    }

    // --- APC ---

    @Test
    fun `an APC string is handed over with its body and the text around it intact`() {
        feed("a${e}_Ga=q,i=31;AAAA$e\\b")
        assertThat(handler.printed).isEqualTo("ab")
        assertThat(handler.events).contains("apc:Ga=q,i=31;AAAA")
    }

    @Test
    fun `BEL terminates an APC as well as ST`() {
        feed("${e}_Gm=0;AA\u0007")
        assertThat(handler.events).containsExactly("apc:Gm=0;AA")
    }

    @Test
    fun `a control byte inside an APC body is dropped like it is in an OSC`() {
        feed("${e}_Gm=1;AA\nBB$e\\")
        assertThat(handler.events).containsExactly("apc:Gm=1;AABB")
    }

    @Test
    fun `an oversized APC is reported as truncated with an empty body`() {
        feed("${e}_G" + "x".repeat(20000) + "$e\\")
        assertThat(handler.events).containsExactly("apc::truncated")
    }

    @Test
    fun `SOS and PM strings are still swallowed whole`() {
        feed("a${e}Xsos$e\\b$e^pm$e\\c")
        assertThat(handler.printed).isEqualTo("abc")
        assertThat(handler.events.none { it.startsWith("apc") }).isTrue()
    }

    // --- real world sequences ---

    @Test
    fun `an alternate screen switch parses as two mode changes`() {
        feed("$e[?1049h$e[?25l")
        assertThat(handler.events).containsExactly("csi:?1049:h", "csi:?25:l").inOrder()
    }

    @Test
    fun `a starship style prompt parses into colours and text`() {
        feed("$e[38;5;39m$e[1m~/dev$e[0m ")
        assertThat(handler.printed).isEqualTo("~/dev ")
        assertThat(handler.events.filter { it.startsWith("csi") })
            .containsExactly("csi:38,5,39:m", "csi:1:m", "csi:0:m").inOrder()
    }

    @Test
    fun `mouse tracking enable and disable round-trip`() {
        feed("$e[?1000h$e[?1006h$e[?1006l$e[?1000l")
        assertThat(handler.events).hasSize(4)
    }
}
