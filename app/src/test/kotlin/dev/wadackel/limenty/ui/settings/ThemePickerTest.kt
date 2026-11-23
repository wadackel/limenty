package dev.wadackel.limenty.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.CustomTheme
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a theme card's menu leads to, driven through the grid itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemePickerTest {
    @get:Rule
    val rule = createComposeRule()

    private val mine = CustomTheme("custom:mine", TerminalPalette.Dracula.copy(name = "Mine"))

    private var preferences by mutableStateOf(TerminalPreferences())
    private var editing: String? = null

    private fun show(initial: TerminalPreferences) {
        preferences = initial
        rule.setContent {
            LimentyTheme {
                Column(modifier = Modifier.width(475.dp).verticalScroll(rememberScrollState())) {
                    ThemePicker(
                        preferences = preferences,
                        mono = FontFamily.Monospace,
                        columns = 2,
                        onChange = { transform -> preferences = transform(preferences) },
                        onEditTheme = { editing = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openMenu(name: String) {
        rule.onNodeWithContentDescription("More for $name").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun press(label: String) {
        rule.onNode(hasText(label) and hasClickAction() and isEnabled()).performClick()
        rule.waitForIdle()
    }

    @Test
    fun aPresetCanOnlyBeDuplicated() {
        show(TerminalPreferences())

        openMenu("Nord")

        rule.onNodeWithText("Duplicate").assertExists()
        rule.onNodeWithText("Edit").assertDoesNotExist()
        rule.onNodeWithText("Rename").assertDoesNotExist()
        rule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun duplicatingAPresetSelectsTheCopyAndOpensItInTheEditor() {
        show(TerminalPreferences())

        openMenu("Nord")
        press("Duplicate")
        rule.onNode(hasSetTextAction() and hasText("Nord copy")).assertExists()
        press("Save")

        val copy = preferences.customThemes.single()
        assertEquals(TerminalPalette.Nord.copy(name = "Nord copy"), copy.palette)
        assertEquals(copy.id, preferences.themeId)
        assertEquals(copy.id, editing)
    }

    @Test
    fun theNewThemeTileStartsFromTheThemeInUse() {
        show(TerminalPreferences(themeId = TerminalPalette.GruvboxDark.name))

        rule.onNodeWithText("New theme").performScrollTo().performClick()
        rule.waitForIdle()
        press("Copy of Gruvbox Dark")
        press("Save")

        assertEquals(TerminalPalette.GruvboxDark.copy(name = "Gruvbox Dark copy"), preferences.customThemes.single().palette)
    }

    @Test
    fun renamingACustomThemeKeepsItsColours() {
        show(TerminalPreferences(customThemes = listOf(mine)))

        openMenu("Mine")
        press("Rename")
        rule.onNode(hasSetTextAction()).performTextReplacement("Night")
        press("Save")

        assertEquals(TerminalPalette.Dracula.copy(name = "Night"), preferences.themeOf(mine.id))
    }

    @Test
    fun deletingTheThemeInUseAsksFirstAndPutsTheTerminalBackOnTheDefault() {
        show(TerminalPreferences(themeId = mine.id, customThemes = listOf(mine)))

        openMenu("Mine")
        press("Delete")
        rule.onNodeWithText("Mine will be removed. The terminal switches to Tokyo Night.").assertExists()
        press("Delete")

        assertNull(preferences.themeOf(mine.id))
        assertEquals(TerminalPalette.Default, preferences.palette)
    }

    private fun copyToClipboard(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("theme", text))
        }
    }

    @Test
    fun aSchemePastedFromTheClipboardIsAddedUnderItsOwnNameAndSelected() {
        copyToClipboard(
            """
            {"name": "Paper", "background": "#F8F8F8", "foreground": "#202020",
             "black": "#000000", "red": "#AA0000", "green": "#00AA00", "yellow": "#AA5500",
             "blue": "#0000AA", "purple": "#AA00AA", "cyan": "#00AAAA", "white": "#AAAAAA",
             "brightBlack": "#555555", "brightRed": "#FF5555", "brightGreen": "#55FF55", "brightYellow": "#FFFF55",
             "brightBlue": "#5555FF", "brightPurple": "#FF55FF", "brightCyan": "#55FFFF", "brightWhite": "#FFFFFF"}
            """.trimIndent(),
        )
        show(TerminalPreferences())

        rule.onNodeWithText("New theme").performScrollTo().performClick()
        rule.waitForIdle()
        press("Paste from clipboard")
        rule.onNode(hasSetTextAction() and hasText("Paper")).assertExists()
        press("Save")

        val imported = preferences.customThemes.single()
        assertEquals("Paper", imported.name)
        assertEquals(0xFFF8F8F8.toInt(), imported.palette.background)
        assertEquals(imported.id, preferences.themeId)
    }

    @Test
    fun clipboardTextThatIsNotAThemeAddsNothingAndSaysWhatToCopy() {
        copyToClipboard("ssh ada@devbox")
        show(TerminalPreferences())

        rule.onNodeWithText("New theme").performScrollTo().performClick()
        rule.waitForIdle()
        press("Paste from clipboard")

        rule.onNodeWithText("Copy a Ghostty theme or a Windows Terminal color scheme, then try again.").assertExists()
        assertEquals(emptyList<CustomTheme>(), preferences.customThemes)
    }
}
