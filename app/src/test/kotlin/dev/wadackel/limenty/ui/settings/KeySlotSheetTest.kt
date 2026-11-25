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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
 * The editing sheet: one sheet for a key or a group, where a group opens into its keys in place,
 * and nothing reaches the bar until Save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeySlotSheetTest {
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

    /** The last match is the one in the sheet, which is drawn after the screen behind it. */
    private fun press(label: String) {
        rule.onAllNodes(hasText(label) and hasClickAction() and isEnabled()).onLast().performClick()
        rule.waitForIdle()
    }

    private fun backTo(group: String) {
        rule.onNode(SemanticsMatcher("goes back to $group") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Back to $group" }).performClick()
        rule.waitForIdle()
    }

    @Test
    fun aNewGroupFilledWithKeysGoesAtTheEndOfTheScrollingRun() {
        show(KeySet("Default", scrolling = listOf(esc), pinned = emptyList()))

        press("+ Group")
        rule.onNode(hasSetTextAction()).performTextInput("nav")
        rule.onAllNodes(hasContentDescription("Add key")).onLast().performClick()
        rule.onNode(hasContentDescription("Escape") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        // On the new key's own page, not the group's.
        rule.onNode(hasText("Label")).assertExists()
        rule.onNode(hasText("Name")).assertDoesNotExist()
        backTo("nav")
        press("Save")

        assertEquals(listOf(esc, KeyGroup("nav", listOf(esc))), preferences.keySet.scrolling)
    }

    /** One sheet throughout: the key opens on a page of the group's sheet, not in a box over it. */
    @Test
    fun aKeyInAGroupIsEditedInsideTheSameSheetAndKeptOnlyOnSave() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("-")
        rule.onAllNodes(isDialog()).assertCountEquals(1)
        press("Remove from group")
        rule.onNode(hasText("Name")).assertExists()
        assertEquals(listOf(sym, esc), preferences.keySet.scrolling)

        press("Save")

        assertEquals(listOf(KeyGroup("sym", listOf(pipe)), esc), preferences.keySet.scrolling)
    }

    @Test
    fun aKeyTakenOutOfTheGroupComesBackWithUndo() {
        show(KeySet("Default", scrolling = listOf(sym), pinned = emptyList()))

        press("sym")
        press("|")
        press("Remove from group")
        press("Undo")
        press("Save")

        assertEquals(listOf(sym), preferences.keySet.scrolling)
    }

    @Test
    fun theWayBackFromAKeyLeadsToItsGroup() {
        show(KeySet("Default", scrolling = listOf(sym), pinned = emptyList()))

        press("sym")
        press("|")
        rule.onNode(hasText("Name")).assertDoesNotExist()
        backTo("sym")

        rule.onNode(hasText("Name")).assertExists()
        rule.onNode(isDialog()).assertExists()
    }

    @Test
    fun cancellingAfterAChangeAsksBeforeAnythingIsThrownAway() {
        show(KeySet("Default", scrolling = listOf(sym), pinned = emptyList()))

        press("sym")
        press("|")
        press("Remove from group")
        press("Cancel")

        rule.onNode(hasText("Discard changes?")).assertExists()
        press("Keep editing")
        rule.onNode(hasText("Discard changes?")).assertDoesNotExist()
        rule.onNode(isDialog()).assertExists()

        press("Cancel")
        press("Discard")

        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf(sym), preferences.keySet.scrolling)
    }

    @Test
    fun cancellingWithoutAChangeClosesWithoutAsking() {
        show(KeySet("Default", scrolling = listOf(sym), pinned = emptyList()))

        press("sym")
        press("Cancel")

        rule.onNode(hasText("Discard changes?")).assertDoesNotExist()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    /** Both parts of one edit are kept: the old Pin right moved the key and dropped the label. */
    @Test
    fun aLabelAndAPositionChangedTogetherAreBothSaved() {
        show(KeySet("Default", scrolling = listOf(esc, sym), pinned = emptyList()))

        press("Esc")
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Bail")
        press("Pinned")
        assertEquals(listOf(esc, sym), preferences.keySet.scrolling)
        press("Save")

        assertEquals(listOf(sym), preferences.keySet.scrolling)
        assertEquals(listOf(AuxiliaryKey("Bail", key = TerminalKey.Escape)), preferences.keySet.pinned)
    }

    @Test
    fun aGroupCanBePinnedAndUnpinned() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        press("Pinned")
        press("Save")
        assertEquals(listOf(sym), preferences.keySet.pinned)
        assertEquals(listOf(esc), preferences.keySet.scrolling)

        press("sym")
        press("Scrolling")
        press("Save")
        assertEquals(emptyList<Any>(), preferences.keySet.pinned)
        assertEquals(listOf(esc, sym), preferences.keySet.scrolling)
    }

    @Test
    fun aKeyOnTheBarGoesIntoAGroupChosenUnderPosition() {
        val nav = KeyGroup("nav", emptyList())
        show(KeySet("Default", scrolling = listOf(esc, sym), pinned = listOf(nav)))

        press("Esc")
        press("sym")
        assertEquals(listOf(esc, sym), preferences.keySet.scrolling)
        press("Save")

        assertEquals(listOf(KeyGroup("sym", listOf(pipe, dash, esc))), preferences.keySet.scrolling)
        assertEquals(listOf(nav), preferences.keySet.pinned)
    }

    @Test
    fun theKeyOffersNoGroupWhenTheSetHasNone() {
        show(KeySet("Default", scrolling = listOf(esc), pinned = emptyList()))

        press("Esc")

        rule.onNode(hasText("Or inside a group")).assertDoesNotExist()
    }

    /** The group was renamed in the same sheet, and the rename is kept with the move. */
    @Test
    fun aKeyMovedOutOfAGroupLandsRightAfterItOnSave() {
        show(KeySet("Default", scrolling = listOf(sym, esc), pinned = emptyList()))

        press("sym")
        rule.onNode(hasSetTextAction()).performTextReplacement("symbols")
        press("|")
        press("Move out of symbols")
        assertEquals(listOf(sym, esc), preferences.keySet.scrolling)
        press("Save")

        assertEquals(listOf(KeyGroup("symbols", listOf(dash)), pipe, esc), preferences.keySet.scrolling)
    }

    /** Nothing would be left to put on the slot's face. */
    @Test
    fun aGroupWithNoNameAndNoKeysCannotBeSaved() {
        show(KeySet("Default", scrolling = listOf(esc), pinned = emptyList()))

        press("+ Group")

        rule.onNode(hasText("Save") and hasClickAction() and isEnabled()).assertDoesNotExist()
    }
}
