package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A slider has to answer a drag, which a screenshot cannot tell you.
 *
 * The first version of this control computed each step from a value captured when its gesture
 * handler was created, so the thumb moved by one event's worth of travel and then stopped no
 * matter how far the finger went — invisible to a still image, and the reason this test exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private fun sliderAt(start: Float): () -> Float {
        var value = start
        rule.setContent {
            var current by remember { mutableFloatStateOf(start) }
            LimentyTheme {
                Box(modifier = Modifier.width(300.dp)) {
                    LimentySlider(
                        label = "Font size",
                        value = current,
                        range = 8f..24f,
                        onValueChange = {
                            current = it
                            value = it
                        },
                    )
                }
            }
        }
        return { value }
    }

    @Test
    fun draggingRightRaisesTheValueByMoreThanOneStep() {
        val value = sliderAt(8f)

        rule.onRoot().performTouchInput { swipeRight() }
        rule.waitForIdle()

        // A swipe across the control is most of its range; anything near the start means the
        // gesture stopped answering.
        assertThat(value()).isGreaterThan(16f)
    }

    @Test
    fun draggingIsBoundedByTheRange() {
        val value = sliderAt(20f)

        rule.onRoot().performTouchInput { swipeRight() }
        rule.waitForIdle()

        assertThat(value()).isAtMost(24f)
    }
}
