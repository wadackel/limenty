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
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
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

/**
 * Removing from the editing sheet: a key goes at once with Undo to bring it back, and a group with
 * keys in it asks first, since its keys go with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyRemovalTest {
    @get:Rule
    val rule = createComposeRule()

    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val tab = AuxiliaryKey("Tab", key = TerminalKey.Tab)
    private val left = AuxiliaryKey("Left", key = TerminalKey.ArrowLeft)
    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val sym = KeyGroup("sym", listOf(pipe))

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
    fun aRemovedKeyIsGoneAtOnceAndUndoPutsItBackWhereItWas() {
        show(KeySet("Default", scrolling = listOf(esc, tab, left), pinned = emptyList()))

        press("Tab")
        press("Remove key")

        assertEquals(listOf(esc, left), preferences.keySet.scrolling)
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNode(hasText("Removed Tab")).assertExists()

        press("Undo")

        assertEquals(listOf(esc, tab, left), preferences.keySet.scrolling)
        rule.onNode(hasText("Removed Tab")).assertDoesNotExist()
    }

    @Test
    fun aPinnedKeyIsRemovedFromThePinnedRunAndPutBackThere() {
        show(KeySet("Default", scrolling = listOf(esc), pinned = listOf(left, tab)))

        press("Left")
        press("Remove key")
        assertEquals(listOf(tab), preferences.keySet.pinned)

        press("Undo")
        assertEquals(listOf(left, tab), preferences.keySet.pinned)
        assertEquals(listOf(esc), preferences.keySet.scrolling)
    }

    @Test
    fun removingAGroupWithKeysAsksFirstAndSaysHowManyGo() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("Remove group")

        rule.onNode(hasText("sym and the key in it will be removed.")).assertExists()
        assertEquals(listOf(sym, esc), preferences.keySet.scrolling)

        press("Remove")

        assertEquals(listOf(esc), preferences.keySet.scrolling)
    }

    @Test
    fun cancellingTheQuestionKeepsTheGroupAndTheSheet() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("Remove group")
        press("Cancel")

        rule.onNode(hasText("sym and the key in it will be removed.")).assertDoesNotExist()
        rule.onNode(isDialog()).assertExists()
        assertEquals(listOf(sym, esc), preferences.keySet.scrolling)
    }

    /** With nothing in it there is nothing to lose but the group, which Undo brings back. */
    @Test
    fun anEmptyGroupIsRemovedLikeAKey() {
        val empty = KeyGroup("nav", emptyList())
        show(KeySet("Default", scrolling = listOf(esc, empty), pinned = emptyList()))

        press("nav")
        press("Remove group")

        assertEquals(listOf(esc), preferences.keySet.scrolling)
        press("Undo")
        assertEquals(listOf(esc, empty), preferences.keySet.scrolling)
    }
}
