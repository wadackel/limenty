package dev.wadackel.limenty.ui.terminal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A key group's slot on the bar, touched the ways a thumb touches it.
 *
 * The bar sits at the bottom of a screen whose rest stands in for the terminal and counts every tap
 * it receives, so a touch that leaks through the popup's catcher shows up as a number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w460dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuxiliaryKeyBarGroupTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val dash = AuxiliaryKey("-", codePoint = '-'.code)
    private val slash = AuxiliaryKey("/", codePoint = '/'.code)
    private val sym = KeyGroup("sym", listOf(pipe, dash, slash))
    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)

    private val pressed = mutableListOf<String>()
    private val switched = mutableListOf<Int>()
    private var behind = 0
    private val groups = KeyGroupPopupState()
    private var setIndex by mutableIntStateOf(0)

    private fun show(
        scrolling: List<KeyBarSlot> = listOf(sym, esc),
        pinned: List<KeyBarSlot> = AuxiliaryKey.DefaultPinned,
        /** How much screen is left above the bar, as on a cover display turned sideways with the keyboard up. */
        above: Dp? = null,
    ) {
        rule.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .then(if (above == null) Modifier.weight(1f) else Modifier.height(above))
                        .fillMaxWidth()
                        .testTag("terminal")
                        .clickable { behind++ },
                )
                AuxiliaryKeyBar(
                    keys = scrolling,
                    pinnedKeys = pinned,
                    onKeyPressed = { pressed += it.label },
                    switcher = KeySetSwitcher(index = setIndex, count = 2, name = "Vim", onSwitch = { switched += it }),
                    groups = groups,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun node(description: String): SemanticsNodeInteraction = rule.onNode(hasContentDescription(description))

    private fun bounds(description: String): Rect = node(description).fetchSemanticsNode().boundsInRoot

    private fun shown(description: String): Boolean = rule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun px(dp: Float) = dp * rule.density.density

    private var finger = Offset.Zero

    /** Held past the hold, then left where it is until [lift]. */
    private fun hold(at: Offset) {
        finger = at
        rule.onRoot().performTouchInput { down(at) }
        rule.mainClock.advanceTimeBy(AuxiliaryKeyBarMetrics.groupHoldMillis + 50)
        rule.waitForIdle()
    }

    private fun slideTo(at: Offset) {
        val from = finger
        rule.onRoot().performTouchInput {
            repeat(STEPS) { step -> moveTo(from + (at - from) * ((step + 1) / STEPS.toFloat())) }
        }
        finger = at
        rule.waitForIdle()
    }

    private fun lift() {
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun aTapOnAGroupOpensItsKeysAndATapOnOneSendsItAndCloses() {
        show()
        node("sym, 3 keys").performClick()
        rule.waitForIdle()
        assertTrue(groups.isOpen)

        node("-").performClick()
        rule.waitForIdle()

        assertEquals(listOf("-"), pressed)
        assertFalse(groups.isOpen)
        assertFalse(shown("-"))
    }

    @Test
    fun holdingAGroupAndSlidingOntoAKeySendsItOnLift() {
        show()
        hold(bounds("sym, 3 keys").center)
        assertTrue(groups.isOpen)

        slideTo(bounds("/").center)
        lift()

        assertEquals(listOf("/"), pressed)
        assertFalse(groups.isOpen)
        assertEquals(emptyList<Int>(), switched)
    }

    @Test
    fun liftingAnywhereButAKeyLeavesThePopupOpen() {
        show()
        hold(bounds("sym, 3 keys").center)
        lift()

        assertTrue(groups.isOpen)
        assertEquals(emptyList<String>(), pressed)
    }

    /** The slide goes up past the switch distance, which would switch sets if the bar still owned it. */
    @Test
    fun aSlideAfterTheHoldDoesNotSwitchSets() {
        show()
        val slot = bounds("sym, 3 keys").center
        hold(slot)
        slideTo(slot - Offset(0f, px(60f)))
        lift()

        assertEquals(emptyList<Int>(), switched)
    }

    @Test
    fun aSwipeThatStartsOnAGroupStillSwitchesSets() {
        show()
        val slot = bounds("sym, 3 keys")
        rule.onRoot().performTouchInput {
            down(Offset(slot.center.x, slot.bottom - px(4f)))
            repeat(STEPS) { moveBy(Offset(0f, -px(30f) / STEPS)) }
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf(1), switched)
        assertFalse(groups.isOpen)
    }

    @Test
    fun aSidewaysDragThatStartsOnAGroupStillScrollsTheRun() {
        show(scrolling = listOf(sym) + AuxiliaryKey.DefaultScrolling)
        val before = bounds("sym, 3 keys")
        rule.onRoot().performTouchInput {
            down(before.center)
            repeat(STEPS) { moveBy(Offset(-px(120f) / STEPS, 0f)) }
            up()
        }
        rule.waitForIdle()

        assertNotEquals(before.left, bounds("sym, 3 keys").left)
        assertFalse(groups.isOpen)
    }

    @Test
    fun aTapOutsideThePopupOnlyClosesIt() {
        show()
        node("sym, 3 keys").performClick()
        rule.waitForIdle()

        rule.onRoot().performTouchInput { click(Offset(centerX, px(100f))) }
        rule.waitForIdle()

        assertFalse(groups.isOpen)
        assertEquals(0, behind)
    }

    @Test
    fun aTapOnAnotherKeyWhileOpenOnlyClosesThePopup() {
        show()
        node("sym, 3 keys").performClick()
        rule.waitForIdle()

        rule.onRoot().performTouchInput { click(bounds("Escape").center) }
        rule.waitForIdle()

        assertFalse(groups.isOpen)
        assertEquals(emptyList<String>(), pressed)
    }

    @Test
    fun backClosesThePopup() {
        show()
        node("sym, 3 keys").performClick()
        rule.waitForIdle()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        assertFalse(groups.isOpen)
    }

    @Test
    fun switchingSetsClosesThePopup() {
        show()
        node("sym, 3 keys").performClick()
        rule.waitForIdle()

        setIndex = 1
        rule.waitForIdle()

        assertFalse(groups.isOpen)
    }

    @Test
    fun thePopupFloatsAboveTheBarWithoutResizingWhatIsAboveIt() {
        show()
        val terminal = rule.onNode(androidx.compose.ui.test.hasTestTag("terminal")).fetchSemanticsNode().boundsInRoot
        node("sym, 3 keys").performClick()
        rule.waitForIdle()

        assertEquals(terminal, rule.onNode(androidx.compose.ui.test.hasTestTag("terminal")).fetchSemanticsNode().boundsInRoot)
        assertTrue(bounds("|").bottom <= bounds("sym, 3 keys").top)
    }

    /** Twenty keys do not fit one row on the cover display. */
    @Test
    fun aGroupTooWideForOneRowWrapsUpwardInReadingOrder() {
        val many = (1..20).map { AuxiliaryKey("F$it", key = TerminalKey.valueOf("F${(it - 1) % 12 + 1}")) }
        show(scrolling = listOf(KeyGroup("Fn", many), esc))
        node("Fn, 20 keys").performClick()
        rule.waitForIdle()

        // Named F1 to F20, so each is found by its own name even past the twelve function keys.
        val all = (1..20).map { bounds("F$it") }
        val rows = all.map { it.top }.distinct().sorted()
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width

        assertTrue("expected more than one row, got $rows", rows.size > 1)
        assertTrue(all.all { it.top >= 0f && it.left >= 0f && it.right <= width })
        assertTrue(all.all { it.bottom <= bounds("Fn, 20 keys").top })
        val first = all.first()
        assertEquals(rows.first(), first.top)
        assertEquals(all.filter { it.top == first.top }.minOf { it.left }, first.left)
    }

    @Test
    fun aGroupTallerThanTheRoomAboveTheBarStaysOnScreen() {
        val many = (1..20).map { AuxiliaryKey("F$it", key = TerminalKey.valueOf("F${(it - 1) % 12 + 1}")) }
        show(scrolling = listOf(KeyGroup("Fn", many), esc), above = 60.dp)
        node("Fn, 20 keys").performClick()
        rule.waitForIdle()

        val all = (1..20).map { bounds("F$it") }
        assertTrue("a key went past the top: ${all.minOf { it.top }}", all.all { it.top >= 0f })
    }

    /** A frame at a time, the way the screen would draw it, so the fade is seen part way. */
    private fun frames(count: Int) = repeat(count) { rule.mainClock.advanceTimeByFrame() }

    /** The keys stay drawn for the length of the fade, then go. */
    @Test
    fun thePopupFadesOutAfterAKeyIsSent() {
        show()
        node("sym, 3 keys").performClick()
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        node("-").performClick()
        val drawn = (1..20).map {
            frames(1)
            shown("|")
        }

        assertFalse(groups.isOpen)
        // Gone at once, it would last a single frame; the fade is 80ms, about five.
        assertTrue("drawn for ${drawn.count { it }} frames", drawn.count { it } >= 4)
        assertFalse(drawn.last())
        assertEquals(listOf("-"), pressed)
    }

    @Test
    fun anEmptyGroupOpensNothing() {
        show(scrolling = listOf(KeyGroup("sym", emptyList()), esc))
        node("sym, no keys").performClick()
        rule.waitForIdle()

        assertFalse(groups.isOpen)
    }

    @Test
    fun aScreenReaderHearsTheGroupAndItsCountAndCanOpenItAndSwitchSets() {
        show()
        val slot = node("sym, 3 keys").fetchSemanticsNode()
        val actions = slot.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

        assertEquals(listOf("Next key set", "Previous key set"), actions)
        assertTrue(slot.config.contains(SemanticsProperties.Role))

        node("sym, 3 keys").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(groups.isOpen)
        assertTrue(shown("|"))
    }

    private companion object {
        const val STEPS = 10
    }
}
