package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.components.moved
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Reordering by dragging, driven as a finger drives it.
 *
 * The gesture is the feature here, so it is exercised rather than the list function underneath it —
 * a reorder that computes the right answer but never fires is the failure worth catching.
 */
class KeyBarReorderTest {
    @get:Rule
    val rule = createComposeRule()

    private val scrolling = listOf(
        AuxiliaryKey("Aaa", key = TerminalKey.Escape),
        AuxiliaryKey("Bbb", key = TerminalKey.Tab),
        AuxiliaryKey("Ccc", key = TerminalKey.Home),
        AuxiliaryKey("Ddd", key = TerminalKey.End),
    )

    /** Enough keys, and wide enough, to need a second line at the width below. */
    private val wrapping = (1..14).map { AuxiliaryKey("K%02d".format(it), key = TerminalKey.Escape) }
    private val pinnedKeys = listOf(
        AuxiliaryKey("Left", key = TerminalKey.ArrowLeft),
        AuxiliaryKey("Down", key = TerminalKey.ArrowDown),
        AuxiliaryKey("Up", key = TerminalKey.ArrowUp),
    )

    // Compose state, not plain fields: the screen has to re-render with the list a drag produced,
    // or the next step of that same drag is computed from the list as it was before it started.
    private var preferences by mutableStateOf(TerminalPreferences())
    private var lastScrolling by mutableStateOf<List<AuxiliaryKey>?>(null)
    private var lastPinned by mutableStateOf<List<AuxiliaryKey>?>(null)

    private val vim = KeySet("Vim", scrolling, pinnedKeys)
    private val agent = KeySet("Agent", listOf(AuxiliaryKey("Yyy", key = TerminalKey.Enter)), emptyList())
    private val tmux = KeySet("tmux", listOf(AuxiliaryKey("Zzz", key = TerminalKey.Home)), emptyList())

    private fun show(keys: List<AuxiliaryKey> = scrolling) = showSets(listOf(KeySet("Default", keys, pinnedKeys)))

