package dev.wadackel.limenty.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The always-visible auxiliary key row.
 *
 * One compact line, shown whether or not a physical keyboard is attached, so it has to cost as
 * little vertical space as possible — the terminal above it is the point of the screen.
 *
 * The row is split. Keys on the left scroll; keys on the right do not. Before that split the bar
 * was a single scrolling run, and on the cover display only half of it fitted — the half that fell
 * off the end held the arrows, Home/End and the page keys, which is to say everything an editor
 * needs. Nothing said the bar scrolled either: the sole hint was the rightmost key being sliced
 * through at the screen edge, which reads as a rendering fault rather than an invitation.
 *
 * So the keys that must always be reachable are held out of the scroll, and the scrolling run
 * fades out as it passes under them, which is what says there is more of it.
 *
 * A swipe up or down switches to the next or previous key set. Vertical, because the run already
 * owns the horizontal, and a button to switch with would take its width from the run that is the
 * reason the bar was split.
 *
 * A slot can hold a [KeyGroup], which opens into a popup above the bar instead of sending.
 */
@Composable
fun AuxiliaryKeyBar(
    keys: List<KeyBarSlot>,
    pinnedKeys: List<KeyBarSlot>,
    onKeyPressed: (AuxiliaryKey) -> Unit,
    modifier: Modifier = Modifier,
    /** False where the bar is shown as an example of itself, so a tap neither buzzes nor sends. */
    interactive: Boolean = true,
    switcher: KeySetSwitcher? = null,
    groups: KeyGroupPopupState = rememberKeyGroupPopupState(),
) {
    // Switching sets puts other slots under the popup's keys, and a popup left open over them
    // would send from a group the bar no longer shows.
    LaunchedEffect(switcher?.index, keys, pinnedKeys) { groups.close() }
    if (interactive) {
        BackHandler(enabled = groups.isOpen) { groups.close() }
    }
    var placement by remember { mutableStateOf(BarPlacement()) }
    val switching = switcher?.takeIf { interactive && it.count > 1 }
    // Which way the last switch went, so a wrap from the last set to the first still slides the
    // way the thumb moved rather than the way the index did.
    var direction by remember { mutableIntStateOf(1) }
    val switch: (Int) -> Unit = { delta ->
        direction = delta
        switching?.onSwitch?.invoke(delta)
    }
    // The swipe detector outlives a recomposition, and would otherwise keep the first set's callback.
    val onSwitch by rememberUpdatedState(switch)
    // On every key rather than on the bar: a screen reader only lands on the keys, so actions on the
    // bar around them are there in the tree and out of reach.
    val setActions = if (switching == null) {
        emptyList()
    } else {
        listOf(
            CustomAccessibilityAction("Next key set") {
                onSwitch(1)
                true
            },
            CustomAccessibilityAction("Previous key set") {
                onSwitch(-1)
                true
            },
        )
    }

    val markBand = if (switching == null) 0.dp else SET_MARK_BAND
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { placement = BarPlacement(it.positionInRoot(), it.findRootCoordinates().size) }
            // A row's height even with no keys in it: the keys used to give the bar its height, and a
            // set emptied in the settings left a sliver too thin to swipe away from. The mark band is
            // counted too, so an empty set is as tall as a full one.
            .heightIn(min = ROW_HEIGHT + markBand)
            .background(LimentyColors.Surface)
            .then(
                if (switching == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) { detectSetSwipe { delta -> onSwitch(delta) } }
                },
            ),
    ) {
        AnimatedContent(
            targetState = BarPage(switcher?.index ?: 0, keys, pinnedKeys),
            modifier = Modifier.padding(top = markBand),
            contentKey = { it.index },
            transitionSpec = {
                val enter = slideInVertically(tween(SLIDE_MILLIS)) { height -> height * direction / 2 } + fadeIn(tween(SLIDE_MILLIS))
                val exit = slideOutVertically(tween(SLIDE_MILLIS)) { height -> -height * direction / 2 } + fadeOut(tween(SLIDE_MILLIS))
                enter togetherWith exit
            },
            label = "keySet",
        ) { page ->
            BarRow(page.keys, page.pinned, BarInput(interactive, onKeyPressed, setActions, groups))
        }
        if (switching != null) {
            SetMarks(switching.index, switching.count, Modifier.align(Alignment.TopCenter))
            SetName(switching.index, switching.name, Modifier.align(Alignment.TopCenter))
        }
        if (interactive) {
            KeyGroupLayer(groups, placement, onKeyPressed)
        }
    }
}

