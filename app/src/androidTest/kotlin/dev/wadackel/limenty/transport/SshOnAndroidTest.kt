package dev.wadackel.limenty.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.HostKeyInfo
import dev.wadackel.limenty.core.transport.HostKeyVerifier
import dev.wadackel.limenty.core.transport.KnownHostsStore
import dev.wadackel.limenty.core.transport.SshSecurity
import dev.wadackel.limenty.core.transport.SshTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.DefaultConfig
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security

/**
 * Proves the SSH stack works on ART, which the JVM tests cannot.
 *
 * The failure this guards against is specific and was real: Android ships a cut-down provider
 * named `BC`, and with it SSHJ offers only host key algorithms that current OpenSSH refuses.
 */
@RunWith(AndroidJUnit4::class)
class SshOnAndroidTest {

    @Before
    fun setUp() {
        SshSecurity.ensureInstalled()
    }

    @Test
    fun bouncyCastleIsInstalledAheadOfThePlatformProvider() {
        val provider = Security.getProviders().first()
        assertThat(provider.name).isEqualTo("BC")
        assertThat(provider.version).isAtLeast(1.8)
    }

    @Test
    fun theAlgorithmsModernOpenSshNegotiatesAreAvailable() {
        val config = DefaultConfig()

        val hostKeys = config.keyAlgorithms.map { it.name }
        assertThat(hostKeys).contains("ssh-ed25519")
        assertThat(hostKeys).contains("rsa-sha2-512")
        assertThat(hostKeys).contains("rsa-sha2-256")
        assertThat(hostKeys.any { it.startsWith("ecdsa-sha2-") }).isTrue()

        val kex = config.keyExchangeFactories.map { it.name }
        assertThat(kex.any { it.contains("curve25519") }).isTrue()

        val ciphers = config.cipherFactories.map { it.name }
        assertThat(ciphers.any { it.startsWith("chacha20") || it.contains("gcm") }).isTrue()
    }

    @Test
    fun theHostKeyAlgorithmsOpenSshDisabledAreNotTheOnlyOnesOffered() {
        // The regression: with sshj's AndroidConfig the list was exactly ssh-rsa / ssh-dss /
        // ssh-ed25519, and the first two have been refused by OpenSSH since 8.8.
        val hostKeys = DefaultConfig().keyAlgorithms.map { it.name }.toSet()
        assertThat(hostKeys - setOf("ssh-rsa", "ssh-dss")).isNotEmpty()
    }

    /**
     * End-to-end against the embedded server on the developer machine:
     * `./gw :core:transport:runTestSshServer -PsshPort=2222`, then run the instrumentation with
     * `-Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2`.
     */
    @Test
    fun connectsToATestServerWhenOneIsReachable() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("sshHost")
        assumeTrue("no sshHost argument; skipping the end-to-end check", host != null)
        val port = arguments.getString("sshPort")?.toIntOrNull() ?: 2222

        val transport = SshTransport(AcceptingVerifier, MemoryKnownHosts())
        transport.connect(
            ConnectionConfig(
                host = host!!,
                port = port,
                username = arguments.getString("sshUser") ?: "tester",
                password = arguments.getString("sshPassword") ?: "correct horse",
                initialColumns = 80,
                initialRows = 24,
                connectTimeoutMillis = 15_000,
            ),
        )

        assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)

        val greeting = withTimeout(10_000) { transport.output.first() }
        assertThat(String(greeting, Charsets.UTF_8)).contains("ready")

        transport.write("日本語".toByteArray(Charsets.UTF_8))
        val echoed = withTimeout(10_000) {
            val builder = StringBuilder()
            transport.output.first { chunk ->
                builder.append(String(chunk, Charsets.UTF_8))
                builder.contains("日本語")
            }
            builder.toString()
        }
        assertThat(echoed).contains("日本語")

        transport.resize(120, 40, 1200, 800)
        assertThat(transport.state.value).isEqualTo(ConnectionState.Connected)

        transport.disconnect()
        transport.shutdown()
    }

    private object AcceptingVerifier : HostKeyVerifier {
        override suspend fun verify(host: String, port: Int, key: HostKeyInfo) = HostKeyVerifier.Decision.Accept
    }

    private class MemoryKnownHosts : KnownHostsStore {
        private val entries = mutableMapOf<String, MutableList<HostKeyInfo>>()

        override suspend fun lookup(host: String, port: Int) = entries["$host:$port"].orEmpty()

        override suspend fun remember(host: String, port: Int, key: HostKeyInfo): Result<Unit> {
            entries.getOrPut("$host:$port") { mutableListOf() }.add(key)
            return Result.success(Unit)
        }

        override suspend fun forget(host: String, port: Int): Result<Unit> {
            entries.remove("$host:$port")
            return Result.success(Unit)
        }
    }
}
