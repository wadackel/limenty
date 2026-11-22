package dev.wadackel.limenty.ui.terminal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.transport.TransportError
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An overlay holds the screen until it is answered: a tap on the dim area around its box reaches
 * neither the terminal behind it nor the screen the trust prompt is drawn over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w933dp-h704dp-420dpi")
class OverlayScrimTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var behindTaps = 0

    @Test
    fun `a tap around a failed connection's box does not reach the terminal behind it`() {
        showOver {
            ConnectionFailedOverlay(
                hostName = "alice@example-host",
                error = TransportError.ConnectionRefused,
                wasConnected = false,
                onRetry = {},
                onBack = {},
            )
        }

        tapTheDimmedCorner()

        assertThat(behindTaps).isEqualTo(0)
    }

    @Test
    fun `a tap around the host key prompt does not reach the screen under it`() {
        showOver { HostKeyPrompt(onCancel = {}) }

        tapTheDimmedCorner()

        assertThat(behindTaps).isEqualTo(0)
    }

    @Test
    fun `the prompt's own buttons still answer it`() {
        var cancelled = 0
        showOver { HostKeyPrompt(onCancel = { cancelled++ }) }

        compose.onNodeWithText("Cancel").performClick()

        assertThat(cancelled).isEqualTo(1)
        assertThat(behindTaps).isEqualTo(0)
    }

    @Composable
    private fun HostKeyPrompt(onCancel: () -> Unit) {
        HostKeyOverlay(
            host = "example-host:2224",
            algorithm = "ssh-ed25519",
            fingerprint = "SHA256:0000000000000000000000000000000000000000000",
            onTrust = {},
            onCancel = onCancel,
        )
    }

    private fun showOver(overlay: @Composable () -> Unit) {
        compose.setContent {
            LimentyTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(BEHIND)
                            .clickable { behindTaps++ },
                    )
                    overlay()
                }
            }
        }
    }

    /** Well clear of the box, which is centred and leaves the edges of the screen dimmed. */
    private fun tapTheDimmedCorner() {
        compose.onNodeWithTag(BEHIND).performTouchInput { click(Offset(40f, 40f)) }
        compose.waitForIdle()
    }

    private companion object {
        const val BEHIND = "behind"
    }
}
