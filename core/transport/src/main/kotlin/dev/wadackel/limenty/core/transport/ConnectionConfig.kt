package dev.wadackel.limenty.core.transport

/**
 * Everything needed to open one session.
 *
 * The password is a plain [String] rather than a char array: it arrives from Android's
 * `EditText` as a String anyway, so a char array here would provide the appearance of zeroing
 * without the substance. It is kept out of logs by [toString] instead.
 */
data class ConnectionConfig(
    /** The name the host is known by: what its key is trusted under, and what the user sees. */
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val password: String,
    val initialColumns: Int,
    val initialRows: Int,
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val keepAliveIntervalSeconds: Int = DEFAULT_KEEPALIVE_SECONDS,
    /**
     * Where to open the socket, when that is not [host] itself: a LAN host is trusted under its
     * Bonjour name but reached at whatever address that name has right now. Null means the name
     * could not be found on this network, and nothing is dialled.
     */
    val address: String? = host,
) {
    override fun toString(): String = "ConnectionConfig(host=$host, address=$address, port=$port, username=$username, password=<redacted>, " +
        "size=${initialColumns}x$initialRows, term=$terminalType)"

    companion object {
        const val DEFAULT_PORT = 22

        /**
         * What the remote's terminfo is keyed on. The emulator implements the xterm sequences
         * this name promises, so claiming anything richer would make applications emit sequences
         * that are then dropped.
         */
        const val DEFAULT_TERMINAL_TYPE = "xterm-256color"

        /**
         * Finite, so a host that never answers ends in an error the user can act on instead of a
         * connect that hangs. Not user-configurable.
         */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L

        /** Keeps a backgrounded session alive as long as Android allows. */
        const val DEFAULT_KEEPALIVE_SECONDS = 30
    }
}