/**
 * Which key group is open on the bar, if any.
 *
 * Held outside the bar so the screen around it can close the popup on what the bar cannot see, such
 * as the session ending.
 */
@Stable
class KeyGroupPopupState internal constructor() {
    internal var open by mutableStateOf<OpenKeyGroup?>(null)
        private set

    /** The member a sliding finger is over, by its place in the group. */
    internal var underFinger by mutableStateOf<Int?>(null)

    /**
     * Where each member is placed, for a slide to find it by. Kept as coordinates and read when the
     * finger moves, because the popup is still rising when it opens and a rectangle taken then
     * would sit below the key that is drawn.
     */
    internal val memberBounds = mutableMapOf<Int, LayoutCoordinates>()

    val isOpen: Boolean get() = open != null

    internal fun show(group: KeyGroup, anchorX: Float, owner: Any) {
        memberBounds.clear()
        underFinger = null
        open = OpenKeyGroup(group, anchorX, owner)
    }

    /** [underFinger] is left as it was, so the key just sent stays lit while the popup fades. */
    fun close() {
        open = null
        memberBounds.clear()
    }

    internal fun memberAt(inRoot: Offset): Int? = memberBounds.entries.firstOrNull { (_, coordinates) ->
        coordinates.isAttached && coordinates.boundsInRoot().contains(inRoot)
    }?.key
}

@Composable
fun rememberKeyGroupPopupState(): KeyGroupPopupState = remember { KeyGroupPopupState() }

/**
 * An open group, with the horizontal centre of its slot in the root's coordinates.
 *
 * [owner] tells two equal groups apart: a set can hold the same group twice, and only the slot that
 * was pressed is drawn open.
 */
internal class OpenKeyGroup(val group: KeyGroup, val anchorX: Float, val owner: Any)

/** Where the bar sits in the root, which is what the popup is placed against. */
private data class BarPlacement(val origin: Offset = Offset.Zero, val root: IntSize = IntSize.Zero)

/** What every slot needs to answer a touch, passed down together. */
private class BarInput(
    val interactive: Boolean,
    val onKeyPressed: (AuxiliaryKey) -> Unit,
    val setActions: List<CustomAccessibilityAction>,
    val groups: KeyGroupPopupState,
)

/**
 * What the bar needs to switch sets, kept together because none of it means anything alone.
 *
 * Null where the bar is shown rather than used, and on a bar with one set the swipe and its marks
 * are left out, so someone who never makes a second set sees the bar exactly as it was.
 */
data class KeySetSwitcher(
    val index: Int,
    val count: Int,
    val name: String,
    val onSwitch: (delta: Int) -> Unit,
)

/** What one set puts on the bar, so the set sliding out keeps its own keys while it goes. */
private data class BarPage(val index: Int, val keys: List<KeyBarSlot>, val pinned: List<KeyBarSlot>)

/**
 * Measured from where the finger went down rather than summed from the drag, which starts counting
 * only once the touch slop is crossed and would make the distance depend on the slop.
 *
 * Consuming the drag is also what keeps the key under the thumb from being pressed: a key's tap
 * gives up when it sees the gesture consumed by anyone.
 */
private suspend fun PointerInputScope.detectSetSwipe(onSwipe: (delta: Int) -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var travelled = 0f
    val dragging = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
        change.consume()
        travelled = change.position.y - down.position.y
    } ?: return@awaitEachGesture
    val completed = verticalDrag(dragging.id) { change ->
        change.consume()
        travelled = change.position.y - down.position.y
    }
    if (completed && abs(travelled) >= AuxiliaryKeyBarMetrics.switchDistance.toPx()) {
        onSwipe(if (travelled < 0) 1 else -1)
    }
}

/** A dot per set along the top edge: the only sign at rest that there is more than one. */
@Composable
private fun SetMarks(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = SET_MARK_TOP).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(SET_MARK_GAP),
    ) {
        repeat(count) { at ->
            Box(
                modifier = Modifier
                    .size(SET_MARK_SIZE)
                    .clip(CircleShape)
                    .background(if (at == index) LimentyColors.Accent else LimentyColors.Line),
            )
        }
    }
}

