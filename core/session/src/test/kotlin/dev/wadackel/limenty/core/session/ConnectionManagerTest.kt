package dev.wadackel.limenty.core.session

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val transports = mutableMapOf<String, FakeTransport>()

    private fun manager(scope: CoroutineScope) = ConnectionManager(
        transportFactory = { _, hostId -> transports.getOrPut(hostId) { FakeTransport() } },
        scope = scope,
    )

    @Test
    fun `opening a host creates a session and makes it active`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)

        val session = connections.openSession("host-1", "MacBook", 80, 24)

        assertThat(connections.sessions.value).containsExactly(session)
        assertThat(connections.activeSessionId.value).isEqualTo(session.id)
    }

    @Test
    fun `tapping a connected host reopens the same session`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        val first = connections.openSession("host-1", "MacBook", 80, 24)

        val second = connections.openSession("host-1", "MacBook", 80, 24)

        assertThat(second).isSameInstanceAs(first)
        assertThat(connections.sessions.value).hasSize(1)
    }

    @Test
    fun `different hosts get their own sessions`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        connections.openSession("host-1", "MacBook", 80, 24)
        connections.openSession("host-2", "Server", 80, 24)

        assertThat(connections.sessions.value).hasSize(2)
    }

    @Test
    fun `a session survives being deactivated`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        val session = connections.openSession("host-1", "MacBook", 80, 24)

        connections.setActive(null)

        assertThat(connections.sessions.value).containsExactly(session)
        assertThat(connections.activeSessionId.value).isNull()
    }

    @Test
    fun `closing a session removes it and picks another active one`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        connections.openSession("host-1", "MacBook", 80, 24)
        val second = connections.openSession("host-2", "Server", 80, 24)

        connections.close(second.id)

        assertThat(connections.sessions.value).hasSize(1)
        assertThat(connections.activeSessionId.value).isNotNull()
    }

    @Test
    fun `a closed host can be opened again as a fresh session`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        val first = connections.openSession("host-1", "MacBook", 80, 24)
        connections.close(first.id)

        val second = connections.openSession("host-1", "MacBook", 80, 24)

        assertThat(second.id).isNotEqualTo(first.id)
    }

    /**
     * Leaving the terminal for the host list and tapping the same host returns to the session
     * that was there, not to a new one. Identity is covered above;
     * what makes it the same terminal is that the connection and the history came with it.
     */
    @Test
    fun `going back to the list and returning keeps the connection and the history`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        val session = connections.openSession("host-1", "MacBook", 20, 5)
        session.connect(config())
        repeat(30) { transports.getValue("host-1").emit("line$it\r\n") }
        val historyBefore = session.maxScrollOffset()
        val screenBefore = session.emulator.buffer.line(0)?.text()?.trimEnd()
        assertThat(historyBefore).isGreaterThan(0)

        // Back to the host list, then the same host again.
        connections.setActive(null)
        val returned = connections.openSession("host-1", "MacBook", 20, 5)

        assertThat(returned).isSameInstanceAs(session)
        assertThat(connections.activeSessionId.value).isEqualTo(session.id)
        assertThat(returned.state.value).isInstanceOf(ConnectionState.Connected::class.java)
        assertThat(returned.maxScrollOffset()).isEqualTo(historyBefore)
        assertThat(returned.emulator.buffer.line(0)?.text()?.trimEnd()).isEqualTo(screenBefore)
    }

    @Test
    fun `output that arrives while the list is showing is still there on return`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        val session = connections.openSession("host-1", "MacBook", 20, 5)
        session.connect(config())
        connections.setActive(null)

        transports.getValue("host-1").emit("while away\r\n")
        val returned = connections.openSession("host-1", "MacBook", 20, 5)

        // The screen kept running behind the list, so the line is on it rather than lost.
        assertThat(returned.emulator.buffer.line(0)?.text()?.trimEnd()).isEqualTo("while away")
    }

    /**
     * The handshake belongs to the session, not to the manager that started it. While it belonged
     * to the manager, closing a session that had a connect queued removed it from the list and
     * then went on to connect its transport — a connection nothing owned and nothing could close.
     */
    @Test
    fun `a connection queued when the session closes never runs`() = runTest {
        val connections = manager(backgroundScope)
        val session = connections.openSession("host-1", "MacBook", 80, 24)
        var finished = false
        session.launchConnect(config()) { finished = true }

        connections.close(session.id)
        runCurrent()

        assertThat(connections.sessions.value).isEmpty()
        assertThat(transports.getValue("host-1").connected).isFalse()
        assertThat(finished).isFalse()
    }

    /**
     * The handshake is already running rather than queued, so it ends in cancellation rather than
     * never starting. `runCatching` folded that into a `Result` and let the completion run for a
     * session that had left the list.
     */
    @Test
    fun `a connection cancelled mid handshake does not run its completion`() = runTest {
        val connections = manager(backgroundScope)
        val session = connections.openSession("host-1", "MacBook", 80, 24)
        transports.getValue("host-1").holdConnect()
        var finished = false
        session.launchConnect(config()) { finished = true }
        runCurrent()

        connections.close(session.id)
        runCurrent()

        assertThat(finished).isFalse()
        assertThat(transports.getValue("host-1").connected).isFalse()
    }

    @Test
    fun `closing one host leaves another host's connection alone`() = runTest(UnconfinedTestDispatcher()) {
        val connections = manager(backgroundScope)
        val first = connections.openSession("host-1", "MacBook", 80, 24)
        val second = connections.openSession("host-2", "Server", 80, 24)
        first.launchConnect(config())

        connections.close(second.id)

        assertThat(connections.sessions.value).containsExactly(first)
        assertThat(transports.getValue("host-1").connected).isTrue()
    }

    /**
     * Real threads rather than a test dispatcher: the two callers are a tap on the main thread and
     * a reconnect on a background one, and what used to go wrong was the interleaving of a lookup
     * and a write that no single-threaded scheduler can produce.
     */
    @Test
    fun `opening the same host from several threads produces one session`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CountDownLatch(1)
        val connections = ConnectionManager(
            // Sleeps where the transport would be dialled, which widens the window between the
            // lookup and the list write to something a thread scheduler will land inside.
            transportFactory = { _, _ ->
                Thread.sleep(20)
                FakeTransport()
            },
            scope = scope,
        )
        val opened = Collections.synchronizedList(mutableListOf<TerminalSession>())
        val threads = (1..8).map {
            Thread {
                gate.await()
                opened += connections.openSession("host-1", "MacBook", 80, 24)
            }
        }

        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join() }

        assertThat(connections.sessions.value).hasSize(1)
        assertThat(opened.toList().distinct()).hasSize(1)
        scope.cancel()
    }

    private fun config(columns: Int = 20, rows: Int = 5) = ConnectionConfig(
        host = "example.test",
        username = "someone",
        password = "secret",
        initialColumns = columns,
        initialRows = rows,
    )
}
