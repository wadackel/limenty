package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard coming up shrinks the scrolling screen under a field being typed into. What has to
 * stay in view is the field as the user sees it, label, surface and error, not only its line of text.
 * The viewport here stands in for the screen above the keyboard: shrinking it is what the IME does.
 */
@RunWith(AndroidJUnit4::class)
class LimentyFieldInViewTest {
    @get:Rule
    val compose = createComposeRule()

    private var viewport by mutableStateOf(400.dp)

    private fun setForm(error: (String) -> String? = { null }) {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            Column(Modifier.height(viewport).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(ABOVE))
                LimentyField(
                    label = "Port",
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag(FIELD),
                    error = error(value),
                )
                Spacer(Modifier.height(400.dp))
            }
        }
    }

    @Test
    fun theWholeFocusedFieldStaysInViewWhenTheKeyboardShrinksTheScreen() {
        setForm()
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        val field = compose.onNodeWithTag(FIELD).getUnclippedBoundsInRoot()
        assertWithMessage("field before the keyboard").that(field.bottom).isLessThan(viewport)

        // Into the field's bottom padding: its line of text still fits, the surface's lower edge does
        // not.
        viewport = field.bottom - 8.dp
        compose.waitForIdle()

        val after = compose.onNodeWithTag(FIELD).getUnclippedBoundsInRoot()
        assertWithMessage("field bottom against the screen above the keyboard")
            .that(after.bottom)
            .isAtMost(viewport + ROUNDING)
    }

    @Test
    fun anErrorThatAppearsUnderTheFocusedFieldIsScrolledIntoView() {
        setForm { value -> if (value.any { !it.isDigit() }) "Enter a number" else null }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        // The field ends at the bottom of the screen above the keyboard, so the error has no room.
        viewport = compose.onNodeWithTag(FIELD).getUnclippedBoundsInRoot().bottom + 1.dp
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("x")
        compose.waitForIdle()

        val error = compose.onNodeWithText("Enter a number", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertWithMessage("error bottom against the screen above the keyboard")
            .that(error.bottom)
            .isAtMost(viewport + ROUNDING)
    }

    private companion object {
        const val FIELD = "field"
        val ABOVE = 300.dp

        /** Bounds are placed on whole pixels, so a fractional-dp viewport can be missed by a fraction. */
        val ROUNDING = 0.5.dp
    }
}
