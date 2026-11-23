package dev.wadackel.limenty.ui.terminal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.data.AuxiliaryKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** How the name of the set just switched to fits the width of the bar it floats above. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w475dp-h900dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuxiliaryKeyBarSetNameLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var index by mutableIntStateOf(0)

    /** Switches to the second set, named [name], and stops the clock while its name is showing. */
    private fun switchTo(name: String) {
        compose.setContent {
            Column(modifier = Modifier.width(BAR_WIDTH)) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp))
                AuxiliaryKeyBar(
                    keys = AuxiliaryKey.DefaultScrolling,
                    pinnedKeys = AuxiliaryKey.DefaultPinned,
                    onKeyPressed = {},
                    switcher = KeySetSwitcher(index = index, count = 2, name = name, onSwitch = {}),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        index = 1
        // Without this the switch is composed only at the end of the jump below, so the name's
        // timer starts there and the name is not yet drawn when the test looks for it.
        compose.waitForIdle()
        // Past the slide, well short of the name fading out.
        compose.mainClock.advanceTimeBy(300)
    }

    @Test
    fun `a long set name ends in an ellipsis inside the bar's edges rather than being clipped at them`() {
        val name = "A-very-long-key-set-name-that-has-no-spaces-and-goes-on-for-a-long-while-until-the-screen-is-exceeded-1234567890"
        switchTo(name)

        val layout = layoutOf(name)
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.isLineEllipsized(0)).isTrue()
        // The node is the text alone; the pill drawn behind it reaches its padding further out.
        val bounds = compose.onNodeWithText(name).getBoundsInRoot()
        assertThat(bounds.left - PILL_PADDING).isAtLeast(AuxiliaryKeyBarMetrics.edgePadding)
        assertThat(bounds.right + PILL_PADDING).isAtMost(BAR_WIDTH - AuxiliaryKeyBarMetrics.edgePadding)
    }

    @Test
    fun `a short set name is shown whole in the middle of the bar`() {
        switchTo("Vim")

        assertThat(layoutOf("Vim").isLineEllipsized(0)).isFalse()
        val bounds = compose.onNodeWithText("Vim").getBoundsInRoot()
        assertThat((bounds.left + bounds.right).value / 2).isWithin(1f).of(BAR_WIDTH.value / 2)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }
}

private val BAR_WIDTH = 460.dp
private val PILL_PADDING = 12.dp
