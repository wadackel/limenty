package dev.wadackel.limenty.ui

import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.wadackel.limenty.AppContainer
import dev.wadackel.limenty.BuildConfig
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.kind
import dev.wadackel.limenty.data.AboutContent
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.LanService
import dev.wadackel.limenty.data.SaveOutcome
import dev.wadackel.limenty.data.loadAboutContent
import dev.wadackel.limenty.data.notSaved
import dev.wadackel.limenty.data.sharingTrustedKeyWith
import dev.wadackel.limenty.log.AppLogging
import dev.wadackel.limenty.service.SessionKeepAliveService
import dev.wadackel.limenty.ui.about.AboutSection
import dev.wadackel.limenty.ui.about.BuildInfo
import dev.wadackel.limenty.ui.hosts.HostFormScreen
import dev.wadackel.limenty.ui.hosts.HostListScreen
import dev.wadackel.limenty.ui.hosts.HostStatus
import dev.wadackel.limenty.ui.hosts.rememberSessionPreview
import dev.wadackel.limenty.ui.settings.KeyBarSettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsSection
import dev.wadackel.limenty.ui.settings.ThemeEditorScreen
import dev.wadackel.limenty.ui.terminal.HostKeyOverlay
import dev.wadackel.limenty.ui.terminal.TerminalScreen
import dev.wadackel.limenty.ui.theme.LimentyMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Serialises a destination to a single string so it can survive an activity recreation. */
internal val DestinationSaver: Saver<Destination, String> = Saver(
    save = { destination ->
        when (destination) {
            is Destination.Hosts -> "hosts"
            is Destination.Settings -> destination.section?.let { "settings:${it.name}" } ?: "settings"
            is Destination.KeyBar -> "keybar"
            is Destination.ThemeEditor -> "theme:${destination.themeId}"
            is Destination.HostForm ->
                destination.discovered
                    // The name goes last because a Bonjour name may itself contain the separator.
                    ?.let { "found:${it.port}:${it.address}:${it.name}" }
                    ?: "form:${destination.hostId.orEmpty()}"
            is Destination.Terminal -> "terminal:${destination.sessionId.value}"
        }
    },
    restore = { encoded ->
        when {
            encoded == "settings" -> Destination.Settings()
            encoded.startsWith("settings:") -> {
                val name = encoded.removePrefix("settings:")
                Destination.Settings(SettingsSection.entries.firstOrNull { it.name == name })
            }
            encoded == "keybar" -> Destination.KeyBar
            encoded.startsWith("theme:") -> Destination.ThemeEditor(encoded.removePrefix("theme:"))
            encoded.startsWith("found:") -> {
                val (port, address, name) = encoded.removePrefix("found:").split(":", limit = 3)
                Destination.HostForm(null, LanService(name = name, address = address, port = port.toInt()))
            }
            encoded.startsWith("form:") ->
                Destination.HostForm(encoded.removePrefix("form:").ifEmpty { null })
            encoded.startsWith("terminal:") ->
                Destination.Terminal(SessionId(encoded.removePrefix("terminal:")))
            else -> Destination.Hosts
        }
    },
)

/**
 * Where the user currently is. Deliberately a sealed value rather than a navigation graph: V1 has
 * a handful of destinations and no deep links, and the terminal's identity is a session, not a route.
 */
internal sealed interface Destination {
    data object Hosts : Destination

    data class Terminal(val sessionId: SessionId) : Destination

    data class HostForm(val hostId: String?, val discovered: LanService? = null) : Destination

    /**
     * Settings, on [section] — null for the index. The section is part of the destination rather
     * than of the settings screen, so it survives a fold, which swaps the folded layout for the
     * unfolded one, as well as an activity recreation.
     */
    data class Settings(val section: SettingsSection? = null) : Destination

    /** Reached from the keyboard section of settings, and returns there rather than to the host list. */
    data object KeyBar : Destination

    /** A custom theme being edited, reached from the appearance section and returning there. */
    data class ThemeEditor(val themeId: String) : Destination
}

/**
 * How far in a destination sits, which is what makes a transition a step in or a step back.
 *
 * Not the same question as whether the target is the root. Written that way — anything but the
 * host list is forward — the key bar's own back step slid settings in from the right, since
 * settings is not the host list either. Depth answers for any pair, including the ones added
 * after the rule was written.
 */
