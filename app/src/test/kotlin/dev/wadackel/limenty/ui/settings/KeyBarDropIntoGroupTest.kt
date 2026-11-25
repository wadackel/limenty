package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
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
 * Dropping a key onto a key group in the key bar settings, driven as a finger drives it.
 *
 * The question each test asks is which of two things a drag over a group did — put the key in, or
 * move it past — so each one is read off the set the screen saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyBarDropIntoGroupTest {
    @get:Rule
    val rule = createComposeRule()

    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val tab = AuxiliaryKey("Tab", key = TerminalKey.Tab)
    private val home = AuxiliaryKey("Home", key = TerminalKey.Home)
    private val sym = KeyGroup("sym", listOf(AuxiliaryKey("Pipe", key = TerminalKey.Escape), AuxiliaryKey("Tilde", key = TerminalKey.Tab)))
    private val nav = KeyGroup("nav", listOf(AuxiliaryKey("PgUp", key = TerminalKey.PageUp)))

    private var preferences by mutableStateOf(TerminalPreferences())

    private fun show(scrolling: List<KeyBarSlot>, pinned: List<KeyBarSlot> = emptyList()) {
        preferences = TerminalPreferences(keySets = listOf(KeySet("Default", scrolling, pinned)))
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp)) {
                    KeyBarSettingsScreen(
                        preferences = preferences,
                        onChange = { transform -> preferences = transform(preferences) },
                        onBack = {},
                    )
                }
            }
        }
    }

    /** The list's copy of a slot, not the inert one in the preview bar above it. */
    private fun slot(spoken: String): SemanticsNodeInteraction = rule.onAllNodes((hasContentDescription(spoken) or hasText(spoken)) and hasClickAction() and isEnabled()).onLast()

    private fun bounds(spoken: String): Rect = slot(spoken).fetchSemanticsNode().boundsInRoot

    /** Holds [spoken], carries it in steps until its centre is at [to], lets go. */
    private fun drag(spoken: String, to: Offset) {
        val from = bounds(spoken).center
        val travel = to - from
        slot(spoken).performTouchInput {
            down(center)
            moveBy(Offset.Zero, delayMillis = HOLD_MILLIS)
            repeat(STEPS) { moveBy(travel / STEPS.toFloat(), delayMillis = 40) }
            up()
        }
        rule.waitForIdle()
    }

    private val labels get() = preferences.keySet.scrolling.map { it.label }

    @Test
    fun aKeyLetGoOverTheMiddleOfAGroupGoesIntoItsEnd() {
        show(listOf(esc, sym, home))
        drag("Esc", bounds("sym, 2 keys").center)
        assertEquals(listOf("sym", "Home"), labels)
        assertEquals(listOf("Pipe", "Tilde", "Esc"), (preferences.keySet.scrolling[0] as KeyGroup).keys.map { it.label })
    }

    @Test
    fun aKeyCarriedFromTheOtherSideAlsoGoesIn() {
        show(listOf(esc, sym, home))
        drag("Home", bounds("sym, 2 keys").center)
        assertEquals(listOf("Esc", "sym"), labels)
        assertEquals("Home", (preferences.keySet.scrolling[1] as KeyGroup).keys.last().label)
    }

    @Test
    fun aKeyCarriedPastAGroupMovesPastItInstead() {
        show(listOf(esc, sym, home))
        val group = bounds("sym, 2 keys")
        drag("Esc", Offset(group.right + 4f, group.center.y))
        assertEquals(listOf("sym", "Esc", "Home"), labels)
        assertEquals(2, (preferences.keySet.scrolling[0] as KeyGroup).keys.size)
    }

    @Test
    fun aKeyCarriedOverAGroupAndOutAgainIsNotPutInIt() {
        show(listOf(esc, sym, home))
        val group = bounds("sym, 2 keys")
        val start = bounds("Esc").center
        slot("Esc").performTouchInput {
            down(center)
            moveBy(Offset.Zero, delayMillis = HOLD_MILLIS)
            repeat(STEPS) { moveBy((group.center - start) / STEPS.toFloat(), delayMillis = 40) }
            repeat(STEPS) { moveBy((start - group.center) / STEPS.toFloat(), delayMillis = 40) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("Esc", "sym", "Home"), labels)
        assertEquals(2, (preferences.keySet.scrolling[1] as KeyGroup).keys.size)
    }

    /**
     * The key is carried down and across from the line above, the way a thumb goes straight for
     * the group, so it arrives level with the group already beside it.
     */
    @Test
    fun aKeyCarriedDownOntoAGroupOnTheNextLineGoesIntoIt() {
        val keys = (1..14).map { AuxiliaryKey("K%02d".format(it), key = TerminalKey.Escape) }
        show(keys.take(9) + sym + keys.drop(9))
        val group = bounds("sym, 2 keys")
        val above = keys.map { it.label }.first { label ->
            val at = bounds(label)
            at.bottom < group.top && at.left > group.right && at.bottom > group.top - at.height * 2
        }
        drag(above, group.center)
        assertEquals(2 + 1, (preferences.keySet.scrolling.single { it is KeyGroup } as KeyGroup).keys.size)
        assertEquals(above, (preferences.keySet.scrolling.single { it is KeyGroup } as KeyGroup).keys.last().label)
    }

    @Test
    fun aGroupLetGoOverAnotherGroupIsNotPutInsideIt() {
        show(listOf(sym, nav, tab))
        drag("sym, 2 keys", bounds("nav, 1 key").center)
        val groups = preferences.keySet.scrolling.filterIsInstance<KeyGroup>()
        assertEquals(mapOf("sym" to 2, "nav" to 1), groups.associate { it.name to it.keys.size })
        assertEquals(3, preferences.keySet.scrolling.size)
    }

    @Test
    fun aPinnedKeyLetGoOverAPinnedGroupGoesIntoIt() {
        show(scrolling = listOf(home), pinned = listOf(tab, sym))
        drag("Tab", bounds("sym, 2 keys").center)
        val pinned = preferences.keySet.pinned
        assertEquals(listOf("sym"), pinned.map { it.label })
        assertEquals("Tab", (pinned[0] as KeyGroup).keys.last().label)
    }
}

private const val HOLD_MILLIS = 900L
private const val STEPS = 12
