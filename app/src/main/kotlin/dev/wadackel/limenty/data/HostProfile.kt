package dev.wadackel.limenty.data

import java.util.UUID

/**
 * A saved connection target.
 *
 * This is deliberately small: no folders, ordering, favourites or tags, because the
 * expected host count is a handful and every one of those would be a feature to maintain.
 * The password lives in [CredentialStore], never here.
 */
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostname: String,
    val username: String,
    val port: Int = DEFAULT_PORT,
    /**
     * Whether [hostname] is the Bonjour name of an `_ssh._tcp` service rather than a DNS name or an
     * address. Such a host is looked up on the current network at every connection, so the
     * address DHCP happens to give it today is never what is saved.
     */
    val lan: Boolean = false,
) {
    /**
     * What the list calls this host.
     *
     * A blank name falls back to the machine's own part of the address rather than the whole of
     * it: the form shows that short form as the name it will use, and a tailnet's suffix repeated
     * on the line below is not a name anyone chose. A service name is already the name someone
     * chose, and cutting it at a dot turned `alice.smith’s Mac` into `alice`.
     */
    val displayName: String get() = name.ifBlank { machineName }.ifBlank { hostname }

    /** The part of [hostname] that names the machine. */
    val machineName: String get() = if (lan) hostname else shortHostName(hostname)

    companion object {
        const val DEFAULT_PORT = 22
    }
}

/**
 * The machine's own part of [hostname]: its first label for a DNS name, the whole of it for an IP
 * literal. An address has no part that names the machine, and cutting `10.0.2.2` at its first dot
 * named the host `10`.
 */
fun shortHostName(hostname: String): String {
    val literal = ':' in hostname || hostname.all { it.isDigit() || it == '.' }
    return if (literal) hostname else hostname.substringBefore('.')
}
