package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.graphics.CellPixelSize
import org.junit.Test

class WebLinksTest {
    private fun terminal(text: String, columns: Int = 40, rows: Int = 5): TerminalEmulator = TerminalEmulator(columns, rows).apply {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /** The link under a cell, read back as the text its span covers. */
    private fun linkAt(terminal: TerminalEmulator, line: Int, column: Int): String? {
        val span = WebLinks.at(terminal.buffer, line, column) ?: return null
        return buildString {
            for (at in span.startLine..span.endLine) {
                val row = terminal.buffer.line(at)!!
                val from = if (at == span.startLine) span.startColumn else 0
                val to = if (at == span.endLine) span.endColumn else row.columns - 1
                append(row.text(from, to + 1))
            }
        }
    }

    @Test
    fun `a press anywhere on a link finds the whole link`() {
        val terminal = terminal("see https://example.com/a ok")
        for (column in listOf(4, 12, 24)) {
            assertThat(linkAt(terminal, 0, column)).isEqualTo("https://example.com/a")
        }
    }

    @Test
    fun `a press beside a link finds nothing`() {
        val terminal = terminal("see https://example.com/a ok")
        assertThat(linkAt(terminal, 0, 1)).isNull()
        assertThat(linkAt(terminal, 0, 3)).isNull()
        assertThat(linkAt(terminal, 0, 26)).isNull()
    }

    @Test
    fun `punctuation that ends the sentence is not part of the link`() {
        assertThat(linkAt(terminal("go to https://example.com/a."), 0, 10)).isEqualTo("https://example.com/a")
        assertThat(linkAt(terminal("https://example.com/a, then"), 0, 3)).isEqualTo("https://example.com/a")
    }

    @Test
    fun `brackets around a link are not part of it`() {
        assertThat(linkAt(terminal("(https://example.com/a)."), 0, 5)).isEqualTo("https://example.com/a")
        assertThat(linkAt(terminal("[https://example.com/a]"), 0, 5)).isEqualTo("https://example.com/a")
    }

    @Test
    fun `a closing bracket the link opened stays in it`() {
        val terminal = terminal("https://en.wikipedia.org/wiki/Foo_(bar)", columns = 60)
        assertThat(linkAt(terminal, 0, 3)).isEqualTo("https://en.wikipedia.org/wiki/Foo_(bar)")
    }

    @Test
    fun `a link the terminal wrapped is found whole from either row`() {
        val terminal = terminal("https://example.com/abcdefghij", columns = 20)
        assertThat(linkAt(terminal, 0, 2)).isEqualTo("https://example.com/abcdefghij")
        assertThat(linkAt(terminal, 1, 3)).isEqualTo("https://example.com/abcdefghij")
    }

    @Test
    fun `a wrapped link whose first row is in scrollback is still found whole`() {
        val terminal = terminal("https://example.com/abcdefghij\r\n1\r\n2", columns = 20, rows = 3)
        assertThat(terminal.buffer.scrollbackSize).isEqualTo(1)
        assertThat(linkAt(terminal, 0, 3)).isEqualTo("https://example.com/abcdefghij")
        assertThat(linkAt(terminal, -1, 3)).isEqualTo("https://example.com/abcdefghij")
    }

    @Test
    fun `a link wrapped at a blank edge cell in scrollback stops at that blank`() {
        val terminal = terminal("see https://a.co/xy next\r\n1\r\n2", columns = 20, rows = 3)
        assertThat(terminal.buffer.line(-1)!!.wrapped).isTrue()
        assertThat(linkAt(terminal, -1, 6)).isEqualTo("https://a.co/xy")
    }

    @Test
    fun `text the screen conceals is not part of a link`() {
        val terminal = terminal("https://a.example\u001b[8m.evil.example\u001b[28m ok")
        assertThat(linkAt(terminal, 0, 3)).isEqualTo("https://a.example")
    }

    @Test
    fun `text an image is drawn over is not part of a link`() {
        val pixels = java.util.Base64.getEncoder().encodeToString(ByteArray(4 * 2 * 4) { 0xFF.toByte() })
        val terminal = TerminalEmulator(40, 5).apply {
            cellPixelSize = CellPixelSize(10, 20)
            write("https://a.example.evil.example")
            // An opaque image over `.evil.example`, placed without moving the cursor.
            write("\u001b_Ga=t,f=32,s=4,v=2,i=1,q=2;$pixels\u001b\\\u001b[1;18H\u001b_Ga=p,i=1,C=1,c=13,r=1,q=2\u001b\\")
        }
        assertThat(terminal.buffer.placements).hasSize(1)
        assertThat(linkAt(terminal, 0, 3)).isEqualTo("https://a.example")
    }

    @Test
    fun `scrollback cells past a narrower screen are not part of a link`() {
        val terminal = terminal("x https://a.example.evil.example\r\n1\r\n2\r\n3", columns = 40, rows = 3)
        terminal.resize(20, 3)
        assertThat(terminal.buffer.line(-1)!!.columns).isGreaterThan(20)
        assertThat(linkAt(terminal, -1, 4)).isEqualTo("https://a.example")
    }

    @Test
    fun `a wrapped scrollback row wider than the screen does not run on into the next`() {
        val terminal = terminal("https://a.example/${"x".repeat(22)}yy\r\n1\r\n2\r\n3", columns = 40, rows = 3)
        terminal.resize(20, 3)
        assertThat(terminal.buffer.line(-2)!!.wrapped).isTrue()
        assertThat(linkAt(terminal, -1, 0)).isNull()
    }

    @Test
    fun `a link is not joined to cells a narrower screen cut away`() {
        val terminal = terminal("https://example.com/abcdefghij", columns = 20)
        terminal.resize(10, 5)
        assertThat(linkAt(terminal, 0, 2)).isEqualTo("https://ex")
    }

    @Test
    fun `text that follows a link without a space is not part of it`() {
        assertThat(linkAt(terminal("https://example.comです"), 0, 3)).isEqualTo("https://example.com")
    }

    @Test
    fun `a scheme in capitals is still a link`() {
        assertThat(linkAt(terminal("HTTPS://EXAMPLE.COM/A"), 0, 3)).isEqualTo("HTTPS://EXAMPLE.COM/A")
    }

    @Test
    fun `words, blanks and other schemes are not links`() {
        assertThat(linkAt(terminal("alpha beta"), 0, 2)).isNull()
        assertThat(linkAt(terminal("alpha beta"), 0, 30)).isNull()
        assertThat(linkAt(terminal("ftp://example.com"), 0, 3)).isNull()
        assertThat(linkAt(terminal("https:// x"), 0, 3)).isNull()
    }

    @Test
    fun `a backslash ends a link`() {
        assertThat(linkAt(terminal("https://evil.example\\@good.example"), 0, 3)).isEqualTo("https://evil.example")
    }

    @Test
    fun `thousands of closing brackets after a link are trimmed`() {
        val terminal = TerminalEmulator(200, 130).apply { write("https://a.co/x${")".repeat(20_000)}".toByteArray()) }
        val span = WebLinks.at(terminal.buffer, 0, 3)!!
        assertThat(WebLinks.exact(terminal.buffer, span)).isEqualTo("https://a.co/x")
    }

