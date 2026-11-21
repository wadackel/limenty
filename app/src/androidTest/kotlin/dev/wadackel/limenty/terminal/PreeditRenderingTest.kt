package dev.wadackel.limenty.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
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
 * The composing text has to appear at the cursor. The guarantee that it never
 * reaches the remote was already tested; this covers the other half — that the user can see what
 * they are converting. It renders through the real view and inspects the pixels, because the
 * defect it guards against was code that was correct but simply never called.
 */
@RunWith(AndroidJUnit4::class)
class PreeditRenderingTest {

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

    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        var count = 0
        for (y in 0 until HEIGHT step 2) {
            for (x in 0 until WIDTH step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            }
        }
        return count
    }

    @Test
    fun composingTextIsDrawn() {
        val target = view()
        val before = render(target)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.onCreateInputConnection(EditorInfo()).setComposingText("にほんご", 1)
        }
        val after = render(target)

        assertThat(differingPixels(before, after)).isGreaterThan(0)
    }

    @Test
    fun committingClearsWhatWasDrawn() {
        val target = view()
        val blank = render(target)

        val connection = target.onCreateInputConnection(EditorInfo())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.setComposingText("にほんご", 1)
        }
        val composing = render(target)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.commitText("", 1)
        }
        val committed = render(target)

        assertThat(differingPixels(blank, composing)).isGreaterThan(0)
        // The preedit is drawn over the screen, so confirming it must leave no trace behind.
        assertThat(differingPixels(blank, committed)).isEqualTo(0)
    }

    @Test
    fun alongerPreeditCoversMoreOfTheScreen() {
        val target = view()
        val blank = render(target)
        val connection = target.onCreateInputConnection(EditorInfo())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.setComposingText("に", 1)
        }
        val short = differingPixels(blank, render(target))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.setComposingText("にほんごにほんご", 1)
        }
        val long = differingPixels(blank, render(target))

        assertThat(long).isGreaterThan(short)
    }

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("preedit-test"),
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
    }
}
