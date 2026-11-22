package dev.wadackel.limenty.data

import android.app.Application
import dev.wadackel.limenty.ui.settings.refusedImportMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading pasted themes, against Catppuccin Mocha as iTerm2-Color-Schemes publishes it for each
 * format. Under Robolectric, because the Windows Terminal reader is Android's `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThemeImportTest {
    private val ghostty = """
palette = 0=#45475a
palette = 1=#f38ba8
palette = 2=#a6e3a1
palette = 3=#f9e2af
palette = 4=#89b4fa
palette = 5=#f5c2e7
palette = 6=#94e2d5
palette = 7=#bac2de
palette = 8=#585b70
palette = 9=#f7aec2
palette = 10=#c2ecbf
palette = 11=#fcd682
palette = 12=#aeccfc
palette = 13=#f398da
palette = 14=#b1eae1
palette = 15=#a6adc8
background = #1e1e2e
foreground = #cdd6f4
cursor-color = #f5e0dc
cursor-text = #1e1e2e
selection-background = #f5e0dc
selection-foreground = #1e1e2e
    """.trimIndent()

    private val windowsTerminal = """
{
  "name": "Catppuccin Mocha",
  "black": "#45475a",
  "red": "#f38ba8",
  "green": "#a6e3a1",
  "yellow": "#f9e2af",
  "blue": "#89b4fa",
  "purple": "#f5c2e7",
  "cyan": "#94e2d5",
  "white": "#bac2de",
  "brightBlack": "#585b70",
  "brightRed": "#f7aec2",
  "brightGreen": "#c2ecbf",
  "brightYellow": "#fcd682",
  "brightBlue": "#aeccfc",
  "brightPurple": "#f398da",
  "brightCyan": "#b1eae1",
  "brightWhite": "#a6adc8",
  "background": "#1e1e2e",
  "foreground": "#cdd6f4",
  "cursorColor": "#f5e0dc",
  "selectionBackground": "#f5e0dc"
}
    """.trimIndent()

    private fun read(text: String) = ThemeImport.parse(text) as ThemeImport.Read

    @Test
    fun `a ghostty theme reads with all sixteen colours, the cursor and the selection`() {
        val palette = read(ghostty).palette

        assertEquals(0xFF1E1E2E.toInt(), palette.background)
        assertEquals(0xFFCDD6F4.toInt(), palette.foreground)
        assertEquals(0xFFF5E0DC.toInt(), palette.cursor)
        assertEquals(0xFFF5E0DC.toInt(), palette.selection)
        assertEquals(0xFF45475A.toInt(), palette.ansi[0])
        assertEquals(0xFFA6ADC8.toInt(), palette.ansi[15])
        assertEquals(ThemeImport.UNNAMED, palette.name)
    }

    @Test
    fun `a windows terminal scheme reads the same colours and keeps its name`() {
        val fromWindowsTerminal = read(windowsTerminal).palette

        assertEquals("Catppuccin Mocha", fromWindowsTerminal.name)
        assertEquals(read(ghostty).palette.copy(name = "Catppuccin Mocha"), fromWindowsTerminal)
    }

    @Test
    fun `a missing cursor takes the foreground and a missing selection is drawn between the two`() {
        val bare = ghostty.lines().filterNot { it.startsWith("cursor-color") || it.startsWith("selection-background") }.joinToString("\n")

        val palette = read(bare).palette

        assertEquals(palette.foreground, palette.cursor)
        assertEquals(0xFF494C5F.toInt(), palette.selection)
    }

    @Test
    fun `a theme without one of its sixteen colours is refused, naming it`() {
        val withoutThree = ghostty.lines().filterNot { it.startsWith("palette = 3=") }.joinToString("\n")

        assertEquals(ThemeImport.Refused(missing = listOf("palette 3"), unreadable = emptyList()), ThemeImport.parse(withoutThree))
    }

    @Test
    fun `a windows terminal scheme without a background is refused in its own words`() {
        val withoutBackground = windowsTerminal.lines().filterNot { it.contains("\"background\"") }.joinToString("\n")

        assertEquals(ThemeImport.Refused(missing = listOf("background"), unreadable = emptyList()), ThemeImport.parse(withoutBackground))
    }

    @Test
    fun `a windows terminal colour that is there but not six hex digits is refused as unreadable, not missing`() {
        for (value in listOf("#fff", "#C50F1F12", "red")) {
            val scheme = windowsTerminal.replace("\"red\": \"#f38ba8\"", "\"red\": \"$value\"")

            assertEquals(value, ThemeImport.Refused(missing = emptyList(), unreadable = listOf("red")), ThemeImport.parse(scheme))
        }
    }

    @Test
    fun `a windows terminal colour set to null is missing`() {
        val scheme = windowsTerminal.replace("\"red\": \"#f38ba8\"", "\"red\": null")

        assertEquals(ThemeImport.Refused(missing = listOf("red"), unreadable = emptyList()), ThemeImport.parse(scheme))
    }

    @Test
    fun `a ghostty palette entry that does not read is refused as unreadable, alongside what is missing`() {
        val text = ghostty.replace("palette = 5=#f5c2e7", "palette = 5=zzz").lines().filterNot { it.startsWith("background") }.joinToString("\n")

        assertEquals(ThemeImport.Refused(missing = listOf("background"), unreadable = listOf("palette 5")), ThemeImport.parse(text))
    }

    @Test
    fun `an optional colour that does not read falls back as though it were left out`() {
        val text = ghostty.replace("cursor-color = #f5e0dc", "cursor-color = cell-foreground")

        val palette = read(text).palette

        assertEquals(palette.foreground, palette.cursor)
    }

    @Test
    fun `the refusal names what is missing and what does not read`() {
        assertEquals("The theme is missing red.", refusedImportMessage(ThemeImport.Refused(listOf("red"), emptyList())))
        assertEquals("red needs six hex digits, like #7AA2F7.", refusedImportMessage(ThemeImport.Refused(emptyList(), listOf("red"))))
        assertEquals(
            "The theme is missing background. palette 5, palette 9 need six hex digits, like #7AA2F7.",
            refusedImportMessage(ThemeImport.Refused(listOf("background"), listOf("palette 5", "palette 9"))),
        )
    }

    @Test
    fun `text that is neither format is not mistaken for one`() {
        assertEquals(ThemeImport.Unrecognised, ThemeImport.parse("ssh ada@devbox"))
        assertEquals(ThemeImport.Unrecognised, ThemeImport.parse(""))
        assertEquals(ThemeImport.Unrecognised, ThemeImport.parse("{ not json"))
    }
}
