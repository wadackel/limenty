package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex

/** The same list with one item lifted out of [from] and put back at [to]. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/**
 * Shared state for one reorderable group.
 *
 * Holds where each slot ended up so a drag can ask what it is over. The alternative — deciding by
 * how far the finger has travelled — needs every item to be the same size, and these are not: a key
 * is as wide as its label.
 */
@Stable
class ReorderState<T> internal constructor(private val haptics: HapticFeedback) {
    internal val slots = mutableStateMapOf<Int, Rect>()
    internal var container: LayoutCoordinates? = null

    /**
     * The order as the drag has it, which is not the order the caller has yet.
     *
     * A drag long enough to pass two keys reorders twice in the same frame, and a recomposition
     * does not happen in between — so computing the second order from what the caller last passed
     * down would compute it from the order before the first move, throwing that move away. The
     * drag keeps its own copy and hands the result out; the caller's value catches up after.
     */
    internal var items: List<T> = emptyList()
    internal var onReorder: (List<T>) -> Unit = {}

    internal fun apply(from: Int, to: Int) {
        items = items.moved(from, to)
        onReorder(items)
    }

    /** The item currently under the finger, by its position in the list, or null. */
    var dragging by mutableStateOf<Int?>(null)
        internal set

    /** How far the finger has travelled since the item was picked up. */
    internal var offset by mutableStateOf(Offset.Zero)

    /** Where the item was, and how big, when it was picked up: the rectangle the finger carries. */
    private var picked = Rect.Zero

