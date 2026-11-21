package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Several themes, all of which have to render the same 256 colours an application asks
 * for. Only the first sixteen belong to the theme; the cube and the grey ramp are xterm's and
 * must not drift between themes or a colourful program looks different in each one.
 */
class TerminalPaletteTest {
    private val themes = TerminalPalette.All

    @Test
    fun everyThemeIsOffered() {
        assertThat(themes).isNotEmpty()
        assertThat(themes.map { it.name }).containsNoDuplicates()
        assertThat(themes).contains(TerminalPalette.Default)
    }

    /**
     * A palette that exists but is not in the list is a palette nobody can choose, which is the
     * one way of adding a theme that compiles and does nothing.
     */
    @Test
    fun everyThemeDefinedIsAlsoOffered() {
        assertThat(themes).contains(TerminalPalette.Dogrun)
    }

    @Test
    fun everyThemeDefinesTheSixteenItOwns() {
        for (theme in themes) {
            assertThat(theme.ansi).hasLength(16)
        }
    }

    @Test
    fun theCubeAndGreyRampAreTheSameInEveryTheme() {
        for (index in 16 until 256) {
            val resolved = themes.map { it.resolve(index) }.distinct()
            assertThat(resolved).hasSize(1)
        }
    }

    @Test
    fun theCubeCornersAreTheColoursXtermDefines() {
        val theme = TerminalPalette.Default

        assertThat(theme.resolve(16)).isEqualTo(argb(0, 0, 0))
        assertThat(theme.resolve(231)).isEqualTo(argb(255, 255, 255))
        // 196 is the pure red corner: offset 180 -> (5,0,0).
        assertThat(theme.resolve(196)).isEqualTo(argb(255, 0, 0))
        assertThat(theme.resolve(46)).isEqualTo(argb(0, 255, 0))
        assertThat(theme.resolve(21)).isEqualTo(argb(0, 0, 255))
    }

    @Test
    fun theGreyRampClimbsEvenly() {
        val theme = TerminalPalette.Default
        val greys = (232 until 256).map { theme.resolve(it) }

        assertThat(greys).isInOrder()
        assertThat(theme.resolve(232)).isEqualTo(argb(8, 8, 8))
        assertThat(theme.resolve(255)).isEqualTo(argb(238, 238, 238))
    }

    @Test
    fun anIndexOutsideThePaletteFallsBackToTheForeground() {
        for (theme in themes) {
            assertThat(theme.resolve(256)).isEqualTo(theme.foreground)
            assertThat(theme.resolve(-1)).isEqualTo(theme.foreground)
        }
    }

    @Test
    fun textIsLegibleAgainstItsOwnBackgroundInEveryTheme() {
        for (theme in themes) {
            assertThat(theme.foreground).isNotEqualTo(theme.background)
            assertThat(theme.cursor).isNotEqualTo(theme.background)
            assertThat(theme.selection).isNotEqualTo(theme.background)
            // Every colour is fully opaque; a translucent one would blend with whatever is behind.
            for (colour in theme.ansi + theme.foreground + theme.background) {
                assertThat(colour ushr 24).isEqualTo(0xFF)
            }
        }
    }

    @Test
    fun twoThemesAreNotConsideredTheSame() {
        assertThat(TerminalPalette.Default).isNotEqualTo(TerminalPalette.Dracula)
        assertThat(TerminalPalette.Default).isEqualTo(TerminalPalette.Default.copy())
    }

    private fun argb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
