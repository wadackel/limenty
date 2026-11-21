package dev.wadackel.limenty.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Where a drag decides an item now belongs, on a bar that wraps.
 *
 * The gesture is covered on a device, but a device cannot say which of several plausible answers
 * the decision gave — it can only say what came out the other end, an hour of emulator boot later.
 * Here the slots are written down, the finger is a list of offsets, and the reorder is read off
 * directly. Equal-width items, so a slot keeps its rectangle when the list is reordered and the
 * layout that a device would re-measure needs no modelling.
 */
class DragAcrossLinesTest {
    // The numbers a device actually laid these out at, read off the key bar settings screen.
    private val width = 128f
    private val height = 116f
    private val gap = 16f
    private val perLine = 7

    private fun slot(index: Int): Rect {
        val left = (index % perLine) * (width + gap)
        val top = (index / perLine) * (height + 16f)
        return Rect(left, top, left + width, top + height)
    }

    private fun state(count: Int): ReorderState<Int> {
        val state = ReorderState<Int>(NoHaptics)
        repeat(count) { state.slots[it] = slot(it) }
        state.items = (0 until count).toList()
        return state
    }

    /** The same sequence the drag modifier runs: decide, settle, hand the new order out. */
    private fun ReorderState<Int>.travel(from: Int, to: Int, steps: Int = 12) {
        start(from)
        val move = (slot(to).center - slot(from).center) / steps.toFloat()
        repeat(steps) {
            drag(move)?.let { target ->
                val was = dragging ?: return@let
                settleInto(target)
                apply(was, target)
            }
        }
        stop()
    }

    @Test
    fun `a key carried onto the line below lands where it was dropped`() {
        val state = state(14)

        state.travel(from = 0, to = 7)

        assertEquals((0 until 14).toList().moved(0, 7), state.items)
    }

    @Test
    fun `a key carried back onto the line above lands where it was dropped`() {
        val state = state(14)

        state.travel(from = 13, to = 2)

        assertEquals((0 until 14).toList().moved(13, 2), state.items)
    }

    /** Straight down onto the key below it, which is the shortest crossing there is. */
    @Test
    fun `a key dropped on the one directly beneath it takes its place`() {
        val state = state(14)

        state.travel(from = 1, to = 8)

        assertEquals((0 until 14).toList().moved(1, 8), state.items)
    }

    /** A removed item's slot stays measured until something lays out there again; it is not a place to go. */
    @Test
    fun `the last item is not carried into the slot of an item that was removed`() {
        val state = state(perLine)
        state.items = state.items.dropLast(1)
        val last = state.items.lastIndex

        state.start(last)
        val target = state.drag(slot(last + 1).center - slot(last).center)

        assertEquals(null, target)
    }

    /** A single line still behaves as it did, which is what the wrapped case must not cost. */
    @Test
    fun `along one line the middle of a key is still the boundary`() {
        val state = state(perLine)

        state.travel(from = 0, to = 2)

        assertEquals((0 until perLine).toList().moved(0, 2), state.items)
    }
}

/**
 * A drag along one line of items that are not the same width, as key set tabs are.
 *
 * Unlike the equal-width case, a reorder here moves the slots themselves, so every step lays the
 * line out again from the new order the way a device would before the next finger event.
 */
class DragUnevenWidthsTest {
    private val gap = 21f
    private val height = 126f

    private fun layOut(state: ReorderState<Float>) {
        var left = 0f
        state.items.forEachIndexed { index, width ->
            state.slots[index] = Rect(left, 0f, left + width, height)
            left += width + gap
        }
    }

    private fun state(widths: List<Float>): ReorderState<Float> {
        val state = ReorderState<Float>(NoHaptics)
        state.items = widths
        layOut(state)
        return state
    }

    private fun ReorderState<Float>.step(delta: Offset): Boolean {
        val target = drag(delta) ?: return false
        val was = dragging ?: return false
        settleInto(target)
        apply(was, target)
        layOut(this)
        return true
    }

    /** Carries the item at [from] until the finger is over the middle of where [to] was drawn. */
    private fun ReorderState<Float>.travel(from: Int, to: Int, steps: Int = 12) {
        val move = (slots.getValue(to).center - slots.getValue(from).center) / steps.toFloat()
        start(from)
        repeat(steps) { step(move) }
        stop()
    }

    @Test
    fun `a wide item dropped on the last one past a narrower neighbour lands where it was dropped`() {
        val state = state(listOf(236f, 124f, 168f))

        state.travel(from = 0, to = 2)

        assertEquals(listOf(124f, 168f, 236f), state.items)
    }

    @Test
    fun `a narrow item dropped on the last one past a wide neighbour lands where it was dropped`() {
        val state = state(listOf(124f, 236f, 168f))

        state.travel(from = 0, to = 2)

        assertEquals(listOf(236f, 168f, 124f), state.items)
    }

    @Test
    fun `a narrow item carried back over a wide one lands at the start`() {
        val state = state(listOf(236f, 168f, 124f))

        state.travel(from = 2, to = 0)

        assertEquals(listOf(124f, 236f, 168f), state.items)
    }

    /**
     * Held still just past a much wider neighbour, the item stays where it got to.
     *
     * Each further event at the same place is a chance to swap back, and a pair that trades places
     * on every frame never lets the drop land anywhere.
     */
    @Test
    fun `a small item held just past a much wider one does not swap back and forth`() {
        val state = state(listOf(126f, 525f))
        state.start(0)
        // A pixel at a time, so the finger stops at the first place the swap happens.
        val travelled = (1..state.slots.getValue(1).right.toInt()).firstOrNull { state.step(Offset(1f, 0f)) }
        assertNotNull("the small item never swapped with the wide one", travelled)

        var swaps = 1
        repeat(5) { if (state.step(Offset.Zero)) swaps++ }

        assertEquals(listOf(525f, 126f), state.items)
        assertEquals(1, swaps)
    }
}

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
