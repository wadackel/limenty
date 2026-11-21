package dev.wadackel.limenty.core.transport

/**
 * The lifecycle of one transport.
 *
 * This is separate from the terminal screen's lifecycle: leaving the terminal must not
 * disturb the state machine, so the state lives with the transport rather than with any UI.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val host: String, val port: Int) : ConnectionState

    data object Authenticating : ConnectionState

    data object Connected : ConnectionState

    /**
     * [error] is null when the remote shell exited normally, which is treated as a success
     * path with its own UI rather than as a failure.
     */
    data class Disconnected(val error: TransportError? = null, val exitStatus: Int? = null) : ConnectionState

    val isActive: Boolean
        get() = this is Connecting || this is Authenticating || this is Connected
}
