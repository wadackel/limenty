package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.KeyStep
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A key names itself after what it sends, until someone names it something else.
 *
 * Almost no key wants a name of its own, and the editor used to insist on one: picking an action
 * left the label exactly as it was, so every key had to be typed out by hand.
 */
class KeyLabelTest {
    @get:Rule
    val rule = createComposeRule()

    private var saved: AuxiliaryKey? = null

    private fun edit(initial: AuxiliaryKey) {
        rule.setContent {
            LimentyTheme {
                AuxiliaryKeyDialog(initial = initial, onDismiss = {}, onSave = { saved = it })
            }
        }
    }

    /** Opens the action picker and takes the named key from it. */
    private fun chooseAction(from: String, to: String) {
        rule.onNodeWithText(from).performClick()
        rule.onNodeWithText(to).performClick()
        rule.waitForIdle()
    }

    @Test
    fun changingTheActionRenamesAKeyThatHasNoNameOfItsOwn() {
        edit(AuxiliaryKey("Esc", key = TerminalKey.Escape))
        chooseAction(from = "Escape", to = "PageDown")
        rule.onNodeWithText("Save").performClick()
        assertEquals("PgDn", saved?.label)
    }

    @Test
    fun changingTheActionLeavesANameSomeoneChose() {
        // Named action rather than a character: with a character chosen, the word `Character` is
        // both the picker's label and the field's, and there is no one node to press.
        edit(AuxiliaryKey("Clear", key = TerminalKey.Escape))
        chooseAction(from = "Escape", to = "PageDown")
        rule.onNodeWithText("Save").performClick()
        assertEquals("Clear", saved?.label)
    }

    @Test
    fun aNameCanBeHandedBack() {
        edit(AuxiliaryKey("Clear", codePoint = 'l'.code, modifiers = Modifiers.Ctrl))
        rule.onNodeWithText("Use key name").performClick()
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

    /** A key added from the list arrives named, not carrying a placeholder it is stuck with. */
    @Test
    fun anAddedKeyArrivesNamedAfterItsAction() {
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
        rule.onNodeWithText("Add key").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Esc"), preferences.keySet.scrolling.map { it.label })
    }

    /**
     * The SENDS row names each step by the same rule as a key face, so it shows symbols too.
     *
     * Asserted on the node rather than in a screenshot: the editor opens in a window of its own,
     * which the root capture does not reach.
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
        rule.onNodeWithText("⏎").assertIsDisplayed()
        rule.onNodeWithText("␣").assertIsDisplayed()
    }
}