/**
 * The name of the set just switched to, shown briefly above the bar, over the terminal's last rows.
 *
 * Not over the keys: there it hid the very keys just switched to, right when the thumb goes for
 * one. It takes no height of its own either — growing the bar for it would resize the terminal,
 * and so reflow the session, on every switch.
 *
 * Not on first composition: arriving at the terminal is not a switch, and a name flashing every
 * time the screen opens would teach that it means nothing.
 *
 * A name has no length limit, so a long one ends in an ellipsis inside the bar's edge padding, as
 * the set tabs in the settings do, rather than being clipped at the screen edge with no sign that
 * any of it is missing.
 */
@Composable
private fun SetName(index: Int, name: String, modifier: Modifier = Modifier) {
    var shownFor by remember { mutableIntStateOf(index) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        if (index == shownFor) return@LaunchedEffect
        shownFor = index
        visible = true
        delay(NAME_MILLIS)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(horizontal = EDGE_PADDING).aboveBar(),
        enter = EnterTransition.None,
        exit = fadeOut(tween(SLIDE_MILLIS)),
    ) {
        Text(
            text = name,
            style = LimentyText.Secondary,
            color = LimentyColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(LimentyShapes.Small)
                .background(LimentyColors.Surface3)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Hangs the content off the bar's top edge, leaving the bar's own size alone. */
private fun Modifier.aboveBar(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, 0) {
        placeable.place(0, -placeable.height - SET_NAME_GAP.roundToPx())
    }
}

@Composable
private fun BarRow(
    keys: List<KeyBarSlot>,
    pinnedKeys: List<KeyBarSlot>,
    input: BarInput,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    // Less the half gap each end key carries as its own reach.
                    .padding(start = EDGE_PADDING - KEY_GAP / 2, end = FADE_WIDTH - KEY_GAP / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                keys.forEach { slot -> BarSlot(slot, pinned = false, input) }
            }
            // Only while there is more to reach: on the unfolded display the whole run often fits,
            // and a fade over nothing says the opposite of what it is for.
            if (scrollState.canScrollForward) {
                // Sized from the row rather than from a key: a fade shorter than what it covers
                // leaves the passing key's face at full strength above and below it, which reads
                // as a key sliced in half rather than as one going out of view.
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(FADE_WIDTH)
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    0.75f to LimentyColors.Surface,
                                    1f to LimentyColors.Surface,
                                ),
                            ),
                    )
                }
            }
        }

        if (pinnedKeys.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .padding(start = DIVIDER_MARGIN, end = DIVIDER_MARGIN - KEY_GAP / 2)
                    .width(1.dp)
                    .height(DIVIDER_HEIGHT)
                    .background(LimentyColors.Line),
            )
            Row(
                modifier = Modifier.padding(end = EDGE_PADDING - KEY_GAP / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pinnedKeys.forEach { slot -> BarSlot(slot, pinned = true, input) }
            }
        }
    }
}

@Composable
private fun BarSlot(slot: KeyBarSlot, pinned: Boolean, input: BarInput) {
    when (slot) {
        is AuxiliaryKey -> BarKey(slot, pinned, input)
        is KeyGroup -> GroupSlot(slot, pinned, input)
    }
}

/** One key on the bar. A pinned key and a scrolling one differ only in colour. */
@Composable
private fun BarKey(key: AuxiliaryKey, pinned: Boolean, input: BarInput) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .height(ROW_HEIGHT)
            .clickable(enabled = input.interactive, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                input.onKeyPressed(key)
            }
            // The face is `←`, which a screen reader would otherwise read out as the character's name.
            .semantics(mergeDescendants = true) {
                contentDescription = key.spoken()
                if (input.setActions.isNotEmpty()) customActions = input.setActions
            }
            // The gap between keys is split between the two keys beside it rather than left to the
            // row: Compose widens a touch target only while it is narrower than 48dp, so between two
            // word keys a gap belonging to the row would answer nothing.
            .padding(horizontal = KEY_GAP / 2),
        contentAlignment = Alignment.Center,
    ) {
        KeyFace(key.label, face = slotFace(pinned), ink = slotInk(pinned))
    }
}

/**
 * A key group's slot: a tap opens its popup, and so does holding it, after which the finger can
 * slide onto a key and send it by lifting.
 *
 * The hold comes first because the bar already uses both directions — a vertical swipe switches
 * sets and a horizontal drag scrolls — so a slide that began at once would take both from every
 * swipe and drag that started on a group. A drag before the hold still belongs to them.
 */
