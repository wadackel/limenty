package dev.wadackel.limenty.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyMotion
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where a [LimentySheet] stands, and the way to close it: [hide] lowers it out of sight before its
 * caller takes it away, which taking it away at once would not leave time for.
 */
@Stable
class LimentySheetState internal constructor(private val scope: CoroutineScope) {
    /** How far below its open place the sheet is, in pixels; NaN until it has been measured. */
    internal var offset by mutableFloatStateOf(Float.NaN)
        private set

    internal var height by mutableFloatStateOf(0f)
        private set

    internal var hiding by mutableStateOf(false)
        private set

    private var motion: Job? = null

    /** How much of the sheet shows, from 0 lowered out of sight to 1 open. */
    internal val shown: Float get() = if (offset.isNaN() || height <= 0f) 0f else 1f - offset / height

    /** Lowers the sheet out of sight, then runs [then]. Later calls while it goes are ignored. */
    fun hide(then: () -> Unit) {
        if (hiding) return
        hiding = true
        motion?.cancel()
        motion = scope.launch {
            if (!offset.isNaN()) {
                animate(offset, height, animationSpec = tween(LimentyMotion.TRANSITION_MILLIS, easing = FastOutLinearInEasing)) { value, _ ->
                    offset = value
                }
            }
            then()
        }
    }

    internal fun measured(newHeight: Float) {
        height = newHeight
        offset = if (offset.isNaN()) newHeight else offset.coerceAtMost(newHeight)
    }

    internal fun rise() = moveTo(0f, tween(LimentyMotion.TRANSITION_MILLIS, easing = LinearOutSlowInEasing))

    /** Back to open, carrying on at the speed the finger let go at. */
    internal fun settle(velocity: Float) = moveTo(0f, spring(stiffness = SETTLE_STIFFNESS), velocity)

    /** Follows the finger, taking over from whatever the sheet was doing; returns what it used. */
    internal fun dragBy(delta: Float): Float {
        if (hiding || offset.isNaN()) return 0f
        motion?.cancel()
        val before = offset
        offset = (before + delta).coerceIn(0f, height)
        return offset - before
    }

    private fun moveTo(target: Float, spec: AnimationSpec<Float>, velocity: Float = 0f) {
        if (hiding || offset.isNaN()) return
        motion?.cancel()
        motion = scope.launch {
            animate(offset, target, velocity, spec) { value, _ -> offset = value }
        }
    }
}

@Composable
fun rememberLimentySheetState(): LimentySheetState {
    val scope = rememberCoroutineScope()
    return remember { LimentySheetState(scope) }
}

/**
 * A panel that rises from the bottom edge, for editing something in place of the screen behind it.
 * It rises when it opens, is lowered by [LimentySheetState.hide], and follows a finger pulling it
 * down — from its header, or from its content once that is scrolled to the top.
 *
 * Its own window rather than Material's bottom sheet, for two reasons. The stock one decides for
 * itself what a swipe down or a tap outside does, and here both have to ask before throwing an edit
 * away, which is [onCloseRequest]'s to decide: a sheet pulled far enough down springs back up
 * unless that hides it. And a question it asks — discard these changes? — is drawn inside it
 * through [overlay], so no box is ever raised over another box.
 *
 * As wide as a box at most, so on the unfolded display it sits under the middle of the screen
 * rather than stretching across both halves.
 */
