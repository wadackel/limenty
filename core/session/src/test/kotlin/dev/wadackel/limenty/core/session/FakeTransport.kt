package dev.wadackel.limenty.core.session

import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import dev.wadackel.limenty.core.transport.TransportError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Stands in for SSH so the session's own behaviour — pumping, resize propagation, scroll follow —
 * can be asserted without a server. Protocol behaviour is covered by the transport's own tests.
 */
class FakeTransport : TerminalTransport {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: MutableStateFlow<ConnectionState> get() = _state

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val output: Flow<ByteArray> = channel.receiveAsFlow()

    val written = mutableListOf<ByteArray>()
    val resizes = mutableListOf<Pair<Int, Int>>()

    /** The pixel size that travelled with each entry of [resizes]. */
    val pixelSizes = mutableListOf<Pair<Int, Int>>()
    val droppedResizes = mutableListOf<Pair<Int, Int>>()
    var connected = false
        private set

    var failWith: TransportError? = null

    private var handshake: CompletableDeferred<Unit>? = null

    /** Holds [connect] where the real one waits on the network, so a test can act mid-handshake. */
    fun holdConnect() {
        handshake = CompletableDeferred()
    }

    override suspend fun connect(config: ConnectionConfig) {
        handshake?.await()
        failWith?.let {
            _state.value = ConnectionState.Disconnected(it)
            return
        }
        connected = true
        _state.value = ConnectionState.Connected
    }

    private var gate: CompletableDeferred<Unit>? = null

    /**
     * Completes when a write has run into [stall], which is how a test lines the writer up
     * deterministically: hand over one known message, wait here, then pile on what the test is
     * about, and only then [release].
     */
    var entered = CompletableDeferred<Unit>()
        private set

    /**
     * Makes the next writes wait until [release], standing in for a remote that stopped reading.
     *
     * Only for what happens *while* the remote is stuck. The real transport blocks inside a
     * stream write that cancellation cannot interrupt; this wait is cancellable, so it says
     * nothing about how `destroy()` behaves against a stuck write.
     */
    fun stall() {
        gate = CompletableDeferred()
        entered = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun write(data: ByteArray) {
        gate?.let { open ->
            entered.complete(Unit)
            open.await()
        }
        written += data
    }

    /** Mirrors the real transport: a resize before the shell exists has nowhere to go. */
    override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (!connected) {
            droppedResizes += columns to rows
            return
        }
        resizes += columns to rows
        pixelSizes += widthPx to heightPx
    }

    override suspend fun disconnect() {
        connected = false
        _state.value = ConnectionState.Disconnected()
    }

    /** Simulates remote output arriving. */
    suspend fun emit(text: String) {
        channel.send(text.toByteArray(Charsets.UTF_8))
    }

    fun writtenText(): String = written.joinToString("") { String(it, Charsets.UTF_8) }
}