@Composable
private fun GroupSlot(group: KeyGroup, pinned: Boolean, input: BarInput) {
    val haptics = LocalHapticFeedback.current
    val popup = input.groups
    val owner = remember { Any() }
    val placed = remember { PlacedSlot() }
    val onKeyPressed by rememberUpdatedState(input.onKeyPressed)
    val open: () -> Boolean = {
        val coordinates = placed.coordinates
        if (group.keys.isNotEmpty() && coordinates != null && coordinates.isAttached) {
            popup.show(group, coordinates.boundsInRoot().center.x, owner)
            true
        } else {
            false
        }
    }
    val isOpen = popup.open?.owner === owner
    Box(
        modifier = Modifier
            .height(ROW_HEIGHT)
            .onGloballyPositioned { placed.coordinates = it }
            .then(
                if (!input.interactive) {
                    Modifier
                } else {
                    Modifier.pointerInput(group) {
                        detectGroupPress(
                            onTap = { open() },
                            onHold = open,
                            onSlide = { at ->
                                placed.coordinates?.takeIf { it.isAttached }?.let { popup.underFinger = popup.memberAt(it.localToRoot(at)) }
                            },
                            onLift = {
                                val member = popup.underFinger?.let { group.keys.getOrNull(it) }
                                if (member != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    popup.close()
                                    onKeyPressed(member)
                                } else {
                                    popup.underFinger = null
                                }
                            },
                        )
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = group.spoken()
                role = Role.Button
                if (input.interactive) onClick { open() }
                if (input.setActions.isNotEmpty()) customActions = input.setActions
            }
            .padding(horizontal = KEY_GAP / 2),
        contentAlignment = Alignment.Center,
    ) {
        KeyFace(
            group.label,
            face = if (isOpen) LimentyColors.Surface5 else slotFace(pinned),
            ink = if (isOpen) LimentyColors.Text else slotInk(pinned),
            marked = true,
        )
    }
}

/** Where a group's slot was placed, kept off the snapshot: only a touch reads it. */
private class PlacedSlot {
    var coordinates: LayoutCoordinates? = null
}

private fun slotFace(pinned: Boolean): Color = if (pinned) LimentyColors.Surface2 else LimentyColors.Surface3

private fun slotInk(pinned: Boolean): Color = if (pinned) LimentyColors.Text2 else LimentyColors.Text

/**
 * A tap, or a hold followed by a slide.
 *
 * The tap and the hold both wait on [waitForUpOrCancellation], which gives up when the set swipe
 * or the scroll consumes the drag; that is what hands those drags back to the bar. Once the hold has
 * fired, every change is consumed here, so the same drag can no longer switch sets or scroll.
 */
private suspend fun PointerInputScope.detectGroupPress(
    onTap: () -> Unit,
    onHold: () -> Boolean,
    onSlide: (Offset) -> Unit,
    onLift: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    var cancelled = false
    val up = withTimeoutOrNull(AuxiliaryKeyBarMetrics.groupHoldMillis) {
        waitForUpOrCancellation().also { cancelled = it == null }
    }
    if (up != null) {
        up.consume()
        onTap()
        return@awaitEachGesture
    }
    if (cancelled || !onHold()) return@awaitEachGesture
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) {
            onLift()
            break
        }
        onSlide(change.position)
    }
}

