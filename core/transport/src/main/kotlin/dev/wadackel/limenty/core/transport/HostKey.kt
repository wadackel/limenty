package dev.wadackel.limenty.core.transport

/** A host key as presented by the server, in the shape the trust dialog needs. */
data class HostKeyInfo(
    /** e.g. `ssh-ed25519`, `ecdsa-sha2-nistp256`. */
    val algorithm: String,
    /** The `SHA256:...` fingerprint, formatted exactly as OpenSSH prints it. */
    val fingerprint: String,
    /** The raw key blob, stored so a later comparison is exact rather than fingerprint-only. */
    val key: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is HostKeyInfo && algorithm == other.algorithm && key.contentEquals(other.key)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + key.contentHashCode()

    override fun toString(): String = "HostKeyInfo($algorithm, $fingerprint)"
}

/**
 * The trust decision for a host key.
 *
 * Asking is asynchronous because it is a user decision made mid-handshake; the transport blocks
 * on it rather than guessing, which is what makes "never auto-accept a changed key" enforceable.
 */
interface HostKeyVerifier {
    suspend fun verify(host: String, port: Int, key: HostKeyInfo): Decision

    enum class Decision {
        /** Trusted for this session and remembered. */
        Accept,

        /** Refused; the connection fails with [TransportError.HostKeyRejected]. */
        Reject,
    }
}

/** Persistence of previously trusted keys. Implemented on the Android side. */
interface KnownHostsStore {
    suspend fun lookup(host: String, port: Int): List<HostKeyInfo>

    /**
     * Both writes answer with what became of them. Callers are free to carry on regardless — a
     * key that could not be stored is a question asked again, not a connection to refuse — but
     * they may not claim the key was kept, and a delete that failed leaves trust on the disk for
     * a host that has left the list.
     */
    suspend fun remember(host: String, port: Int, key: HostKeyInfo): Result<Unit>

    suspend fun forget(host: String, port: Int): Result<Unit>
}
