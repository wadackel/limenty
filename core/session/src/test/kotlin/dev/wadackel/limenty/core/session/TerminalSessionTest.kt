package dev.wadackel.limenty.core.session

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.common.log.LogLevel
import dev.wadackel.limenty.core.common.log.LogRecord
import dev.wadackel.limenty.core.common.log.LogSink
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.graphics.CellPixelSize
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    private val transport = FakeTransport()

    private val e = "\u001B"

    private fun sessionIn(scope: CoroutineScope, columns: Int = 20, rows: Int = 5) = TerminalSession(
        id = SessionId("s1"),
        hostId = "host-1",
        hostName = "MacBook",
        transport = transport,
        columns = columns,
        rows = rows,
        parentScope = scope,
    )

    private fun config(columns: Int = 20, rows: Int = 5) = ConnectionConfig(
        host = "macbook",
        username = "wadackel",
        password = "secret",
        initialColumns = columns,
        initialRows = rows,
    )

    @Test
    fun `remote output reaches the screen`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        transport.emit("hello")

        assertThat(session.emulator.buffer.screenRow(0).text().trimEnd()).isEqualTo("hello")
    }

    @Test
    fun `escape sequences from the remote are interpreted`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        transport.emit("a$e[31mb")

        assertThat(session.emulator.buffer.screenRow(0).text().trimEnd()).isEqualTo("ab")
        assertThat(session.emulator.buffer.screenRow(0).foregroundAt(1).isIndexed).isTrue()
    }

    @Test
    fun `input is forwarded to the transport`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        session.send("ls\r".toByteArray())

        assertThat(transport.writtenText()).isEqualTo("ls\r")
    }

    @Test
    fun `a terminal reply is sent back to the remote`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        transport.emit("$e[6n")

        assertThat(transport.writtenText()).contains("R")
    }

    @Test
    fun `the revision advances when the screen changes`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        val before = session.revision.value

        transport.emit("x")

        assertThat(session.revision.value).isGreaterThan(before)
    }

    @Test
    fun `the window title is captured from the remote`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        transport.emit("$e]0;~/dev\u0007")

        assertThat(session.title.value).isEqualTo("~/dev")
    }

    @Test
    fun `modes set by the remote are published for the input layer`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        transport.emit("$e[?1h$e[?2004h")

        assertThat(session.modes.value.keys.applicationCursorKeys).isTrue()
        assertThat(session.modes.value.bracketedPaste).isTrue()
    }

    // --- resize ---

    @Test
    fun `resizing updates the screen at once and tells the remote once it settles`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        session.resize(40, 10, 8, 16)

        assertThat(session.emulator.columns).isEqualTo(40)
        assertThat(session.emulator.rows).isEqualTo(10)

        advanceTimeBy(500)
        assertThat(transport.resizes).contains(40 to 10)
    }

    @Test
    fun `an animating viewport reports one size to the remote, not one per frame`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        // What a soft keyboard sliding into view produces.
        listOf(29, 28, 26, 25, 24, 23, 22).forEach { rows -> session.resize(88, rows, 8, 16) }
        advanceTimeBy(500)

        assertThat(transport.resizes).containsExactly(88 to 22)
    }

    @Test
    fun `resizing keeps the text already on screen`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        transport.emit("keep me")

        session.resize(40, 10, 8, 16)

        assertThat(session.emulator.buffer.screenRow(0).text().trimEnd()).isEqualTo("keep me")
    }

    @Test
    fun `repeated fold style resizes do not lose the connection`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        transport.emit("stable")

        repeat(10) {
            session.resize(40, 12, 8, 16)
            session.resize(20, 5, 8, 16)
        }
        advanceTimeBy(500)

        assertThat(session.state.value).isEqualTo(ConnectionState.Connected)
        assertThat(session.emulator.buffer.screenRow(0).text().trimEnd()).isEqualTo("stable")
    }

    // --- scroll follow ---

    @Test
    fun `the viewport follows new output while at the bottom`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        repeat(20) { transport.emit("line$it\r\n") }

        assertThat(session.scrollOffset.value).isEqualTo(0)
    }

    @Test
    fun `scrolling up holds the same content still as output arrives`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(20) { transport.emit("line$it\r\n") }

        session.scrollBy(5)
        val watched = lineAtTopOfScreen(session)

        repeat(4) { transport.emit("more$it\r\n") }

        // The offset grows with the output; what matters is that the same line stays on screen.
        assertThat(lineAtTopOfScreen(session)).isEqualTo(watched)
    }

    @Test
    fun `a burst of output does not drag the viewport away`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(30) { transport.emit("history$it\r\n") }
        session.scrollBy(10)
        val watched = lineAtTopOfScreen(session)

        transport.emit((0 until 50).joinToString("") { "flood$it\r\n" })

        assertThat(lineAtTopOfScreen(session)).isEqualTo(watched)
    }

    @Test
    fun `typing returns the viewport to the live screen`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(20) { transport.emit("line$it\r\n") }
        session.scrollBy(5)

        session.send("x".toByteArray())

        assertThat(session.scrollOffset.value).isEqualTo(0)
    }

    @Test
    fun `scrolling is clamped to the retained history`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(10) { transport.emit("line$it\r\n") }

        session.scrollBy(10_000)

        assertThat(session.scrollOffset.value).isEqualTo(session.maxScrollOffset())
    }

    @Test
    fun `scroll to bottom resumes following`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(20) { transport.emit("line$it\r\n") }
        session.scrollBy(4)

        session.scrollToBottom()

        assertThat(session.scrollOffset.value).isEqualTo(0)
    }

    /** The text currently drawn on the top row of the viewport. */
    private fun lineAtTopOfScreen(session: TerminalSession): String = session.emulator.buffer.line(-session.scrollOffset.value)?.text()?.trimEnd().orEmpty()

    @Test
    fun `the measured size reaches a remote that was not yet connected`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = 80, rows = 24)

        // What the view does: it measures the real viewport while the handshake is running,
        // and the resize it sends is dropped because there is no shell yet.
        session.resize(120, 40, 8, 16)
        advanceTimeBy(500)
        assertThat(transport.resizes).isEmpty()

        session.connect(config(columns = 80, rows = 24))

        assertThat(transport.resizes).contains(120 to 40)
    }

    @Test
    fun `a connection opened before the view measured anything does not resend`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = 80, rows = 24)

        session.connect(config(columns = 80, rows = 24))

        assertThat(transport.resizes).isEmpty()
    }

    @Test
    fun `a measured cell is sent once after connecting even when the grid matched`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = 80, rows = 24)
        session.resize(80, 24, cellWidthPx = 12, cellHeightPx = 24)
        advanceTimeBy(500)

        session.connect(config(columns = 80, rows = 24))

        assertThat(transport.resizes).containsExactly(80 to 24)
        assertThat(transport.pixelSizes).containsExactly(960 to 576)
    }

    @Test
    fun `a resize carries the pixel size of the whole grid`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        session.resize(40, 10, cellWidthPx = 11, cellHeightPx = 22)
        advanceTimeBy(500)

        assertThat(transport.resizes).contains(40 to 10)
        assertThat(transport.pixelSizes).contains(440 to 220)
        assertThat(session.emulator.cellPixelSize).isEqualTo(CellPixelSize(11, 22))
    }

    @Test
    fun `a cell size change alone still reaches the remote`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        session.resize(20, 5, cellWidthPx = 10, cellHeightPx = 20)
        advanceTimeBy(500)

        session.resize(20, 5, cellWidthPx = 14, cellHeightPx = 28)
        advanceTimeBy(500)

        assertThat(transport.pixelSizes).containsExactly(200 to 100, 280 to 140).inOrder()
    }

    @Test
    fun `shrinking while scrolled back keeps the same content on screen`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = 20, rows = 10)
        session.connect(config(columns = 20, rows = 10))
        repeat(40) { transport.emit("line$it\r\n") }
        session.scrollBy(8)
        val watched = lineAtTopOfScreen(session)

        // A soft keyboard opening is a shrink, and it pushes lines into scrollback.
        session.resize(20, 4, 8, 16)

        assertThat(lineAtTopOfScreen(session)).isEqualTo(watched)
    }

    @Test
    fun `growing while scrolled back keeps the same content on screen`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = 20, rows = 4)
        session.connect(config(columns = 20, rows = 4))
        repeat(40) { transport.emit("line$it\r\n") }
        session.scrollBy(12)
        val watched = lineAtTopOfScreen(session)

        // A soft keyboard closing is a growth, and it pulls lines back out of scrollback.
        session.resize(20, 10, 8, 16)

        assertThat(lineAtTopOfScreen(session)).isEqualTo(watched)
    }

    @Test
    fun `entering the alternate screen returns the viewport to the live screen`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(30) { transport.emit("line$it\r\n") }
        session.scrollBy(6)

        transport.emit("$e[?1049h")

        // The alternate screen has no shared history, so a remembered offset points at nothing.
        assertThat(session.scrollOffset.value).isEqualTo(0)
    }

    @Test
    fun `dropping the scrollback does not strand the viewport past the end`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(30) { transport.emit("line$it\r\n") }
        session.scrollBy(10)

        transport.emit("$e[3J")

        assertThat(session.scrollOffset.value).isAtMost(session.maxScrollOffset())
        assertThat(session.emulator.buffer.line(-session.scrollOffset.value)).isNotNull()
    }

    @Test
    fun `a bell from the remote is counted`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())

        transport.emit("\u0007")

        assertThat(session.bellCount.value).isEqualTo(1)
    }

    /**
     * Typography can change while connected, which reaches the session as a resize. The
     * connection and the history behind the screen both have to come through it.
     */
    @Test
    fun `a settings change resizes without costing the connection or the history`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(30) { transport.emit("line$it\r\n") }
        val historyBefore = session.maxScrollOffset()
        assertThat(historyBefore).isGreaterThan(0)

        // A smaller font fits more of both, a larger one less; neither may drop the past.
        session.resize(30, 8, 8, 16)
        session.resize(14, 4, 8, 16)
        session.resize(20, 5, 8, 16)

        assertThat(session.state.value).isInstanceOf(ConnectionState.Connected::class.java)
        assertThat(session.maxScrollOffset()).isAtLeast(historyBefore)
    }

    @Test
    fun `history stays reachable after a settings change while scrolled back`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        repeat(30) { transport.emit("line$it\r\n") }
        session.scrollBy(6)
        val watched = lineAtTopOfScreen(session)

        // A larger font is a shrink, a smaller one a growth; the reading position holds.
        session.resize(30, 8, 8, 16)
        assertThat(lineAtTopOfScreen(session)).isEqualTo(watched)

        session.resize(20, 3, 8, 16)
        assertThat(lineAtTopOfScreen(session)).isEqualTo(watched)
    }

    // --- the reply queue ---

    /** Parks the writer inside a stalled write, holding one known byte, so what follows queues up. */
    private suspend fun stallWriter(session: TerminalSession) {
        transport.stall()
        session.send("k".toByteArray())
        transport.entered.await()
    }

    /** Wide enough that 1100 distinct cursor cells fit in 25 rows. */
    private val gridColumns = 60
    private val replyCapacity = 1024

    /** Moves the cursor to a distinct cell, then asks for it back, so each reply is recognisable. */
    private fun cursorQuery(index: Int): String = "$e[${index / gridColumns + 1};${index % gridColumns + 1}H$e[6n"

    private fun cursorReport(index: Int): String = "$e[${index / gridColumns + 1};${index % gridColumns + 1}R"

    private fun writtenMessages(): List<String> = transport.written.map { String(it, Charsets.US_ASCII) }

    @Test
    fun `replies to a remote that stopped reading are bounded and the newest survive`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = gridColumns, rows = 25)
        session.connect(config(columns = gridColumns, rows = 25))
        stallWriter(session)

        transport.emit(buildString { repeat(1100) { append(cursorQuery(it)) } })
        transport.release()
        advanceUntilIdle()

        val replies = writtenMessages().drop(1)
        assertThat(replies).hasSize(replyCapacity)
        assertThat(replies).isEqualTo((1100 - replyCapacity until 1100).map { cursorReport(it) })
    }

    @Test
    fun `typed input is never dropped while the remote is not reading`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope)
        session.connect(config())
        stallWriter(session)

        repeat(200) { session.send("i$it,".toByteArray()) }
        transport.release()
        advanceUntilIdle()

        assertThat(writtenMessages()).isEqualTo(listOf("k") + (0 until 200).map { "i$it," })
    }

    @Test
    fun `typed input leaves before replies that were queued ahead of it`() = runTest(UnconfinedTestDispatcher()) {
        val session = sessionIn(backgroundScope, columns = gridColumns, rows = 25)
        session.connect(config(columns = gridColumns, rows = 25))
        stallWriter(session)

        transport.emit(buildString { repeat(5) { append(cursorQuery(it)) } })
        repeat(3) { session.send("i$it".toByteArray()) }
        transport.release()
        advanceUntilIdle()

        assertThat(writtenMessages()).isEqualTo(listOf("k", "i0", "i1", "i2") + (0 until 5).map { cursorReport(it) })
    }

    @Test
    fun `dropped replies are logged once rather than once per drop`() = runTest(UnconfinedTestDispatcher()) {
        val warnings = mutableListOf<LogRecord>()
        val sink = LogSink { record -> if (record.event == "reply.dropped") warnings += record }
        TermLog.addSink(sink)
        try {
            val session = sessionIn(backgroundScope, columns = gridColumns, rows = 25)
            session.connect(config(columns = gridColumns, rows = 25))
            stallWriter(session)

            transport.emit(buildString { repeat(1100) { append(cursorQuery(it)) } })
            transport.release()
            advanceUntilIdle()

            assertThat(warnings).hasSize(1)
            assertThat(warnings.single().level).isEqualTo(LogLevel.WARN)
            assertThat(warnings.single().fields).contains("count" to "1")
        } finally {
            TermLog.removeSink(sink)
        }
    }
}
