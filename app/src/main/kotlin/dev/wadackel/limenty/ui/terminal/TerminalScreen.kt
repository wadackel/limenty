package dev.wadackel.limenty.ui.terminal

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.PasteEncoder
import dev.wadackel.limenty.core.input.TerminalInputPipeline
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TransportError
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.SelectionBounds
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.terminal.TerminalView
import dev.wadackel.limenty.ui.theme.LimentyColors

/**
 * The terminal, occupying the whole screen apart from the auxiliary key row.
 *
 * The screen owns no session state: it renders whatever [session] currently holds and sends input
 * back to it, so being torn down by a rotation or a fold changes nothing about the connection.
 */
@Composable
fun TerminalScreen(
    session: TerminalSession,
    preferences: TerminalPreferences,
    typefaces: TerminalTypefaces,
    onFontSizeChanged: (Float) -> Unit,
    onKeySetSwitched: (delta: Int) -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onRetryWithPassword: (String) -> Unit,
    onCancelConnect: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val density = LocalDensity.current
    val log = remember { TermLog.logger(LogTags.TERMINAL) }

    val state by session.state.collectAsState()
    val revision by session.revision.collectAsState()
    val modes by session.modes.collectAsState()

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var selectionActive by remember { mutableStateOf(false) }
    var selectionLink by remember { mutableStateOf<String?>(null) }
    var selectionBounds by remember { mutableStateOf<SelectionBounds?>(null) }
    var retypedPassword by remember { mutableStateOf("") }
    val keyGroups = rememberKeyGroupPopupState()

    val pipeline = remember(session) {
        TerminalInputPipeline(
            modes = { session.modes.value.keys },
            sink = { bytes -> session.send(bytes) },
        )
    }

    // Kept awake only while the terminal is on screen, and back to normal Android behaviour when
    // it is not — which is exactly the lifetime of this composable.
    DisposableEffect(preferences.keepScreenAwake) {
        hostView.keepScreenOn = preferences.keepScreenAwake
        onDispose { hostView.keepScreenOn = false }
    }

    // Back closes the keyboard first, and only then leaves the terminal. Neither step
    // touches the connection.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled = true) {
        when {
            selectionActive -> {
                terminalView?.selection = null
                selectionActive = false
            }
            imeVisible -> terminalView?.hideSoftKeyboard()
            else -> onBack()
        }
    }

    // The overlay that replaces a session is drawn over the bar, and a popup left open under it would
    // still be open, over other keys, when the session came back.
    LaunchedEffect(state) {
        if (state !is ConnectionState.Connected) keyGroups.close()
    }

    LaunchedEffect(revision, terminalView) {
        terminalView?.invalidate()
    }

    // Only while resumed: a session outlives this screen, and Android lets a background app write
    // the clipboard, so without the gate a remote could replace it while the user is elsewhere.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            session.clipboardWrites.collect { text ->
                runCatching { copyToClipboard(context, text) }
                    .onFailure { log.w(it, "osc52.copyFailed", "length" to text.length) }
            }
        }
    }

    LaunchedEffect(preferences.gestures, terminalView) {
        terminalView?.gestures = preferences.gestures
    }

    LaunchedEffect(preferences.doubleTapDragZoom, terminalView) {
        terminalView?.doubleTapDragZoom = preferences.doubleTapDragZoom
    }

    // Keyed on the palette's colours rather than its id: editing a custom theme changes the colours
    // and keeps the id, and the open session has to repaint as each one changes.
    LaunchedEffect(preferences.typography, preferences.palette, terminalView) {
        terminalView?.let { view ->
            view.palette = preferences.palette
            val font = TerminalFont.byId(preferences.typography.fontId)
            view.setTypefaces(typefaces.regular(font), typefaces.bold(font))
            view.refreshTypography(preferences.typography)
        }
    }

    // Changing this restarts the input connection, so it is applied on its own rather than
    // alongside typography, which would drop a conversion in flight on every font change.
    LaunchedEffect(preferences.allowPersonalizedLearning, terminalView) {
        terminalView?.allowPersonalizedLearning = preferences.allowPersonalizedLearning
    }

    Box(modifier = modifier.fillMaxSize().background(Color(preferences.palette.background))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        ) {
            // The toolbar shares this box with the view so the selection's position, which the view
            // reports in its own pixels, places it without converting between coordinate systems.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 12.dp),
            ) {
                AndroidView(
                    factory = { viewContext ->
                        TerminalView(viewContext).apply {
                            this.session = session
                            palette = preferences.palette
                            val font = TerminalFont.byId(preferences.typography.fontId)
                            setTypefaces(typefaces.regular(font), typefaces.bold(font))
                            refreshTypography(preferences.typography)
                            allowPersonalizedLearning = preferences.allowPersonalizedLearning
                            onInputEvents = { events -> pipeline.send(events) }
                            gestures = preferences.gestures
                            doubleTapDragZoom = preferences.doubleTapDragZoom
                            onMouseReport = { bytes -> session.send(bytes) }
                            onGestureBehaviour = { behaviour ->
                                when (behaviour) {
                                    is GestureBehaviour.Paste ->
                                        pasteFromClipboard(context)?.let { text ->
                                            session.send(
                                                PasteEncoder.encode(text, session.modes.value.bracketedPaste),
                                            )
                                        }
                                    is GestureBehaviour.Copy ->
                                        terminalView?.selectedText()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.let { copyToClipboard(context, it) }
                                    else -> Unit
                                }
                            }
                            this.onFontSizeChanged = onFontSizeChanged
                            onSelectionChanged = { range ->
                                selectionActive = range != null
                                selectionLink = range?.let { selectedLink() }
                            }
                            onSelectionBoundsChanged = { selectionBounds = it }
                            onUnhandledKey = { false }
                            terminalView = this
                            requestFocus()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                val anchor = selectionBounds
                if (selectionActive && anchor != null) {
                    SelectionToolbar(
                        anchor = anchor,
                        // Read again on the press rather than taken from selectionLink, which was settled
                        // when the selection last moved: output redrawing the cells since then would have
                        // Open go somewhere other than what is highlighted.
                        onOpen = if (selectionLink == null) {
                            null
                        } else {
                            {
                                terminalView?.selectedLink()?.let { url ->
                                    if (!openLink(context, url)) onNotice("No app can open this link")
                                }
                                terminalView?.selection = null
                                selectionActive = false
                            }
                        },
                        onCopy = {
                            val text = terminalView?.selectedText().orEmpty()
                            if (text.isNotEmpty()) copyToClipboard(context, text)
                            terminalView?.selection = null
                            selectionActive = false
                        },
                        onPaste = {
                            pasteFromClipboard(context)?.let { text ->
                                session.send(PasteEncoder.encode(text, modes.bracketedPaste))
                            }
                            terminalView?.selection = null
                            selectionActive = false
                        },
                    )
                }
            }

            // One UI declares a 15dp navigation bar and then takes 32dp for the home swipe, so keys
            // that cleared `safeDrawing` still had their lower two fifths inside the swipe, and a
            // thumb aiming for Esc went Home. `systemGestureExclusion` buys none of that back — the
            // bottom swipe is not one an app may opt out of — and a fixed inset would overpay on
            // stock Android, where the two measurements already agree and this correctly adds
            // nothing.
            //
            // The band is painted rather than left to the session behind it: chrome is deliberately
            // darker than any terminal background, so a lighter strip below the bar reads as the bar
            // floating loose. It is a wrapper rather than a modifier handed to `AuxiliaryKeyBar`,
            // which paints its own background over whatever the caller passes — whether the band
            // came out painted would then depend on an ordering that lives in the bar, not here.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LimentyColors.Surface)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .union(WindowInsets.mandatorySystemGestures)
                            .only(WindowInsetsSides.Bottom),
                    ),
            ) {
                AuxiliaryKeyBar(
                    keys = preferences.keySet.scrolling,
                    pinnedKeys = preferences.keySet.pinned,
                    onKeyPressed = { key ->
                        val events = key.toEvents()
                        if (events.isNotEmpty()) {
                            log.d("aux.press", "label" to key.label, "steps" to events.size)
                            pipeline.send(events)
                        }
                        terminalView?.requestFocus()
                    },
                    switcher = KeySetSwitcher(
                        index = preferences.keySetIndex,
                        count = preferences.keySets.size,
                        name = preferences.keySet.name,
                        onSwitch = { delta ->
                            // Positions only: a set's name is the owner's words, and stays out of the log.
                            log.d(
                                "aux.keySet",
                                "index" to preferences.switchKeySet(delta).keySetIndex,
                                "count" to preferences.keySets.size,
                            )
                            onKeySetSwitched(delta)
                        },
                    ),
                    groups = keyGroups,
                )
            }
        }

        val current = state
        when {
            // Idle as well as Connecting: the password is fetched off the main thread after the
            // screen has already changed, and a terminal sitting behind nothing at all for that
            // moment looked like a connection that had silently failed. Authenticating too: a
            // server slow to refuse a password left a blank screen with no way to cancel.
            current is ConnectionState.Idle ||
                current is ConnectionState.Connecting ||
                current is ConnectionState.Authenticating ->
                ConnectingOverlay(session.hostName, onCancel = onCancelConnect)
            current is ConnectionState.Disconnected && current.error == null ->
                DisconnectedOverlay(session.hostName, onReconnect = onReconnect, onBack = onBack)
            // Authentication is the one failure the user can fix without leaving the terminal, so
            // it gets a field rather than a message telling them to go back and edit the host.
            current is ConnectionState.Disconnected && current.error is TransportError.Authentication ->
                AuthenticationRetryOverlay(
                    hostName = session.hostName,
                    password = retypedPassword,
                    onPasswordChange = { retypedPassword = it },
                    onRetry = {
                        val entered = retypedPassword
                        retypedPassword = ""
                        onRetryWithPassword(entered)
                    },
                    onBack = onBack,
                )
            current is ConnectionState.Disconnected && current.error is TransportError.HostKeyMismatch ->
                HostKeyMismatchOverlay(
                    hostName = session.hostName,
                    error = current.error as TransportError.HostKeyMismatch,
                    onBack = onBack,
                )
            current is ConnectionState.Disconnected ->
                ConnectionFailedOverlay(
                    hostName = session.hostName,
                    error = current.error!!,
                    wasConnected = session.connectedFor() != null,
                    onRetry = onReconnect,
                    onBack = onBack,
                )
            else -> Unit
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    TermLog.logger(LogTags.CLIPBOARD).i("copy", "length" to text.length)
}

/**
 * Hands a link to whatever the user has set to open it, and says whether anything took it.
 *
 * BROWSABLE limits the candidates to activities that agreed to be opened from a link; without it
 * a crafted address printed by the remote could reach an activity that never expected one.
 */
private fun openLink(context: Context, url: String): Boolean {
    val log = TermLog.logger(LogTags.TERMINAL)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme())
        .addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        log.i("link.open", "length" to url.length)
        true
    } catch (_: ActivityNotFoundException) {
        log.w("link.noHandler", "length" to url.length)
        false
    }
}

private fun pasteFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    TermLog.logger(LogTags.CLIPBOARD).i("paste", "length" to (text?.length ?: 0))
    return text
}