/**
 * The open group's keys, floating above the bar, and what catches a touch anywhere else.
 *
 * Laid out over the whole window from inside the bar while taking no room in it, so opening it
 * neither resizes the terminal nor reflows the session. The catcher sits under the popup and over
 * everything else: a tap outside only closes it, the way a tap around the terminal's other overlays
 * does not reach the terminal either.
 *
 * Closing plays the opening backwards, so the popup does not blink out. Only the drawing lingers:
 * the catcher and the keys stop answering the moment it closes, so a touch made while it fades goes
 * where it would with nothing open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGroupLayer(popup: KeyGroupPopupState, placement: BarPlacement, onKeyPressed: (AuxiliaryKey) -> Unit) {
    val open = popup.open
    var last by remember { mutableStateOf<OpenKeyGroup?>(null) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open != null) {
            last = open
            rise.snapTo(0f)
            rise.animateTo(1f, tween(POPUP_RISE_MILLIS, easing = LinearOutSlowInEasing))
        } else {
            rise.animateTo(0f, tween(POPUP_RISE_MILLIS, easing = FastOutLinearInEasing))
            last = null
        }
    }
    val shown = open ?: last ?: return
    if (placement.root == IntSize.Zero) return
    val answering = open != null
    val haptics = LocalHapticFeedback.current
    val topInset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
    Box(
        modifier = Modifier.layout { measurable, _ ->
            val layer = measurable.measure(Constraints.fixed(placement.root.width, placement.root.height))
            layout(0, 0) { layer.place(-placement.origin.x.roundToInt(), -placement.origin.y.roundToInt()) }
        },
    ) {
        if (answering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Hit but not handled past closing, so the rest of the touch goes nowhere.
                    .pointerInput(popup) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            popup.close()
                        }
                    },
            )
        }
        FlowRow(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val margin = POPUP_MARGIN.roundToPx()
                    val panel = measurable.measure(Constraints(maxWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0)))
                    val x = (shown.anchorX - panel.width / 2f).roundToInt()
                        .coerceIn(margin, (constraints.maxWidth - margin - panel.width).coerceAtLeast(margin))
                    // Over the bar rather than past the top of the screen when there are more rows
                    // than room above it: a group never scrolls, so every key has to stay on screen.
                    val y = (placement.origin.y.roundToInt() - POPUP_GAP.roundToPx() - panel.height)
                        .coerceAtLeast(topInset + margin)
                    layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
                }
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1f - rise.value) * POPUP_RISE.toPx()
                }
                .shadow(POPUP_ELEVATION, POPUP_SHAPE)
                .background(LimentyColors.Surface3, POPUP_SHAPE)
                // The panel's own edge takes a touch too, rather than letting it fall to the catcher.
                .then(if (answering) Modifier.pointerInput(Unit) {} else Modifier)
                .padding(POPUP_PADDING),
        ) {
            shown.group.keys.forEachIndexed { index, member ->
                Box(
                    modifier = Modifier
                        .height(KEY_HEIGHT + KEY_GAP)
                        .onGloballyPositioned { if (popup.isOpen) popup.memberBounds[index] = it }
                        .clickable(enabled = answering, role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            popup.close()
                            onKeyPressed(member)
                        }
                        .semantics(mergeDescendants = true) { contentDescription = member.spoken() }
                        .padding(horizontal = KEY_GAP / 2),
                    contentAlignment = Alignment.Center,
                ) {
                    KeyFace(
                        member.label,
                        face = if (popup.underFinger == index) LimentyColors.KeyUnderFinger else LimentyColors.Surface4,
                        ink = LimentyColors.Text,
                    )
                }
            }
        }
    }
}

/**
 * A key's face and label, as every key the bar draws has it.
 *
 * [marked] adds the small triangle in the bottom right that tells a group from a key. It sits
 * inside the face, so a group is exactly as wide as a key with the same label.
 */