@Composable
fun LimentySheet(
    state: LimentySheetState,
    /** Back, which the caller may take to mean "one page up" rather than "close". */
    onDismissRequest: () -> Unit,
    /** A tap outside the sheet, or the sheet pulled down far enough to close. */
    onCloseRequest: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val flingToClose = with(LocalDensity.current) { CLOSING_FLING.toPx() }
    val release: (Float) -> Unit = { velocity ->
        if (state.offset > 0f && (velocity > flingToClose || state.offset > state.height * CLOSING_SHARE)) onCloseRequest()
        // Asked to close and told no, as when there are changes to keep: back up to ask.
        state.settle(velocity)
    }
    val nestedScroll = remember(state, release) { SheetNestedScroll(state, release) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        // The platform's dim comes and goes in a frame; the scrim drawn below follows the sheet.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        LaunchedEffect(state.height > 0f) { if (state.height > 0f) state.rise() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.shown }
                .background(LimentyColors.Scrim)
                .pointerInput(onCloseRequest) { detectTapGestures { onCloseRequest() } },
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = LimentyDialogMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight - SHEET_TOP_GAP)
                    .onSizeChanged { state.measured(it.height.toFloat()) }
                    .graphicsLayer { translationY = if (state.offset.isNaN()) size.height else state.offset }
                    .clip(SHEET_SHAPE)
                    .background(LimentyColors.Surface3)
                    // A tap on the sheet's own background is not a tap outside it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .nestedScroll(nestedScroll)
                    .draggable(
                        state = rememberDraggableState { delta -> state.dragBy(delta) },
                        orientation = Orientation.Vertical,
                        enabled = !state.hiding,
                        onDragStopped = { velocity -> release(velocity) },
                    ),
            ) {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                    Handle()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = SHEET_PADDING - LimentyTextButtonInset)
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = header,
                    )
                    Column(modifier = Modifier.weight(1f, fill = false), content = content)
                }
                overlay?.invoke(this)
            }
        }
    }
}

/** The mark that says the sheet can be pulled down. */
@Composable
private fun ColumnScope.Handle() {
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 10.dp, bottom = 2.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(LimentyColors.Line),
    )
}

/**
 * Hands the sheet what its content does not use: a pull down past the top of the content lowers
 * the sheet, and a push up raises a lowered sheet before the content scrolls.
 */
private class SheetNestedScroll(
    private val state: LimentySheetState,
    private val release: (Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = if (available.y < 0f && source == NestedScrollSource.UserInput && state.offset > 0f) {
        Offset(0f, state.dragBy(available.y))
    } else {
        Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, state.dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.offset <= 0f) return Velocity.Zero
        release(available.y)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (state.offset <= 0f) return Velocity.Zero
        release(available.y)
        return available
    }
}

/** The sheet's title, between what its header holds at either end. */
@Composable
fun RowScope.LimentySheetTitle(text: String) {
    Text(
        text,
        style = LimentyText.Heading,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * A question the sheet asks about itself, drawn over it rather than in a box of its own: a box
 * raised over the sheet is the stacking the sheet is there to end.
 */
@Composable
fun BoxScope.LimentySheetQuestion(
    title: String,
    body: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(QUESTION_SCRIM)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(LimentySpacing.FloatingPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(LimentyShapes.Floating)
                .background(LimentyColors.Surface4)
                .padding(LimentySpacing.FloatingPadding),
            verticalArrangement = Arrangement.spacedBy(LimentyDialogGap),
        ) {
            Text(title, style = LimentyText.Heading)
            Text(body, style = LimentyText.Body, color = LimentyColors.Text2)
            LimentyDialogActions(actions)
        }
    }
}

/** Between the sheet's content and its sides. */
val LimentySheetPadding = 20.dp

private val SHEET_PADDING = LimentySheetPadding
private val SHEET_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** What the sheet leaves of the screen above it at its tallest, so it still reads as over something. */
private val SHEET_TOP_GAP = 48.dp

/** Over the sheet, behind a question it asks; lighter than [LimentyColors.Scrim] behind the sheet. */
private val QUESTION_SCRIM = Color(0x99060608)

/** Faster than this, a finger letting go downwards closes the sheet however little it moved it. */
private val CLOSING_FLING = 1000.dp

/** Pulled down past this share of its height, the sheet closes when let go. */
private const val CLOSING_SHARE = 0.4f

private const val SETTLE_STIFFNESS = 800f