    internal fun start(index: Int) {
        dragging = index
        offset = Offset.Zero
        picked = slots[index] ?: Rect.Zero
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * How far the lifted item is drawn from the slot it is laid out in, so that it stays under the
     * finger whichever slot the reorder has put it in.
     */
    internal fun lift(index: Int): Offset {
        val laid = slots[index] ?: return offset
        return picked.topLeft + offset - laid.topLeft
    }

    internal fun stop() {
        dragging = null
        offset = Offset.Zero
    }

    /**
     * How far along the list the dragged item has got.
     *
     * Answered by whether its centre has passed a neighbour's centre, one neighbour at a time. The
     * obvious alternative — which slot the finger is inside — cannot work here, because the swap
     * changes the very geometry it was decided from: keys are as wide as their labels, so trading a
     * three-character key with a five-character one moves the boundary the finger was just judged
     * against, and the pair then swap back and forth on every frame. Passing a centre does not
     * reverse itself, since after the swap the item sits at that centre and has a whole neighbour
     * to travel back across.
     */
    internal fun drag(delta: Offset): Int? {
        offset += delta
        val from = dragging ?: return null
        val moving = picked.translate(offset).center
        var target = from

        // Bounded by the list rather than by what has been measured: a removed item's slot stays
        // in the map until something is laid out there again.
        while (target + 1 < items.size) {
            val next = slots[target + 1] ?: break
            if (!hasPassed(moving, next, forward = true)) break
            target++
        }
        if (target == from) {
            while (true) {
                val previous = slots[target - 1] ?: break
                if (!hasPassed(moving, previous, forward = false)) break
                target--
            }
        }
        return target.takeIf { it != from }
    }

    /**
     * Whether the item, now centred at [moving], has crossed [neighbour].
     *
     * A wrapped bar is read the way text is — along a line, then on to the next — so the axis is a
     * question about where the item is now, not about where the two slots started. A neighbour on
     * another line has been crossed as soon as the item is past it vertically, whatever the finger
     * did sideways; sideways only decides between neighbours the item is level with.
     *
     * Reading the axis off the two slots instead is what made a key carried straight down onto the
     * line below move nowhere: every key still to its right on the first line was judged by x, x
     * had not moved, and the walk stopped at the first of them — so the second line could only be
     * reached by dragging the whole way along the first.
     */
    private fun hasPassed(moving: Offset, neighbour: Rect, forward: Boolean): Boolean {
        val level = kotlin.math.abs(moving.y - neighbour.center.y) < neighbour.height / 2f
        if (!level) {
            return if (forward) moving.y > neighbour.center.y else moving.y < neighbour.center.y
        }
        // Just short of the middle, not the middle itself. Dropping a key onto another key is
        // aiming at its middle, so a boundary drawn exactly there sits where the aim is best and
        // decides on a fraction of a pixel — the key then lands one place short of where it was
        // put. Scaled by the narrower of the two, because the item has to travel back across its
        // own width to reverse, and a slack sized off a much wider neighbour alone can exceed that.
        val slack = SETTLE_SLACK * minOf(neighbour.width, picked.width)
        return if (forward) moving.x >= neighbour.center.x - slack else moving.x <= neighbour.center.x + slack
    }

    /**
     * Follows the item to [target]. Its position needs no correcting here: [lift] measures it from
     * wherever it is laid out next. Correcting by the target's old corner instead assumed the item
     * lands there, which holds only when the two are the same width — a wide tab passing a narrow
     * one then trailed the finger, and stopped a place short of where it was dropped.
     */
    internal fun settleInto(target: Int) {
        if (dragging == null) return
        dragging = target
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

/**
 * Put this on the row or flow that holds the items.
 *
 * It gives their positions one origin to share, and it is where the move itself is handled: one
 * handler for the group rather than one per item, so it stays current as the list changes.
 */
fun <T> Modifier.reorderGroup(
    state: ReorderState<T>,
    items: List<T>,
    onReorder: (List<T>) -> Unit,
): Modifier {
    // Not while a drag is in flight: what arrives then is the order the drag has already moved on
    // from, and taking it would undo the move that produced it.
    if (state.dragging == null) state.items = items
    state.onReorder = onReorder
    return onGloballyPositioned { state.container = it }
}

@Composable
fun <T> rememberReorderState(): ReorderState<T> {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { ReorderState<T>(haptics) }
}

/**
 * Makes one item of a reorderable group draggable.
 *
 * The drag begins on a long press so the item keeps its own taps — a key in this app is edited,
 * pinned and removed by tapping parts of it. [name] is what a screen reader calls the item; the
 * same reorder is offered there as two actions, since dragging is not something it can do.
 */
fun Modifier.reorderable(
    state: ReorderState<*>,
    index: Int,
    count: Int,
    name: String,
): Modifier {
    val dragged = state.dragging == index
    return this
        // Before the lift, deliberately. `graphicsLayer` scales the coordinate space of everything
        // inside it, so a gesture read there reports a finger that has moved 1/LIFTED_SCALE of what
        // it really moved — six per cent short on every delta, which accumulates over a long drag
        // until the item trails the finger by most of a key. Outside the layer the deltas are the
        // finger's own. Hit testing is unaffected: the pointer is captured at the press.
        .pointerInput(index, count) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.start(index) },
                onDragEnd = { state.stop() },
                onDragCancel = { state.stop() },
                onDrag = { change, delta ->
                    change.consume()
                    state.drag(delta)?.let { target ->
                        val from = state.dragging ?: return@let
                        state.settleInto(target)
                        state.apply(from, target)
                    }
                },
            )
        }
        // Measured against the group rather than against whatever the modifier chain happens to
        // nest this in, so every slot is in one coordinate space and can be compared.
        .onGloballyPositioned { here ->
            val group = state.container
            if (group != null && group.isAttached && here.isAttached) {
                state.slots[index] = Rect(group.localPositionOf(here, Offset.Zero), here.size.toSize())
            }
        }
        .zIndex(if (dragged) 1f else 0f)
        .graphicsLayer {
            if (dragged) {
                val lift = state.lift(index)
                translationX = lift.x
                translationY = lift.y
                scaleX = LIFTED_SCALE
                scaleY = LIFTED_SCALE
            }
        }
        .semantics {
            customActions = buildList {
                if (index > 0) {
                    add(
                        CustomAccessibilityAction("Move $name earlier") {
                            state.apply(index, index - 1)
                            true
                        },
                    )
                }
                if (index < count - 1) {
                    add(
                        CustomAccessibilityAction("Move $name later") {
                            state.apply(index, index + 1)
                            true
                        },
                    )
                }
            }
        }
}

/** Just enough lift to read as picked up without covering its neighbours. */
private const val LIFTED_SCALE = 1.06f

/** How far short of a neighbour's middle counts as having reached it, as a share of its size. */
private const val SETTLE_SLACK = 0.15f
