package dev.wadackel.limenty.core.session

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns every live session for the process.
 *
 * V1's UI shows one session at a time, but the model holds many by design: retrofitting
 * a tab UI onto a single-connection assumption would mean redesigning this layer, whereas keeping
 * a map here costs almost nothing today.
 *
 * It lives above the UI so a screen can be destroyed and recreated — rotation, fold, navigation —
 * without the connection noticing.
 */
class ConnectionManager(
    private val transportFactory: (session: SessionId, hostId: String) -> TerminalTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val log = TermLog.logger(LogTags.SESSION)
    private val sequence = AtomicLong(0)

    /**
     * Guards every change to the session list.
     *
     * [openSession] runs from a tap on the main thread and from [launchReconnect] on a background
     * one, and both look the host up, decide, and write the list back. Interleaved, the two
     * lookups each found nothing and each created a session for the same host. An `update` on the
     * flow does not close that: the race is between the lookup and the creation, not inside the
     * assignment.
     */
    private val sessionsLock = Any()

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<SessionId?>(null)
    val activeSessionId: StateFlow<SessionId?> = _activeSessionId.asStateFlow()

    /**
     * Every session's connection state, as one observable value.
     *
     * The session list itself does not change when a connection moves from connecting to
     * connected, so a UI observing only the list never learns that it happened — which is how the
     * host list's status and the keep-alive service both ended up looking at a stale value.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionStates: StateFlow<Map<SessionId, ConnectionState>> = _sessions
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(list.map { session -> session.state.map { session.id to it } }) { it.toMap() }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun sessionFor(hostId: String): TerminalSession? = _sessions.value.firstOrNull { it.hostId == hostId }

    fun session(id: SessionId): TerminalSession? = _sessions.value.firstOrNull { it.id == id }

    val activeSession: TerminalSession? get() = _activeSessionId.value?.let(::session)

    /**
     * Returns the existing session for the host, or creates one.
     *
     * Tapping a connected host reopens its terminal rather than starting a second connection,
     * so identity is the host, not the tap.
     */
    fun openSession(
        hostId: String,
        hostName: String,
        columns: Int,
        rows: Int,
    ): TerminalSession = synchronized(sessionsLock) {
        sessionFor(hostId)?.let { existing ->
            _activeSessionId.value = existing.id
            log.i("session.reused", "host" to hostName, "session" to existing.id.value)
            return@synchronized existing
        }

        val id = SessionId("s${sequence.incrementAndGet()}")
        val session = TerminalSession(
            id = id,
            hostId = hostId,
            hostName = hostName,
            transport = transportFactory(id, hostId),
            columns = columns,
            rows = rows,
            parentScope = scope,
        )
        _sessions.value = _sessions.value + session
        _activeSessionId.value = session.id
        log.i("session.created", "host" to hostName, "session" to session.id.value, "size" to "${columns}x$rows")
        session
    }

    fun launchClose(id: SessionId) {
        scope.launch { close(id) }
    }

    /**
     * Closes a session and opens a fresh one for the same host, in that order.
     *
     * Closing is asynchronous, so starting the new session immediately after asking for the close
     * finds the old one still in the list and reuses it — then the close destroys it underneath,
     * leaving the screen pointing at a session that no longer exists.
     */
    fun launchReconnect(
        id: SessionId,
        hostId: String,
        hostName: String,
        columns: Int,
        rows: Int,
        onReady: (TerminalSession) -> Unit,
    ) {
        scope.launch {
            close(id)
            onReady(openSession(hostId, hostName, columns, rows))
        }
    }

    fun setActive(id: SessionId?) {
        _activeSessionId.value = id
    }

    suspend fun close(id: SessionId) {
        // Out of the list before anything suspends. While it is still in there, opening the same
        // host hands back a session on its way to being destroyed — and a disconnect that throws
        // would leave the host permanently unreachable behind a session that no longer works.
        val session = synchronized(sessionsLock) {
            val found = _sessions.value.firstOrNull { it.id == id } ?: return
            _sessions.value = _sessions.value - found
            if (_activeSessionId.value == id) {
                _activeSessionId.value = _sessions.value.firstOrNull()?.id
            }
            found
        }
        runCatching { session.disconnect() }
            .onFailure { error -> log.w(error, "session.disconnectFailed", "session" to id.value) }
        session.destroy()
        log.i("session.closed", "session" to id.value)
    }
}
