package dev.wadackel.limenty.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.enableLimentyEdgeToEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

/**
 * `docs/design.md`: the chrome is dark whatever the Android setting, so the clock and icons in the
 * system bars are drawn light on it in either mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarIconsTest {
    @Test
    @Config(qualifiers = "notnight")
    fun `the system bars draw light icons when Android is in light mode`() {
        val bars = barsAfterEdgeToEdge()

        assertFalse("status bar", bars.isAppearanceLightStatusBars)
        assertFalse("navigation bar", bars.isAppearanceLightNavigationBars)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the system bars draw light icons when Android is in dark mode`() {
        val bars = barsAfterEdgeToEdge()

        assertFalse("status bar", bars.isAppearanceLightStatusBars)
        assertFalse("navigation bar", bars.isAppearanceLightNavigationBars)
    }

    /** Without it three-button navigation sits bare over whatever is drawn under it. */
    @Test
    @Config(qualifiers = "notnight")
    fun `the system keeps its scrim behind three-button navigation`() {
        assertTrue(edgeToEdgeActivity().window.isNavigationBarContrastEnforced)
    }

    private fun barsAfterEdgeToEdge(): WindowInsetsControllerCompat {
        val window = edgeToEdgeActivity().window
        return WindowCompat.getInsetsController(window, window.decorView)
    }

    private fun edgeToEdgeActivity(): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.enableLimentyEdgeToEdge()
        return activity
    }
}
