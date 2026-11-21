package dev.wadackel.limenty.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
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

/**
 * Selection end to end, driven through the view's own touch handling.
 *
 * The selection was reachable only for as long as the finger that started it stayed down, so a
 * range the long press guessed wrong had to be redone. These drive real [MotionEvent]s rather
 * than calling the helpers, because that gap was in the dispatch and not in the arithmetic.
 */
@RunWith(AndroidJUnit4::class)
class SelectionInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun view(text: String = "alpha beta gamma delta epsilon"): TerminalView {
        lateinit var created: TerminalView
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val session = offlineSession()
            session.emulator.write(text.toByteArray())
            created = TerminalView(context).apply {
                this.session = session
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return created
    }

    private fun send(target: TerminalView, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        onMain {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            target.onTouchEvent(event)
            event.recycle()
        }
    }

    /** A long press, expressed the way the view sees one. */
    private fun longPress(target: TerminalView, x: Float, y: Float) {
        onMain { target.beginSelectionAt(x, y) }
    }

    private fun cellCentre(target: TerminalView, line: Int, column: Int): Pair<Float, Float> {
        val metrics = target.metrics
        return (column + 0.5f) * metrics.cellWidth to (line + 0.5f) * metrics.cellHeight
    }

    private fun handle(target: TerminalView, line: Int, column: Int): Pair<Float, Float> {
        val metrics = target.metrics
        return SelectionGeometry.handleCentreX(column, metrics) to
            SelectionGeometry.handleCentreY(line, 0, metrics)
    }

