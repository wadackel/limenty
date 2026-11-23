package dev.wadackel.limenty.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Switching key sets by swiping the bar up or down, measured by touching it.
 *
 * Every swipe here starts on a key's face on purpose: the bar has almost no surface that is not a
 * key, so a swipe that also pressed the key under the thumb would send a stray Escape on every
 * switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuxiliaryKeyBarSwitchTest {
    @get:Rule
    val rule = createComposeRule()

    private var pressed: String? = null
    private val switched = mutableListOf<Int>()

    private val keys = listOf(
        AuxiliaryKey("Esc", key = TerminalKey.Escape),
        AuxiliaryKey("Tab", key = TerminalKey.Tab),
    ) + AuxiliaryKey.DefaultScrolling

    private fun show(
        count: Int = 2,
        interactive: Boolean = true,
        scrolling: List<AuxiliaryKey> = keys,
        pinned: List<AuxiliaryKey> = AuxiliaryKey.DefaultPinned,
    ) {
        rule.setContent {
            Box(modifier = Modifier.width(460.dp)) {
                AuxiliaryKeyBar(
                    keys = scrolling,
                    pinnedKeys = pinned,
                    onKeyPressed = { pressed = it.label },
                    interactive = interactive,
                    switcher = KeySetSwitcher(index = 0, count = count, name = "Vim", onSwitch = { switched += it }),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun px(dp: Dp) = dp.value * rule.density.density

    /** On `Esc`, which is drawn from 12dp. */
    private val onFirstKey get() = Offset(px(28.dp), px(AuxiliaryKeyBarMetrics.rowHeight / 2))

    private fun drag(from: Offset, by: Offset) {
        rule.onRoot().performTouchInput {
            down(from)
            // In steps, the way a thumb moves, so the gesture detectors see the slop crossed.
            repeat(STEPS) { moveBy(by / STEPS.toFloat()) }
            up()
        }
        rule.waitForIdle()
    }

    /**
     * From near the bottom of the row to near its top, so the finger never leaves the key's touch
     * target — a swipe that left it would cancel the press on its own and prove nothing.
     */
    @Test
    fun aSwipeUpOnAKeySwitchesToTheNextSetWithoutPressingTheKey() {
        show()
        drag(Offset(px(28.dp), px(40.dp)), Offset(0f, -px(30.dp)))

        assertEquals(listOf(1), switched)
        assertNull(pressed)
    }

    @Test
    fun aSwipeDownSwitchesToThePreviousSet() {
        show()
        drag(onFirstKey, Offset(0f, px(30.dp)))

        assertEquals(listOf(-1), switched)
        assertNull(pressed)
    }

    @Test
    fun aTapStillPressesTheKey() {
        show()
        rule.onRoot().performTouchInput { click(onFirstKey) }
        rule.waitForIdle()

        assertEquals("Esc", pressed)
        assertEquals(emptyList<Int>(), switched)
    }

    /** Short of the distance a switch needs: a thumb that wobbles on its way to a key. */
    @Test
    fun aShortVerticalWobbleDoesNotSwitch() {
        show()
        drag(onFirstKey, Offset(0f, -px(14.dp)))

        assertEquals(emptyList<Int>(), switched)
    }

    /** Far enough down to switch if it were measured alone; the run has already taken the drag. */
    @Test
    fun aSidewaysDragDoesNotSwitch() {
        show()
        drag(Offset(px(200.dp), px(40.dp)), Offset(-px(150.dp), -px(30.dp)))

        assertEquals(emptyList<Int>(), switched)
        assertNull(pressed)
    }

    @Test
    fun withASingleSetASwipeDoesNothing() {
        show(count = 1)
        drag(onFirstKey, Offset(0f, -px(40.dp)))

        assertEquals(emptyList<Int>(), switched)
    }

    /**
     * A set can be emptied in the settings, and the bar is the only way to another set from the
     * terminal, so the empty one still has to be a bar's height to swipe on.
     */
    @Test
    fun aSetWithNoKeysStillLeavesABarToSwipeOn() {
        show(scrolling = emptyList(), pinned = emptyList())
        drag(Offset(px(28.dp), px(40.dp)), Offset(0f, -px(30.dp)))

        assertEquals(listOf(1), switched)
    }

    /** The settings screen shows the bar as an example of itself, where a swipe has nothing to switch. */
    @Test
    fun aBarShownAsAnExampleDoesNotSwitch() {
        show(interactive = false)
        drag(Offset(px(28.dp), px(40.dp)), Offset(0f, -px(30.dp)))

        assertEquals(emptyList<Int>(), switched)
        assertNull(pressed)
    }

    /**
     * A swipe is no use to a screen reader, so the same two moves are offered as actions — on a key,
     * because a key is what a screen reader lands on; the bar around the keys is never focused.
     */
    @Test
    fun aScreenReaderCanSwitchSetsFromAKey() {
        show()
        val node = rule.onAllNodes(hasContentDescription("Escape"))[0].fetchSemanticsNode()
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()

        rule.runOnIdle { actions.first { it.label == "Next key set" }.action() }
        rule.runOnIdle { actions.first { it.label == "Previous key set" }.action() }

        assertEquals(listOf(1, -1), switched)
    }

    private companion object {
        const val STEPS = 8
    }
}
