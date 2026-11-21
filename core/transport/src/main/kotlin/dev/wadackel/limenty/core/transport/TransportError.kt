package dev.wadackel.limenty.core.transport

/**
 * Why a connection could not be established or could not continue.
 *
 * The user has to be able to tell these apart and to have a
 * sensible next action, so the cases are modelled rather than collapsed into a message string.
 */
sealed interface TransportError {
    /** No answer within the connect timeout. */
    data object Timeout : TransportError

    /**
     * The hostname did not resolve — typically a MagicDNS name while Tailscale is down, or a LAN
     * host that Bonjour cannot find on the network the device is on.
     */
    data class HostResolution(val host: String) : TransportError

    /** The host answered but nothing is listening on the port. */
    data object ConnectionRefused : TransportError

    /** The network itself is unavailable. */
    data object NetworkUnreachable : TransportError

    /** The credentials were rejected; the user can retry in place. */
    data class Authentication(val message: String? = null) : TransportError

    /**
     * The host presented a different key than the one previously trusted. Never resolved
     * automatically.
     */
    data class HostKeyMismatch(val expected: String, val actual: String) : TransportError

    /** The user declined to trust an unknown host key. */
    data object HostKeyRejected : TransportError

    /** The connection dropped after being established, e.g. keepalive failure. */
    data class ConnectionLost(val message: String? = null) : TransportError

    data class Protocol(val message: String) : TransportError

    data class Unknown(val message: String) : TransportError
}

/** A short, stable token for logs and for choosing which message to show. */
val TransportError.kind: String
    get() = when (this) {
        is TransportError.Timeout -> "timeout"
        is TransportError.HostResolution -> "dns"
        is TransportError.ConnectionRefused -> "refused"
        is TransportError.NetworkUnreachable -> "network"
        is TransportError.Authentication -> "auth"
        is TransportError.HostKeyMismatch -> "hostkey-mismatch"
        is TransportError.HostKeyRejected -> "hostkey-rejected"
        is TransportError.ConnectionLost -> "lost"
        is TransportError.Protocol -> "protocol"
        is TransportError.Unknown -> "unknown"
    }
