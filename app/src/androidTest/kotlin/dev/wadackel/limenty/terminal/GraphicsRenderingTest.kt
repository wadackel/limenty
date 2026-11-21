package dev.wadackel.limenty.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
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
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A kitty graphics placement reaches the pixels. The emulator's own tests prove the placement is
 * recorded; this covers the half only a device can — that the bitmap is decoded and drawn in the
 * cells the placement names, and that it moves with the text.
 */
@RunWith(AndroidJUnit4::class)
class GraphicsRenderingTest {
    private val e = "\u001B"

    private fun view(): TerminalView {
        lateinit var created: TerminalView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            created = TerminalView(context).apply {
                session = offlineSession()
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return created
    }

    private fun render(target: TerminalView): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.draw(Canvas(bitmap))
        }
        return bitmap
    }

    /**
     * Draws until [condition] holds, or the deadline passes and the caller's assertion says so.
     *
     * Decoding happens off the drawing thread, so the frame in which a placement first arrives
     * carries no pixels for it; the view asks for another once they exist. Polling rather than
     * waiting on the decode keeps this a test of what reaches the canvas.
     */
    private fun renderUntil(target: TerminalView, condition: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + DECODE_TIMEOUT_MILLIS
        var bitmap = render(target)
        while (!condition(bitmap) && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            bitmap = render(target)
        }
        return bitmap
    }

    private fun write(target: TerminalView, text: String) {
        val session = requireNotNull(target.session)
        synchronized(session.lock) { session.emulator.write(text) }
    }

    private fun pixelAtCell(bitmap: Bitmap, target: TerminalView, column: Int, row: Int): Int {
        val x = ((column + 0.5f) * target.metrics.cellWidth).toInt()
        val y = ((row + 0.5f) * target.metrics.cellHeight).toInt()
        return bitmap.getPixel(x, y)
    }

    private fun isRed(pixel: Int): Boolean = Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) < 60

    @Test
    fun aRawRgbaPixelFillsTheCellsItWasPlacedOver() {
        val target = view()
        val background = pixelAtCell(render(target), target, 0, 0)

        write(target, "${e}_Ga=T,f=32,s=1,v=1,i=1,c=2,r=1,C=1;/wAA/w==$e\\")
        val drawn = renderUntil(target) { isRed(pixelAtCell(it, target, 0, 0)) }

        assertThat(isRed(pixelAtCell(drawn, target, 0, 0))).isTrue()
        assertThat(isRed(pixelAtCell(drawn, target, 1, 0))).isTrue()
        assertThat(pixelAtCell(drawn, target, 2, 0)).isEqualTo(background)
        assertThat(pixelAtCell(drawn, target, 0, 1)).isEqualTo(background)
    }

    @Test
    fun aPngIsDecodedAndDrawn() {
        val target = view()
        val png = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(intArrayOf(Color.RED), 1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()

        write(target, "${e}_Ga=T,f=100,i=2,c=1,r=1,C=1;${Base64.getEncoder().encodeToString(png)}$e\\")

        val drawn = renderUntil(target) { isRed(pixelAtCell(it, target, 0, 0)) }

        assertThat(isRed(pixelAtCell(drawn, target, 0, 0))).isTrue()
    }

    @Test
    fun anImageScrollsUpWithTheText() {
        val target = view()
        val rows = requireNotNull(target.session).emulator.rows
        write(target, "$e[$rows;1H${e}_Ga=T,f=32,s=1,v=1,i=3,c=1,r=1,C=1;/wAA/w==$e\\")
        val placed = renderUntil(target) { isRed(pixelAtCell(it, target, 0, rows - 1)) }
        assertThat(isRed(pixelAtCell(placed, target, 0, rows - 1))).isTrue()

        write(target, "\n")
        val scrolled = renderUntil(target) { isRed(pixelAtCell(it, target, 0, rows - 2)) }

        assertThat(isRed(pixelAtCell(scrolled, target, 0, rows - 2))).isTrue()
        assertThat(isRed(pixelAtCell(scrolled, target, 0, rows - 1))).isFalse()
    }

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("graphics-test"),
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

        /** Generous: this waits for a thread hop, not for anything that should take milliseconds. */
        const val DECODE_TIMEOUT_MILLIS = 2000L
        const val POLL_INTERVAL_MILLIS = 10L
    }
}