internal val Destination.depth: Int
    get() = when (this) {
        is Destination.Hosts -> 0
        is Destination.Terminal, is Destination.HostForm, is Destination.Settings -> 1
        is Destination.KeyBar, is Destination.ThemeEditor -> 2
    }

/** Whether arriving here from [previous] is a step further in, which is the direction to slide. */
internal fun Destination.stepsInFrom(previous: Destination): Boolean = depth >= previous.depth

/**
 * Whether two destinations are one screen, which the shell keeps composed and does not slide between.
 *
 * Every settings section is the settings screen: on the unfolded display the sections are a
 * sidebar beside a pane, and sliding the whole screen to change the pane would move the sidebar
 * the choice was made in. The folded display's step into a section is animated by the screen.
 */
internal fun Destination.isScreenOf(other: Destination): Boolean = this is Destination.Settings && other is Destination.Settings

/** Where the shell's own Back goes from a destination it handles Back for. */
internal val Destination.backTarget: Destination
    get() = when (this) {
        is Destination.KeyBar -> Destination.Settings(SettingsSection.Keyboard)
        is Destination.ThemeEditor -> Destination.Settings(SettingsSection.Appearance)
        else -> Destination.Hosts
    }

/**
 * The app shell.
 *
 * Navigation never touches the connection: leaving the terminal changes [destination] and nothing
 * else.
 */
