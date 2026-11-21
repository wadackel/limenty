package dev.wadackel.limenty.core.transport

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.signature.BuiltinSignatures
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * SSH connection behaviour, exercised against a real SSH server running in this process.
 */
class SshTransportTest {
    private lateinit var server: TestSshServer
    private lateinit var knownHosts: InMemoryKnownHosts
    private lateinit var verifier: RecordingHostKeyVerifier
    private var transport: SshTransport? = null

    @Before
    fun setUp() {
        server = TestSshServer().start()
        knownHosts = InMemoryKnownHosts()
        verifier = RecordingHostKeyVerifier()
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { transport?.disconnect() } }
        transport?.shutdown()
        server.stop()
    }

    private fun newTransport(
        hostKeyVerifier: HostKeyVerifier = verifier,
        store: KnownHostsStore = knownHosts,
    ): SshTransport = SshTransport(hostKeyVerifier, store).also { transport = it }

    private fun config(
        host: String = "127.0.0.1",
        port: Int = server.port,
        username: String = "tester",
        password: String = "correct horse",
        columns: Int = 80,
        rows: Int = 24,
        timeoutMillis: Long = 10_000,
    ) = ConnectionConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        initialColumns = columns,
        initialRows = rows,
        connectTimeoutMillis = timeoutMillis,
    )

    /** Collects output until [predicate] is satisfied, so assertions do not race the read loop. */
    private suspend fun SshTransport.readUntil(timeoutMillis: Long = 5_000, predicate: (String) -> Boolean): String {
        val builder = StringBuilder()
        return withTimeout(timeoutMillis) {
            output.first { chunk ->
                builder.append(String(chunk, Charsets.UTF_8))
                predicate(builder.toString())
            }
            builder.toString()
        }
    }

    // --- connection ---

    @Test
    fun `an idle connection keeps sending keep-alives`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config().copy(keepAliveIntervalSeconds = 1))

        val deadline = System.currentTimeMillis() + 5_000
        while (server.ignoredMessages.get() < 2 && System.currentTimeMillis() < deadline) delay(100)
        assertThat(server.ignoredMessages.get()).isAtLeast(2)
    }

    @Test
    fun `a successful connection reaches the connected state`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())

        assertThat(ssh.state.value).isEqualTo(ConnectionState.Connected)
    }

    @Test
    fun `the shell output reaches the transport`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())

        assertThat(ssh.readUntil { it.contains("ready") }).contains("ready")
    }

    @Test
    fun `written bytes reach the remote`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.readUntil { it.contains("ready") }

        ssh.write("echo hello".toByteArray())

        assertThat(ssh.readUntil { it.contains("echo hello") }).contains("echo hello")
    }

    @Test
    fun `utf-8 survives the round trip`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.readUntil { it.contains("ready") }

        ssh.write("日本語".toByteArray(Charsets.UTF_8))

        assertThat(ssh.readUntil { it.contains("日本語") }).contains("日本語")
    }

    @Test
    fun `control bytes are delivered verbatim`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.readUntil { it.contains("ready") }

        ssh.write(byteArrayOf(0x03, 0x1B, 0x0D))

        val echoed = withTimeout(5_000) { ssh.output.first() }
        assertThat(echoed.toList()).containsAtLeast(0x03.toByte(), 0x1B.toByte(), 0x0D.toByte())
    }

    // --- authentication ---

    @Test
    fun `a wrong password fails with an authentication error`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config(password = "wrong"))

        val state = ssh.state.value
        assertThat(state).isInstanceOf(ConnectionState.Disconnected::class.java)
        assertThat((state as ConnectionState.Disconnected).error).isInstanceOf(TransportError.Authentication::class.java)
    }

    @Test
    fun `an unknown user fails with an authentication error rather than hanging`(): Unit = runBlocking {
        val ssh = newTransport()
        withTimeout(15_000) { ssh.connect(config(username = "nobody")) }

        val state = ssh.state.value as ConnectionState.Disconnected
        assertThat(state.error).isInstanceOf(TransportError.Authentication::class.java)
    }

    @Test
    fun `a failed connection can be retried in place`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config(password = "wrong"))
        ssh.connect(config())

        assertThat(ssh.state.value).isEqualTo(ConnectionState.Connected)
    }

    // --- host key verification ---

    @Test
    fun `an unknown host key is presented to the user once and then remembered`(): Unit = runBlocking {
        newTransport().connect(config())
        assertThat(verifier.askCount).isEqualTo(1)
        assertThat(verifier.asked?.fingerprint).startsWith("SHA256:")

        transport?.disconnect()
        transport?.shutdown()

        val second = SshTransport(verifier, knownHosts).also { transport = it }
        second.connect(config())

        assertThat(verifier.askCount).isEqualTo(1)
        assertThat(second.state.value).isEqualTo(ConnectionState.Connected)
    }

    @Test
    fun `the handshake waits while the user reads the fingerprint`(): Unit = runBlocking {
        // The key exchange has its own deadline, separate from the socket timeout, and it defaults
        // to a value shorter than a person takes to compare a fingerprint. A first connection used
        // to abort with a protocol timeout while the prompt was still on screen.
        val slowVerifier = RecordingHostKeyVerifier(deliberationMillis = 35_000)
        val ssh = newTransport(hostKeyVerifier = slowVerifier)

        withTimeout(90_000) { ssh.connect(config(timeoutMillis = 15_000)) }

        assertThat(ssh.state.value).isEqualTo(ConnectionState.Connected)
        assertThat(slowVerifier.askCount).isEqualTo(1)
    }

    @Test
    fun `rejecting the host key aborts the connection`(): Unit = runBlocking {
        val ssh = newTransport(hostKeyVerifier = RecordingHostKeyVerifier(HostKeyVerifier.Decision.Reject))
        ssh.connect(config())

        val state = ssh.state.value as ConnectionState.Disconnected
        assertThat(state.error).isEqualTo(TransportError.HostKeyRejected)
    }

    @Test
    fun `a changed host key is refused without asking the user`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        val realKey = verifier.asked!!
        ssh.disconnect()
        ssh.shutdown()

        val tampered = InMemoryKnownHosts().apply {
            remember("127.0.0.1", server.port, realKey.copy(key = realKey.key.clone().also { it[0]++ }))
        }
        val freshVerifier = RecordingHostKeyVerifier()
        val second = SshTransport(freshVerifier, tampered).also { transport = it }
        second.connect(config())

        val state = second.state.value as ConnectionState.Disconnected
        assertThat(state.error).isInstanceOf(TransportError.HostKeyMismatch::class.java)
        assertThat(freshVerifier.askCount).isEqualTo(0)
    }

    @Test
    fun `a key of a type not yet trusted for a known host is refused without asking the user`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        val realKey = verifier.asked!!
        ssh.disconnect()
        ssh.shutdown()

        val trustedOtherType = InMemoryKnownHosts().apply {
            remember("127.0.0.1", server.port, realKey.copy(algorithm = "ssh-ed25519"))
        }
        val freshVerifier = RecordingHostKeyVerifier()
        val second = SshTransport(freshVerifier, trustedOtherType).also { transport = it }
        second.connect(config())

        val state = second.state.value as ConnectionState.Disconnected
        assertThat(state.error).isInstanceOf(TransportError.HostKeyMismatch::class.java)
        assertThat(freshVerifier.askCount).isEqualTo(0)
    }

    @Test
    fun `a host trusted only for RSA connects to a server that no longer signs with ssh-rsa`(): Unit = runBlocking {
        // Shaped like OpenSSH 8.8 and later: an RSA key it signs for only as rsa-sha2-*, and an
        // ECDSA key that the client would otherwise rank above RSA.
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val ecdsa = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val modern = TestSshServer(configure = {
            keyPairProvider = KeyPairProvider.wrap(rsa, ecdsa)
            signatureFactories = listOf(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256, BuiltinSignatures.nistp256)
        }).start()
        try {
            val trustedForRsa = InMemoryKnownHosts().apply {
                remember("127.0.0.1", modern.port, HostKeyDescription.of(rsa.public))
            }
            val freshVerifier = RecordingHostKeyVerifier()
            val ssh = SshTransport(freshVerifier, trustedForRsa).also { transport = it }
            ssh.connect(config(port = modern.port))

            assertThat(ssh.state.value).isEqualTo(ConnectionState.Connected)
            assertThat(freshVerifier.askCount).isEqualTo(0)
        } finally {
            runCatching { transport?.disconnect() }
            modern.stop()
        }
    }

    // --- failures ---

    @Test
    fun `an unresolvable hostname is reported as a DNS failure`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config(host = "no-such-host.invalid"))

        val state = ssh.state.value as ConnectionState.Disconnected
        assertThat(state.error).isInstanceOf(TransportError.HostResolution::class.java)
    }

    @Test
    fun `a host reached at another address keeps its key under its own name`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config(host = "limenty-test.invalid").copy(address = "127.0.0.1"))

        assertThat(ssh.state.value).isEqualTo(ConnectionState.Connected)
        assertThat(knownHosts.lookup("limenty-test.invalid", server.port)).hasSize(1)
        assertThat(knownHosts.lookup("127.0.0.1", server.port)).isEmpty()
    }

    @Test
    fun `a host with no address on this network is not dialled by its name`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config(host = "localhost").copy(address = null))

        val state = ssh.state.value as ConnectionState.Disconnected
        assertThat(state.error).isEqualTo(TransportError.HostResolution("localhost"))
        assertThat(verifier.askCount).isEqualTo(0)
    }

    @Test
    fun `a closed port is reported as refused`(): Unit = runBlocking {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val ssh = newTransport()
        ssh.connect(config(port = closedPort))

        val state = ssh.state.value as ConnectionState.Disconnected
        assertThat(state.error).isEqualTo(TransportError.ConnectionRefused)
    }

    @Test
    fun `connect does not hang past its timeout`(): Unit = runBlocking {
        val ssh = newTransport()
        // 10.255.255.1 is routable-looking but black-holed, so the socket stalls rather than failing.
        val completed = withTimeoutOrNull(20_000) {
            ssh.connect(config(host = "10.255.255.1", timeoutMillis = 3_000))
            true
        }
        assertThat(completed).isTrue()
        assertThat(ssh.state.value).isInstanceOf(ConnectionState.Disconnected::class.java)
    }

    // --- lifecycle ---

    @Test
    fun `a remote exit is a normal ending rather than an error`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.readUntil { it.contains("ready") }

        ssh.write(byteArrayOf(0x04))

        val state = withTimeout(10_000) {
            ssh.state.first { it is ConnectionState.Disconnected }
        } as ConnectionState.Disconnected
        assertThat(state.error).isNull()
    }

    @Test
    fun `the server dropping the connection is reported as a loss`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.readUntil { it.contains("ready") }

        server.disconnectAll()

        val state = withTimeout(10_000) {
            ssh.state.first { it is ConnectionState.Disconnected }
        } as ConnectionState.Disconnected
        assertThat(state).isInstanceOf(ConnectionState.Disconnected::class.java)
    }

    @Test
    fun `an explicit disconnect is not reported as an error`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.disconnect()

        val state = ssh.state.value as ConnectionState.Disconnected
        assertThat(state.error).isNull()
    }

    @Test
    fun `writing after a disconnect does not throw`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.disconnect()

        ssh.write("ignored".toByteArray())
    }

    // --- PTY ---

    @Test
    fun `resizing an established session is accepted`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config(columns = 80, rows = 24))
        ssh.readUntil { it.contains("ready") }

        ssh.resize(120, 40, 1200, 800)
        ssh.resize(60, 20, 600, 400)

        assertThat(ssh.state.value).isEqualTo(ConnectionState.Connected)
    }

    @Test
    fun `resizing before a connection exists is harmless`(): Unit = runBlocking {
        newTransport().resize(80, 24, 800, 480)
    }

    // --- state observability ---

    @Test
    fun `the state starts idle`() {
        assertThat(newTransport().state.value).isEqualTo(ConnectionState.Idle)
    }

    @Test
    fun `a large burst of output is delivered without loss`(): Unit = runBlocking {
        val ssh = newTransport()
        ssh.connect(config())
        ssh.readUntil { it.contains("ready") }

        val payload = "x".repeat(200_000)
        ssh.write(payload.toByteArray())

        var received = 0
        withTimeout(30_000) {
            ssh.output.first { chunk ->
                received += chunk.size
                received >= payload.length
            }
        }
        assertThat(received).isAtLeast(payload.length)
    }

    /**
     * A session with nothing to say must survive. The socket read timeout doubles as the connect
     * deadline in sshj, so a short deadline used to reach the reader thread and tear the transport
     * down while the user was simply not typing.
     */
    @Test
    fun idleSessionSurvivesLongerThanTheConnectTimeout() = runBlocking {
        val transport = newTransport()
        transport.connect(config(timeoutMillis = 2_000))
        assertThat(transport.state.value).isInstanceOf(ConnectionState.Connected::class.java)

        val idleMillis = 6_000L
        val died = withTimeoutOrNull(idleMillis) {
            transport.state.first { it is ConnectionState.Disconnected }
        }

        assertThat(died).isNull()
        assertThat(transport.state.value).isInstanceOf(ConnectionState.Connected::class.java)

        transport.write("still here\n".toByteArray())
        assertThat(transport.readUntil { it.contains("still here") }).contains("still here")
    }
}
