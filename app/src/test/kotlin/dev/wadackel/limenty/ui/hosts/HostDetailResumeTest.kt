package dev.wadackel.limenty.ui.hosts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w933dp-h704dp-420dpi")
class HostDetailResumeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `pressing Resume under the preview opens the session`() {
        var opened = 0
        compose.setContent {
            LimentyTheme {
                HostDetail(
                    host = HostProfile(id = "a", name = "devbox", hostname = "devbox", username = "alice"),
                    absent = false,
                    connected = true,
                    preview = SessionPreview(lines = listOf("alice@devbox ~", "❯"), meta = "tmux · 14m"),
                    palette = TerminalPalette.Default,
                    mono = FontFamily.Monospace,
                    onOpen = { opened++ },
                    onEdit = {},
                    onDisconnect = {},
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithText("Resume").performClick()

        assertThat(opened).isEqualTo(1)
    }
}
