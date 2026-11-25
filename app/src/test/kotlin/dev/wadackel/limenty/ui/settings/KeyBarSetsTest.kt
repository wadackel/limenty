package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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

/** Making, naming and removing key sets on the Key bar screen, driven through the screen itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyBarSetsTest {
    @get:Rule
    val rule = createComposeRule()

    private val vim = KeySet(
        name = "Vim",
        scrolling = listOf(AuxiliaryKey("Aaa", key = TerminalKey.Escape), AuxiliaryKey("Bbb", key = TerminalKey.Tab)),
        pinned = listOf(AuxiliaryKey("Left", key = TerminalKey.ArrowLeft)),
    )
    private val agent = KeySet(
        name = "Agent",
        scrolling = listOf(AuxiliaryKey("Xxx", key = TerminalKey.Escape), AuxiliaryKey("Yyy", key = TerminalKey.Enter)),
        pinned = listOf(AuxiliaryKey("Up", key = TerminalKey.ArrowUp)),
    )
    private val tmux = KeySet("tmux", listOf(AuxiliaryKey("Zzz", key = TerminalKey.Home)), emptyList())

    // State the screen reads back, so a change shows the way it would once the store published it.
    private var preferences by mutableStateOf(TerminalPreferences())

    private fun show(sets: List<KeySet>, active: Int = 0) {
        preferences = TerminalPreferences(keySets = sets, activeKeySet = active)
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

    private fun nameAs(name: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(name)
        press("Save")
    }

    /** The set's name above the bar, told apart from the same name in its menu by what tapping it does. */
    private val choosesSet = SemanticsMatcher("opens the key set menu") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Choose key set" }

    /** From the set's name, which opens the menu of sets and of what can be done to this one. */
    private fun fromMenu(choice: String) {
        rule.onNode(choosesSet).performClick()
        rule.waitForIdle()
        press(choice)
    }

    private fun editingSet(name: String) = rule.onNode(choosesSet and hasText(name)).assertExists()

    @Test
    fun duplicatingASetAddsItUnderTheNameGivenAndOpensIt() {
        show(listOf(vim))

        fromMenu("Duplicate")
        rule.onNode(hasSetTextAction() and hasText("Vim copy")).assertExists()
        nameAs("Agent")

        assertEquals(listOf(vim, vim.copy(name = "Agent")), preferences.keySets)
        editingSet("Agent")
    }

    @Test
    fun aNewSetIsAddedUnderTheNameGivenAndOpened() {
        show(listOf(vim))

        fromMenu("New set")
        nameAs("Logs")

        assertEquals(listOf(vim, KeySet.starter("Logs")), preferences.keySets)
        assertEquals(0, preferences.keySetIndex)
        editingSet("Logs")
    }

    @Test
    fun renamingASetKeepsItsKeys() {
        show(listOf(vim, agent))

        fromMenu("Rename")
        nameAs("Editor")

        assertEquals(listOf(vim.copy(name = "Editor"), agent), preferences.keySets)
    }

    @Test
    fun choosingASetToEditDoesNotChangeTheSetInUse() {
        show(listOf(vim, agent))

        fromMenu("Agent")

        editingSet("Agent")
        assertEquals(0, preferences.keySetIndex)
        rule.onAllNodes(hasText("Xxx") and hasClickAction()).onLast().assertExists()
    }

    /** "In use" says which set the terminal shows, which is not always the one being edited. */
    @Test
    fun theSetInUseIsMarkedOnlyWhileItIsTheOneBeingEdited() {
        show(listOf(vim, agent), active = 1)

        editingSet("Agent")
        rule.onNode(hasText("In use")).assertExists()

        fromMenu("Vim")
        rule.onNode(hasText("In use")).assertDoesNotExist()
    }

    @Test
    fun editingTheChosenSetLeavesTheOthersAlone() {
        show(listOf(vim, agent))

        fromMenu("Agent")
        press("Xxx")
        press("Remove key")

        assertEquals(vim, preferences.keySets[0])
        assertEquals(listOf("Yyy"), preferences.keySets[1].scrolling.map { it.label })
    }

    /** The key's set has moved up a place, so an Undo still offered would put it in the next one. */
    @Test
    fun deletingASetTakesBackTheUndoOfAKeyRemovedFromAnother() {
        show(listOf(vim, agent, tmux))

        fromMenu("Agent")
        press("Xxx")
        press("Remove key")
        fromMenu("Vim")
        fromMenu("Delete")
        press("Delete")

        rule.onNode(hasText("Undo")).assertDoesNotExist()
        assertEquals(listOf(agent.copy(scrolling = agent.scrolling.drop(1)), tmux), preferences.keySets)
    }

    @Test
    fun deletingTheSetInUseLeavesAValidSetInUseAndOnScreen() {
        show(listOf(vim, agent, tmux), active = 2)

        fromMenu("Delete")
        press("Delete")

        assertEquals(listOf(vim, agent), preferences.keySets)
        assertEquals(1, preferences.keySetIndex)
        editingSet("Agent")
    }

    @Test
    fun withOneSetThereIsNoWayToDeleteIt() {
        show(listOf(vim))

        fromMenu("Rename")
        press("Cancel")
        rule.onNode(choosesSet).performClick()
        rule.waitForIdle()

        rule.onNode(hasText("Delete")).assertDoesNotExist()
    }
}
