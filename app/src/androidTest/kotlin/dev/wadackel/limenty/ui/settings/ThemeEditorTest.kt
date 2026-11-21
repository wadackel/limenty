package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.CustomTheme
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Finding a colour, changing it and taking the change back, driven through the editor itself. */
class ThemeEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private val id = "custom:test"
    private val nord = TerminalPalette.Nord

    // State the screen reads back, so a change shows the way it would once the store published it.
    private var preferences by mutableStateOf(
        TerminalPreferences(customThemes = listOf(CustomTheme(id, nord.copy(name = "Nord copy")))),
    )
    private val palette get() = preferences.themeOf(id)!!

    private fun show() {
        val typefaces = TerminalTypefaces(InstrumentationRegistry.getInstrumentation().targetContext)
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(475.dp).height(1400.dp)) {
                    ThemeEditorScreen(
                        preferences = preferences,
                        themeId = id,
                        typefaces = typefaces,
                        onChange = { transform -> preferences = transform(preferences) },
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(label: String) {
        rule.onNode(hasText(label) and hasClickAction() and isEnabled()).performClick()
        rule.waitForIdle()
    }

    private fun typeHex(value: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(value)
        rule.waitForIdle()
    }

    @Test
    fun tappingTextInTheSamplePicksTheColourThatDrawsIt() {
        show()

        press("warning:")

        rule.onNodeWithText("Warnings").assertExists()
        rule.onNodeWithText("ANSI 3").assertExists()
    }

    @Test
    fun typingAHexValueChangesThatColourOfTheTheme() {
        show()
        press("warning:")

        typeHex("#FF8800")

        assertEquals(0xFFFF8800.toInt(), palette.ansi[3])
        assertEquals(nord.background, palette.background)
    }

    @Test
    fun aChangedColourIsMarkedAndCounted() {
        show()
        press("warning:")

        typeHex("ff8800")

        rule.onNodeWithText("1 changed").assertExists()
        rule.onNodeWithContentDescription("Yellow, changed").assertExists()
    }

    @Test
    fun revertPutsOneColourBackToWhatItWasWhenTheEditorOpened() {
        show()
        rule.onNodeWithContentDescription("Background").performClick()
        typeHex("#000000")
        press("warning:")
        typeHex("#FF8800")

        press("Revert")

        assertEquals(nord.ansi[3], palette.ansi[3])
        assertEquals(0xFF000000.toInt(), palette.background)
    }

    @Test
    fun revertAllPutsEveryColourBack() {
        show()
        rule.onNodeWithContentDescription("Background").performClick()
        typeHex("#000000")
        press("warning:")
        typeHex("#FF8800")

        press("Revert all")

        assertEquals(nord.copy(name = "Nord copy"), palette)
    }
}
