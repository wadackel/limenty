package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.SlotPosition
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
 * A key names itself after what it sends, until someone names it something else.
 *
 * Almost no key wants a name of its own, and the editor used to insist on one: picking an action
 * left the label exactly as it was, so every key had to be typed out by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyLabelTest {
    @get:Rule
    val rule = createComposeRule()

    private var saved: AuxiliaryKey? = null

    private fun edit(initial: AuxiliaryKey) {
        rule.setContent {
            LimentyTheme {
                KeySlotSheet(
                    set = KeySet("Default", listOf(initial), emptyList()),
                    target = SheetTarget.Existing(SlotPosition(pinned = false, index = 0)),
                    onSave = { saved = it.slot as AuxiliaryKey },
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
    }

    /** Takes the last step off, then presses [key] on the editor's keyboard in its place. */
    private fun replaceLastStepWith(spokenKey: String) {
        rule.onNodeWithContentDescription("Delete last step").performClick()
        rule.onNode(hasContentDescription(spokenKey) and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    @Test
    fun changingTheKeysRenamesAKeyThatHasNoNameOfItsOwn() {
        edit(AuxiliaryKey("Esc", key = TerminalKey.Escape))
        replaceLastStepWith("Page Down")
        rule.onNodeWithText("Save").performClick()
        assertEquals("PgDn", saved?.label)
    }

    @Test
    fun changingTheKeysLeavesANameSomeoneChose() {
        edit(AuxiliaryKey("Clear", key = TerminalKey.Escape))
        replaceLastStepWith("Page Down")
        rule.onNodeWithText("Save").performClick()
        assertEquals("Clear", saved?.label)
    }

    @Test
    fun aNameCanBeHandedBack() {
        edit(AuxiliaryKey("Clear", codePoint = 'l'.code, modifiers = Modifiers.Ctrl))
        rule.onNodeWithText("Use keys").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals("⌃l", saved?.label)
    }

    @Test
    fun aNameTypedInIsKept() {
        // Named `Clear` rather than `Esc`: the step it sends is called `Esc` on its chip, and a
        // label that reads the same gives the field no node of its own to type into.
        edit(AuxiliaryKey("Clear", key = TerminalKey.Escape))
        rule.onNodeWithText("Clear").performTextReplacement("Bail out")
        rule.onNodeWithText("Save").performClick()
        assertEquals("Bail out", saved?.label)
    }

    /** A key added from the list starts empty and is named after the first key pressed for it. */
    @Test
    fun anAddedKeyIsNamedAfterTheKeysPressedForIt() {
        var preferences by mutableStateOf(TerminalPreferences(keySets = listOf(KeySet("Default", emptyList(), emptyList()))))
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(800.dp)) {
                    KeyBarSettingsScreen(
                        preferences = preferences,
                        onChange = { transform -> preferences = transform(preferences) },
                        onBack = {},
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Add key").performClick()
        rule.onNode(hasText("Save") and isEnabled()).assertDoesNotExist()
        rule.onNode(hasContentDescription("Escape") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Esc"), preferences.keySet.scrolling.map { it.label })
    }

    /**
     * The row of steps names each one by the same rule as a key face, so it shows symbols too.
     *
     * Asserted on the node rather than in a screenshot: the editor opens in a window of its own,
     * which the root capture does not reach. The first match is the step; the keyboard below it
     * draws the same faces.
     */
    @Test
    fun eachStepIsNamedTheWayAKeyFaceIs() {
        edit(
            AuxiliaryKey(
                label = "win",
                steps = listOf(
                    KeyStep.Press(codePoint = 's'.code, modifiers = Modifiers.Ctrl),
                    KeyStep.Press(key = TerminalKey.Enter),
                    KeyStep.Press(key = TerminalKey.Space),
                ),
            ),
        )
        rule.onNodeWithText("⌃s").assertIsDisplayed()
        rule.onAllNodesWithText("⏎").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("␣").onFirst().assertIsDisplayed()
    }
}
