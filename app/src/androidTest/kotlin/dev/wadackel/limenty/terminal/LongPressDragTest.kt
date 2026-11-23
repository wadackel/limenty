package dev.wadackel.limenty.terminal

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.common.log.LogRecord
import dev.wadackel.limenty.core.common.log.LogSink
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.TerminalInputEvent
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A long press held still selects the word under it; a long press that moves sends arrow keys.
 *
 * The long press is a real one: the platform's detector has to have fired it, because the moves
 * that follow are exactly what it drops once it has, and a test that called the helpers directly
 * would never see that.
 */
@RunWith(AndroidJUnit4::class)
class LongPressDragTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private val sent = mutableListOf<TerminalInputEvent>()

    private fun view(
        text: String = "alpha beta gamma delta epsilon",
        bindings: GestureBindings = GestureBindings.Initial,
    ): TerminalView {
        lateinit var created: TerminalView
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val session = offlineSession()
            session.emulator.write(text.toByteArray())
            created = TerminalView(context).apply {
                this.session = session
                gestures = bindings
                onInputEvents = { sent += it }
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return created
    }

    /** One finger's gesture, with the second finger available for the cases that need it. */
    private inner class Touch(private val target: TerminalView, private var x: Float, private var y: Float) {
        // Back-dated past the long-press timeout, so the detector's delayed message is already due
        // and runs as soon as the main thread goes idle.
        private val down = SystemClock.uptimeMillis() - ViewConfiguration.getLongPressTimeout() - LONG_PRESS_MARGIN_MILLIS
        private var time = SystemClock.uptimeMillis()

        private fun dispatch(event: MotionEvent) {
            onMain { target.onTouchEvent(event) }
            event.recycle()
            time += FRAME_MILLIS
        }

        private fun single(action: Int) = MotionEvent.obtain(down, time, action, x, y, 0)

        private fun pair(action: Int, secondX: Float): MotionEvent {
            val properties = Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(2) { index ->
                MotionEvent.PointerCoords().apply {
                    this.x = if (index == 0) this@Touch.x else secondX
                    this.y = this@Touch.y
                    pressure = 1f
                    size = 1f
                }
            }
            return MotionEvent.obtain(down, time, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }

        /** Puts the finger down and waits for the long press to fire. */
        fun press(): Touch {
            dispatch(single(MotionEvent.ACTION_DOWN))
            instrumentation.waitForIdleSync()
            return this
        }

        fun moveBy(dx: Float, dy: Float = 0f, steps: Int = 8): Touch {
            val fromX = x
            val fromY = y
            for (step in 1..steps) {
                x = fromX + dx * step / steps
                y = fromY + dy * step / steps
                dispatch(single(MotionEvent.ACTION_MOVE))
            }
            return this
        }

        /** A second finger lands [gap] to the right, both move by [dx] together, and it lifts. */
        fun secondFingerDrag(gap: Float, dx: Float, steps: Int = 8): Touch {
            val pointerIndex = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            dispatch(pair(MotionEvent.ACTION_POINTER_DOWN or pointerIndex, x + gap))
            val fromX = x
            for (step in 1..steps) {
                x = fromX + dx * step / steps
                dispatch(pair(MotionEvent.ACTION_MOVE, x + gap))
            }
            dispatch(pair(MotionEvent.ACTION_POINTER_UP or pointerIndex, x + gap))
            return this
        }

        /** Keeps the finger where it is; sleeping off the main thread lets the key repeat run. */
        fun hold(millis: Long): Touch {
            SystemClock.sleep(millis)
            instrumentation.waitForIdleSync()
            time += millis
            return this
        }

        fun lift() = dispatch(single(MotionEvent.ACTION_UP))
    }

    private fun cellCentre(target: TerminalView, line: Int, column: Int): Pair<Float, Float> {
        val metrics = target.metrics
        return (column + 0.5f) * metrics.cellWidth to (line + 0.5f) * metrics.cellHeight
    }

    /** Far enough past the dead zone that a drag counts whatever the cell size. */
    private fun drag(target: TerminalView): Float = DRAG_DP * target.metrics.density

    // The repeat appends from the main thread, so the list is read there too.
    private fun keys(): List<TerminalKey> {
        lateinit var read: List<TerminalKey>
        onMain { read = sent.map { (it as TerminalInputEvent.Key).key } }
        return read
    }

    @Test
    fun aLongPressHeldStillSelectsTheWordWhenTheFingerLifts() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)

        val touch = Touch(target, x, y).press()
        assertThat(target.selection).isNull()
        touch.lift()

        assertThat(target.selectedText()).isEqualTo("beta")
        assertThat(sent).isEmpty()
    }

    @Test
    fun aLongPressDraggedRightSendsOneArrowStraightAway() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)

        Touch(target, x, y).press().moveBy(drag(target)).lift()

        assertThat(keys()).containsExactly(TerminalKey.ArrowRight)
        assertThat(target.selection).isNull()
    }

    @Test
    fun aLongPressDraggedUpSendsOneArrowUpStraightAway() {
        val target = view()
        val (x, y) = cellCentre(target, 4, 8)

        Touch(target, x, y).press().moveBy(0f, -drag(target)).lift()

        assertThat(keys()).containsExactly(TerminalKey.ArrowUp)
    }

    @Test
    fun holdingTheDragRepeatsTheArrowUntilTheFingerLifts() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)

        val touch = Touch(target, x, y).press().moveBy(drag(target))
        val beforeHold = keys().size
        touch.hold(REPEAT_HOLD_MILLIS)
        val afterHold = keys().size
        touch.lift()
        // Counted after the lift rather than before it: a repeat due in between still counts as
        // held, and only what arrives once the finger is up would be wrong.
        val atLift = keys().size
        touch.hold(AFTER_LIFT_MILLIS)

        assertThat(afterHold - beforeHold).isAtLeast(2)
        assertThat(beforeHold).isEqualTo(1)
        assertThat(keys().toSet()).containsExactly(TerminalKey.ArrowRight)
        assertThat(keys().size).isEqualTo(atLift)
    }

    @Test
    fun draggingBackToWhereThePressStartedStopsTheRepeat() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)
        val distance = drag(target)

        Touch(target, x, y).press().moveBy(distance).moveBy(-distance).hold(REPEAT_HOLD_MILLIS).lift()

        assertThat(keys()).containsExactly(TerminalKey.ArrowRight)
    }

    @Test
    fun draggingOverToTheOtherSideSwitchesToTheOppositeArrow() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)
        val distance = drag(target)

        Touch(target, x, y).press().moveBy(distance).moveBy(-distance * 2).lift()

        assertThat(keys()).containsExactly(TerminalKey.ArrowRight, TerminalKey.ArrowLeft).inOrder()
    }

    @Test
    fun liftingInsideTheDeadZoneAfterSendingArrowsSelectsNothing() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)
        val distance = drag(target)

        Touch(target, x, y).press().moveBy(distance).moveBy(-distance).lift()

        assertThat(keys()).containsExactly(TerminalKey.ArrowRight)
        assertThat(target.selection).isNull()
    }

    @Test
    fun theDropOpensWithTheLongPressAndClosesWhenTheFingerLifts() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)
        fun open(): Boolean {
            var read = false
            onMain { read = target.arrowDropOpen }
            return read
        }

        val touch = Touch(target, x, y).press()
        val whileHeld = open()
        touch.lift()

        assertThat(whileHeld).isTrue()
        assertThat(open()).isFalse()
    }

    @Test
    fun aSecondFingerStopsTheRepeatOfADirectionAlreadyChosen() {
        val target = view()
        val (x, y) = cellCentre(target, 2, 4)

        Touch(target, x, y)
            .press()
            .moveBy(drag(target))
            .secondFingerDrag(gap = WIDTH / 3f, dx = 0f)
            .hold(REPEAT_HOLD_MILLIS)
            .lift()

        assertThat(keys()).containsExactly(TerminalKey.ArrowRight)
    }

    @Test
    fun aWobbleThatStaysInsideTheDeadZoneStillSelects() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 8)

        Touch(target, x, y).press().moveBy(WOBBLE_DP * target.metrics.density).lift()

        assertThat(sent).isEmpty()
        assertThat(target.selectedText()).isEqualTo("beta")
    }

    @Test
    fun aSecondFingerAfterALongPressLeavesNeitherASelectionNorArrows() {
        val target = view()
        val (x, y) = cellCentre(target, 2, 4)

        Touch(target, x, y).press().secondFingerDrag(gap = WIDTH / 3f, dx = target.metrics.cellWidth * 4).lift()

        assertThat(sent).isEmpty()
        assertThat(target.selection).isNull()
    }

    @Test
    fun holdingAHandleStillBeforeDraggingItExtendsTheSelectionAndSendsNoArrows() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 2)
        onMain { target.beginSelectionAt(pressX, pressY) }
        assertThat(target.selectedText()).isEqualTo("alpha")

        val handleX = SelectionGeometry.handleCentreX(target.selection!!.endColumn, target.metrics)
        val handleY = SelectionGeometry.handleCentreY(0, 0, target.metrics)
        val (toX, toY) = cellCentre(target, 0, 9)
        Touch(target, handleX, handleY).press().moveBy(toX - handleX, toY - handleY).lift()

        assertThat(sent).isEmpty()
        assertThat(target.selectedText()).isEqualTo("alpha beta")
    }

    @Test
    fun aLongPressBoundToSomethingElseSendsNoArrowsWhenDragged() {
        val target = view(bindings = GestureBindings.Initial.with(Gesture.LongPress, GestureBehaviour.Paste))
        val (x, y) = cellCentre(target, 0, 8)

        Touch(target, x, y).press().moveBy(drag(target)).lift()

        assertThat(sent).isEmpty()
    }

    /** The steps reach the remote in order; the log, which outlives the moment, holds none of them. */
    @Test
    fun aLongPressBoundToKeysSendsEachStepInOrderAndLogsNoneOfThem() {
        val records = CopyOnWriteArrayList<LogRecord>()
        val sink = LogSink { records += it }
        TermLog.addSink(sink)
        try {
            val keys = GestureBehaviour.SendKeys(listOf(KeyStep.Literal("hunter2"), KeyStep.Press(key = TerminalKey.Enter)))
            val target = view(bindings = GestureBindings.Initial.with(Gesture.LongPress, keys))
            val (x, y) = cellCentre(target, 0, 8)

            Touch(target, x, y).press().lift()

            assertThat(sent).containsExactly(TerminalInputEvent.Text("hunter2"), TerminalInputEvent.Key(TerminalKey.Enter)).inOrder()
            assertThat(records.map { it.fields }).contains(listOf("gesture" to "LongPress", "behaviour" to "keys", "steps" to "2"))
            assertThat(records.filter { record -> record.fields.any { "hunter2" in it.second } }).isEmpty()
        } finally {
            TermLog.removeSink(sink)
        }
    }

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("long-press-drag-test"),
        hostId = "test",
        hostName = "test",
        transport = NullTransport(),
        columns = 40,
        rows = 12,
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private class NullTransport : TerminalTransport {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val output: Flow<ByteArray> = emptyFlow()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
        override suspend fun disconnect() = Unit
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 400
        const val FRAME_MILLIS = 16L
        const val LONG_PRESS_MARGIN_MILLIS = 200L

        // Either side of ArrowDropGeometry.DEAD_ZONE_DP, with room for the steps a drag is cut into.
        const val DRAG_DP = 40f
        const val WOBBLE_DP = 15f

        // Past the repeat delay and a few intervals, so a working repeat has certainly fired.
        const val REPEAT_HOLD_MILLIS = 1000L
        const val AFTER_LIFT_MILLIS = 300L
    }
}
