package dev.wadackel.limenty.terminal

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
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

/** Pinches, and what else the platform would read as one, driven through the view's own touch handling. */
@RunWith(AndroidJUnit4::class)
class PinchGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun view(
        bindings: GestureBindings,
        fired: MutableList<GestureBehaviour>,
        fontSizes: MutableList<Float> = mutableListOf(),
    ): TerminalView {
        lateinit var created: TerminalView
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            created = TerminalView(context).apply {
                session = offlineSession()
                gestures = bindings
                onGestureBehaviour = { fired += it }
                onFontSizeChanged = { fontSizes += it }
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return created
    }

    /**
     * Two fingers on a horizontal line through the centre, their gap going from [from] to [to].
     *
     * The gaps are wider than the view on purpose: the platform ignores a pinch whose span is
     * under `config_minScalingSpan`, 27mm, which is several hundred pixels on any phone.
     */
    private fun pinch(target: TerminalView, from: Float, to: Float, steps: Int = 10, wobble: Float = 0f) {
        val down = SystemClock.uptimeMillis()
        val centreX = WIDTH / 2f
        val centreY = HEIGHT / 2f
        var time = down

        fun event(action: Int, gap: Float, pointers: Int): MotionEvent {
            val properties = Array(pointers) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(pointers) { index ->
                MotionEvent.PointerCoords().apply {
                    x = if (index == 0) centreX - gap / 2 else centreX + gap / 2
                    y = centreY
                    pressure = 1f
                    size = 1f
                }
            }
            return MotionEvent.obtain(down, time, action, pointers, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        }

        fun dispatch(event: MotionEvent) {
            onMain { target.onTouchEvent(event) }
            event.recycle()
            time += FRAME_MILLIS
        }

        dispatch(event(MotionEvent.ACTION_DOWN, from, 1))
        dispatch(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), from, 2))
        for (step in 1..steps) {
            val gap = from + (to - from) * step / steps + if (step % 2 == 0) wobble else 0f
            dispatch(event(MotionEvent.ACTION_MOVE, gap, 2))
        }
        dispatch(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), to, 2))
        dispatch(event(MotionEvent.ACTION_UP, to, 1))
    }

    /** One finger: a tap, then a second touch at the same point held and dragged down by [dy]. */
    private fun doubleTapDrag(target: TerminalView, dy: Float, steps: Int = 20) {
        val x = WIDTH / 2f
        val y = HEIGHT / 4f
        var down = SystemClock.uptimeMillis()
        var time = down

        fun dispatch(action: Int, atY: Float) {
            val event = MotionEvent.obtain(down, time, action, x, atY, 0)
            onMain { target.onTouchEvent(event) }
            event.recycle()
            time += FRAME_MILLIS
        }

        dispatch(MotionEvent.ACTION_DOWN, y)
        dispatch(MotionEvent.ACTION_UP, y)
        // Inside the platform's double-tap window, and past its minimum gap between the taps.
        time += SECOND_TAP_GAP_MILLIS
        down = time
        dispatch(MotionEvent.ACTION_DOWN, y)
        for (step in 1..steps) dispatch(MotionEvent.ACTION_MOVE, y + dy * step / steps)
        dispatch(MotionEvent.ACTION_UP, y + dy)
    }

    @Test
    fun aDoubleTapAndDragLeavesTheFontSizeAloneByDefault() {
        val fontSizes = mutableListOf<Float>()
        val target = view(GestureBindings.Initial, mutableListOf(), fontSizes)

        doubleTapDrag(target, dy = 250f)

        assertThat(fontSizes).isEmpty()
    }

    @Test
    fun aDoubleTapAndDragZoomsOnceItIsSwitchedOn() {
        val fontSizes = mutableListOf<Float>()
        val target = view(GestureBindings.Initial, mutableListOf(), fontSizes)
        onMain { target.doubleTapDragZoom = true }

        doubleTapDrag(target, dy = 250f)

        assertThat(fontSizes).isNotEmpty()
    }

    @Test
    fun aPinchOutBoundToPasteFiresItOnce() {
        val fired = mutableListOf<GestureBehaviour>()
        val target = view(GestureBindings.Initial.with(Gesture.PinchOut, GestureBehaviour.Paste), fired)

        pinch(target, from = 600f, to = 1500f)

        assertThat(fired).containsExactly(GestureBehaviour.Paste)
    }

    @Test
    fun aPinchInBoundToCopyFiresItOnce() {
        val fired = mutableListOf<GestureBehaviour>()
        val target = view(GestureBindings.Initial.with(Gesture.PinchIn, GestureBehaviour.Copy), fired)

        pinch(target, from = 1500f, to = 600f)

        assertThat(fired).containsExactly(GestureBehaviour.Copy)
    }

    @Test
    fun aZoomingPinchDoesNotFireTheOtherDirectionsBehaviour() {
        val fired = mutableListOf<GestureBehaviour>()
        val bindings = GestureBindings.Initial
            .with(Gesture.PinchIn, GestureBehaviour.FontZoom)
            .with(Gesture.PinchOut, GestureBehaviour.Paste)
        val target = view(bindings, fired)

        // Mostly in, with a wobble outwards on every other frame, as a real pinch has.
        pinch(target, from = 1500f, to = 600f, steps = 40, wobble = 60f)

        assertThat(fired).isEmpty()
    }

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("pinch-test"),
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
        const val SECOND_TAP_GAP_MILLIS = 100L
    }
}
