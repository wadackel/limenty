package dev.wadackel.limenty.core.transport

import org.apache.sshd.common.session.ReservedSessionMessagesHandler
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real SSH server, in-process.
 *
 * Authentication, PTY allocation, window-change and host key handling are protocol behaviour;
 * asserting them against a mock would only prove the mock matches the code under test, so the
 * tests speak SSH to an actual server instead.
 */
open class TestSshServer(
    private val expectedUser: String = "tester",
    private val expectedPassword: String = "correct horse",
    private val shellFactory: ShellFactory = EchoShell.Factory,
    private val requestedPort: Int = 0,
    private val bindHost: String = "127.0.0.1",
    /** Applied over the defaults, for a test that needs a server shaped differently. */
    private val configure: SshServer.() -> Unit = {},
) {
    private lateinit var server: SshServer
    private lateinit var hostKeyFile: File

    val port: Int get() = server.port

    /** `SSH_MSG_IGNORE` packets received, which is what sshj's keep-alive sends. */
    val ignoredMessages = AtomicInteger()

    fun start(): TestSshServer {
        hostKeyFile = Files.createTempFile("limenty-hostkey", ".ser").toFile().apply { delete() }
        server = SshServer.setUpDefaultServer().apply {
            host = bindHost
            this.port = requestedPort
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == expectedUser && password == expectedPassword
            }
            setShellFactory(this@TestSshServer.shellFactory)
            reservedSessionMessagesHandler = object : ReservedSessionMessagesHandler {
                override fun handleIgnoreMessage(session: Session, buffer: Buffer) {
                    ignoredMessages.incrementAndGet()
                }
            }
            configure()
        }
        server.start()
        return this
    }

    fun stop() {
        runCatching { server.stop(true) }
        runCatching { hostKeyFile.delete() }
    }

    fun activeSessions(): Int = server.activeSessions.size

    fun disconnectAll() {
        server.activeSessions.forEach { session -> runCatching { session.close(true) } }
    }
}

/** Echoes what it receives, so a test can assert that a write reached the far end and came back. */
class EchoShell : Command {
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null
    private var callback: org.apache.sshd.server.ExitCallback? = null
    private var pump: Thread? = null

    override fun setInputStream(inputStream: java.io.InputStream) {
        input = inputStream
    }

    override fun setOutputStream(outputStream: java.io.OutputStream) {
        output = outputStream
    }

    override fun setErrorStream(errorStream: java.io.OutputStream) = Unit

    override fun setExitCallback(exitCallback: org.apache.sshd.server.ExitCallback) {
        callback = exitCallback
    }

    override fun start(channel: org.apache.sshd.server.channel.ChannelSession, env: org.apache.sshd.server.Environment) {
        val source = input ?: return
        val sink = output ?: return
        pump = Thread {
            val buffer = ByteArray(1024)
            try {
                sink.write("ready\r\n".toByteArray())
                sink.flush()
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    // A lone Ctrl-D ends the shell, which is how the normal-exit path is tested.
                    if (read == 1 && buffer[0] == 0x04.toByte()) break
                    sink.write(buffer, 0, read)
                    sink.flush()
                }
            } catch (_: Exception) {
                // The client closing the channel surfaces as an IO failure; it is a normal ending.
            } finally {
                runCatching { callback?.onExit(0) }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun destroy(channel: org.apache.sshd.server.channel.ChannelSession) {
        pump?.interrupt()
    }

    object Factory : ShellFactory {
        override fun createShell(channel: org.apache.sshd.server.channel.ChannelSession): Command = EchoShell()
    }
}

/** Binds on every interface so an emulator can reach it through the host loopback alias. */
class FixedPortTestSshServer(port: Int) :
    TestSshServer(
        // Deliberately free of spaces: the credentials are typed on a real device through `adb input`,
        // which cannot pass a space reliably.
        expectedPassword = "testpass",
        requestedPort = port,
        bindHost = "0.0.0.0",
    )