    private fun showSets(sets: List<KeySet>, active: Int = 0) {
        preferences = TerminalPreferences(keySets = sets, activeKeySet = active)
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp)) {
                    KeyBarSettingsScreen(
                        preferences = preferences,
                        onChange = { transform ->
                            val before = preferences.keySet
                            preferences = transform(preferences)
                            if (preferences.keySet.scrolling != before.scrolling) lastScrolling = preferences.keySet.scrolling
                            if (preferences.keySet.pinned != before.pinned) lastPinned = preferences.keySet.pinned
                        },
                        onBack = {},
                    )
                }
            }
        }
    }

    /**
     * The key by that name in the list, not the one in the preview above it.
     *
     * The screen shows the bar it is configuring, so every label is on screen twice. The preview
     * copy is inert, which is what tells them apart.
     */
    private fun key(label: String): SemanticsNodeInteraction = rule.onNode(hasText(label) and hasClickAction() and isEnabled())

    /**
     * Holds, travels onto another key in steps, lets go.
     *
     * In steps because a finger arrives in steps, and because a drag long enough to pass several
     * keys reorders several times — the second reorder has to be computed from the list the first
     * one produced, which a single-jump drag never checks.
     */
    private fun dragOnto(label: String, target: String) {
        val from = key(label).fetchSemanticsNode().boundsInRoot
        val to = key(target).fetchSemanticsNode().boundsInRoot
        // Onto the middle of the target and no further, which is where aiming at a key puts the
        // finger, and the case that used to leave the key one place short of where it was dropped.
        val travel = Offset(to.center.x - from.center.x, to.center.y - from.center.y)
        key(label).performTouchInput {
            down(center)
            // Past the long-press threshold before moving, which is what tells a hold from a scroll.
            moveBy(Offset.Zero, delayMillis = HOLD_MILLIS)
            repeat(STEPS) { moveBy(travel / STEPS.toFloat(), delayMillis = 40) }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun aScrollingKeyCanBeDraggedPastTheOnesAfterIt() {
        show()
        dragOnto("Aaa", "Ccc")
        assertEquals(listOf("Bbb", "Ccc", "Aaa", "Ddd"), lastScrolling?.map { it.label })
    }

    @Test
    fun aScrollingKeyCanBeDraggedBack() {
        show()
        dragOnto("Ddd", "Bbb")
        assertEquals(listOf("Aaa", "Ddd", "Bbb", "Ccc"), lastScrolling?.map { it.label })
    }

    @Test
    fun aPinnedKeyCanBeDragged() {
        show()
        dragOnto("Up", "Left")
        assertEquals(listOf("Up", "Left", "Down"), lastPinned?.map { it.label })
    }

    /**
     * The bar wraps, and a key dragged onto the line below lands there.
     *
     * The cover display fits about ten keys on a line and the owner configures more than that, so
     * crossing a line is not an edge case here — it is how the second half of the bar is reached.
     */
    @Test
    fun aScrollingKeyCanBeDraggedOntoTheLineBelow() {
        show(wrapping)
        val labels = wrapping.map { it.label }
        val where = labels.associateWith { key(it).fetchSemanticsNode().boundsInRoot }
        val top = where.getValue(labels.first()).center.y
        val below = labels.firstOrNull { where.getValue(it).center.y > top + 1f }
        // Guards the guard: with everything on one line this test would pass by testing nothing.
        assertNotNull("the keys did not wrap onto a second line", below)

        dragOnto(labels.first(), below!!)

        val landed = labels.indexOf(below)
        assertEquals(labels.moved(0, landed), lastScrolling?.map { it.label })
    }

    /** And back up again, which travels the other way through the same decision. */
    @Test
    fun aScrollingKeyCanBeDraggedBackOntoTheLineAbove() {
        show(wrapping)
        val labels = wrapping.map { it.label }
        val where = labels.associateWith { key(it).fetchSemanticsNode().boundsInRoot }
        val top = where.getValue(labels.first()).center.y
        val last = labels.last()
        assertNotNull(
            "the keys did not wrap onto a second line",
            labels.firstOrNull { where.getValue(it).center.y > top + 1f },
        )
        // Onto a key in the middle of the first line, so the drag crosses the line break and then
        // has to stop somewhere along the line above rather than running to its start.
        val target = labels[2]

        dragOnto(last, target)

        assertEquals(labels.moved(labels.lastIndex, 2), lastScrolling?.map { it.label })
    }

    @Test
    fun aSetDraggedPastTheOthersStaysInUseAndBeingEdited() {
        showSets(listOf(vim, agent, tmux))
        dragOnto("Vim", "tmux")
        assertEquals(listOf("Agent", "tmux", "Vim"), preferences.keySets.map { it.name })
        assertEquals(2, preferences.keySetIndex)
        rule.onNode(hasText("Vim") and isSelected()).assertExists()
    }

    /** Tabs are as wide as their names, and a long one passing a short one still lands where dropped. */
    @Test
    fun aSetWithALongNameDraggedPastAShortOneLandsWhereDropped() {
        val long = agent.copy(name = "Agent workspace")
        showSets(listOf(long, vim, tmux))
        dragOnto("Agent workspace", "tmux")
        assertEquals(listOf("Vim", "tmux", "Agent workspace"), preferences.keySets.map { it.name })
    }

    /** Moving a set the screen is not editing must not swap out the one it is. */
    @Test
    fun draggingAnotherSetKeepsTheOneBeingEditedOnScreen() {
        showSets(listOf(vim, agent, tmux))
        key("tmux").performClick()
        rule.waitForIdle()

        dragOnto("Vim", "tmux")

        assertEquals(listOf("Agent", "tmux", "Vim"), preferences.keySets.map { it.name })
        rule.onNode(hasText("tmux") and isSelected()).assertExists()
        key("Zzz").assertExists()
    }

    @Test
    fun aShortPressOnASetChoosesItWithoutMovingIt() {
        showSets(listOf(vim, agent, tmux))
        key("Agent").performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f), delayMillis = 20)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(vim, agent, tmux), preferences.keySets)
        rule.onNode(hasText("Agent") and isSelected()).assertExists()
    }

    /** A tap is still a tap: it opens the editor rather than picking the key up. */
    @Test
    fun aShortPressDoesNotReorder() {
        show()
        key("Aaa").performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f), delayMillis = 20)
            up()
        }
        rule.waitForIdle()
        assertEquals(null, lastScrolling)
    }
}

/** Comfortably past the platform's long-press timeout, whatever the device sets it to. */
private const val HOLD_MILLIS = 900L

/** Enough that a drag across several keys lands inside each one it passes. */
private const val STEPS = 12
