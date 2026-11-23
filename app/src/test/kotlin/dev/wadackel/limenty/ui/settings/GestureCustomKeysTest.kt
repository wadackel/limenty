package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
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
 * A gesture can send keys no preset offers, built the way a key bar button's are.
 *
 * Shift+Tab was the key that was missing; adding presets one missing key at a time is what this
 * replaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureCustomKeysTest {
    @get:Rule
    val rule = createComposeRule()

    private var bindings by mutableStateOf(GestureBindings.Initial)

    private fun show(initial: GestureBindings = GestureBindings.Initial) {
        bindings = initial
        rule.setContent {
            LimentyTheme {
                // The settings screen lays the groups out in a scrolling column of its own.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GestureSettings(
                        bindings = bindings,
                        onChange = { bindings = it },
                        doubleTapDragZoom = false,
                        onDoubleTapDragZoomChange = {},
                    )
                }
            }
        }
    }

    /** A row of the open picker rather than the list behind it, which names the same bindings. */
    private fun inPicker(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun keys(vararg steps: KeyStep) = GestureBehaviour.SendKeys(steps.toList())

    @Test
    fun keysBuiltFromThePickerBecomeTheGesturesBinding() {
        show()
        rule.onNodeWithText("Double tap").performClick()
        rule.onNodeWithText("Custom keys…").performClick()
        rule.onNodeWithText("Action").performClick()
        rule.onNodeWithText("Tab").performClick()
        rule.onNodeWithText("Shift").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        assertEquals(keys(KeyStep.Press(key = TerminalKey.Tab, modifiers = Modifiers.Shift)), bindings[Gesture.DoubleTap])
        rule.onNodeWithText("⇧Tab").assertIsDisplayed()
    }

    @Test
    fun keysThatSendNothingCannotBeSaved() {
        show()
        rule.onNodeWithText("Double tap").performClick()
        rule.onNodeWithText("Custom keys…").performClick()

        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun cancellingTheEditorGoesBackToThePickerAndKeepsTheBinding() {
        show()
        rule.onNodeWithText("Double tap").performClick()
        rule.onNodeWithText("Custom keys…").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Custom keys…").assertIsDisplayed()
        assertEquals(GestureBehaviour.None, bindings[Gesture.DoubleTap])
    }

    @Test
    fun keysNoPresetMatchesShowOnTheCustomRow() {
        val prefixThenC = keys(KeyStep.Press(codePoint = 'b'.code, modifiers = Modifiers.Ctrl), KeyStep.Press(codePoint = 'c'.code))
        show(GestureBindings.Initial.with(Gesture.DoubleTap, prefixThenC))
        rule.onNodeWithText("Double tap").performClick()

        rule.onNodeWithText("Custom keys…", substring = true).assertIsSelected()
        inPicker("None").assertIsNotSelected()
    }

    @Test
    fun keysThatMatchAPresetShowThatPresetAsChosen() {
        show(GestureBindings.Initial.with(Gesture.DoubleTap, keys(KeyStep.Press(key = TerminalKey.Tab))))
        rule.onNodeWithText("Double tap").performClick()

        inPicker("Tab").performScrollTo().assertIsSelected()
        inPicker("Esc").performScrollTo().assertIsNotSelected()
        rule.onNodeWithText("Custom keys…", substring = true).assertIsNotSelected()
    }

    @Test
    fun theCustomEditorStartsFromTheKeysAlreadyBound() {
        val prefixThenC = keys(KeyStep.Press(codePoint = 'b'.code, modifiers = Modifiers.Ctrl), KeyStep.Press(codePoint = 'c'.code))
        show(GestureBindings.Initial.with(Gesture.DoubleTap, prefixThenC))
        rule.onNodeWithText("Double tap").performClick()
        rule.onNodeWithText("Custom keys…", substring = true).performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        assertEquals(prefixThenC, bindings[Gesture.DoubleTap])
    }
}