@Composable
fun LimentyApp(container: AppContainer, modifier: Modifier = Modifier) {
    // The container's scope, not the composition's: reading a password decrypts through the
    // Keystore, and an activity destroyed in that window used to cancel the launch before it
    // reached the handshake — leaving a session stuck at Idle behind an overlay that never
    // resolved. Navigation set from these coroutines is a no-op once the screen is gone, which is
    // the correct outcome; the connection and the save are not.
    val scope = container.scope
    val context = LocalContext.current
    val log = remember { TermLog.logger(LogTags.LIFECYCLE) }

    // Saved so an activity recreation returns to the screen the user left, rather than to the
    // host list. The session id is a plain string, so the whole value is parcelable.
    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf<Destination>(Destination.Hosts)
    }
    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }

    val hosts by container.hosts.hosts.collectAsState()
    val preferences by container.settings.values.collectAsState()
    val sessions by container.connections.sessions.collectAsState()
    val sessionStates by container.connections.sessionStates.collectAsState()
    val pendingHostKey by container.hostKeyPrompt.pending.collectAsState()
    val notice by container.notice.collectAsState()
    val lanServices by container.lan.services.collectAsState()
    val lanSettled by container.lan.settled.collectAsState()

    // An unexpected disconnect is the failure most likely to be reported from a device nobody was
    // watching — a fold, a network handoff — and it does not crash, so nothing would otherwise be
    // written to disk. The report is the only way to see the event sequence afterwards.
    LaunchedEffect(sessionStates) {
        sessionStates.forEach { (id, state) ->
            if (state is ConnectionState.Disconnected && state.error != null) {
                val session = container.connections.session(id) ?: return@forEach
                AppLogging.dumpToDisk("disconnect-${state.error!!.kind}")
                log.w("session.disconnected", "host" to session.hostName, "kind" to state.error!!.kind)
            }
        }
    }

    // The service exists only while something is actually connected: a notification for an idle
    // app is noise, and a process with nothing to keep alive should be free to be reclaimed.
    val connectedSession = sessions.firstOrNull { sessionStates[it.id] is ConnectionState.Connected }
    LaunchedEffect(connectedSession?.id, connectedSession?.hostName) {
        if (connectedSession != null) {
            SessionKeepAliveService.start(context, connectedSession.hostName, connectedSession.id)
        } else {
            SessionKeepAliveService.stop(context)
        }
    }

    /**
     * The session behind a host, if it has one that is connected.
     *
     * The row watches it rather than being handed a snapshot: composing the preview once froze it
     * at whatever the screen held when the list was last drawn, so "what is running on it" stopped
     * being true the moment a build printed anything.
     */
    fun sessionOf(host: HostProfile): TerminalSession? = sessions.firstOrNull { it.hostId == host.id }
        ?.takeIf { sessionStates[it.id] is ConnectionState.Connected }

    fun statusOf(host: HostProfile): HostStatus {
        val session = sessions.firstOrNull { it.hostId == host.id } ?: return HostStatus.Disconnected
        return when (val state = sessionStates[session.id] ?: session.state.value) {
            is ConnectionState.Connected -> HostStatus.Connected
            is ConnectionState.Connecting, is ConnectionState.Authenticating -> HostStatus.Connecting
            is ConnectionState.Disconnected -> if (state.error == null) HostStatus.Disconnected else HostStatus.Error
            else -> HostStatus.Disconnected
        }
    }

    /**
     * Sessions whose connection has been asked for but not yet started.
     *
     * Such a session is still Idle, which reads as "not connecting", and a LAN host can stay that
     * way for seconds while it is looked up. A second tap in that time started a second connection
     * on the same transport, and both went on to ask about the host key.
     */
    val preparing = remember { ConcurrentHashMap.newKeySet<SessionId>() }

    fun startConnect(session: TerminalSession, host: HostProfile, overridePassword: String?) {
        // The screen changes first and the password is read afterwards: reading it decrypts
        // through the Keystore, and doing that before the navigation put a key operation between
        // the tap and the terminal appearing. The session shows its connecting overlay meanwhile.
        preparing += session.id
        scope.launch {
            val config = try {
                val password = overridePassword ?: container.hosts.passwordFor(host.id).orEmpty()
                connectionConfig(container, host, password, session)
            } finally {
                preparing -= session.id
            }
            // Cancelling from that overlay closes the session while this is still reading or
            // looking the host up, and connecting one that has left the list would leave a
            // connection behind a screen the user has already dismissed.
            if (container.connections.session(session.id) == null) return@launch
            session.launchConnect(config) {
                // A password that worked replaces the stored one, so the next connection is one
                // tap again rather than another prompt. The session is asked directly
                // rather than through the composed state, which is a snapshot from before this ran.
                if (overridePassword != null && session.state.value is ConnectionState.Connected) {
                    container.hosts.updatePassword(host.id, overridePassword).onFailure {
                        container.report(
                            "Connected, but the password could not be kept on this device. " +
                                "You will be asked for it again next time.",
                        )
                    }
                }
            }
        }
    }

    fun connect(host: HostProfile, overridePassword: String? = null) {
        // A session whose shell has gone still holds that shell's screen and modes — the alternate
        // screen, mouse reporting — and a new shell started into it inherited them, so a drag at
        // the fresh prompt typed mouse reports. The overlay's Reconnect starts afresh for the same
        // reason, and so does this.
        val previous = container.connections.sessionFor(host.id)
        if (previous != null && previous.state.value is ConnectionState.Disconnected) {
            container.connections.launchReconnect(
                id = previous.id,
                hostId = host.id,
                hostName = host.displayName,
                columns = previous.emulator.columns,
                rows = previous.emulator.rows,
            ) { fresh ->
                destination = Destination.Terminal(fresh.id)
                startConnect(fresh, host, overridePassword)
            }
            return
        }

        // The session is created at the terminal's default size; the view corrects it to the real
        // viewport as soon as it is laid out, which is also what a fold or rotation does later.
        val session = container.connections.openSession(host.id, host.displayName, 80, 24)
        destination = Destination.Terminal(session.id)

        if (session.state.value.isActive || session.id in preparing) {
            log.i("connect.reuse", "host" to host.displayName)
            return
        }
        startConnect(session, host, overridePassword)
    }

    // The screens reached from the host list are dismissed by the system Back as well as by
    // their own button. The terminal is excluded: it registers its own handler, which has to
    // close the keyboard and drop a selection before it will leave. The list itself is
    // excluded too, where Back means backgrounding the app rather than going anywhere.
    // Settings registers its own handler too, for the folded display's step from a section back to
    // the index; that one is composed later and so is asked first.
    BackHandler(
        enabled = destination is Destination.Settings ||
            destination is Destination.HostForm ||
            destination is Destination.KeyBar ||
            destination is Destination.ThemeEditor,
    ) {
        destination = destination.backTarget
    }

    // Nothing is drawn until the stored settings arrive. A first frame in the default font and
    // palette, replaced by the real ones once the file has been read, reads as the app changing
    // its mind about how it looks.
    val settings = preferences ?: return

    // Out here rather than inside the terminal screen: a reconnect composes the new screen while
    // the old one is still leaving, and whichever of the two went last would decide the window.
    KeyboardRestoreEffect(active = destination is Destination.Terminal && settings.restoreKeyboardOnReturn)

    // Position and opacity only. The terminal reports every size change to the remote, so a
    // transition that animated size would redraw tmux and nvim on each screen change; every
    // destination fills the same box, which keeps the size constant throughout.
    AnimatedContent(
        targetState = destination,
        contentKey = { if (it is Destination.Settings) Destination.Settings::class else it },
        transitionSpec = {
            if (targetState.isScreenOf(initialState)) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val forward = targetState.stepsInFrom(initialState)
                val enterFrom: (Int) -> Int = { width -> if (forward) width else -width }
                val exitTo: (Int) -> Int = { width -> if (forward) -width else width }
                (slideInHorizontally(tween(LimentyMotion.TRANSITION_MILLIS), enterFrom) + fadeIn(tween(LimentyMotion.TRANSITION_MILLIS)))
                    .togetherWith(
                        slideOutHorizontally(tween(LimentyMotion.TRANSITION_MILLIS), exitTo) + fadeOut(tween(LimentyMotion.TRANSITION_MILLIS)),
                    )
            }
        },
        label = "destination",
    ) { current ->
        when (current) {
            // Withheld until the file has been read: an empty list drawn first shows the
            // "no hosts yet" state over hosts that are one frame away.
            is Destination.Hosts -> {
                hosts?.let { stored ->
                    HostListScreen(
                        hosts = stored,
                        statusOf = ::statusOf,
                        previewOf = { host -> sessionOf(host)?.let { rememberSessionPreview(it) } },
                        palette = settings.palette,
                        mono = container.typefaces.family(
                            TerminalFont.byId(settings.typography.fontId),
                        ),
                        found = lanServices.notSaved(stored),
                        absent = { host -> host.lan && lanSettled && lanServices.none { it.name == host.hostname } },
                        onConnect = ::connect,
                        onAddFound = { destination = Destination.HostForm(null, it) },
                        onEdit = { destination = Destination.HostForm(it.id) },
                        onDelete = { host ->
                            sessions.firstOrNull { it.hostId == host.id }
                                ?.let { container.connections.launchClose(it.id) }
                            scope.launch {
                                // The trusted key goes only once the profile is actually gone: taking
                                // it from a host that is still in the list would make the next
                                // connection ask about a fingerprint already accepted for it.
                                container.hosts.delete(host.id)
                                    .onFailure {
                                        container.report(
                                            "Could not delete this host. It is still on this device.",
                                        )
                                    }
                                    .onSuccess {
                                        // The key belongs to the machine rather than the profile, so
                                        // it stays while another profile — another user on the same
                                        // host, say — still connects there. The delete dialog says so.
                                        val stillReached = container.hosts.hosts.value.orEmpty()
                                            .sharingTrustedKeyWith(host).isNotEmpty()
                                        if (stillReached) return@onSuccess
                                        container.knownHosts.forget(host.hostname, host.port).onFailure {
                                            container.report(
                                                "Deleted this host, but its trusted key is still on this " +
                                                    "device and will be used if you add the host again.",
                                            )
                                        }
                                    }
                            }
                        },
                        onDisconnect = { host ->
                            sessions.firstOrNull { it.hostId == host.id }
                                ?.let { container.connections.launchClose(it.id) }
                        },
                        onAdd = { destination = Destination.HostForm(null) },
                        onOpenSettings = { destination = Destination.Settings() },
                        selectedHostId = selectedHostId,
                        onSelectHost = { selectedHostId = it },
                    )
                }
            }
            is Destination.HostForm -> {
                var notice by remember { mutableStateOf<String?>(null) }
                // Read once, not in the composition body: the body runs again on every keystroke
                // in the form, and each run decrypted the stored password through the Keystore.
                // The form is withheld until both arrive, because it captures the values it is
                // first given — an empty string would become the password the user then saves, and
                // a profile that is merely still being read would make an edit open as an add.
                val stored by produceState<Pair<HostProfile?, String>?>(
                    // A new host has neither to wait for, and holding the form back a frame for
                    // values that are known to be absent is a blank screen on every add.
                    initialValue = if (current.hostId == null) null to "" else null,
                    key1 = current.hostId,
                ) {
                    val hostId = current.hostId
                    value = if (hostId == null) {
                        null to ""
                    } else {
                        container.hosts.host(hostId) to container.hosts.passwordFor(hostId).orEmpty()
                    }
                }
                stored?.let { (existing, storedPassword) ->
                    HostFormScreen(
                        initial = existing,
                        discovered = current.discovered,
                        initialPassword = storedPassword,
                        onSave = { profile, password ->
                            scope.launch {
                                when (val outcome = container.hosts.upsert(profile, password)) {
                                    is SaveOutcome.Saved -> destination = Destination.Hosts
                                    is SaveOutcome.PasswordNotStored -> {
                                        notice = "Saved, but the password could not be kept on this " +
                                            "device. You will be asked for it when connecting."
                                    }
                                    is SaveOutcome.Failed -> {
                                        notice = "Could not save this host: " +
                                            (outcome.cause.message ?: "unknown error")
                                    }
                                }
                            }
                        },
                        onCancel = { destination = Destination.Hosts },
                        notice = notice,
                    )
                }
            }
            is Destination.Settings -> {
                // The live destination while settings is still where the app is, because a change
                // of section keeps this entry rather than composing a new one for the new value.
                val live = destination
                SettingsScreen(
                    preferences = settings,
                    typefaces = container.typefaces,
                    section = if (live is Destination.Settings) live.section else current.section,
                    // Its own Back step must stop with it: while it slides out under the key bar
                    // editor it would otherwise catch a Back first and, unable to act, swallow it.
                    active = live is Destination.Settings,
                    // Only while settings is still where the app is: the screen stays composed
                    // while it slides out, and a tap on its back arrow or a row then must not pull
                    // the app back.
                    onSelectSection = { section ->
                        if (destination is Destination.Settings) destination = Destination.Settings(section)
                    },
                    onChange = { transform -> container.settings.update(transform) },
                    onDrag = { transform -> container.settings.updateWithoutWriting(transform) },
                    onBack = { destination = Destination.Hosts },
                    onOpenKeyBar = { destination = Destination.KeyBar },
                    onEditTheme = { id ->
                        if (destination is Destination.Settings) destination = Destination.ThemeEditor(id)
                    },
                    version = BuildConfig.VERSION_NAME,
                    about = {
                        // Read off the main thread, and only once About is shown: the definitions
                        // and the licence files come out of the APK, which is disk, and the section
                        // draws its identity without waiting for them.
                        val content by produceState<AboutContent?>(null) {
                            value = withContext(Dispatchers.IO) { loadAboutContent(context) }
                        }
                        AboutSection(
                            build = BuildInfo(
                                version = BuildConfig.VERSION_NAME,
                                type = BuildConfig.BUILD_TYPE,
                                commit = BuildConfig.GIT_COMMIT,
                                dirty = BuildConfig.GIT_DIRTY,
                            ),
                            ownLicense = content?.ownLicense,
                            notices = content?.notices,
                            fontFamilyOf = { notice ->
                                TerminalFont.All.firstOrNull { notice.isFont && it.displayName == notice.name }
                                    ?.let(container.typefaces::family)
                            },
                        )
                    },
                    onSettled = { container.settings.flush() },
                )
            }
            is Destination.KeyBar ->
                KeyBarSettingsScreen(
                    preferences = settings,
                    onChange = container.settings::update,
                    onBack = { destination = Destination.KeyBar.backTarget },
                )
            is Destination.ThemeEditor ->
                ThemeEditorScreen(
                    preferences = settings,
                    themeId = current.themeId,
                    typefaces = container.typefaces,
                    onChange = container.settings::update,
                    onDrag = container.settings::updateWithoutWriting,
                    onSettled = { container.settings.flush() },
                    // Only while the editor is still where the app is, like settings' own rows.
                    onBack = { if (destination == current) destination = current.backTarget },
                )
            is Destination.Terminal -> {
                val session = container.connections.session(current.sessionId)
                if (session == null) {
                    // Only when this is still where the app is. A reconnect gives the host a new
                    // session and points `destination` at it while this entry stays composed for
                    // the exit transition; unconditionally, the effect below then sent the app to
                    // the host list and undid the navigation that had already happened.
                    LaunchedEffect(current.sessionId) {
                        if (destination == current) destination = Destination.Hosts
                    }
                } else {
                    TerminalScreen(
                        session = session,
                        preferences = settings,
                        typefaces = container.typefaces,
                        onFontSizeChanged = { size ->
                            container.settings.update { it.copy(typography = it.typography.withFontSize(size)) }
                        },
                        onKeySetSwitched = { delta -> container.settings.update { it.switchKeySet(delta) } },
                        onBack = { destination = Destination.Hosts },
                        onRetryWithPassword = { password ->
                            scope.launch {
                                val host = container.hosts.host(session.hostId)
                                if (host != null) connect(host, overridePassword = password)
                            }
                        },
                        onReconnect = {
                            scope.launch {
                                val host = container.hosts.host(session.hostId)
                                if (host != null) {
                                    container.connections.launchReconnect(
                                        id = session.id,
                                        hostId = host.id,
                                        hostName = host.displayName,
                                        columns = session.emulator.columns,
                                        rows = session.emulator.rows,
                                    ) { fresh ->
                                        destination = Destination.Terminal(fresh.id)
                                        startConnect(fresh, host, overridePassword = null)
                                    }
                                }
                            }
                        },
                        onCancelConnect = {
                            container.connections.launchClose(session.id)
                            destination = Destination.Hosts
                        },
                        onNotice = container::report,
                        modifier = modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // A prompt outlives its connection when the handshake ends underneath it — a timeout, the
    // network dropping, the session being closed — and the transport cannot withdraw its own,
    // because it asks from a blocking call on an SSHJ thread. Anything no live handshake is
    // waiting on goes, which is also what keeps a dead question from sitting in front of a live
    // one and hiding it.
    LaunchedEffect(pendingHostKey, sessions, sessionStates) {
        if (pendingHostKey == null) return@LaunchedEffect
        val waitedOn = sessions
            .filter { sessionStates[it.id]?.isActive == true }
            .mapTo(mutableSetOf()) { it.id.value }
        container.hostKeyPrompt.withdrawExcept(waitedOn)
    }

    // Above every screen and below the trust prompt: it reports work that has already finished,
    // so it must not cover a question that is still blocking a handshake.
    AppNotice(message = notice, onDismiss = container::dismissNotice)

    // The trust prompt is drawn above whatever screen is showing, because the handshake it blocks
    // may well have been started from a different one.
    pendingHostKey?.let { request ->
        HostKeyOverlay(
            host = request.host,
            algorithm = request.key.algorithm,
            fingerprint = request.key.fingerprint,
            onTrust = { request.accept() },
            onCancel = { request.reject() },
        )
    }
}

/**
 * What a connection to [host] dials, looking a LAN host up on the current network first.
 *
 * A LAN host that cannot be found is given no address rather than its name: the name is a
 * Bonjour service name, and sending it to the network's DNS asks a stranger about the machine and
 * waits on whatever that resolver decides to do.
 */
private suspend fun connectionConfig(
    container: AppContainer,
    host: HostProfile,
    password: String,
    session: TerminalSession,
): ConnectionConfig = ConnectionConfig(
    host = host.hostname,
    port = host.port,
    username = host.username,
    password = password,
    initialColumns = session.emulator.columns,
    initialRows = session.emulator.rows,
    address = if (host.lan) container.lan.resolve(host.hostname, LAN_WAIT_MILLIS)?.address else host.hostname,
)

/** How long a connection waits for a LAN host to be found, discovery having just started. */
private const val LAN_WAIT_MILLIS = 3_000L

/**
 * While [active], coming back to the app leaves the keyboard as it was when the app was left.
 *
 * Left unspecified, the platform raises the keyboard on every return to a window whose focus is in
 * an editor, which the terminal always is — including a return from having closed it on purpose.
 */
@Composable
internal fun KeyboardRestoreEffect(active: Boolean) {
    val window = LocalActivity.current?.window ?: return
    // Not a LaunchedEffect: that runs a dispatch later, which on a recreated activity can be after
    // the focus gain this is meant to be in place for.
    DisposableEffect(window, active) {
        val state = if (active) SOFT_INPUT_STATE_UNCHANGED else SOFT_INPUT_STATE_UNSPECIFIED
        // Only the state bits are swapped, read at the moment of writing rather than remembered.
        // The word also carries the resize mode and a forward-navigation bit the platform sets on
        // resume and reads at the next focus gain; a remembered copy would put a stale one back.
        //
        // `setSoftInputMode` ignores a zero, so going inactive only works while the manifest
        // declares a resize mode to keep the word from being empty.
        window.setSoftInputMode((window.attributes.softInputMode and SOFT_INPUT_MASK_STATE.inv()) or state)
        onDispose {}
    }
}
