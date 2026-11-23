package dev.wadackel.limenty.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import dev.wadackel.limenty.Screenshots
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalTypography
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.CustomTheme
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.hosts.HostListScreen
import dev.wadackel.limenty.ui.hosts.HostStatus
import dev.wadackel.limenty.ui.hosts.SessionPreview
import dev.wadackel.limenty.ui.settings.KeyBarSettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsScreen
import dev.wadackel.limenty.ui.settings.SettingsSection
import dev.wadackel.limenty.ui.terminal.TerminalScreen
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
 * The screens that show a name the owner typed, drawn with names far longer than the space they
 * get, and compared on every `./gw test` against the images in `src/test/screenshots/long-names/`.
 *
 * Names have no length limit, and the bugs this has caught before were each found by hand on one
 * screen: a theme name wrapping under the Theme heading, a key set name clipped at the screen edge
 * with nothing to show it was cut. A layout assertion per screen would have to know in advance
 * which way each one goes wrong; the picture shows all of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = FOLDED)
class LongNameScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val typefaces by lazy { TerminalTypefaces(compose.activity) }

    @Test
    fun `a long theme name in Appearance on the cover display`() {
        save(show { Settings(SettingsSection.Appearance) }, "appearance-folded")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `a long theme name in Appearance on the unfolded display`() {
        save(show { Settings(SettingsSection.Appearance) }, "appearance-unfolded")
    }

    @Test
    fun `a long theme name on its card in the theme picker on the cover display`() {
        show { Settings(SettingsSection.Appearance) }
        save(scrolledTo(themeMore), "theme-picker-folded")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `a long theme name on its card in the theme picker on the unfolded display`() {
        show { Settings(SettingsSection.Appearance) }
        save(scrolledTo(themeMore), "theme-picker-unfolded")
    }

    @Test
    fun `a long theme name as the title of its card's menu`() {
        show { Settings(SettingsSection.Appearance) }
        compose.onNodeWithContentDescription(themeMore).performScrollTo().performClick()
        compose.waitForIdle()
        save(Screenshots.capture(compose, compose.onNode(isDialog())), "theme-menu-folded")
    }

    @Test
    fun `a long key set name flashed above the key bar on the cover display`() {
        save(flashLongSetName(), "key-bar-flash-folded")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `a long key set name flashed above the key bar on the unfolded display`() {
        save(flashLongSetName(), "key-bar-flash-unfolded")
    }

    @Test
    fun `long key set names on the key set tabs on the cover display`() {
        save(show { KeyBarSettingsScreen(preferences = keyBarPreferences, onChange = {}, onBack = {}) }, "key-set-tabs-folded")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `long key set names on the key set tabs on the unfolded display`() {
        save(show { KeyBarSettingsScreen(preferences = keyBarPreferences, onChange = {}, onBack = {}) }, "key-set-tabs-unfolded")
    }

    @Test
    fun `a long host name and hostname on the host list on the cover display`() {
        save(show { HostList(selectedHostId = null) }, "host-list-folded")
    }

    @Test
    @Config(qualifiers = UNFOLDED)
    fun `a long host name and hostname on the host list and its detail on the unfolded display`() {
        save(show { HostList(selectedHostId = longHost.id) }, "host-detail-unfolded")
    }

    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var started = false

    /** One composition swapped between scenes, since a compose rule takes setContent only once. */
    private fun show(scene: @Composable () -> Unit): Bitmap {
        content = scene
        if (!started) {
            compose.setContent { LimentyTheme { content() } }
            started = true
        }
        compose.waitForIdle()
        return Screenshots.capture(compose, compose.onRoot())
    }

    private fun scrolledTo(contentDescription: String): Bitmap {
        compose.onNodeWithContentDescription(contentDescription).performScrollTo()
        compose.waitForIdle()
        return Screenshots.capture(compose, compose.onRoot())
    }

    /** Switches the terminal's bar to the long-named set and stops the clock while its name shows. */
    private fun flashLongSetName(): Bitmap {
        var preferences by mutableStateOf(keyBarPreferences.copy(activeKeySet = 0))
        val session = connectedSession()
        show { Terminal(session, preferences) }
        compose.mainClock.autoAdvance = false
        preferences = preferences.copy(activeKeySet = 1)
        // Without this the switch is composed only at the end of the jump below, so the name's
        // timer starts there and the name is not yet drawn when it is captured.
        compose.waitForIdle()
        // Past the slide, well short of the name fading out.
        compose.mainClock.advanceTimeBy(300)
        return Screenshots.capture(compose, compose.onRoot())
    }

    private fun save(image: Bitmap, name: String) {
        image.captureRoboImage("$IMAGES/$name.png", roborazziOptions = Screenshots.options)
    }

    @Composable
    private fun Settings(section: SettingsSection) {
        SettingsScreen(
            preferences = themePreferences,
            typefaces = typefaces,
            section = section,
            onSelectSection = {},
            onChange = {},
            onBack = {},
            version = "0.1.0",
        )
    }

    @Composable
    private fun Terminal(session: TerminalSession, preferences: TerminalPreferences) {
        TerminalScreen(
            session = session,
            preferences = preferences,
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
    private fun HostList(selectedHostId: String?) {
        HostListScreen(
            hosts = listOf(longHost, shortHost),
            statusOf = { if (it.id == longHost.id) HostStatus.Connected else HostStatus.Disconnected },
            previewOf = {
                if (it.id == longHost.id) SessionPreview(lines = listOf("ada@build ~", "❯"), meta = "$LONG_TITLE · 14m") else null
            },
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
            selectedHostId = selectedHostId,
            onSelectHost = {},
        )
    }

    private fun connectedSession() = TerminalSession(
        id = SessionId("long"),
        hostId = longHost.id,
        hostName = longHost.displayName,
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
        /** Relative to the module, which is where Gradle runs unit tests from. */
        const val IMAGES = "src/test/screenshots/long-names"

        /** The names #15 and #20 were filed with: one with spaces to wrap at, one with none. */
        const val LONG_THEME_NAME = "Averyveryveryverylongthemename and then some more words that keep going on and on " +
            "until the dialog title and the card label must wrap or clip somewhere 1234567890"
        const val LONG_SET_NAME = "A-very-long-key-set-name-that-has-no-spaces-and-goes-on-for-a-long-while-until-the-screen-is-exceeded-1234567890"

        /** A terminal title as a shell sets it, working directory and all; the host's preview shows it. */
        const val LONG_TITLE = "ada@build-machine-under-the-stairs: ~/src/github.com/someone/a-project-with-a-long-name/app/src/main/kotlin"

        val themeMore = "More for $LONG_THEME_NAME"

        val longTheme = CustomTheme("custom:long", TerminalPalette.Default.copy(name = LONG_THEME_NAME))
        val themePreferences = TerminalPreferences(themeId = longTheme.id, customThemes = listOf(longTheme))

        val keyBarPreferences = TerminalPreferences(
            keySets = listOf(
                KeySet.Default,
                KeySet(LONG_SET_NAME, AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned),
                KeySet("tmux", AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned),
            ),
            activeKeySet = 1,
        )

        val longHost = HostProfile(
            id = "long",
            name = "The build machine under the stairs that runs every nightly job and never sleeps 1234567890",
            hostname = "build-machine-under-the-stairs-nightly-runner-0123456789.tail0a1b.ts.net",
            username = "a-service-account-with-a-long-name",
            port = 2222,
        )
        val shortHost = HostProfile(id = "devbox", name = "devbox", hostname = "devbox", username = "ada")
    }
}

/** The phone's cover display, which DocsScreenshotsTest draws on too. */
private const val FOLDED = "w475dp-h751dp-420dpi"

/** The inner display unfolded, where the host list gains its detail pane. */
private const val UNFOLDED = "w933dp-h704dp-420dpi"
