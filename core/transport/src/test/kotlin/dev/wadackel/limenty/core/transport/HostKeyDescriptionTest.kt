package dev.wadackel.limenty.core.transport

import com.google.common.truth.Truth.assertThat
import net.schmizz.sshj.common.Buffer
import org.junit.Test
import java.security.PublicKey
import java.util.Base64

/**
 * The user compares a fingerprint before trusting a host, which only works if the
 * string matches the one OpenSSH prints. The expectations here were produced by `ssh-keygen -lf`
 * over the checked-in public keys, so this measures against OpenSSH rather than against itself.
 */
class HostKeyDescriptionTest {
    private fun publicKey(resource: String): PublicKey {
        val line = javaClass.getResourceAsStream("/$resource")!!.bufferedReader().readText().trim()
        val blob = Base64.getDecoder().decode(line.split(" ")[1])
        return Buffer.PlainBuffer(blob).readPublicKey()
    }

    @Test
    fun `an ed25519 fingerprint matches what ssh-keygen prints`() {
        val described = HostKeyDescription.of(publicKey("oracle_ed25519.pub"))

        assertThat(described.fingerprint)
            .isEqualTo("SHA256:eEi4wzGf44S2Yw8yoIuOF07kca5S1IxaJNaiNQ1ca2U")
        assertThat(described.algorithm).isEqualTo("ssh-ed25519")
    }

    @Test
    fun `an rsa fingerprint matches what ssh-keygen prints`() {
        val described = HostKeyDescription.of(publicKey("oracle_rsa.pub"))

        assertThat(described.fingerprint)
            .isEqualTo("SHA256:cWCmIPPuu/9mtU6BKB9CC5sSGvVHZ0f6oWjxyLD92rk")
        assertThat(described.algorithm).isEqualTo("ssh-rsa")
    }

    /** OpenSSH prints the digest without padding; a trailing `=` would fail a visual comparison. */
    @Test
    fun `the fingerprint is unpadded base64 behind the SHA256 label`() {
        val fingerprint = HostKeyDescription.of(publicKey("oracle_ed25519.pub")).fingerprint

        assertThat(fingerprint).startsWith("SHA256:")
        assertThat(fingerprint).doesNotContain("=")
        assertThat(fingerprint.removePrefix("SHA256:")).hasLength(43)
    }

    /** The stored blob is the wire encoding, which is what makes a later comparison exact. */
    @Test
    fun `the stored key is the ssh blob and not the java encoding`() {
        val key = publicKey("oracle_ed25519.pub")
        val described = HostKeyDescription.of(key)

        assertThat(described.key).isEqualTo(Buffer.PlainBuffer().putPublicKey(key).compactData)
        assertThat(described.key).isNotEqualTo(key.encoded)
    }

    @Test
    fun `two different keys do not describe the same`() {
        val ed25519 = HostKeyDescription.of(publicKey("oracle_ed25519.pub"))
        val rsa = HostKeyDescription.of(publicKey("oracle_rsa.pub"))

        assertThat(ed25519.fingerprint).isNotEqualTo(rsa.fingerprint)
        assertThat(ed25519).isNotEqualTo(rsa)
    }
}
