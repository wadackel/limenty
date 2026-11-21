package dev.wadackel.limenty.data

import android.app.Application
import dev.wadackel.limenty.core.terminal.TerminalPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How custom themes are stored: under Robolectric, because the codec is Android's `org.json`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CustomThemeTest {
    private val nordCopy = CustomTheme("custom:a", TerminalPalette.Nord.copy(name = "Nord copy"))
    private val mine = CustomTheme("custom:b", TerminalPalette.Dracula.copy(name = "Mine", cursor = 0xFFFF9E64.toInt()))

    @Test
    fun `themes read back with every colour and name they were written with`() {
        val stored = CustomTheme.encode(listOf(nordCopy, mine))

        assertEquals(listOf(nordCopy, mine), CustomTheme.decode(stored))
    }

    @Test
    fun `a damaged theme is dropped and the others still read back`() {
        val stored = CustomTheme.encode(listOf(nordCopy, mine)).replace("\"#FF9E64\"", "\"orange\"")

        assertEquals(listOf(nordCopy), CustomTheme.decode(stored))
    }

    @Test
    fun `a value that is not a list of themes reads as nothing`() {
        assertNull(CustomTheme.decode("{not json"))
    }

    @Test
    fun `hex is read with or without the hash and in either case`() {
        assertEquals(0xFF7AA2F7.toInt(), parseHex("#7aa2f7"))
        assertEquals(0xFF7AA2F7.toInt(), parseHex("7AA2F7"))
        assertNull(parseHex("#7AA2F"))
        assertNull(parseHex("#7AA2FG"))
    }
}
