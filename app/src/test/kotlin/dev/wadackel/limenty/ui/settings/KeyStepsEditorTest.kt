package dev.wadackel.limenty.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Building what a key sends on the editor's own keyboard, counted in taps: the shortcuts this is
 * for are a key with a modifier and a prefix followed by a key, and the list it replaced took about
 * seven operations to build `⌃b c`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyStepsEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private var steps by mutableStateOf(emptyList<KeyStep>())
    private var taps = 0

    private fun show(initial: List<KeyStep> = emptyList()) {
        steps = initial
        rule.setContent { LimentyTheme { KeyStepsEditor(steps = steps, onStepsChange = { steps = it }) } }
    }

    private fun tap(label: String) {
        rule.onNode((hasText(label) or hasContentDescription(label)) and hasClickAction()).performClick()
        rule.waitForIdle()
        taps++
    }

    private fun ctrl(c: Char) = KeyStep.Press(codePoint = c.code, modifiers = Modifiers.Ctrl)

    private fun char(c: Char) = KeyStep.Press(codePoint = c.code)

    @Test
    fun ctrlCIsThreeTaps() {
        show()
        tap("Ctrl")
        tap("abc")
        tap("Ctrl c")

        assertEquals(listOf(ctrl('c')), steps)
        assertEquals(3, taps)
    }

    /** Ctrl is let go after the key it was held for, so the second key goes out bare. */
    @Test
    fun aPrefixAndTheKeyAfterItAreFourTaps() {
        show()
        tap("Ctrl")
        tap("abc")
        tap("Ctrl b")
        tap("c")

        assertEquals(listOf(ctrl('b'), char('c')), steps)
        assertEquals(4, taps)
    }

    @Test
    fun aNameTypedInTheFieldAddsTheKeyItNamesOnEnter() {
        show()
        rule.onNode(hasSetTextAction()).performTextInput("pgd")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()

        assertEquals(listOf(KeyStep.Press(key = TerminalKey.PageDown)), steps)
    }

    @Test
    fun aCharacterTypedInTheFieldTakesTheModifierHeldForIt() {
        show()
        tap("Alt")
        rule.onNode(hasSetTextAction()).performTextInput("x")
        tap("Add Alt x")

        assertEquals(listOf(KeyStep.Press(codePoint = 'x'.code, modifiers = Modifiers(Modifiers.ALT))), steps)
    }

    @Test
    fun aSelectedStepIsReplacedByTheNextKeyPressed() {
        show(listOf(ctrl('b'), char('c')))
        rule.onNode(hasContentDescription("Ctrl b") and isSelectable()).performClick()
        tap("Escape")

        assertEquals(listOf(KeyStep.Press(key = TerminalKey.Escape), char('c')), steps)
    }

    @Test
    fun deleteTakesTheSelectedStepOrElseTheLast() {
        show(listOf(ctrl('b'), char('c'), char('d')))
        tap("Delete last step")
        assertEquals(listOf(ctrl('b'), char('c')), steps)

        rule.onNode(hasContentDescription("Ctrl b") and isSelectable()).performClick()
        tap("Delete selected step")
        assertEquals(listOf(char('c')), steps)
    }

    @Test
    fun textIsAStepOfItsOwnTypedIntoItsField() {
        show(listOf(char('c')))
        tap("Text…")
        rule.onNode(hasSetTextAction() and hasText("Text, sent as typed")).performTextInput("ls")

        assertEquals(listOf(char('c'), KeyStep.Literal("ls")), steps)
    }

    /** `ls` then Enter: the key pressed after the text goes after it rather than over it. */
    @Test
    fun aKeyPressedAfterTypingTextIsAddedAfterIt() {
        show()
        tap("Text…")
        rule.onNode(hasSetTextAction() and hasText("Text, sent as typed")).performTextInput("ls")
        tap("Enter")

        assertEquals(listOf(KeyStep.Literal("ls"), KeyStep.Press(key = TerminalKey.Enter)), steps)
    }
}
