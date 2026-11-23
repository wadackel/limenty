package dev.wadackel.limenty.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.Magnifier
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
import dev.wadackel.limenty.core.input.TerminalInputEvent
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.CellFlags
import dev.wadackel.limenty.core.terminal.MouseEncoder
import dev.wadackel.limenty.core.terminal.MouseTracking
import dev.wadackel.limenty.core.terminal.RowSelection
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalTypography
import dev.wadackel.limenty.core.terminal.WebLinks
import dev.wadackel.limenty.core.terminal.graphics.CellPixelSize
import dev.wadackel.limenty.input.TerminalInputView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The terminal surface: draws the session's screen and owns the touch gestures over it.
 *
 * Rendering and input live on one view because they address the same grid — a tap has to become a
 * cell coordinate, and a selection has to be drawn where the finger is. Splitting them would mean
 * keeping two copies of the geometry in step.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context) : TerminalInputView(context) {

    private val renderLog = TermLog.logger(LogTags.RENDER)
    private val resizeLog = TermLog.logger(LogTags.RESIZE)
    private val gestureLog = TermLog.logger(LogTags.GESTURE)

    private val renderer = TerminalRenderer()

    /**
     * Where image decoding runs, on the thread that draws.
     *
     * The scope outlives a detach rather than being cancelled with one: a view can be taken out
     * of the hierarchy and put back, and a cancelled scope would leave every later image
     * undecoded with nothing to say why. Detaching drops the work in flight instead.
     */
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Decoded images for the session on screen; another session's serials mean nothing here. */
    private val graphicsCache = GraphicsBitmapCache(decodeScope, onDecoded = ::invalidate)

    var session: TerminalSession? = null
        set(value) {
            if (field !== value) {
                graphicsCache.clear()
                // A repeat still running would carry on into whichever session replaced this one.
                releaseHeldPress()
                arrowDrop.close(animate = false)
            }
            field = value
            invalidate()
        }

    var palette: TerminalPalette = TerminalPalette.Default
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var typography: TerminalTypography = TerminalTypography()
        set(value) {
            if (field == value) return
            field = value
            remeasure()
        }

    var selection: SelectionRange? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Raised when a pinch changes the font size, so the setting can be persisted. */
    var onFontSizeChanged: (Float) -> Unit = {}

    var onSelectionChanged: (SelectionRange?) -> Unit = {}

    /**
     * Where the selection is in the view, raised from drawing whenever that changes.
     *
     * Not from [onSelectionChanged]: output arriving and the scrollback moving carry the selection
     * with the text without changing the range, and a toolbar placed from the range alone was left
     * behind. Every one of those redraws, so drawing is the one place that sees them all.
     */
    var onSelectionBoundsChanged: (SelectionBounds?) -> Unit = {}

    private var reportedBounds: SelectionBounds? = null

    var gestures: GestureBindings = GestureBindings.Initial

    /** Whether a double tap held and dragged up or down counts as a pinch. */
    var doubleTapDragZoom: Boolean
        get() = scaleDetector.isQuickScaleEnabled
        set(value) {
            scaleDetector.isQuickScaleEnabled = value
        }

    /** Raised for a bound behaviour the view cannot carry out itself, such as Paste. */
    var onGestureBehaviour: (GestureBehaviour) -> Unit = {}

    /** Bytes destined for the remote's mouse reporting; empty until an application enables it. */
    var onMouseReport: (ByteArray) -> Unit = {}

    internal var metrics: TerminalMetrics = TerminalMetrics(1f, 1f, 1f, 1f, 0f)
        private set

    private var scrollRemainder = 0f

    init {
        isFocusableInTouchMode = true
        // A key from a physical keyboard takes the window out of touch mode, and Android then lays
        // its translucent focus highlight over the focused view — here, the whole terminal, which
        // turned grey the moment anyone typed on a keyboard.
        defaultFocusHighlightEnabled = false
        onComposingChanged = { invalidate() }
        remeasure()
    }

    fun setTypefaces(regular: Typeface, bold: Typeface) {
        renderer.typeface = regular
        renderer.boldTypeface = bold
        remeasure()
    }

    private fun remeasure() {
        metrics = TerminalMetrics.measure(renderer.measurePaint(), typography, resources.displayMetrics.density)
        applyViewportSize()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resizeLog.i("view.size", "width" to width, "height" to height)
        applyViewportSize()
    }

    /**
     * Converts the viewport into a column/row count and hands it to the session.
     *
     * Every size-affecting change — fold, rotation, split screen, keyboard, font size — funnels
     * through here, so there is exactly one path from pixels to a PTY window-change.
     */
    private fun applyViewportSize() {
        val current = session ?: return
        if (width == 0 || height == 0) return
        val columns = metrics.columnsFor(width.toFloat())
        val rows = metrics.rowsFor(height.toFloat())
        // A font change can keep the same grid while every cell grows; the remote still has to
        // hear the new pixel size, or images sized in pixels come out at the old scale.
        val cell = CellPixelSize(metrics.cellWidth.roundToInt().coerceAtLeast(1), metrics.cellHeight.roundToInt().coerceAtLeast(1))
        if (columns == current.emulator.columns && rows == current.emulator.rows && cell == current.emulator.cellPixelSize) return
        resizeLog.i("viewport.apply", "cols" to columns, "rows" to rows, "cellWidthPx" to cell.width, "cellHeightPx" to cell.height)
        // Under the lock throughout, so output arriving between the two reads cannot be taken
        // for something the resize did.
        val lost = synchronized(current.lock) {
            val before = selection?.let { selectedText() }
            current.resize(columns, rows, cell.width, cell.height)
            if (before == null) return@synchronized false
            // A range past a narrower grid would put its handle off the view. Clamped before the
            // second read, because pulling an end left onto the grid can take in text it never
            // covered, and that has to count as the selection changing.
            selection = selection?.clampedTo(columns)
            selectionAnchor = selectionAnchor?.clampedTo(columns)
            selectedText() != before
        }
        // Nothing reflows: a narrower screen cuts rows off and the cut text is gone, so a range
        // over it now reads as something else. Copy would hand that over as if it were what the
        // user picked; dropping the selection is the honest answer.
        if (lost) {
            releaseHandle()
            handleHeld = false
            selection = null
            onSelectionChanged(null)
            resizeLog.i("selection.dropped")
        }
    }

    override fun onDraw(canvas: Canvas) {
        val current = session
        if (current == null) {
            canvas.drawColor(palette.background)
            reportSelectionBounds(null)
            return
        }
        val composing = buffer.composing
        val bounds = synchronized(current.lock) {
            updateBlinking(hasBlinkingCells(current))
            val scrollOffset = current.scrollOffset.value
            val shifted = selection?.shiftedTo(current.linesScrolled())
            renderer.draw(
                canvas = canvas,
                buffer = current.emulator.buffer,
                metrics = metrics,
                palette = palette,
                cursor = current.emulator.cursor,
                scrollOffset = scrollOffset,
                selection = shifted,
                // The preedit sits where the cursor is, so the block cursor is suppressed while
                // one is in flight rather than drawn underneath it.
                showCursor = hasFocus() && !composing.active,
                graphics = current.emulator.graphics,
                bitmaps = graphicsCache,
            )
            if (composing.active && current.scrollOffset.value == 0) {
                renderer.drawPreedit(
                    canvas = canvas,
                    text = composing.text,
                    caret = composing.cursor,
                    cursor = current.emulator.cursor,
                    metrics = metrics,
                    palette = palette,
                )
            }
            shifted?.let { SelectionGeometry.selectionBounds(it, scrollOffset, current.emulator.columns, metrics) }
        }
        // Outside the session lock: the listener updates UI state, which has no business holding it.
        reportSelectionBounds(bounds)
        if (arrowDrop.draw(canvas, metrics.density)) postInvalidateOnAnimation()
        // Magnifier copies whatever the window last showed, and the frame this draw produces is
        // not on the screen yet; refreshing straight from here would magnify the previous one.
        if (magnifier != null) post { magnifier?.update() }
    }

    /** Only on a change, so a listener that redraws the UI does not ask for another frame forever. */
    private fun reportSelectionBounds(bounds: SelectionBounds?) {
        if (bounds == reportedBounds) return
        reportedBounds = bounds
        onSelectionBoundsChanged(bounds)
    }

    /** Scans the visible rows only; scrollback that is off screen cannot be blinking at anyone. */
    private fun hasBlinkingCells(active: TerminalSession): Boolean {
        val buffer = active.emulator.buffer
        val offset = active.scrollOffset.value
        for (screenRow in 0 until buffer.rows) {
            val row = buffer.line(screenRow - offset) ?: continue
            for (column in 0 until row.columns) {
                if (row.flagsAt(column) and CellFlags.BLINK != 0) return true
            }
        }
        return false
    }

    // --- gestures ---

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var accumulated = 1f

            /** The pinch so far, for a discrete behaviour: zoom resets its own figure every step. */
            private var discreteScale = 1f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulated = 1f
                discreteScale = 1f
                pinchBehaviourFired = false
                return gestures[Gesture.PinchIn] !is GestureBehaviour.None ||
                    gestures[Gesture.PinchOut] !is GestureBehaviour.None
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Every frame counts, whichever way it went: counting only the frames that go the
                // non-zoom way let the jitter of a finished zoom add up to a pinch the other way.
                discreteScale *= detector.scaleFactor
                val gesture = if (detector.scaleFactor < 1f) Gesture.PinchIn else Gesture.PinchOut
                if (gestures[gesture] !is GestureBehaviour.FontZoom) {
                    // Settings offers every behaviour for a pinch, and only zoom used to do
                    // anything. The rest fire once per pinch, as they do once per swipe, and only
                    // after a clear change in size so a first jittery frame cannot pick the
                    // direction.
                    if (pinchBehaviourFired) return true
                    pendingPinch = when {
                        discreteScale <= PINCH_IN_THRESHOLD -> Gesture.PinchIn
                        discreteScale >= PINCH_OUT_THRESHOLD -> Gesture.PinchOut
                        else -> return true
                    }
                    pinchBehaviourFired = true
                    return true
                }
                accumulated *= detector.scaleFactor
                // Applied in whole steps: a continuously scaled font would re-measure the grid on
                // every frame and send a window-change to the remote with it.
                val step = (typography.fontSizeSp * accumulated) - typography.fontSizeSp
                if (abs(step) < 1f) return true
                val updated = typography.withFontSize(typography.fontSizeSp + step)
                accumulated = 1f
                if (updated.fontSizeSp != typography.fontSizeSp) {
                    typography = updated
                    onFontSizeChanged(updated.fontSizeSp)
                    gestureLog.i("pinch.zoom", "fontSizeSp" to updated.fontSizeSp)
                }
                return true
            }
        },
    ).apply {
        // The platform turns this on by default, and then a tap followed straight away by a
        // scroll reads as a pinch: one finger changes the font size with nobody meaning it to.
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                requestFocus()
                // Releasing a handle without moving it keeps the selection rather than acting as
                // the tap that dismisses one.
                if (handleGrabbed) return true
                return perform(Gesture.Tap, event)
            }

            override fun onDoubleTap(event: MotionEvent): Boolean = perform(Gesture.DoubleTap, event)

            override fun onScroll(
                start: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // A drag on a selection handle adjusts the selection, whatever the swipe is bound
                // to: the handle is under the finger, and scrolling out from under it would make
                // the selection impossible to finish.
                if (selectionAnchor != null) {
                    extendSelection(current)
                    autoScrollForSelection(current)
                    return true
                }

                val fingers = current.pointerCount
                val vertical = abs(distanceY) >= abs(distanceX)
                val gesture = when {
                    fingers >= 2 && vertical -> if (distanceY > 0) Gesture.TwoFingerSwipeUp else Gesture.TwoFingerSwipeDown
                    fingers >= 2 -> if (distanceX > 0) Gesture.TwoFingerSwipeLeft else Gesture.TwoFingerSwipeRight
                    vertical -> if (distanceY > 0) Gesture.SwipeUp else Gesture.SwipeDown
                    else -> if (distanceX > 0) Gesture.SwipeLeft else Gesture.SwipeRight
                }
                return when (val behaviour = gestures[gesture]) {
                    is GestureBehaviour.Scroll -> {
                        dragScroll(distanceY, current)
                        true
                    }
                    is GestureBehaviour.SelectText -> {
                        extendSelection(current)
                        true
                    }
                    // A discrete behaviour on a swipe fires once per drag, not per frame.
                    else -> if (!dragBehaviourFired) {
                        dragBehaviourFired = true
                        apply(behaviour, current)
                    } else {
                        true
                    }
                }
            }

            override fun onLongPress(event: MotionEvent) {
                // A handle held still is a slow drag about to start, not a new selection. The
                // detector drops every move after a long press, so the drag is picked up in
                // onTouchEvent instead.
                if (handleGrabbed) {
                    handleHeld = true
                    return
                }
                if (gestures[Gesture.LongPress] !is GestureBehaviour.SelectText) {
                    perform(Gesture.LongPress, event)
                    return
                }
                // Whether this selects or moves the cursor is not known until the finger lifts or
                // moves; showing the selection now and withdrawing it on a move would flicker.
                gestureLog.d("gesture", "gesture" to Gesture.LongPress.name, "behaviour" to "held")
                heldPress = PointF(event.x, event.y)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                arrowDrop.open(event.x, event.y)
                invalidate()
            }
        },
    )

    /** True once a discrete behaviour has fired for the current drag. */
    private var dragBehaviourFired = false

    /** Where a long press bound to selection landed, until the finger lifts or moves away. */
    private var heldPress: PointF? = null

    /**
     * The arrow the held press is repeating, or null while it rests inside the dead zone.
     *
     * It follows the finger: back inside the dead zone it stops, and moved to another side it
     * switches. A thumb drifting while it holds stays inside the dead zone, which the drop under it
     * shows, rather than sending keys nobody meant.
     */
    private var arrowKey: TerminalKey? = null

    /** The last arrow the drag sent, for the log; [arrowKey] is null again once it stops. */
    private var lastArrowKey: TerminalKey? = null

    /** Every key the drag has sent: the log reports it, and the lift selects only when it is zero. */
    private var arrowKeysSent = 0

    private val arrowDrop = ArrowDrop()

    /** Whether the drop is showing for a held press, apart from any fade still running. */
    internal val arrowDropOpen: Boolean get() = arrowDrop.isOpen

    /** Set when a long press fires on a grabbed handle, whose drag the detector then drops. */
    private var handleHeld = false

    // Not the view's own postDelayed: a view outside a window queues those until it is attached,
    // so the repeat would never run for a view driven without one.
    private val repeatHandler = Handler(Looper.getMainLooper())

    private val arrowRepeat = object : Runnable {
        override fun run() {
            sendArrow()
            repeatHandler.postDelayed(this, ARROW_REPEAT_INTERVAL_MILLIS)
        }
    }

    private fun releaseHeldPress() {
        repeatHandler.removeCallbacks(arrowRepeat)
        if (arrowKeysSent > 0) gestureLog.i("longpress.arrows", "key" to lastArrowKey?.name, "keys" to arrowKeysSent)
        if (arrowDrop.isOpen) {
            arrowDrop.close()
            invalidate()
        }
        heldPress = null
        arrowKey = null
        lastArrowKey = null
        arrowKeysSent = 0
        handleHeld = false
    }

    /** Sends the arrow the held press points at, repeating it like a held key until it changes. */
    private fun dragArrows(event: MotionEvent) {
        val origin = heldPress ?: return
        val dx = event.x - origin.x
        val dy = event.y - origin.y
        val next = ArrowDropGeometry.aim(dx, dy, arrowKey, ArrowDropGeometry.deadZone(metrics))
        if (next != arrowKey) {
            repeatHandler.removeCallbacks(arrowRepeat)
            arrowKey = next
            if (next != null) {
                sendArrow()
                repeatHandler.postDelayed(arrowRepeat, ARROW_REPEAT_DELAY_MILLIS)
            }
        }
        arrowDrop.aimAt(dx, dy, next, metrics)
        invalidate()
    }

    private fun sendArrow() {
        val key = arrowKey ?: return
        lastArrowKey = key
        arrowKeysSent++
        onInputEvents(listOf(TerminalInputEvent.Key(key)))
    }

    private var pinchActive = false

    /** True once a discrete behaviour has fired for the current pinch. */
    private var pinchBehaviourFired = false

    /** A pinch that crossed its threshold, dispatched once the detector has returned. */
    private var pendingPinch: Gesture? = null

    /**
     * Recognises a two-finger tap, which no `GestureDetector` callback reports.
     *
     * Offering it in settings without detecting it would make the binding silently do nothing —
     * the opposite of the closed-set design, where anything selectable is something the app can
     * actually see.
     */
    private var twoFingerDownAt = 0L
    private var sawSecondPointer = false

    private fun trackTwoFingerTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFingerDownAt = event.eventTime
                sawSecondPointer = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) sawSecondPointer = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val quick = event.eventTime - twoFingerDownAt < TWO_FINGER_TAP_TIMEOUT_MILLIS
                if (sawSecondPointer && quick && !scaleDetector.isInProgress && !dragBehaviourFired) {
                    perform(Gesture.TwoFingerTap, event)
                }
                sawSecondPointer = false
            }
        }
    }

    /**
     * Drives the blink phase, and only while something on screen is actually blinking — an idle
     * terminal must not wake up twice a second for a frame that would be identical.
     */
    private val blinkTick = object : Runnable {
        override fun run() {
            renderer.blinkOn = !renderer.blinkOn
            invalidate()
            postDelayed(this, BLINK_PERIOD_MILLIS)
        }
    }

    private var blinking = false

    private fun updateBlinking(anyBlinkingCells: Boolean) {
        if (anyBlinkingCells == blinking) return
        blinking = anyBlinkingCells
        if (anyBlinkingCells) {
            postDelayed(blinkTick, BLINK_PERIOD_MILLIS)
        } else {
            removeCallbacks(blinkTick)
            renderer.blinkOn = true
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkTick)
        releaseHeldPress()
        arrowDrop.close(animate = false)
        dismissMagnifier()
        decodeScope.coroutineContext.cancelChildren()
        // The decoded pixels stay. Dropping them contradicted the scope above, which is kept alive
        // precisely so a view taken out of the hierarchy and put back does not start from nothing.
        graphicsCache.dropInFlight()
    }

    private fun perform(gesture: Gesture, event: MotionEvent): Boolean {
        val behaviour = gestures[gesture]
        // The id and a count, never the steps: a step can be text typed at a password prompt.
        val fields = listOfNotNull(
            "gesture" to gesture.name,
            "behaviour" to behaviour.id,
            (behaviour as? GestureBehaviour.SendKeys)?.let { "steps" to it.steps.size },
        )
        gestureLog.d("gesture", *fields.toTypedArray())
        return apply(behaviour, event)
    }

    private fun apply(behaviour: GestureBehaviour, event: MotionEvent): Boolean = when (behaviour) {
        is GestureBehaviour.None -> false
        is GestureBehaviour.ShowKeyboard -> {
            showSoftKeyboard()
            true
        }
        is GestureBehaviour.HideKeyboard -> {
            hideSoftKeyboard()
            true
        }
        is GestureBehaviour.ToggleKeyboard -> {
            toggleSoftKeyboard()
            true
        }
        is GestureBehaviour.SendKeys -> {
            val events = behaviour.steps.mapNotNull { it.toEvent() }
            if (events.isNotEmpty()) onInputEvents(events)
            events.isNotEmpty()
        }
        is GestureBehaviour.SelectText -> {
            beginSelectionAt(event.x, event.y)
            true
        }
        // Scroll and zoom are driven by the drag itself; reaching them here means the gesture
        // that was bound to them cannot supply a drag, so there is nothing to do.
        is GestureBehaviour.Scroll, is GestureBehaviour.FontZoom -> false
        is GestureBehaviour.Paste, is GestureBehaviour.Copy -> {
            onGestureBehaviour(behaviour)
            true
        }
    }

    private fun dragScroll(distanceY: Float, at: MotionEvent) {
        val active = session ?: return
        // A downward drag reveals older output: the finger moves the content, not the viewport.
        scrollRemainder += distanceY
        val lines = (scrollRemainder / metrics.cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * metrics.cellHeight

        if (active.modes.value.mouse != MouseTracking.None) {
            // The remote asked to receive the mouse, so the drag is its scroll to interpret. This
            // is the only way to reach the scrollback of a full-screen application: tmux and an
            // editor both run on the alternate screen, where there is none of our own to move.
            val button = if (lines < 0) MouseEncoder.Button.WheelUp else MouseEncoder.Button.WheelDown
            repeat(min(abs(lines), MAX_WHEEL_PER_FRAME)) {
                reportMouse(at, button, MouseEncoder.Action.Press)
            }
            return
        }

        active.scrollBy(-lines)
        invalidate()
    }

    /** Selects the link or else the word under a point, which is what a long press means. */
    internal fun beginSelectionAt(x: Float, y: Float) {
        val active = session ?: return
        val line = lineAt(y, active)
        val column = columnAt(x)
        val range = (linkAt(active, line, column) ?: wordAt(active, line, column))
            .copy(scrolledAt = active.linesScrolled())
        selectionAnchor = range
        selection = range
        onSelectionChanged(range)
        invalidate()
    }

    /** Moves the free end of a selection to the finger while a handle is being dragged. */
    private fun extendSelection(event: MotionEvent) {
        val active = session ?: return
        // The anchor may predate output that has scrolled since the long press, so it is read in
        // today's coordinates before the moving end is attached to it.
        val scrolled = active.linesScrolled()
        val anchor = selectionAnchor?.shiftedTo(scrolled) ?: return
        val x = SelectionGeometry.carry(event.x, grabOffsetX, width.toFloat())
        val y = SelectionGeometry.carry(event.y, grabOffsetY, height.toFloat())
        val range = SelectionRange(
            startLine = anchor.startLine,
            startColumn = anchor.startColumn,
            endLine = lineAt(y, active),
            endColumn = columnAt(x),
            scrolledAt = scrolled,
        )
        selection = range
        onSelectionChanged(range)
        if (handleGrabbed) magnify(x, screenRowAt(y))
    }

    private var selectionAnchor: SelectionRange? = null

    /** Shown while a handle is dragged, so the cell under the finger can be seen. */
    private var magnifier: Magnifier? = null

    /**
     * Magnifies the row the dragged end is on, around where the end is.
     *
     * The source is pinned to the middle of the row rather than following the finger up and down,
     * which would slide the text around inside the magnifier without the end moving at all.
     */
    private fun magnify(x: Float, row: Int) {
        // A view with no window has no surface to copy, and building one anyway is the platform
        // allocating native layers for nothing — which is every view the instrumentation tests make.
        if (!isAttachedToWindow) return
        val shown = magnifier ?: Magnifier.Builder(this)
            .setSize(
                SelectionGeometry.magnifierWidth(metrics).roundToInt(),
                SelectionGeometry.magnifierHeight(metrics).roundToInt(),
            )
            .setInitialZoom(SelectionGeometry.magnifierZoom(metrics))
            .setDefaultSourceToMagnifierOffset(0, SelectionGeometry.magnifierLift(metrics).roundToInt())
            .build()
            .also {
                magnifier = it
                gestureLog.d("magnifier.show", "zoom" to SelectionGeometry.magnifierZoom(metrics))
            }
        shown.show(x.coerceIn(0f, width.toFloat()), (row + 0.5f) * metrics.cellHeight)
    }

    private fun dismissMagnifier() {
        magnifier?.dismiss()
        magnifier = null
    }

    /** Set when a touch takes hold of a handle, so the tap that follows is not a plain tap. */
    private var handleGrabbed = false

    /** From the finger to the grabbed end, kept for the whole drag; zero when nothing is held. */
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    /**
     * Lets go of a handle without touching the selection itself.
     *
     * A pinch has to do this as well as a lift: its second finger ends the drag, and the finger
     * left behind would otherwise go on moving an end measured in cells the zoom has resized.
     */
    private fun releaseHandle() {
        dismissMagnifier()
        selectionAnchor = null
        handleGrabbed = false
        grabOffsetX = 0f
        grabOffsetY = 0f
    }

    /**
     * Takes hold of a selection handle the touch landed on, pinning the opposite end.
     *
     * A drag can change the range. Without this a selection can only ever be
     * whatever the long press guessed, and a wrong guess has to be redone from the beginning.
     */
    private fun grabHandle(event: MotionEvent, active: TerminalSession): Boolean {
        val current = selection?.shiftedTo(active.linesScrolled()) ?: return false
        // The drawn dot is smaller than a fingertip, so the target is widened to one.
        val reach = maxOf(
            SelectionGeometry.handleReach(metrics),
            ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        )
        val grab = SelectionGeometry.grabAt(
            x = event.x,
            y = event.y,
            selection = current,
            scrollOffset = active.scrollOffset.value,
            metrics = metrics,
            reach = reach,
        ) ?: return false
        selectionAnchor = grab.anchor
        grabOffsetX = grab.offsetX
        grabOffsetY = grab.offsetY
        selection = current
        return true
    }

    /**
     * Scrolls while the finger sits near an edge, so a selection can reach beyond the screen and
     * into the scrollback without the user having to let go.
     */
    private fun autoScrollForSelection(event: MotionEvent) {
        // The finger, not the end it carries, is what the user pushes against the edge. The carried
        // end sits above the finger, so testing it would scroll up early and scroll down late.
        val margin = metrics.cellHeight * AUTO_SCROLL_MARGIN_CELLS
        val lines = when {
            event.y < margin -> 1
            event.y > height - margin -> -1
            else -> 0
        }
        if (lines != 0) {
            session?.scrollBy(lines)
            invalidate()
        }
    }

    fun toggleSoftKeyboard() {
        val visible = rootWindowInsets
            ?.isVisible(android.view.WindowInsets.Type.ime()) == true
        if (visible) hideSoftKeyboard() else showSoftKeyboard()
    }

    /**
     * Physical pointers report to the remote; touch never does.
     *
     * The split is by tool type rather than by event source because a stylus and a trackball both
     * arrive as generic motion, and because treating a finger as a mouse would send every scroll
     * gesture into tmux instead of the scrollback.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isPointerDevice(event)) return super.onGenericMotionEvent(event)
        val active = session ?: return super.onGenericMotionEvent(event)

        return when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val button = if (vertical > 0) MouseEncoder.Button.WheelUp else MouseEncoder.Button.WheelDown
                if (vertical == 0f) {
                    false
                } else {
                    // A wheel with no application listening is the user's own scrollback request.
                    if (active.modes.value.mouse == MouseTracking.None) {
                        scrollLines(if (vertical > 0) 3 else -3)
                        true
                    } else {
                        reportMouse(event, button, MouseEncoder.Action.Press)
                    }
                }
            }
            MotionEvent.ACTION_HOVER_MOVE ->
                reportMouse(event, MouseEncoder.Button.None, MouseEncoder.Action.Motion)
            else -> super.onGenericMotionEvent(event)
        }
    }

    private fun isPointerDevice(event: MotionEvent): Boolean = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

    private fun reportMouse(
        event: MotionEvent,
        button: MouseEncoder.Button,
        action: MouseEncoder.Action,
    ): Boolean {
        val active = session ?: return false
        val bytes = MouseEncoder.encode(
            modes = active.modes.value,
            button = button,
            action = action,
            column = columnAt(event.x),
            row = screenRowAt(event.y),
            shift = event.metaState and KeyEvent.META_SHIFT_MASK != 0,
            alt = event.metaState and KeyEvent.META_ALT_MASK != 0,
            ctrl = event.metaState and KeyEvent.META_CTRL_MASK != 0,
        ) ?: return false
        gestureLog.d("mouse.report", "button" to button, "action" to action, "bytes" to bytes.size)
        onMouseReport(bytes)
        return true
    }

    private fun buttonFor(event: MotionEvent): MouseEncoder.Button = when {
        event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> MouseEncoder.Button.Right
        event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> MouseEncoder.Button.Middle
        event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 -> MouseEncoder.Button.Left
        // A move with nothing held during a touch-screen gesture is not a drag.
        event.actionMasked == MotionEvent.ACTION_MOVE -> MouseEncoder.Button.None
        else -> MouseEncoder.Button.Left
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackTwoFingerTap(event)
        if (isPointerDevice(event) && session?.modes?.value?.mouse !=
            MouseTracking.None
        ) {
            val handled = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> reportMouse(event, buttonFor(event), MouseEncoder.Action.Press)
                MotionEvent.ACTION_UP -> reportMouse(event, buttonFor(event), MouseEncoder.Action.Release)
                MotionEvent.ACTION_MOVE -> reportMouse(event, buttonFor(event), MouseEncoder.Action.Motion)
                else -> false
            }
            if (handled) {
                // The lift of a press that started before the remote enabled mouse tracking lands
                // here, and would otherwise leave its arrow repeating.
                releaseHeldPress()
                requestFocus()
                return true
            }
        }

        scaleDetector.onTouchEvent(event)
        pendingPinch?.let { gesture ->
            pendingPinch = null
            perform(gesture, event)
        }
        if (scaleDetector.isInProgress) {
            // The single-finger DOWN armed a long-press timer; without cancelling it, a slow
            // pinch starts a text selection underneath the zoom.
            if (!pinchActive) {
                pinchActive = true
                releaseHeldPress()
                releaseHandle()
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestureDetector.onTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            pinchActive = false
        }
        // A second finger ends the long press as far as the detector is concerned; without this
        // the lift that follows would still select the word under the first one.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) releaseHeldPress()
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (handleHeld) {
                extendSelection(event)
                autoScrollForSelection(event)
                return true
            }
            if (heldPress != null) {
                dragArrows(event)
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            releaseHeldPress()
            scrollRemainder = 0f
            dragBehaviourFired = false
            releaseHandle()
            handleGrabbed = session?.let { grabHandle(event, it) } == true
            // Touching anywhere else dismisses the selection, which is how a selection ends.
            if (!handleGrabbed && selection != null) {
                selection = null
                onSelectionChanged(null)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            heldPress?.let { press ->
                if (arrowKeysSent == 0) beginSelectionAt(press.x, press.y)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            releaseHeldPress()
            // The anchor is released on lift so the next drag scrolls again, while the selection
            // itself stays on screen for the toolbar to act on. Not releaseHandle(): the tap that
            // follows a lift is confirmed later and still needs handleGrabbed to tell it apart.
            selectionAnchor = null
            grabOffsetX = 0f
            grabOffsetY = 0f
            dismissMagnifier()
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun columnAt(x: Float): Int = (x / metrics.cellWidth).toInt().coerceIn(0, (session?.emulator?.columns ?: 1) - 1)

    private fun lineAt(y: Float, active: TerminalSession): Int = screenRowAt(y) - active.scrollOffset.value

    private fun screenRowAt(y: Float): Int = (y / metrics.cellHeight).toInt().coerceIn(0, (session?.emulator?.rows ?: 1) - 1)

    /** Selects the word under the press, which is what a long press means in every text UI. */
    private fun wordAt(active: TerminalSession, line: Int, column: Int): SelectionRange {
        val word = synchronized(active.lock) {
            active.emulator.buffer.line(line)?.let { RowSelection.wordAt(it, column) }
        } ?: return SelectionRange(line, column, line, column)
        return SelectionRange(line, word.first, line, word.last)
    }

    /** The whole link under the press, across the rows the terminal wrapped it onto. */
    private fun linkAt(active: TerminalSession, line: Int, column: Int): SelectionRange? {
        val link = synchronized(active.lock) {
            WebLinks.at(active.emulator.buffer, line, column)
        } ?: return null
        return SelectionRange(link.startLine, link.startColumn, link.endLine, link.endColumn)
    }

    /** The selection when it is exactly a web link, which is what Open acts on. */
    fun selectedLink(): String? {
        val active = session ?: return null
        val pending = selection ?: return null
        return synchronized(active.lock) {
            val range = pending.shiftedTo(active.linesScrolled()).normalized
            val span = WebLinks.Span(range.startLine, range.startColumn, range.endLine, range.endColumn)
            WebLinks.exact(active.emulator.buffer, span)
        }
    }

    /** The selected text, joined across wrapped lines so a copied command stays one command. */
    fun selectedText(): String {
        val active = session ?: return ""
        val pending = selection ?: return ""
        return synchronized(active.lock) {
            // Read the count under the same lock as the rows, so output arriving mid-copy cannot
            // shift the range between resolving it and reading it.
            val range = pending.shiftedTo(active.linesScrolled())
            val ordered = listOf(range.startLine to range.startColumn, range.endLine to range.endColumn)
                .sortedWith(compareBy({ it.first }, { it.second }))
            val (fromLine, fromColumn) = ordered.first()
            val (toLine, toColumn) = ordered.last()
            buildString {
                for (line in fromLine..toLine) {
                    val row = active.emulator.buffer.line(line) ?: continue
                    val start = if (line == fromLine) fromColumn else 0
                    val end = if (line == toLine) toColumn else row.columns - 1
                    // A row is stored padded to the terminal's width, and those blanks are not
                    // text the user selected — pasting them puts trailing whitespace into an
                    // editor. A wrapped row has no padding, its content runs to the edge.
                    append(RowSelection.textIn(row, start, end, trimTrailingBlanks = !row.wrapped))
                    if (line != toLine && !row.wrapped) append('\n')
                }
            }
        }
    }

    fun scrollLines(lines: Int) {
        session?.scrollBy(lines)
        invalidate()
    }

    /** Recomputes the grid without waiting for a size change, e.g. after a typography edit. */
    fun refreshTypography(newTypography: TerminalTypography) {
        typography = newTypography
    }

    private companion object {
        /** How close to an edge the finger must be before a selection starts scrolling. */
        const val AUTO_SCROLL_MARGIN_CELLS = 2f

        /** A fast flick can cover many rows in one frame; the remote does not need every one. */
        const val MAX_WHEEL_PER_FRAME = 5

        /** Matches the rate hardware terminals used, which is what applications were tuned for. */
        const val BLINK_PERIOD_MILLIS = 500L

        /** Long enough for two fingers to land and lift, short enough not to catch a drag. */
        const val TWO_FINGER_TAP_TIMEOUT_MILLIS = 300L

        // Shaped like a held hardware key: the pause lets a single step be taken on purpose,
        // the interval is slow enough to stop on the intended cell.
        const val ARROW_REPEAT_DELAY_MILLIS = 400L
        const val ARROW_REPEAT_INTERVAL_MILLIS = 220L

        // Reciprocals, so a pinch in and a pinch out need the same change: well past the drift of
        // two resting fingers, well short of a deliberate pinch.
        const val PINCH_IN_THRESHOLD = 0.8f
        const val PINCH_OUT_THRESHOLD = 1.25f
    }
}
