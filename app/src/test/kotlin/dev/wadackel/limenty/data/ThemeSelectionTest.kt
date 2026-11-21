package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.terminal.TerminalPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which theme the terminal uses, now that some themes are made on the device. */
class ThemeSelectionTest {
    private val customNord = CustomTheme("custom:a", TerminalPalette.Dracula.copy(name = "Nord"))
    private val mine = CustomTheme("custom:b", TerminalPalette.GruvboxDark.copy(name = "Mine"))

    @Test
    fun `a preset chosen before custom themes existed is still the one in use`() {
        assertEquals(TerminalPalette.Nord, TerminalPreferences(themeId = "Nord").palette)
    }

    @Test
    fun `a custom theme named like a preset is told apart from it`() {
        val preferences = TerminalPreferences(themeId = "Nord", customThemes = listOf(customNord))

        assertEquals(TerminalPalette.Nord, preferences.palette)
        assertEquals(customNord.palette, preferences.copy(themeId = customNord.id).palette)
    }

    /**
     * The id itself, not only the palette: an id left pointing at nothing would still draw the
     * default, but the picker marks the theme in use by id and would then mark no card at all.
     */
    @Test
    fun `removing the theme in use selects the default`() {
        val preferences = TerminalPreferences(themeId = mine.id, customThemes = listOf(customNord, mine))

        val removed = preferences.removeTheme(mine.id)

        assertEquals(listOf(customNord), removed.customThemes)
        assertEquals(TerminalPalette.Default.name, removed.themeId)
    }

    @Test
    fun `removing another theme leaves the one in use alone`() {
        val preferences = TerminalPreferences(themeId = mine.id, customThemes = listOf(customNord, mine))

        assertEquals(mine.palette, preferences.removeTheme(customNord.id).palette)
    }

    @Test
    fun `editing a theme changes that theme and no other`() {
        val preferences = TerminalPreferences(customThemes = listOf(customNord, mine))

        val edited = preferences.updateTheme(mine.id) { it.copy(background = 0xFF000000.toInt()) }

        assertEquals(0xFF000000.toInt(), edited.themeOf(mine.id)!!.background)
        assertEquals(customNord.palette, edited.themeOf(customNord.id))
    }
}
