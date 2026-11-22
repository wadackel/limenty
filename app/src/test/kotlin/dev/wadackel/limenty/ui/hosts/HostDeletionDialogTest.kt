package dev.wadackel.limenty.ui.hosts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class HostDeletionDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun host(name: String, port: Int) = HostProfile(id = name, name = name, hostname = "10.0.2.2", username = "alice", port = port)

    private fun askToDelete(name: String, hosts: List<HostProfile>) {
        compose.setContent {
            LimentyTheme {
                HostListScreen(
                    hosts = hosts,
                    statusOf = { HostStatus.Disconnected },
                    previewOf = { null },
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
                    selectedHostId = null,
                    onSelectHost = {},
                )
            }
        }
        compose.onNodeWithContentDescription("More for $name").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
    }

    @Test
    fun `deleting the only host on an address says its trusted key goes too`() {
        askToDelete("first", listOf(host("first", 2223), host("other", 2224)))

        compose.onNodeWithText("The saved password and trusted host key are removed too.").assertExists()
    }

    @Test
    fun `deleting a host whose address another host uses says the trusted key stays`() {
        askToDelete("first", listOf(host("first", 2223), host("twin", 2223), host("other", 2224)))

        compose.onNodeWithText(
            "The saved password is removed too. The trusted host key stays, because twin also " +
                "connects to 10.0.2.2 on port 2223.",
        ).assertExists()
    }

    @Test
    fun `the dialog names every other host that keeps the trusted key`() {
        askToDelete("first", listOf(host("first", 2223), host("twin", 2223), host("third", 2223)))

        compose.onNodeWithText(
            "The saved password is removed too. The trusted host key stays, because twin and third " +
                "also connect to 10.0.2.2 on port 2223.",
        ).assertExists()
    }

    @Test
    fun `beyond two other hosts the dialog counts the rest`() {
        askToDelete(
            "first",
            listOf(host("first", 2223), host("twin", 2223), host("third", 2223), host("fourth", 2223)),
        )

        compose.onNodeWithText(
            "The saved password is removed too. The trusted host key stays, because twin and 2 other " +
                "hosts also connect to 10.0.2.2 on port 2223.",
        ).assertExists()
    }
}
