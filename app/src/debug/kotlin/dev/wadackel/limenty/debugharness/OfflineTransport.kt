package dev.wadackel.limenty.debugharness

import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A transport that never connects, for harness screens that only need a screen to draw on. */
class OfflineTransport : TerminalTransport {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val output: Flow<ByteArray> = emptyFlow()

    override suspend fun connect(config: ConnectionConfig) = Unit

    override suspend fun write(data: ByteArray) = Unit

    override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit

    override suspend fun disconnect() = Unit
}
