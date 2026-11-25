package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyGroup
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

/** Making and changing a key group from the key bar settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyGroupSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val dash = AuxiliaryKey("-", codePoint = '-'.code)
    private val sym = KeyGroup("sym", listOf(pipe, dash))

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

    /** The last match is the one in the dialog opened last, which is the one in front. */
    private fun press(label: String) {
        rule.onAllNodes(hasText(label) and hasClickAction() and isEnabled()).onLast().performClick()
        rule.waitForIdle()
    }

    @Test
    fun aNewGroupFilledWithKeysGoesAtTheEndOfTheScrollingRun() {
        show(KeySet("Default", scrolling = listOf(esc), pinned = emptyList()))

        press("Add group")
        rule.onNode(hasSetTextAction()).performTextInput("nav")
        rule.onNode(hasContentDescription("Add key")).performClick()
        rule.waitForIdle()
        press("Save")
        press("Save")

        assertEquals(listOf(esc, KeyGroup("nav", listOf(esc))), preferences.keySet.scrolling)
    }

    @Test
    fun aGroupsKeysAreEditedInItsOwnEditorAndKeptOnlyOnSave() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("-")
        press("Remove")
        assertEquals(listOf(sym, esc), preferences.keySet.scrolling)

        press("Save")

        assertEquals(listOf(KeyGroup("sym", listOf(pipe)), esc), preferences.keySet.scrolling)
    }

    @Test
    fun cancellingTheGroupEditorKeepsTheGroupAsItWas() {
        show(KeySet("Default", scrolling = listOf(sym), pinned = emptyList()))

        press("sym")
        press("|")
        press("Remove")
        press("Cancel")

        assertEquals(listOf(sym), preferences.keySet.scrolling)
    }

    @Test
    fun removingAGroupAsksFirstAndTakesItsKeysWithIt() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("Remove")
        rule.onNode(hasText("sym and the 2 keys in it will be removed.")).assertExists()
        assertEquals(listOf(sym, esc), preferences.keySet.scrolling)

        press("Remove")

        assertEquals(listOf(esc), preferences.keySet.scrolling)
    }

    @Test
    fun aGroupCanBePinnedAndUnpinned() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("Pin right")
        assertEquals(listOf(sym), preferences.keySet.pinned)
        assertEquals(listOf(esc), preferences.keySet.scrolling)

        press("sym")
        press("Unpin")
        assertEquals(emptyList<Any>(), preferences.keySet.pinned)
        assertEquals(listOf(esc, sym), preferences.keySet.scrolling)
    }

    /** Nothing would be left to put on the slot's face. */
    @Test
    fun aGroupWithNoNameAndNoKeysCannotBeSaved() {
        show(KeySet("Default", scrolling = listOf(esc), pinned = emptyList()))

        press("Add group")

        rule.onNode(hasText("Save") and hasClickAction() and isEnabled()).assertDoesNotExist()
    }
}
