package dev.wadackel.limenty.ui.hosts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The list and detail layout of the host list on the unfolded display. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostDetailPaneTest {
    @get:Rule
    val rule = createComposeRule()

    private val idle = HostProfile(id = "a", name = "Workstation", hostname = "workstation.example", username = "someone")
    private val live = HostProfile(id = "b", name = "MacBook Air", hostname = "macbook.example", username = "someone")

    private val connected = mutableListOf<HostProfile>()
    private val deleted = mutableListOf<HostProfile>()
    private val disconnected = mutableListOf<HostProfile>()

    private fun show() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            LimentyTheme {
                // Not Modifier.width: wider than the window it is clamped to the window, which on a
                // phone-sized display is under the wide-layout breakpoint and draws the one-pane list.
                // ForcedSize lowers the density instead, so the unfolded size fits on any display.
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(933.dp, 704.dp))) {
                    HostListScreen(
                        hosts = listOf(idle, live),
                        statusOf = { if (it == live) HostStatus.Connected else HostStatus.Disconnected },
                        previewOf = { host -> if (host == live) SessionPreview(listOf("❯ "), "zsh · 3m") else null },
                        palette = TerminalPalette.Default,
                        mono = TerminalTypefaces(context).family(TerminalFont.Default),
                        found = emptyList(),
                        absent = { false },
                        onConnect = { connected += it },
                        onAddFound = {},
                        onEdit = {},
                        onDelete = { deleted += it },
                        onDisconnect = { disconnected += it },
                        onAdd = {},
                        onOpenSettings = {},
                        selectedHostId = selected,
                        onSelectHost = { selected = it },
                    )
                }
            }
        }
    }

    private fun row(name: String) = rule.onNode(hasText(name) and isSelectable())

    @Test
    fun theConnectedHostIsSelectedWhenNothingHasBeenChosen() {
        show()
        row("MacBook Air").assertIsSelected()
    }

    @Test
    fun pressingThePreviewResumesTheSelectedHost() {
        show()
        rule.onNodeWithText("Resume", substring = true).performClick()
        assertThat(connected).containsExactly(live)
    }

    @Test
    fun aHostWithoutASessionConnectsFromItsEmptyArea() {
        show()
        row("Workstation").performClick()
        rule.onNodeWithText("No session", substring = true).performClick()
        assertThat(connected).containsExactly(idle)
    }

    @Test
    fun disconnectIsOfferedOnlyWhileTheHostIsConnected() {
        show()
        rule.onNodeWithContentDescription("Disconnect").performClick()
        assertThat(disconnected).containsExactly(live)

        row("Workstation").performClick()
        rule.onNodeWithContentDescription("Disconnect").assertDoesNotExist()
    }

    @Test
    fun deletingFromThePaneAsksBeforeDeleting() {
        show()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertThat(deleted).isEmpty()

        rule.onNodeWithText("Delete").performClick()
        assertThat(deleted).containsExactly(live)
    }
}
