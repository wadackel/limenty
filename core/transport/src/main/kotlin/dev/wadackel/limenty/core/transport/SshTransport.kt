package dev.wadackel.limenty.core.transport

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.common.log.redacted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SSH implementation of [TerminalTransport].
 *
 * SSHJ's API is blocking, so every call into it runs on [Dispatchers.IO] and the read loop lives
 * in its own coroutine. Nothing here reaches the terminal or the UI directly: output goes through
 * a bounded channel, which is what applies backpressure when the remote outruns the renderer
 * instead of letting a `cat` of a large file exhaust memory.
 */
class SshTransport(
    private val hostKeyVerifier: HostKeyVerifier,
    private val knownHosts: KnownHostsStore,
    private val clientFactory: () -> SSHClient = ::defaultClient,
) : TerminalTransport,
    AutoCloseable {

    private val log = TermLog.logger(LogTags.SSH)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val outputChannel = Channel<ByteArray>(
        capacity = OUTPUT_BUFFER_CHUNKS,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val output: Flow<ByteArray> = outputChannel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var remoteInput: InputStream? = null
    private var remoteOutput: OutputStream? = null
    private var readJob: Job? = null

    @Volatile
    private var closing = false

    override suspend fun connect(config: ConnectionConfig) {
        check(_state.value == ConnectionState.Idle || _state.value is ConnectionState.Disconnected) {
            "connect() called while ${_state.value}"
        }
        closing = false
        _state.value = ConnectionState.Connecting(config.host, config.port)
        log.i(
            "connect.start",
            "host" to config.host,
            "address" to config.address,
            "port" to config.port,
            "user" to config.username,
            "password" to config.password.redacted(),
            "size" to "${config.initialColumns}x${config.initialRows}",
        )

        try {
            withContext(Dispatchers.IO) {
                openSession(config)
            }
            _state.value = ConnectionState.Connected
            log.i("connect.established", "host" to config.host)
            startReading()
        } catch (error: Throwable) {
            val classified = classify(error, config)
            log.w(error, "connect.failed", "host" to config.host, "kind" to classified.kind)
            closeQuietly()
            _state.value = ConnectionState.Disconnected(classified)
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    private suspend fun openSession(config: ConnectionConfig) {
        val ssh = clientFactory()
        client = ssh
        ssh.connectTimeout = config.connectTimeoutMillis.toInt()
        ssh.timeout = config.connectTimeoutMillis.toInt()
        // `SSHClient.timeout` is the socket read timeout; the key exchange waits on the transport's
        // own deadline, which defaults to 30 seconds. The handshake pauses there on the user's host
        // key decision, so that deadline has to allow for a person reading a fingerprint — otherwise
        // a first connection aborts while the prompt is still on screen.
        val decisionTimeout = maxOf(config.connectTimeoutMillis, HOST_KEY_DECISION_TIMEOUT_MILLIS).toInt()
        ssh.transport.timeoutMs = decisionTimeout
        ssh.addHostKeyVerifier(TrustDelegatingVerifier(config, hostKeyVerifier, knownHosts, log))
        // sshj starts the keep-alive thread inside `connect` and only if an interval is already
        // set; setting it afterwards stores a number nothing ever reads, and idle sessions behind a
        // NAT died without a single packet having been sent to keep them open.
        ssh.connection.keepAlive.keepAliveInterval = config.keepAliveIntervalSeconds

        // An empty string would not do as "nowhere": the socket resolves it to loopback and
        // connects to whatever listens on this device.
        ssh.connect(config.address ?: throw UnknownHostException(config.host), config.port)

        _state.value = ConnectionState.Authenticating
        // macOS runs password authentication through PAM, which presents itself as
        // keyboard-interactive; offering only the `password` method fails against a stock Mac.
        val finder = singleAttemptPassword(config.password)
        ssh.auth(config.username, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
        log.i("auth.succeeded", "user" to config.username)

        val newSession = ssh.startSession()
        session = newSession
        // The last argument is the pty's initial terminal modes, and an empty map does not mean
        // "no opinion" — it means the server's defaults, which include IXON. That makes Ctrl-S the
        // stop character, so at a shell prompt it silently freezes the screen with nothing on it
        // to say why, and Ctrl-Q is the only way out for anyone who knows. A desktop client
        // inherits that flag because it forwards a local terminal's settings; this app has no
        // local terminal to inherit from, so the choice is its own to make.
        //
        // Only the initial modes, mind: `stty sane` or a shell that sets `ixon` puts it back.
        // Inside tmux it never applied in the first place, since tmux takes the tty raw.
        newSession.allocatePTY(
            config.terminalType,
            config.initialColumns,
            config.initialRows,
            0,
            0,
            mapOf(PTYMode.IXON to 0),
        )
        val newShell = newSession.startShell()
        shell = newShell
        remoteInput = newShell.inputStream
        remoteOutput = newShell.outputStream
        log.i(
            "pty.allocated",
            "term" to config.terminalType,
            "cols" to config.initialColumns,
            "rows" to config.initialRows,
            "ixon" to false,
        )
    }

    private fun startReading() {
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val input = remoteInput ?: return@launch
            var total = 0L
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    outputChannel.send(buffer.copyOf(read))
                }
                log.i("read.eof", "totalBytes" to total)
                finish(exitStatus = (session as? SessionChannel)?.exitStatus)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (closing) {
                    log.d("read.stopped", "reason" to "disconnecting")
                    return@launch
                }
                log.w(error, "read.failed", "totalBytes" to total)
                closeQuietly()
                _state.value = ConnectionState.Disconnected(
                    TransportError.ConnectionLost(error.message),
                )
            }
        }
    }

    private fun finish(exitStatus: Int?) {
        closeQuietly()
        // A remote `exit` is a normal ending with its own UI, not an error.
        _state.value = ConnectionState.Disconnected(error = null, exitStatus = exitStatus)
        log.i("session.closed", "exitStatus" to exitStatus)
    }

    override suspend fun write(data: ByteArray) {
        if (data.isEmpty()) return
        val stream = remoteOutput ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                stream.write(data)
                stream.flush()
            }.onFailure { error ->
                if (closing) return@onFailure
                log.w(error, "write.failed", "bytes" to data.size)
                closeQuietly()
                _state.value = ConnectionState.Disconnected(TransportError.ConnectionLost(error.message))
            }
        }
    }

    override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        val active = shell ?: return
        withContext(Dispatchers.IO) {
            runCatching { active.changeWindowDimensions(columns, rows, widthPx, heightPx) }
                .onSuccess { log.i("resize.sent", "cols" to columns, "rows" to rows, "widthPx" to widthPx, "heightPx" to heightPx) }
                .onFailure { log.w(it, "resize.failed", "cols" to columns, "rows" to rows) }
        }
    }

    override suspend fun disconnect() {
        if (closing) return
        closing = true
        log.i("disconnect.requested")
        withContext(Dispatchers.IO) { closeQuietly() }
        readJob?.cancel()
        _state.value = ConnectionState.Disconnected(error = null)
    }

    /** Releases the SSH resources without changing the observable state. */
    private fun closeQuietly() {
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
        shell = null
        session = null
        remoteInput = null
        remoteOutput = null
        client = null
    }

    /** Frees the coroutine scope; the transport cannot be reused afterwards. */
    fun shutdown() {
        scope.cancel()
        outputChannel.close()
    }

    override fun close() {
        closeQuietly()
        shutdown()
    }

    private fun classify(error: Throwable, config: ConnectionConfig): TransportError {
        // SSHJ wraps whatever the host key verifier throws in a transport exception, so the
        // decision the user actually made is only visible through the cause chain. Reading the
        // wrapper alone reports every refused key as a generic protocol failure.
        causeOfType<HostKeyMismatchException>(error)?.let {
            return TransportError.HostKeyMismatch(it.expected, it.actual)
        }
        if (causeOfType<HostKeyRejectedException>(error) != null) return TransportError.HostKeyRejected
        // The wrapper's message is generic; the cause carries what the server actually said.
        causeOfType<UserAuthException>(error)?.let { return TransportError.Authentication(it.message) }
        causeOfType<UnknownHostException>(error)?.let { return TransportError.HostResolution(config.host) }
        causeOfType<SocketTimeoutException>(error)?.let { return TransportError.Timeout }

        return when (error) {
            is UnknownHostException -> TransportError.HostResolution(config.host)
            is SocketTimeoutException -> TransportError.Timeout
            is ConnectException ->
                if (error.message?.contains("refused", ignoreCase = true) == true) {
                    TransportError.ConnectionRefused
                } else {
                    TransportError.NetworkUnreachable
                }
            is NoRouteToHostException -> TransportError.NetworkUnreachable
            is UserAuthException -> TransportError.Authentication(error.message)
            is HostKeyRejectedException -> TransportError.HostKeyRejected
            is HostKeyMismatchException -> TransportError.HostKeyMismatch(error.expected, error.actual)
            is TransportException -> TransportError.Protocol(error.message ?: "transport failure")
            is SSHException -> TransportError.Protocol(error.message ?: "ssh failure")
            is IOException -> TransportError.ConnectionLost(error.message)
            else -> TransportError.Unknown(error.message ?: error::class.java.simpleName)
        }
    }

    private inline fun <reified T : Throwable> causeOfType(error: Throwable): T? {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    companion object {
        /** How long the handshake may wait on a person reading a fingerprint. */
        private const val HOST_KEY_DECISION_TIMEOUT_MILLIS = 120_000L

        /**
         * Chunks in flight between the reader and the terminal. Bounded so a burst of remote
         * output blocks the reader rather than accumulating without limit.
         */
        private const val OUTPUT_BUFFER_CHUNKS = 64
        private const val READ_BUFFER_BYTES = 8192

        /**
         * `DefaultConfig` rather than sshj's `AndroidConfig`: the latter drops ECDSA and the
         * rsa-sha2 signatures, leaving only algorithms modern OpenSSH has disabled. With the full
         * BouncyCastle provider installed, the default set works on Android.
         */
        private fun defaultClient(): SSHClient {
            SshSecurity.ensureInstalled()
            return SSHClient(net.schmizz.sshj.DefaultConfig())
        }

        /**
         * Supplies the stored password exactly once.
         *
         * Retrying inside the library would burn through the server's attempt allowance without
         * the user ever seeing a prompt; the terminal UI offers the retry instead.
         */
        private fun singleAttemptPassword(password: String): PasswordFinder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = password.toCharArray()

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
    }
}

internal class HostKeyRejectedException(message: String) : SSHException(message)

internal class HostKeyMismatchException(
    val expected: String,
    val actual: String,
) : SSHException("host key mismatch: expected $expected but got $actual")
