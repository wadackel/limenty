package dev.wadackel.limenty

import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A transport that never connects, for tests about what the app does with a session's screen
 * rather than with its connection. Output is written straight into the emulator instead.
 */
class OfflineTransport : TerminalTransport {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)
    override val output: Flow<ByteArray> = emptyFlow()

    override suspend fun connect(config: ConnectionConfig) = Unit

    override suspend fun write(data: ByteArray) = Unit

    override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit

    override suspend fun disconnect() = Unit
}

fun offlineSession(columns: Int = 40, rows: Int = 12): TerminalSession = TerminalSession(
    id = SessionId("test"),
    hostId = "host",
    hostName = "Host",
    transport = OfflineTransport(),
    columns = columns,
    rows = rows,
)
