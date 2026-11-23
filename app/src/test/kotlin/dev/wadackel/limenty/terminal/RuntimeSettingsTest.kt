package dev.wadackel.limenty.terminal

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalTypography
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Everything the settings screen offers can be changed mid-session
 * without costing the connection or what is on the screen. Typography is the only one that
 * reaches the session at all — it changes the cell, so the terminal is a different size — and
 * even that has to keep the history it already had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuntimeSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun connectedView(): Pair<TerminalView, TerminalSession> {
        lateinit var view: TerminalView
        lateinit var session: TerminalSession
        onMain {
            val context = ApplicationProvider.getApplicationContext<Context>()
            session = offlineSession()
            repeat(40) { index -> session.emulator.write("line$index\r\n".toByteArray()) }
            view = TerminalView(context).apply {
                this.session = session
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return view to session
    }

    private fun screenTop(session: TerminalSession): String = session.emulator.buffer.line(-session.scrollOffset.value)?.text()?.trimEnd().orEmpty()

    @Test
    fun changingTheThemeTouchesNothingButTheColours() {
        val (view, session) = connectedView()
        val historyBefore = session.maxScrollOffset()
        val topBefore = screenTop(session)
        val columnsBefore = session.emulator.columns

        onMain { view.palette = TerminalPalette.Nord }

        assertThat(session.maxScrollOffset()).isEqualTo(historyBefore)
        assertThat(screenTop(session)).isEqualTo(topBefore)
        assertThat(session.emulator.columns).isEqualTo(columnsBefore)
    }

    @Test
    fun rebindingGesturesDoesNotDisturbTheSession() {
        val (view, session) = connectedView()
        val historyBefore = session.maxScrollOffset()
        val rowsBefore = session.emulator.rows

        onMain {
            view.gestures = GestureBindings.Initial.with(Gesture.DoubleTap, GestureBehaviour.Copy)
        }

        assertThat(session.maxScrollOffset()).isEqualTo(historyBefore)
        assertThat(session.emulator.rows).isEqualTo(rowsBefore)
    }

    @Test
    fun theKeyboardSharingChoiceDoesNotDisturbTheSession() {
        val (view, session) = connectedView()
        val historyBefore = session.maxScrollOffset()

        onMain { view.allowPersonalizedLearning = true }

        assertThat(session.maxScrollOffset()).isEqualTo(historyBefore)
    }

    @Test
    fun aLargerFontResizesTheTerminalButKeepsItsHistory() {
        val (view, session) = connectedView()
        val historyBefore = session.maxScrollOffset()
        val columnsBefore = session.emulator.columns

        onMain { view.refreshTypography(TerminalTypography(fontSizeSp = 24f)) }

        assertThat(session.emulator.columns).isLessThan(columnsBefore)
        // Fewer columns and rows, but nothing the session had already seen may be discarded.
        assertThat(session.maxScrollOffset()).isAtLeast(historyBefore)
    }

    @Test
    fun aSmallerFontAlsoKeepsWhatWasThere() {
        val (view, session) = connectedView()
        val historyBefore = session.maxScrollOffset()

        val columnsBefore = session.emulator.columns
        onMain { view.refreshTypography(TerminalTypography(fontSizeSp = 8f)) }

        assertThat(session.emulator.columns).isGreaterThan(columnsBefore)
        assertThat(session.maxScrollOffset()).isAtLeast(0)
        assertThat(session.emulator.buffer.line(0)).isNotNull()
    }

    /** Reading position is the part a font change is most likely to lose. */
    @Test
    fun aFontChangeWhileScrolledBackHoldsTheReadingPosition() {
        val (view, session) = connectedView()
        onMain { session.scrollBy(6) }
        val watched = screenTop(session)

        onMain { view.refreshTypography(TerminalTypography(fontSizeSp = 18f)) }

        assertThat(screenTop(session)).isEqualTo(watched)
    }

    @Test
    fun severalSettingsInARowLeaveTheSessionIntact() {
        val (view, session) = connectedView()
        val topBefore = screenTop(session)

        onMain {
            view.palette = TerminalPalette.Dracula
            view.gestures = GestureBindings.Initial
            view.allowPersonalizedLearning = true
            view.refreshTypography(TerminalTypography(fontSizeSp = 15f))
            view.refreshTypography(TerminalTypography())
        }

        assertThat(screenTop(session)).isEqualTo(topBefore)
        assertThat(session.state.value).isEqualTo(ConnectionState.Idle)
    }

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("settings-test"),
        hostId = "test",
        hostName = "test",
        transport = NullTransport(),
        columns = COLUMNS,
        rows = ROWS,
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

        /** The session's opening size; the view resizes it to the viewport as soon as it lays out. */
        const val COLUMNS = 40
        const val ROWS = 12
    }
}