    @Test
    fun aLongPressSelectsTheWordUnderIt() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 2)

        longPress(target, x, y)

        val selection = target.selection!!
        assertThat(target.selectedText()).isEqualTo("alpha")
        assertThat(selection.startColumn).isEqualTo(0)
    }

    @Test
    fun aLongPressOnALinkSelectsTheLinkWithoutTheSentenceAroundIt() {
        val target = view("see https://example.com/a/b. ok")
        val (x, y) = cellCentre(target, 0, 15)

        longPress(target, x, y)

        assertThat(target.selectedText()).isEqualTo("https://example.com/a/b")
        assertThat(target.selectedLink()).isEqualTo("https://example.com/a/b")
    }

    @Test
    fun aLongPressOnAWordOffersNoLink() {
        val target = view()
        val (x, y) = cellCentre(target, 0, 2)

        longPress(target, x, y)

        assertThat(target.selectedLink()).isNull()
    }

    @Test
    fun aHandleCanBeGrabbedAfterTheFingerLifts() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 2)
        longPress(target, pressX, pressY)
        val initial = target.selectedText()

        // The gesture that made the selection has ended before this one starts.
        val (handleX, handleY) = handle(target, 0, target.selection!!.endColumn)
        send(target, MotionEvent.ACTION_DOWN, handleX, handleY)

        assertThat(target.selection).isNotNull()
        assertThat(target.selectedText()).isEqualTo(initial)
    }

    @Test
    fun draggingTheEndHandleExtendsTheRange() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 2)
        longPress(target, pressX, pressY)
        assertThat(target.selectedText()).isEqualTo("alpha")

        val (handleX, handleY) = handle(target, 0, target.selection!!.endColumn)
        send(target, MotionEvent.ACTION_DOWN, handleX, handleY)
        val (toX, toY) = cellCentre(target, 0, 9)
        send(target, MotionEvent.ACTION_MOVE, toX, toY)

        assertThat(target.selectedText()).isEqualTo("alpha beta")
    }

    @Test
    fun draggingTheStartHandleMovesTheOtherEnd() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 8)
        longPress(target, pressX, pressY)
        assertThat(target.selectedText()).isEqualTo("beta")

        val (handleX, handleY) = handle(target, 0, target.selection!!.startColumn)
        send(target, MotionEvent.ACTION_DOWN, handleX, handleY)
        val (toX, toY) = cellCentre(target, 0, 0)
        send(target, MotionEvent.ACTION_MOVE, toX, toY)

        assertThat(target.selectedText()).isEqualTo("alpha beta")
    }

    @Test
    fun aDraggedHandleMovesItsEndAlongTheRowItMarksRatherThanTheRowUnderTheFinger() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 2)
        longPress(target, pressX, pressY)
        assertThat(target.selectedText()).isEqualTo("alpha")

        // The handle hangs below row 0, so the finger holding it is on row 1.
        val (handleX, handleY) = handle(target, 0, target.selection!!.endColumn)
        send(target, MotionEvent.ACTION_DOWN, handleX, handleY)
        send(target, MotionEvent.ACTION_MOVE, handleX + target.metrics.cellWidth * 3, handleY)

        val selection = target.selection!!
        assertThat(selection.endLine).isEqualTo(0)
        assertThat(selection.endColumn).isEqualTo(7)
    }

    /** Carrying the grab offset unchanged would stop this end two columns short of the edge. */
    @Test
    fun aHandleGrabbedOffCentreCanStillBeDraggedToTheFirstColumn() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 8)
        longPress(target, pressX, pressY)
        assertThat(target.selectedText()).isEqualTo("beta")

        val (startX, handleY) = handle(target, 0, target.selection!!.startColumn)
        val grabX = startX - target.metrics.cellWidth * 2
        send(target, MotionEvent.ACTION_DOWN, grabX, handleY)
        send(target, MotionEvent.ACTION_MOVE, 0f, handleY)

        assertThat(target.selectedText()).isEqualTo("alpha beta")
    }

    @Test
    fun touchingAwayFromTheHandlesDismissesTheSelection() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 2)
        longPress(target, pressX, pressY)
        assertThat(target.selection).isNotNull()

        val (awayX, awayY) = cellCentre(target, 5, 20)
        send(target, MotionEvent.ACTION_DOWN, awayX, awayY)

        assertThat(target.selection).isNull()
    }

    @Test
    fun aGrabbedHandleCanBeDraggedMoreThanOnce() {
        val target = view()
        val (pressX, pressY) = cellCentre(target, 0, 2)
        longPress(target, pressX, pressY)

        // "alpha beta" ends at column 9, "alpha beta gamma" at column 15.
        for (column in listOf(9, 15)) {
            val (handleX, handleY) = handle(target, 0, target.selection!!.endColumn)
            send(target, MotionEvent.ACTION_DOWN, handleX, handleY)
            val (toX, toY) = cellCentre(target, 0, column)
            send(target, MotionEvent.ACTION_MOVE, toX, toY)
            send(target, MotionEvent.ACTION_UP, toX, toY)
        }

        assertThat(target.selectedText()).isEqualTo("alpha beta gamma")
    }

    @Test
    fun aSelectionSpanningRowsCopiesEveryOneOfThem() {
        val target = view("first line\r\nsecond line\r\nthird line\r\n")
        val (pressX, pressY) = cellCentre(target, 0, 2)
        longPress(target, pressX, pressY)

        val (handleX, handleY) = handle(target, 0, target.selection!!.endColumn)
        send(target, MotionEvent.ACTION_DOWN, handleX, handleY)
        // The finger carries the handle, which hangs below the cell it marks.
        val (toX, toY) = handle(target, 2, 4)
        send(target, MotionEvent.ACTION_MOVE, toX, toY)

        assertThat(target.selectedText().lines()).containsExactly("first line", "second line", "third").inOrder()
    }

    /** A range may start above the screen and still copy what it covers. */
    @Test
    fun aSelectionReachingIntoScrollbackCopiesFromIt() {
        val target = view()
        onMain {
            val session = target.session!!
            // Enough rows to push the first ones out of a 12-row screen.
            repeat(20) { index -> session.emulator.write("row$index\r\n".toByteArray()) }
            session.scrollBy(8)
        }

        // Whatever row the scrolled-back viewport is showing at its top; the point is that the
        // copy reads it out of scrollback rather than off the live screen.
        val topOfViewport = readLine(target, -SCROLLED_BACK)
        val (pressX, pressY) = cellCentre(target, -SCROLLED_BACK, 1)
        longPress(target, pressX, pressY)

        assertThat(topOfViewport).startsWith("row")
        assertThat(target.selectedText()).isEqualTo(topOfViewport)
    }

    private fun readLine(target: TerminalView, line: Int): String {
        lateinit var text: String
        onMain { text = target.session!!.emulator.buffer.line(line)!!.text().trimEnd() }
        return text
    }

    @Test
    fun aSelectionMadeInScrollbackSurvivesNewOutput() {
        val target = view()
        onMain {
            val session = target.session!!
            repeat(20) { index -> session.emulator.write("row$index\r\n".toByteArray()) }
            session.scrollBy(SCROLLED_BACK)
        }
        val (pressX, pressY) = cellCentre(target, -SCROLLED_BACK, 1)
        longPress(target, pressX, pressY)
        val picked = target.selectedText()

        onMain { target.session!!.emulator.write("noise\r\nnoise\r\nnoise\r\n".toByteArray()) }

        assertThat(target.selectedText()).isEqualTo(picked)
    }

    /** Every bounds the view reported, in order; filled by drawing, which is where it reports from. */
    private fun recordBounds(target: TerminalView): MutableList<SelectionBounds?> {
        val reported = mutableListOf<SelectionBounds?>()
        onMain { target.onSelectionBoundsChanged = { reported += it } }
        return reported
    }

    private fun drawFrame(target: TerminalView) {
        onMain { target.draw(Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))) }
    }

    @Test
    fun theSelectionIsReportedWhereTheSelectedWordIsDrawn() {
        val target = view()
        val reported = recordBounds(target)
        val (x, y) = cellCentre(target, 0, 2)

        longPress(target, x, y)
        drawFrame(target)

        val bounds = reported.last()!!
        assertThat(bounds.top).isEqualTo(0f)
        assertThat(bounds.left).isEqualTo(0f)
        assertThat(bounds.right).isEqualTo(5 * target.metrics.cellWidth)
    }

    @Test
    fun theReportedSelectionFollowsTheTextAsOutputScrollsIt() {
        val target = view("")
        // Eleven rows put the cursor on the last row of twelve, so the next line scrolls the screen.
        onMain { repeat(11) { index -> target.session!!.emulator.write("row$index word\r\n".toByteArray()) } }
        val reported = recordBounds(target)
        val (x, y) = cellCentre(target, 5, 1)
        longPress(target, x, y)
        drawFrame(target)
        val before = reported.last()!!

        onMain { target.session!!.emulator.write("more\r\n".toByteArray()) }
        drawFrame(target)

        val after = reported.last()!!
        assertThat(before.top).isEqualTo(5 * target.metrics.cellHeight)
        assertThat(after.top).isEqualTo(4 * target.metrics.cellHeight)
    }

    @Test
    fun clearingTheSelectionReportsThatThereIsNoneOnce() {
        val target = view()
        val reported = recordBounds(target)
        val (x, y) = cellCentre(target, 0, 2)
        longPress(target, x, y)
        drawFrame(target)

        onMain { target.selection = null }
        drawFrame(target)
        drawFrame(target)

        assertThat(reported.last()).isNull()
        assertThat(reported.count { it == null }).isEqualTo(1)
    }

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("selection-test"),
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

        /** Far enough back that the top of the viewport is a row the live screen no longer shows. */
        const val SCROLLED_BACK = 8
    }
}
