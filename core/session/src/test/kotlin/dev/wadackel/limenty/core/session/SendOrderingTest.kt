package dev.wadackel.limenty.core.session

import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the app sends leaves in the order it was handed over.
 *
 * Real dispatchers rather than virtual time, because the thing under test is what happens when two
 * writes are in flight at once — which a single test thread cannot produce.
 */
class SendOrderingTest {
    private val steps = 6

    @Test
    fun `bytes handed over in order reach the transport in that order`() {
        val transport = SlowTransport(steps)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession(
            id = SessionId("s1"),
            hostId = "host-1",
            hostName = "MacBook",
            transport = transport,
            columns = 20,
            rows = 5,
            parentScope = scope,
        )

        // A key that sends a sequence hands its steps over one after another with nothing in
        // between, which is the case that showed this: `C-s` then `w`, arriving the wrong way
        // round often enough for a tmux prefix to be swallowed and the letter to reach the shell.
        repeat(steps) { step -> session.send(byteArrayOf(step.toByte())) }

        assertEquals(true, transport.awaitAll())
        assertEquals((0 until steps).toList(), transport.written.toList())

        session.destroy()
        scope.cancel()
    }
}

/**
 * Takes longer over the writes handed to it first.
 *
 * The real one hops to a pool thread and then blocks on a socket, so the time a write takes has
 * nothing to do with the order it was asked for. Making the first write the slowest turns "these
 * may race" into "these will come back reversed unless something is keeping them in line".
 */
private class SlowTransport(private val steps: Int) : TerminalTransport {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    override val output: Flow<ByteArray> = emptyFlow()

    val written = CopyOnWriteArrayList<Int>()
    private val finished = CountDownLatch(steps)

    override suspend fun connect(config: ConnectionConfig) = Unit

    override suspend fun write(data: ByteArray) {
        val step = data.first().toInt()
        withContext(Dispatchers.IO) {
            delay((steps - step) * 10L)
            written += step
            finished.countDown()
        }
    }

    override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit

    override suspend fun disconnect() = Unit

    fun awaitAll(): Boolean = runBlocking { finished.await(10, TimeUnit.SECONDS) }
}
