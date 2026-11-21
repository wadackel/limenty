package dev.wadackel.limenty.docs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalTypography
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.KeyStep
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.terminal.TerminalView
import dev.wadackel.limenty.ui.hosts.HostListScreen
import dev.wadackel.limenty.ui.hosts.HostStatus
import dev.wadackel.limenty.ui.hosts.SessionPreview
import dev.wadackel.limenty.ui.settings.KeyBarSettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsSection
import dev.wadackel.limenty.ui.terminal.HostKeyOverlay
import dev.wadackel.limenty.ui.terminal.TerminalScreen
import dev.wadackel.limenty.ui.theme.LimentyColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screenshots in the README, rendered from the real screens.
 *
 * `./gw recordRoborazziDebug` writes them to `docs/images/`; CI runs the same test in verify mode,
 * so a change that alters any of these screens fails until the images are recorded again. The
 * device is the phone's cover display, which is what the app is designed around.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w475dp-h751dp-420dpi")
class DocsScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = compose.activity
    private val typefaces by lazy { TerminalTypefaces(activity) }
    private val density get() = activity.resources.displayMetrics.density

    private var scene by mutableStateOf<@Composable () -> Unit>({})

    private fun show(content: @Composable () -> Unit): Bitmap {
        if (!started) {
            compose.setContent { scene() }
            started = true
        }
        scene = content
        compose.waitForIdle()
        return capture(compose.onRoot())
    }

    /**
     * Draws the window holding [node] into a bitmap.
     *
     * Compose's own captureToImage waits for the next frame, which never comes on Robolectric's
     * paused looper; drawing the view directly is what the frame would have done anyway.
     */
    private fun capture(node: SemanticsNodeInteraction): Bitmap {
        val root = (node.fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        return bitmap
    }

    private var started = false

    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().boundsInRoot.top

    private fun save(image: Bitmap, name: String) {
        image.captureRoboImage("$IMAGES/$name.png", roborazziOptions = options)
    }

    @Test
    fun `the hero shows the host list, a tmux session and the theme picker`() {
        val hosts = show { HostList() }
        val session = connectedSession()
        show { Terminal(session) }
        compose.runOnUiThread { session.emulator.write(DocsScenes.tmuxWorkspace(session.emulator.columns, session.emulator.rows)) }
        val terminal = show { Terminal(session) }
        val themes = show { Settings(SettingsSection.Appearance) }
        save(DocsImages.hero(hosts, terminal, themes), "hero")
    }

    @Test
    fun `the key bar strip shows a named set with a key that sends two steps`() {
        val session = connectedSession()
        show { Terminal(session) }
        compose.runOnUiThread { session.emulator.write(DocsScenes.tmuxWorkspace(session.emulator.columns, session.emulator.rows)) }
        val screen = show { Terminal(session) }
        val cell = terminalView().metrics.cellHeight
        val keyTop = compose.onAllNodesWithText("new win")[0].top()
        val top = (keyTop - cell * 3 - 12 * density).toInt()
        val strip = DocsImages.crop(screen, Rect(0, top, screen.width, screen.height))
        save(DocsImages.frame(DocsImages.scaleToWidth(strip, 960), radius = 28f), "keybar")
    }

    @Test
    fun `the key editor shows a key labelled new win that sends Ctrl-b then c`() {
        show { KeyBarSettingsScreen(preferences = docsPreferences, onChange = {}, onBack = {}) }
        compose.onAllNodesWithText("new win").let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
        compose.waitForIdle()
        val dialog = capture(compose.onNode(isDialog()))
        save(DocsImages.frame(DocsImages.scaleToWidth(trim(dialog), 640), radius = 40f), "key-editor")
    }

    @Test
    fun `the gesture settings list every gesture with its binding`() {
        val screen = show { Settings(SettingsSection.Gestures) }
        save(DocsImages.frame(DocsImages.scaleToWidth(screen, 640), radius = 40f), "gestures")
    }

    @Test
    fun `the host key prompt shows the fingerprint to compare`() {
        val screen = show {
            Box(Modifier.fillMaxSize().background(LimentyColors.Bg)) {
                HostKeyOverlay(
                    host = "devbox",
                    algorithm = "ssh-ed25519",
                    fingerprint = "SHA256:7/wrqcPQ2DPFoUxFENIE2s3B2Zx65cTreEQQCzwkudQ",
                    onTrust = {},
                    onCancel = {},
                )
            }
        }
        save(DocsImages.frame(DocsImages.scaleToWidth(trim(screen), 640), radius = 40f), "host-key")
    }

    @Test
    fun `Japanese being composed is drawn at the cursor before anything is sent`() {
        val session = connectedSession()
        show { Terminal(session) }
        // After the screen is up, not before: laying the terminal out again resets its input, and
        // with it the composition.
        compose.runOnUiThread {
            session.emulator.write(DocsScenes.commitPrompt())
            terminalView().onCreateInputConnection(EditorInfo()).setComposingText("終了時にキューを空にする", 1)
        }
        compose.waitForIdle()
        val screen = capture(compose.onRoot())
        val cell = terminalView().metrics.cellHeight
        val strip = DocsImages.crop(screen, Rect(0, 0, screen.width, (cell * 7).toInt()))
        save(DocsImages.frame(DocsImages.scaleToWidth(DocsImages.pad(strip, 24), 960), radius = 28f), "ime")
    }

    @Composable
    private fun HostList() {
        val devbox = HostProfile(id = "devbox", name = "devbox", hostname = "devbox", username = "ada")
        val homelab = HostProfile(id = "homelab", name = "homelab", hostname = "homelab.tail0a1b.ts.net", username = "ada")
        val previews = mapOf(
            "devbox" to SessionPreview(
                lines = listOf(
                    "  0[||||||||||        41.8%]  Tasks: 38, 2 running",
                    "  1[||||||            26.9%]  Load average: 0.84 0.61",
                    "Mem[||||||||||||| 2.21G/3.81G] Uptime: 4 days, 02:13:51",
                    "",
                    "  PID USER     CPU% MEM%   TIME+  Command",
                    "  812 ada      38.2  4.7  2:41.07 orbit --workers 8",
                    " orbit  0:nvim*  1:logs-                         14:02",
                ),
                meta = "orbit · 2h 14m",
            ),
            "homelab" to SessionPreview(
                lines = listOf(
                    "ada@homelab ~ ",
                    "❯ docker compose ps --format '{{.Name}} {{.Status}}'",
                    "grafana     Up 6 days",
                    "prometheus  Up 6 days",
                    "restic      Up 2 hours",
                    "ada@homelab ~ ",
                    "❯ ",
                ),
                meta = "18m",
            ),
        )
        HostListScreen(
            hosts = listOf(devbox, homelab),
            statusOf = { HostStatus.Connected },
            previewOf = { previews[it.id] },
            palette = TerminalPalette.Default,
            mono = typefaces.family(TerminalFont.byId(TerminalTypography().fontId)),
            found = emptyList(),
            absent = { false },
            onConnect = {},
            onAddFound = {},
            onEdit = {},
            onDelete = {},
            onDisconnect = {},
            onAdd = {},
            onOpenSettings = {},
            selectedHostId = null,
            onSelectHost = {},
        )
    }

    @Composable
    private fun Terminal(session: TerminalSession) {
        TerminalScreen(
            session = session,
            preferences = docsPreferences,
            typefaces = typefaces,
            onFontSizeChanged = {},
            onKeySetSwitched = {},
            onBack = {},
            onReconnect = {},
            onRetryWithPassword = {},
            onCancelConnect = {},
            onNotice = {},
        )
    }

    @Composable
    private fun Settings(section: SettingsSection) {
        SettingsScreen(
            preferences = TerminalPreferences(),
            typefaces = typefaces,
            section = section,
            onSelectSection = {},
            onChange = {},
            onBack = {},
            version = "0.1.0",
        )
    }

    private fun connectedSession() = TerminalSession(
        id = SessionId("docs"),
        hostId = "devbox",
        hostName = "devbox",
        transport = ConnectedTransport(),
        columns = 80,
        rows = 24,
    )

    private fun terminalView(): TerminalView = findTerminalView(activity.window.decorView)
        ?: error("TerminalScreen did not lay out a TerminalView")

    private fun findTerminalView(view: View): TerminalView? = when (view) {
        is TerminalView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTerminalView(view.getChildAt(it)) }
        else -> null
    }

    /** Cuts away the uniform area around a dialog, judged against the top-left pixel. */
    private fun trim(source: Bitmap): Bitmap {
        val background = source.getPixel(0, 0)
        var left = source.width
        var top = source.height
        var right = 0
        var bottom = 0
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                if (source.getPixel(x, y) != background) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return DocsImages.crop(source, Rect(left, top, right + 1, bottom + 1))
    }

    private class ConnectedTransport : TerminalTransport {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val output: Flow<ByteArray> = emptyFlow()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
        override suspend fun disconnect() = Unit
    }

    private companion object {
        /** Relative to the module, which is where Gradle runs unit tests from. */
        const val IMAGES = "../docs/images"

        /**
         * Each pixel may drift by a shade or sit one pixel over, which is how text rasterised on
         * macOS and on the Linux CI runner differs; any pixel beyond that fails. A share of
         * differing pixels was the obvious tolerance and was rejected: at 1% a recoloured button
         * passed on every screen where it was small.
         */
        val options = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.007f, vShift = 1, hShift = 1),
                changeThreshold = 0f,
            ),
        )

        private fun ctrl(char: Char) = KeyStep.Press(codePoint = char.code, modifiers = Modifiers.Ctrl)
        private fun press(char: Char) = KeyStep.Press(codePoint = char.code)

        val tmux = KeySet(
            name = "tmux",
            scrolling = listOf(
                AuxiliaryKey("new win", listOf(ctrl('b'), press('c'))),
                AuxiliaryKey("split", listOf(ctrl('b'), press('"'))),
                AuxiliaryKey("next", listOf(ctrl('b'), press('n'))),
                AuxiliaryKey("copy", listOf(ctrl('b'), press('['))),
            ) + AuxiliaryKey.DefaultScrolling,
            pinned = AuxiliaryKey.DefaultPinned,
        )

        val nvim = KeySet(
            name = "nvim",
            scrolling = listOf(AuxiliaryKey(":w", listOf(KeyStep.Literal(":w\r")))) + AuxiliaryKey.DefaultScrolling,
            pinned = AuxiliaryKey.DefaultPinned,
        )

        val docsPreferences = TerminalPreferences(
            typography = TerminalTypography(fontSizeSp = 10f),
            keySets = listOf(KeySet.Default, tmux, nvim),
            activeKeySet = 1,
        )
    }
}
