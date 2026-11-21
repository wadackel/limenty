package dev.wadackel.limenty

import android.content.Context
import dev.wadackel.limenty.core.session.ConnectionManager
import dev.wadackel.limenty.core.transport.SshTransport
import dev.wadackel.limenty.data.AndroidKnownHostsStore
import dev.wadackel.limenty.data.CredentialStore
import dev.wadackel.limenty.data.HostRepository
import dev.wadackel.limenty.data.LanDiscovery
import dev.wadackel.limenty.data.TerminalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual dependency wiring.
 *
 * A DI framework would add annotation processing and a build-time indirection for a graph that is
 * this small and this stable; the container is read once at startup and never mutated.
 *
 * It is held by the Application, not by an Activity, because the connection manager must outlive
 * every screen.
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    /**
     * Where work that must not die with a screen runs: reading the stores at startup, fetching a
     * password before a handshake, saving and deleting a host.
     *
     * Nothing cancels it, and nothing should. A composition's scope was what these used to run on,
     * and an activity destroyed between the Keystore read and the handshake left a session that
     * never connected behind an overlay that never resolved.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * The last failure worth telling the user about, until they dismiss it.
     *
     * It lives here rather than in a composable's state because the work that produces it runs on
     * [scope]: a failure raised while no screen is composed would otherwise be published to
     * nobody, which is the silence these messages exist to end.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun report(message: String) {
        _notice.value = message
    }

    fun dismissNotice() {
        _notice.value = null
    }

    val credentials = CredentialStore(applicationContext)
    val hosts = HostRepository(applicationContext, credentials, scope)
    val settings = TerminalSettings(applicationContext, scope)
    val knownHosts = AndroidKnownHostsStore(applicationContext)
    val lan = LanDiscovery(applicationContext, scope)
    val typefaces = dev.wadackel.limenty.terminal.TerminalTypefaces(applicationContext)
    val hostKeyPrompt = HostKeyPrompt()

    val connections = ConnectionManager(
        transportFactory = { session, _ -> SshTransport(hostKeyPrompt.forConnection(session.value), knownHosts) },
    )
}
