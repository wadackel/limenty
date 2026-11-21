package dev.wadackel.limenty.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A byte pipe to a remote shell, plus the window size it is attached to.
 *
 * Nothing above this interface names an SSH library, so the implementation can be replaced
 * without touching the terminal, the session layer or the UI.
 */
interface TerminalTransport {
    val state: StateFlow<ConnectionState>

    /**
     * Output from the remote, as it arrives.
     *
     * Chunks are delivered as received rather than line-buffered: a terminal must show a prompt
     * that has no trailing newline, and waiting for one would stall every interactive program.
     */
    val output: Flow<ByteArray>

    /** Suspends until the session is established or fails. */
    suspend fun connect(config: ConnectionConfig)

    suspend fun write(data: ByteArray)

    /**
     * Propagates to the PTY as a window-change, so tmux and nvim re-lay out.
     *
     * The pixel size rides along because a program drawing images divides it by the cell count
     * to learn how big a cell is; zero tells it nothing and it guesses.
     */
    suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)

    suspend fun disconnect()
}
