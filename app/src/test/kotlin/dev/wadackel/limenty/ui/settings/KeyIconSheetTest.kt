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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.data.KeyIcon
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
 * Choosing, changing and taking off an icon in the editing sheet, for a key, a group and a key in a
 * group, where the icon is part of the draft that Save keeps and Cancel throws away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyIconSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val steps = listOf(KeyStep.Press(null, 'b'.code, Modifiers.Ctrl))
    private val ctrlB = AuxiliaryKey("⌃b", steps)
    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val sym = KeyGroup("sym", listOf(pipe))

    private var preferences by mutableStateOf(TerminalPreferences())

    private fun show(set: KeySet, height: Dp = 1200.dp) {
        preferences = TerminalPreferences(keySets = listOf(set))
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(height)) {
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

    /** A key drawn as its icon in the list, which has no text to find it by. The list is drawn after the bar. */
    private fun open(description: String) {
        rule.onAllNodes(hasContentDescription(description) and hasClickAction()).onLast().performClick()
        rule.waitForIdle()
    }

    private fun tap(description: String) {
        rule.onNode(hasContentDescription(description) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun backTo(page: String) {
        rule.onNode(SemanticsMatcher("goes back to $page") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Back to $page" }).performClick()
        rule.waitForIdle()
    }

    private fun labelField() = rule.onNode(hasSetTextAction() and hasText("Label") and hasAnyAncestor(isDialog()))

    @Test
    fun aKeyWithoutANameIsCalledByTheIconChosenForItAndKeepsItOnSave() {
        show(KeySet("Default", scrolling = listOf(ctrlB), pinned = emptyList()))

        press("⌃b")
        tap("Icon, none")
        tap("New window")

        // Back on the key's page at once, with the icon's name in the field and where it came from.
        rule.onNode(hasText("Label")).assertExists()
        labelField().assert(hasText("New window"))
        rule.onNode(hasText("From icon")).assertExists()
        rule.onNode(hasContentDescription("Icon, New window")).assertExists()
        assertEquals(listOf(ctrlB), preferences.keySet.scrolling)

        press("Save")

        assertEquals(listOf(AuxiliaryKey("New window", steps, KeyIcon.APP_WINDOW)), preferences.keySet.scrolling)
    }

    @Test
    fun cancelAsksBeforeThrowingAwayAChosenIcon() {
        show(KeySet("Default", scrolling = listOf(ctrlB), pinned = emptyList()))

        press("⌃b")
        tap("Icon, none")
        tap("Star")
        press("Cancel")
        rule.onNode(hasText("Discard changes?")).assertExists()
        press("Discard")

        assertEquals(listOf(ctrlB), preferences.keySet.scrolling)
    }

    @Test
    fun takingTheIconOffReturnsAKeyWithoutANameToTheNameItsKeysGiveIt() {
        show(KeySet("Default", scrolling = listOf(AuxiliaryKey("New window", steps, KeyIcon.APP_WINDOW)), pinned = emptyList()))

        open("New window")
        tap("Icon, New window")
        tap("None")
        labelField().assert(hasText("⌃b"))
        rule.onNode(hasText("From keys")).assertExists()
        press("Save")

        assertEquals(listOf(ctrlB), preferences.keySet.scrolling)
    }

    @Test
    fun takingTheIconOffKeepsANameTheOwnerTyped() {
        show(KeySet("Default", scrolling = listOf(AuxiliaryKey("win", steps, KeyIcon.APP_WINDOW)), pinned = emptyList()))

        open("win")
        tap("Icon, New window")
        tap("None")
        press("Save")

        assertEquals(listOf(AuxiliaryKey("win", steps)), preferences.keySet.scrolling)
    }

    @Test
    fun useIconPutsTheIconsNameBackOverATypedOne() {
        show(KeySet("Default", scrolling = listOf(AuxiliaryKey("New window", steps, KeyIcon.APP_WINDOW)), pinned = emptyList()))

        open("New window")
        labelField().performTextReplacement("win")
        rule.waitForIdle()
        press("Use icon")
        labelField().assert(hasText("New window"))
        press("Save")

        assertEquals(listOf(AuxiliaryKey("New window", steps, KeyIcon.APP_WINDOW)), preferences.keySet.scrolling)
    }

    @Test
    fun theWayBackFromTheIconsLeadsToTheKeyWithNothingChosen() {
        show(KeySet("Default", scrolling = listOf(ctrlB), pinned = emptyList()))

        press("⌃b")
        tap("Icon, none")
        backTo("⌃b")

        rule.onNode(hasText("Label")).assertExists()
        rule.onNode(hasContentDescription("Icon, none")).assertExists()
    }

    @Test
    fun comingBackFromTheIconsFindsTheKeysEditorAsItWasLeft() {
        show(KeySet("Default", scrolling = listOf(ctrlB), pinned = emptyList()))

        press("⌃b")
        press("abc")
        rule.onNode(hasText("q") and hasAnyAncestor(isDialog())).assertExists()
        tap("Icon, none")
        backTo("⌃b")

        rule.onNode(hasText("q") and hasAnyAncestor(isDialog())).assertExists()
    }

    /** The field stays composed under the icons, so left focused it would still take the keyboard's typing. */
    @Test
    fun openingTheIconsTakesTheFocusOffTheLabelField() {
        show(KeySet("Default", scrolling = listOf(ctrlB), pinned = emptyList()))

        press("⌃b")
        labelField().performClick()
        labelField().assertIsFocused()
        tap("Icon, none")
        backTo("⌃b")

        labelField().assertIsNotFocused()
    }

    @Test
    fun theIconsOpenAtTheOneAlreadyChosen() {
        show(KeySet("Default", scrolling = listOf(AuxiliaryKey("Lock", steps, KeyIcon.LOCK)), pinned = emptyList()), height = 600.dp)

        open("Lock")
        tap("Icon, Lock")

        rule.onNode(hasContentDescription("Lock") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNode(hasContentDescription("None") and hasAnyAncestor(isDialog())).assertIsNotDisplayed()
    }

    @Test
    fun aGroupTakesAnIconFromItsNamePageAndIsCalledByIt() {
        show(KeySet("Default", scrolling = listOf(KeyGroup("", listOf(pipe)), esc), pinned = emptyList()))

        press("|")
        tap("Icon, none")
        tap("Bolt")
        press("Save")

        assertEquals(listOf(KeyGroup("", listOf(pipe), KeyIcon.ZAP), esc), preferences.keySet.scrolling)
        assertEquals("Bolt", preferences.keySet.scrolling[0].label)
    }

    @Test
    fun aKeyInAGroupTakesAnIconFromItsOwnPage() {
        show(KeySet("Default", scrolling = listOf(sym), pinned = emptyList()))

        press("sym")
        press("|")
        tap("Icon, none")
        tap("Star")
        backTo("sym")
        press("Save")

        assertEquals(listOf(KeyGroup("sym", listOf(AuxiliaryKey("Star", pipe.steps, KeyIcon.STAR)))), preferences.keySet.scrolling)
    }
}
