package dev.wadackel.limenty.core.transport

import dev.wadackel.limenty.core.common.log.Logger
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier

/**
 * Bridges SSHJ's synchronous host key callback onto the app's asynchronous trust decision.
 *
 * SSHJ calls this from the handshake thread and requires an answer before proceeding, so the
 * suspending decision is awaited with [runBlocking]. That is safe here precisely because the
 * thread is SSHJ's own: nothing else is waiting on it, and blocking is the protocol's semantics.
 */
internal class TrustDelegatingVerifier(
    private val config: ConnectionConfig,
    private val verifier: HostKeyVerifier,
    private val knownHosts: KnownHostsStore,
    private val log: Logger,
) : SshjHostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val info = describe(key)
        val remembered = runBlocking { knownHosts.lookup(config.host, config.port) }

        val matching = remembered.firstOrNull { it.algorithm == info.algorithm }
        if (matching != null) {
            if (matching == info) {
                log.i("hostkey.known", "host" to config.host, "algorithm" to info.algorithm)
                return true
            }
            // A changed key is never accepted silently, and never resolved by asking
            // the user a question they cannot evaluate mid-handshake.
            log.e(
                "hostkey.mismatch",
                "host" to config.host,
                "expected" to matching.fingerprint,
                "actual" to info.fingerprint,
            )
            throw HostKeyMismatchException(matching.fingerprint, info.fingerprint)
        }
        // A host already trusted that now offers only a kind of key it never showed is treated as
        // changed, not as new. Asking would put the first-connection question to the user, and
        // an impostor announcing a LAN host's name needs only a different key type to get it.
        if (remembered.isNotEmpty()) {
            val expected = remembered.joinToString(", ") { "${it.algorithm} ${it.fingerprint}" }
            val actual = "${info.algorithm} ${info.fingerprint}"
            log.e("hostkey.typeChanged", "host" to config.host, "expected" to expected, "actual" to actual)
            throw HostKeyMismatchException(expected, actual)
        }

        log.i("hostkey.unknown", "host" to config.host, "fingerprint" to info.fingerprint)
        val decision = runBlocking { verifier.verify(config.host, config.port, info) }
        return when (decision) {
            HostKeyVerifier.Decision.Accept -> {
                // The connection goes ahead either way; only the claim that the key was kept is
                // withheld, so a later prompt for the same fingerprint has a record explaining it.
                runBlocking { knownHosts.remember(config.host, config.port, info) }
                    .onSuccess {
                        log.i("hostkey.trusted", "host" to config.host, "fingerprint" to info.fingerprint)
                    }
                    .onFailure { log.w("hostkey.trustNotStored", "host" to config.host) }
                true
            }
            HostKeyVerifier.Decision.Reject -> {
                log.w("hostkey.rejected", "host" to config.host, "fingerprint" to info.fingerprint)
                throw HostKeyRejectedException("host key rejected by user")
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = knownSignatureAlgorithms(runBlocking { knownHosts.lookup(config.host, config.port) }.map { it.algorithm })

    private fun describe(key: PublicKey): HostKeyInfo = HostKeyDescription.of(key)
}

/**
 * The signature algorithms to ask for first, given the key types already trusted for a host.
 *
 * SSHJ matches these against signature names exactly. An RSA key is stored as `ssh-rsa`, which
 * OpenSSH 8.8 and later no longer signs with; asked only for that, a server holding an ed25519
 * key as well negotiates ed25519, and a host trusted for RSA reads as one whose key changed.
 */
internal fun knownSignatureAlgorithms(keyTypes: List<String>): List<String> = keyTypes.flatMap { type ->
    if (type == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", type) else listOf(type)
}

/**
 * Describes a host key the way OpenSSH does.
 *
 * The fingerprint is what the user compares against `ssh-keygen -lf` before trusting a host, so
 * it has to be the same string down to the padding. It is taken from the SSH wire encoding rather
 * than `PublicKey.encoded`, which is X.509 DER and hashes to something that matches nothing the
 * user can check. The stored blob uses that same encoding, so a later comparison is exact.
 */
internal object HostKeyDescription {
    fun of(key: PublicKey): HostKeyInfo {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return HostKeyInfo(
            algorithm = KeyType.fromKey(key).toString(),
            fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest),
            key = blob,
        )
    }
}
