package dev.wadackel.limenty.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import com.google.common.truth.Truth.assertWithMessage
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import dev.wadackel.limenty.core.transport.TransportError
import dev.wadackel.limenty.data.CustomTheme
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.NoticeLicense
import dev.wadackel.limenty.data.OpenSourceNotice
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.about.AboutSection
import dev.wadackel.limenty.ui.about.BuildInfo
import dev.wadackel.limenty.ui.hosts.HostFormScreen
import dev.wadackel.limenty.ui.hosts.HostListScreen
import dev.wadackel.limenty.ui.hosts.HostStatus
import dev.wadackel.limenty.ui.hosts.SessionPreview
import dev.wadackel.limenty.ui.settings.KeyBarSettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsSection
import dev.wadackel.limenty.ui.settings.ThemeColor
import dev.wadackel.limenty.ui.settings.ThemeEditorScreen
import dev.wadackel.limenty.ui.terminal.ConnectionFailedOverlay
import dev.wadackel.limenty.ui.terminal.HostKeyOverlay
import dev.wadackel.limenty.ui.terminal.TerminalScreen
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyTheme
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
 * Holds every screen to docs/design.md's "anything pressed is at least 44 tall", measured on the
 * laid-out bounds of each node that takes a click, across as well as down.
 *
 * The bounds, not the picture: Compose lets a press land up to 48dp around a small target, but a
 * press inside a neighbour's bounds goes to the neighbour. Version and Build on About were as
 * tall as their text, so a press near the line between them copied the other value, and no
 * screenshot showed it.
 * Native graphics, because the default fake text measurement makes every word a few dp wide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = FOLDED)
class PressTargetSizeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val typefaces by lazy { TerminalTypefaces(compose.activity) }

    @Test
    fun `everything pressed on the host list is large enough to press`() {
        show { HostList(selectedHostId = null) }
        assertPressablesAreLargeEnough("host list")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `everything pressed on the host list beside a host's detail is large enough to press`() {
        show { HostList(selectedHostId = "devbox") }
        assertPressablesAreLargeEnough("host list with detail")
    }

    @Test
    fun `everything pressed in the dialog that confirms deleting a host is large enough to press`() {
        show { HostList(selectedHostId = null) }
        compose.onNodeWithContentDescription("More for devbox").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertPressablesAreLargeEnough("delete host dialog")
    }

    @Test
    fun `everything pressed on the host form is large enough to press`() {
        show { HostFormScreen(initial = devbox, initialPassword = "", onSave = { _, _ -> }, onCancel = {}) }
        assertPressablesAreLargeEnough("host form")
    }

    @Test
    fun `everything pressed on a connected terminal is large enough to press`() {
        val session = connectedSession()
        show {
            TerminalScreen(
                session = session,
                preferences = TerminalPreferences(),
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
        assertPressablesAreLargeEnough("terminal")
    }

    @Test
    fun `everything pressed on the host key prompt is large enough to press`() {
        show {
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
        assertPressablesAreLargeEnough("host key prompt")
    }

    @Test
    fun `everything pressed when a connection fails is large enough to press`() {
        show {
            Box(Modifier.fillMaxSize().background(LimentyColors.Bg)) {
                ConnectionFailedOverlay(
                    hostName = "devbox",
                    error = TransportError.ConnectionRefused,
                    wasConnected = false,
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        assertPressablesAreLargeEnough("connection failed")
    }

    @Test
    fun `everything pressed on the settings index is large enough to press`() {
        show { Settings(section = null) }
        assertPressablesAreLargeEnough("settings")
    }

    @Test
    fun `everything pressed in each settings section is large enough to press`() {
        val small = SettingsSection.entries.flatMap { section ->
            show { Settings(section) }
            smallTargets("settings ${section.title}")
        }
        assertWithMessage("Pressable targets under ${MIN_DP}dp").that(small).isEmpty()
    }

    @Test
    fun `everything pressed on the key bar settings and its key editor is large enough to press`() {
        show { KeyBarSettingsScreen(preferences = TerminalPreferences(), onChange = {}, onBack = {}) }
        assertPressablesAreLargeEnough("key bar settings")
        compose.onAllNodesWithText("Esc").let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
        assertPressablesAreLargeEnough("key editor")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `everything pressed in each settings section on the unfolded display is large enough to press`() {
        val small = (listOf(null) + SettingsSection.entries).flatMap { section ->
            show { Settings(section) }
            smallTargets("settings ${section?.title ?: "index"}")
        }
        assertWithMessage("Pressable targets under ${MIN_DP}dp").that(small).isEmpty()
    }

    @Test
    fun `everything pressed on the theme editor is large enough to press`() {
        show { ThemeEditor() }
        assertPressablesAreLargeEnough(THEME_EDITOR)
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `everything pressed on the theme editor on the unfolded display is large enough to press`() {
        show { ThemeEditor() }
        assertPressablesAreLargeEnough(THEME_EDITOR_UNFOLDED)
    }

    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var started = false

    /** One composition swapped between scenes, since a compose rule takes setContent only once. */
    private fun show(scene: @Composable () -> Unit) {
        content = scene
        if (!started) {
            compose.setContent { LimentyTheme { content() } }
            started = true
        }
        compose.waitForIdle()
    }

    private fun assertPressablesAreLargeEnough(screen: String) {
        assertWithMessage("Pressable targets under ${MIN_DP}dp").that(smallTargets(screen)).isEmpty()
    }

    /**
     * Each target under the minimum on [screen], as "screen: name: width x height dp", and each
     * exception for [screen] that no longer excuses anything, so one cannot outlive its reason.
     */
    private fun smallTargets(screen: String): List<String> {
        val density = compose.activity.resources.displayMetrics.density
        val pressable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (pressable.isEmpty()) return listOf("$screen: nothing pressable was found, so nothing was checked")
        val small = pressable.filter { it.size.width / density < MIN_DP || it.size.height / density < MIN_DP }
        val allowed = exceptions.filter { screen in it.screens }
        val unexcused = small.filterNot { node -> allowed.any { it.excuses(node) } }.map { node ->
            "$screen: ${describe(node)}: ${"%.1f".format(node.size.width / density)} x ${"%.1f".format(node.size.height / density)} dp"
        }
        val stale = allowed.filter { exception -> small.none(exception.excuses) }.map { "$screen: ${it.name}: excuses nothing" }
        return unexcused + stale
    }

    private fun describe(node: SemanticsNode): String {
        val config = node.config
        val words = config.getOrNull(SemanticsActions.OnClick)?.label
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
            ?: config.getOrNull(SemanticsProperties.TestTag)
            ?: generateSequence(node) { it.children.firstOrNull() }.drop(1)
                .firstNotNullOfOrNull { child -> child.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        return words ?: "unnamed node ${node.id}"
    }

    /** Targets on [screens] the rule knowingly does not hold to, and why it does not. */
    private class Allowed(val screens: List<String>, val name: String, val reason: String, val excuses: (SemanticsNode) -> Boolean)

    private val exceptions = listOf(
        Allowed(
            screens = listOf(THEME_EDITOR, THEME_EDITOR_UNFOLDED),
            name = "the sample's spans",
            reason = "The sample is set at a terminal's size, so its words, blocks and cursor are a " +
                "line tall. Pressing one is a shortcut to its colour, which the swatch rows also " +
                "offer at 44dp tall; spans 44 tall would no longer show a terminal.",
        ) { node -> node.config.getOrNull(SemanticsActions.OnClick)?.label in themeColorLabels },
        Allowed(
            screens = listOf(THEME_EDITOR_UNFOLDED),
            name = "the swatches' width",
            reason = "Eight swatches share the side panel, which stops at 400dp so the sample beside " +
                "it keeps its lines; each gets 38dp of width, all of it its own, and the full 44 of " +
                "height. Never narrower than the 36dp swatch it draws.",
        ) { node ->
            val density = node.layoutInfo.density.density
            node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.substringBefore(",") in themeColorLabels &&
                node.size.width / density >= SWATCH_DP &&
                node.size.height / density >= MIN_DP
        },
    )

    @Composable
    private fun HostList(selectedHostId: String?) {
        HostListScreen(
            hosts = listOf(devbox, homelab),
            statusOf = { if (it.id == "devbox") HostStatus.Connected else HostStatus.Disconnected },
            previewOf = { if (it.id == "devbox") SessionPreview(lines = listOf("ada@devbox ~", "❯"), meta = "tmux · 14m") else null },
            palette = TerminalPalette.Default,
            mono = FontFamily.Monospace,
            found = emptyList(),
            absent = { false },
            onConnect = {},
            onAddFound = {},
            onEdit = {},
            onDelete = {},
            onDisconnect = {},
            onAdd = {},
            onOpenSettings = {},
            selectedHostId = selectedHostId,
            onSelectHost = {},
        )
    }

    @Composable
    private fun ThemeEditor() {
        ThemeEditorScreen(
            preferences = withCustomTheme,
            themeId = customTheme.id,
            typefaces = typefaces,
            onChange = {},
            onBack = {},
        )
    }

    @Composable
    private fun Settings(section: SettingsSection?) {
        SettingsScreen(
            preferences = withCustomTheme,
            typefaces = typefaces,
            section = section,
            onSelectSection = {},
            onChange = {},
            onBack = {},
            version = "0.1.0",
            about = {
                AboutSection(
                    build = BuildInfo(version = "0.1.0", type = "debug", commit = "07a5ff5", dirty = false),
                    ownLicense = mit,
                    notices = listOf(OpenSourceNotice(name = "Kotlin", detail = "org.jetbrains.kotlin", parts = listOf("kotlin-stdlib"), licenses = listOf(mit))),
                    fontFamilyOf = { null },
                )
            },
        )
    }

    private fun connectedSession() = TerminalSession(
        id = SessionId("press"),
        hostId = "devbox",
        hostName = "devbox",
        transport = ConnectedTransport(),
        columns = 80,
        rows = 24,
    )

    private class ConnectedTransport : TerminalTransport {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val output: Flow<ByteArray> = emptyFlow()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
        override suspend fun disconnect() = Unit
    }

    private companion object {
        const val MIN_DP = 44f
        const val SWATCH_DP = 36f
        const val THEME_EDITOR = "theme editor"
        const val THEME_EDITOR_UNFOLDED = "theme editor unfolded"
        val themeColorLabels = ThemeColor.entries.map { it.label }

        val devbox = HostProfile(id = "devbox", name = "devbox", hostname = "devbox", username = "ada")
        val homelab = HostProfile(id = "homelab", name = "homelab", hostname = "homelab.tail0a1b.ts.net", username = "ada")
        val mit = NoticeLicense(label = "MIT", name = "MIT License", text = "Permission is hereby granted.")
        val customTheme = CustomTheme("custom:mine", TerminalPalette.Default.copy(name = "Mine"))
        val withCustomTheme = TerminalPreferences(themeId = customTheme.id, customThemes = listOf(customTheme))
    }
}

/** The phone's cover display, which DocsScreenshotsTest draws on too. */
private const val FOLDED = "w475dp-h751dp-420dpi"

/** The inner display unfolded, where the host list gains its detail pane. */
private const val UNFOLDED = "w933dp-h704dp-420dpi"
