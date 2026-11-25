package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two equal keys on the Key bar screen are still two keys: what is done to the one tapped happens
 * to it alone. A new key starts as an Escape step, so a second `Esc` beside the default one is a
 * single Save away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyBarIdenticalKeysTest {
    @get:Rule
    val rule = createComposeRule()

    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val tab = AuxiliaryKey("Tab", key = TerminalKey.Tab)

    private var preferences by mutableStateOf(TerminalPreferences())

    private fun show(set: KeySet) {
        preferences = TerminalPreferences(keySets = listOf(set))
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(1200.dp)) {
                    KeyBarSettingsScreen(
                        preferences = preferences,
                        onChange = { transform -> preferences = transform(preferences) },
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(label: String) {
        rule.onAllNodes(hasText(label) and hasClickAction() and isEnabled()).onLast().performClick()
        rule.waitForIdle()
    }

    @Test
    fun savingAnEditToOneOfTwoEqualKeysChangesOnlyTheOneTapped() {
        show(KeySet("Default", scrolling = listOf(esc, esc, tab), pinned = emptyList()))

        // The last `Esc` that answers a tap is the second chip of the scrolling row.
        press("Esc")
        press("Alt")
        press("Save")

        val altEsc = AuxiliaryKey("⌥Esc", key = TerminalKey.Escape, modifiers = Modifiers(Modifiers.ALT))
        assertEquals(listOf(esc, altEsc, tab), preferences.keySet.scrolling)
    }

    @Test
    fun aScrollingKeyEqualToAPinnedOneIsTreatedAsTheScrollingKeyItIs() {
        show(KeySet("Default", scrolling = listOf(esc, tab), pinned = listOf(esc)))

        press("Esc")
        rule.onNode(hasText("Scrolling") and isSelected()).assertExists()
        press("Remove key")

        assertEquals(listOf(tab), preferences.keySet.scrolling)
        assertEquals(listOf(esc), preferences.keySet.pinned)
    }
}