    @Test
    fun `a span that is exactly a link reads as its address`() {
        val terminal = terminal("see https://example.com/a?b=c#d. ok")
        val span = WebLinks.at(terminal.buffer, 0, 8)!!
        assertThat(WebLinks.exact(terminal.buffer, span)).isEqualTo("https://example.com/a?b=c#d")
    }

    @Test
    fun `a span that holds more or less than a link reads as nothing`() {
        val terminal = terminal("see https://example.com/a. ok")
        assertThat(WebLinks.exact(terminal.buffer, WebLinks.Span(0, 4, 0, 25))).isNull()
        assertThat(WebLinks.exact(terminal.buffer, WebLinks.Span(0, 0, 0, 24))).isNull()
        assertThat(WebLinks.exact(terminal.buffer, WebLinks.Span(0, 4, 0, 23))).isNull()
        assertThat(WebLinks.exact(terminal.buffer, WebLinks.Span(0, 4, 0, 24))).isEqualTo("https://example.com/a")
    }

    @Test
    fun `a span over concealed text reads as nothing`() {
        val terminal = terminal("https://a.example\u001b[8m.evil\u001b[28m ok")
        assertThat(WebLinks.exact(terminal.buffer, WebLinks.Span(0, 0, 0, 21))).isNull()
    }
}
