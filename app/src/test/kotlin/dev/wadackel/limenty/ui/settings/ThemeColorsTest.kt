package dev.wadackel.limenty.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.ui.theme.LimentyColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsTest {
    @Test
    fun `every colour of every preset survives the trip through the sliders unchanged`() {
        TerminalPalette.All.forEach { palette ->
            ThemeColor.entries.forEach { color ->
                val argb = palette.colorOf(color)
                assertEquals("${palette.name} ${color.label}", argb, Oklch.of(argb).toArgb())
            }
        }
    }

    /** `docs/design.md`: the chrome sits behind the terminal whichever preset is showing. */
    @Test
    fun `every preset's background is lighter than the app's own background`() {
        val chrome = LimentyColors.Bg.luminance()
        TerminalPalette.All.forEach { palette ->
            assertTrue(palette.name, Color(palette.background).luminance() > chrome)
        }
    }

    @Test
    fun `raising lightness inside what the screen can show keeps the hue`() {
        val blue = Oklch.of(0xFF81A1C1.toInt())
        val lighter = Oklch.of(blue.copy(lightness = blue.lightness + 0.08).toArgb())

        assertTrue(lighter.lightness > blue.lightness)
        assertEquals(blue.hue, lighter.hue, 3.0)
    }

    @Test
    fun `a colour set on one slot of a palette lands there and nowhere else`() {
        val edited = TerminalPalette.Nord.with(ThemeColor.BrightCyan, 0xFF123456.toInt())

        assertEquals(0xFF123456.toInt(), edited.ansi[14])
        ThemeColor.entries.filter { it != ThemeColor.BrightCyan }.forEach { color ->
            assertEquals(color.label, TerminalPalette.Nord.colorOf(color), edited.colorOf(color))
        }
    }
}