@Composable
private fun KeyFace(label: String, face: Color, ink: Color, marked: Boolean = false) {
    // A one-character label gets a square rather than a letterbox: `|` and `~` in a key shaped for
    // `PgDn` read as mostly empty, and the four of them together made the run look gappy.
    val square = label.length == 1
    Box(
        modifier = Modifier
            // Fixed height, not a floor: with a floor the label's own line box sets the height,
            // so raising the system font scale grew these keys past the pinned ones beside them
            // and past the fade that has to cover them.
            .height(KEY_HEIGHT)
            // Otherwise content-fit with a floor, so a short label stays legible without
            // padding every key out to a uniform size.
            .then(if (square) Modifier.width(KEY_HEIGHT) else Modifier.defaultMinSize(minWidth = KEY_MIN_WIDTH))
            .clip(RoundedCornerShape(KEY_RADIUS))
            .background(face)
            .then(if (marked) Modifier.keyGroupMark() else Modifier)
            // After the face is painted, so the label sits inside the key rather than the key
            // shrinking away from it.
            .then(if (square) Modifier else Modifier.padding(horizontal = LABEL_PADDING)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ink,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The triangle in the bottom right of a face that says it opens a group rather than sends. */
internal fun Modifier.keyGroupMark(): Modifier = drawWithContent {
    drawContent()
    val inset = GROUP_MARK_INSET.toPx()
    val side = GROUP_MARK_SIZE.toPx()
    val right = size.width - inset
    val bottom = size.height - inset
    val triangle = Path().apply {
        moveTo(right - side, bottom)
        lineTo(right, bottom)
        lineTo(right, bottom - side)
        close()
    }
    drawPath(triangle, LimentyColors.Text3)
}

/**
 * The bar's measurements and the rules built on them, in one place.
 *
 * The settings screen works out how much of a folded display the pinned keys claim, which is
 * arithmetic over these numbers — kept here so changing a key's width cannot leave that answer
 * quietly wrong. [labelStyle] and [keyWidth] are here for the same reason: a second copy of either
 * would be a second answer to how wide a key is.
 */
internal object AuxiliaryKeyBarMetrics {
    val edgePadding = 12.dp

    /**
     * How far apart keys sit, which is also how wide one is to hit: a tap in the gap goes to the
     * nearer key, so a thumb aims at the distance from one key to the next rather than at the face.
     * Widening the face instead would give up the square one-character key.
     */
    val keyGap = 12.dp

    /**
     * How tall a key is drawn, and how tall the band around it is.
     *
     * The two differ on purpose. The bar is on screen the whole time a session is, so every row of
     * pixels it takes is a row the terminal does not get — but a key small enough to be polite is
     * too small to hit. Drawing at [keyHeight] inside a [rowHeight] band gives the thumb the larger
     * target without charging the terminal for it.
     */
    val keyHeight = 32.dp
    val rowHeight = 44.dp

    val keyMinWidth = 32.dp

    /** [LimentyShapes.Surface]'s radius, as a length the measurements around a key can use. */
    val keyRadius = 12.dp

    /** Either side of a label, inside the key face. */
    val labelPadding = 10.dp
    val fadeWidth = 40.dp
    val dividerMargin = 10.dp
    val dividerHeight = 22.dp

    /** Everything between the scrolling run and the screen edge: the rule and its margins. */
    val dividerBlock = dividerMargin * 2 + 1.dp

    /**
     * The display the pinned keys are budgeted against, and the least that is worth scrolling in.
     *
     * The folded panel rather than the unfolded one, because over-pinning is invisible while
     * configuring on the larger screen — which is the only reason the settings note exists.
     */
    val coverWidth = 460.dp
    val minScrollRoom = 200.dp

    /**
     * How far a swipe has to travel to switch sets. Half a key's height again, so a thumb that
     * wobbles on its way to a key does not take the bar somewhere else.
     */
    val switchDistance = 24.dp

    /**
     * The dots along the top edge that show which set is in use, and the band added above the keys
     * to hold them. Fitted into the band the keys already sit in, the dots crowd the keys' top edge
     * and read as part of them. The band is added only with two or more sets, so a bar
     * with one set takes no more from the terminal than it did.
     *
     * [setMarkTop] centres the dots between the bar's top edge and the keys: pushed up against the
     * edge, they read as stuck to it rather than as belonging to the bar.
     */
    val setMarkSize = 3.dp
    val setMarkGap = 5.dp
    val setMarkTop = 2.5.dp
    val setMarkBand = 2.dp

    /** Between the bottom of the set's name and the top of the bar it floats above. */
    val setNameGap = 8.dp

    /**
     * How long a group's slot is held before a slide picks from its popup. Shorter and an ordinary
     * swipe or scroll that pauses on the way would open it; longer and the slide stops feeling like
     * one gesture.
     */
    val groupHoldMillis = 250L

    /** The triangle that marks a group, and how far it sits in from the face's corner. */
    val groupMarkSize = 5.dp
    val groupMarkInset = 4.dp

    /** Between the popup's bottom and the bar's top, and the least it keeps from the screen's edges. */
    val popupGap = 8.dp
    val popupMargin = 8.dp
    val popupPadding = 4.dp
    val popupRadius = 16.dp

    /** How far the popup rises as it opens, and how long it takes. */
    val popupRise = 6.dp
    val popupRiseMillis = 80

    /**
     * How a label is drawn, and what anything measuring one has to measure it with.
     *
     * The face is bundled rather than `FontFamily.Monospace`, which resolves to the platform's
     * monospace face and then falls through to whatever the vendor supplies for a symbol. On a
     * Galaxy Fold that put the four arrows in three different faces — `→` came from Samsung's own
     * `SECFallback`, `↑` and `↓` from `OneUISans`, and only `←` reached Noto — so `→` was drawn
     * half again as long and twice as thick as the `←` beside it. A bundled face is also properly
     * monospaced across the symbols, which is what lets [keyWidth] work from a character count.
     *
     * Letter spacing is spelled out because passing a style to a Material `Text` replaces
     * `LocalTextStyle` rather than merging with it. The 0.5sp is what `bodyLarge` was handing down
     * before, and leaving it off would quietly narrow every key by half a dp per character.
     */
    @Composable
    fun labelStyle(): TextStyle = TextStyle(
        fontSize = 13.sp,
        fontFamily = labelFamily(),
        letterSpacing = 0.5.sp,
    )

    @Composable
    private fun labelFamily(): FontFamily {
        val assets = LocalContext.current.assets
        // Not wrapped in a catch: Compose resolves an asset font lazily at draw time, so nothing
        // throws here to catch, and a fallback that cannot fire only implies a safety net there is
        // no room for. A wrong asset path shows up as the wrong face on the first frame.
        return remember(assets) { FontFamily(Font(TerminalFont.JetBrainsMono.regularAsset, assets)) }
    }

    /** How wide a key ends up, given what its label measured to in [labelStyle]. */
    fun keyWidth(label: String, textWidth: Dp): Dp = if (label.length == 1) keyHeight else maxOf(keyMinWidth, textWidth + labelPadding * 2)

    /** What the pinned keys and the gaps between them claim. */
    fun pinnedRun(widths: List<Dp>): Dp = if (widths.isEmpty()) 0.dp else widths.reduce(Dp::plus) + keyGap * (widths.size - 1)

    /**
     * What is left over for the run that scrolls.
     *
     * The divider is only drawn when something is pinned, so with nothing pinned its block is not
     * taken out — otherwise an empty right-hand side would read as 21dp narrower than it is.
     */
    fun scrollRoom(coverWidth: Dp, pinnedRun: Dp): Dp = coverWidth - edgePadding * 2 - (if (pinnedRun > 0.dp) dividerBlock else 0.dp) - pinnedRun
}

private val ROW_HEIGHT = AuxiliaryKeyBarMetrics.rowHeight
private val EDGE_PADDING = AuxiliaryKeyBarMetrics.edgePadding
private val KEY_GAP = AuxiliaryKeyBarMetrics.keyGap
private val KEY_HEIGHT = AuxiliaryKeyBarMetrics.keyHeight
private val KEY_MIN_WIDTH = AuxiliaryKeyBarMetrics.keyMinWidth
private val KEY_RADIUS = AuxiliaryKeyBarMetrics.keyRadius
private val LABEL_PADDING = AuxiliaryKeyBarMetrics.labelPadding
private val FADE_WIDTH = AuxiliaryKeyBarMetrics.fadeWidth
private val DIVIDER_MARGIN = AuxiliaryKeyBarMetrics.dividerMargin
private val DIVIDER_HEIGHT = AuxiliaryKeyBarMetrics.dividerHeight
private val SET_MARK_SIZE = AuxiliaryKeyBarMetrics.setMarkSize
private val SET_MARK_GAP = AuxiliaryKeyBarMetrics.setMarkGap
private val SET_MARK_TOP = AuxiliaryKeyBarMetrics.setMarkTop
private val SET_MARK_BAND = AuxiliaryKeyBarMetrics.setMarkBand
private val SET_NAME_GAP = AuxiliaryKeyBarMetrics.setNameGap
private val GROUP_MARK_SIZE = AuxiliaryKeyBarMetrics.groupMarkSize
private val GROUP_MARK_INSET = AuxiliaryKeyBarMetrics.groupMarkInset
private val POPUP_GAP = AuxiliaryKeyBarMetrics.popupGap
private val POPUP_MARGIN = AuxiliaryKeyBarMetrics.popupMargin
private val POPUP_PADDING = AuxiliaryKeyBarMetrics.popupPadding
private val POPUP_SHAPE = RoundedCornerShape(AuxiliaryKeyBarMetrics.popupRadius)
private val POPUP_RISE = AuxiliaryKeyBarMetrics.popupRise
private val POPUP_RISE_MILLIS = AuxiliaryKeyBarMetrics.popupRiseMillis
private val POPUP_ELEVATION = 12.dp
private const val SLIDE_MILLIS = 180
private const val NAME_MILLIS = 650L
