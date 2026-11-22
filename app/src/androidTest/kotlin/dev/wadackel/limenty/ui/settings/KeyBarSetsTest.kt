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
import androidx.compose.ui.test.isSelected
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

/** Making, naming and removing key sets on the Key bar screen, driven through the screen itself. */
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

    @Test
    fun duplicatingASetAddsItUnderTheNameGivenAndOpensIt() {
        show(listOf(vim))

        press("Duplicate")
        rule.onNode(hasSetTextAction() and hasText("Vim copy")).assertExists()
        nameAs("Agent")

        assertEquals(listOf(vim, vim.copy(name = "Agent")), preferences.keySets)
        rule.onNode(hasText("Agent") and isSelected()).assertExists()
    }

    @Test
    fun renamingASetKeepsItsKeys() {
        show(listOf(vim, agent))

        press("Rename")
        nameAs("Editor")

        assertEquals(listOf(vim.copy(name = "Editor"), agent), preferences.keySets)
    }

    @Test
    fun choosingATabToEditDoesNotChangeTheSetInUse() {
        show(listOf(vim, agent))

        press("Agent")

        rule.onNode(hasText("Agent") and isSelected()).assertExists()
        assertEquals(0, preferences.keySetIndex)
    }

    @Test
    fun editingTheChosenSetLeavesTheOthersAlone() {
        show(listOf(vim, agent))

        press("Agent")
        press("Xxx")
        press("Remove")
        press("Remove")

        assertEquals(vim, preferences.keySets[0])
        assertEquals(listOf("Yyy"), preferences.keySets[1].scrolling.map { it.label })
    }

    @Test
    fun deletingTheSetInUseLeavesAValidSetInUseAndOnScreen() {
        show(listOf(vim, agent, tmux), active = 2)

        press("Delete")
        press("Delete")

        assertEquals(listOf(vim, agent), preferences.keySets)
        assertEquals(1, preferences.keySetIndex)
        rule.onNode(hasText("Agent") and isSelected()).assertExists()
    }

    @Test
    fun withOneSetThereIsNoWayToDeleteIt() {
        show(listOf(vim))

        rule.onNode(hasText("Delete")).assertDoesNotExist()
    }
}
