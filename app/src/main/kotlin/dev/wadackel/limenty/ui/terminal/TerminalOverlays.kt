package dev.wadackel.limenty.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.transport.TransportError
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyDialogMargin
import dev.wadackel.limenty.ui.components.LimentyDialogSurface
import dev.wadackel.limenty.ui.components.LimentyField
import dev.wadackel.limenty.ui.components.LimentyPrimaryButton
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * The only chrome the terminal shows.
 *
 * The healthy state is kept entirely free of overlays — no title, no "connected" badge — so
 * anything drawn here is by definition a state the user has to act on. Each is the same box the
 * rest of the app asks its questions in; they were the last surfaces still mixing their own colours
 * out of hex, from a palette that predates the one the app now uses.
 */
@Composable
fun ConnectingOverlay(hostName: String, onCancel: () -> Unit) {
    Overlay("Connecting") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                color = LimentyColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Host(hostName)
        }
        LimentyDialogActions {
            LimentyTextButton("Cancel", onCancel, color = LimentyColors.Text2)
        }
    }
}

@Composable
fun ConnectionFailedOverlay(
    hostName: String,
    error: TransportError,
    /** The session had been up, so this is a drop rather than a connection that never happened. */
    wasConnected: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Overlay(if (wasConnected) "Disconnected" else "Could not connect") {
        Host(hostName)
        Note(error.describe())
        LimentyDialogActions {
            LimentyTextButton("Back", onBack, color = LimentyColors.Text2)
            LimentyPrimaryButton(if (wasConnected) "Reconnect" else "Retry", onRetry)
        }
    }
}

@Composable
fun DisconnectedOverlay(hostName: String, onReconnect: () -> Unit, onBack: () -> Unit) {
    Overlay("Connection closed") {
        Host(hostName)
        LimentyDialogActions {
            LimentyTextButton("Back", onBack, color = LimentyColors.Text2)
            LimentyPrimaryButton("Reconnect", onReconnect)
        }
    }
}

@Composable
fun AuthenticationRetryOverlay(
    hostName: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Overlay("Authentication failed") {
        Host(hostName)
        LimentyField(
            label = "Password",
            value = password,
            onValueChange = onPasswordChange,
            // Never learned and never suggested: this is the one field in the app whose contents
            // are a secret, and an IME that remembers it would carry it out of the app.
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onRetry() }),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation('•'),
        )
        LimentyDialogActions {
            LimentyTextButton("Back", onBack, color = LimentyColors.Text2)
            LimentyPrimaryButton("Retry", onRetry, enabled = password.isNotEmpty())
        }
    }
}

@Composable
fun HostKeyOverlay(
    host: String,
    algorithm: String,
    fingerprint: String,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    Overlay("Host authenticity could not be verified") {
        Host(host)
        Note("Compare this fingerprint against the host before trusting it.")
        Evidence(algorithm to fingerprint)
        LimentyDialogActions {
            LimentyTextButton("Cancel", onCancel, color = LimentyColors.Text2)
            // Not the filled button, which would present trusting as the expected way on: the key
            // is remembered, and a wrong answer here holds until the host itself is deleted.
            LimentyTextButton("Trust & connect", onTrust, color = LimentyColors.Danger)
        }
    }
}

/**
 * A changed host key is never something to retry past.
 *
 * The only ways forward are to leave, or to deliberately forget the old key — which is why this has
 * no Retry button and shows both fingerprints for the user to compare against the host.
 */
@Composable
fun HostKeyMismatchOverlay(
    hostName: String,
    error: TransportError.HostKeyMismatch,
    onBack: () -> Unit,
) {
    Overlay("Host key has changed") {
        Host(hostName)
        Alarm("Someone could be impersonating the host, or its key was reinstalled.")
        Evidence("expected" to error.expected, "received" to error.actual)
        Note("Delete the host to trust a new key.")
        LimentyDialogActions {
            LimentyTextButton("Back", onBack, color = LimentyColors.Text2)
        }
    }
}

/** The box, over a dimmed session. */
@Composable
private fun Overlay(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LimentyColors.Scrim)
            // Hit but not handled: a background takes no input, so without a hit target here a tap
            // on the dim area would go on to the terminal's own gestures, or to the screen under the
            // trust prompt. Not `clickable {}`, which would also announce the dim area to TalkBack
            // as a button that does nothing.
            .pointerInput(Unit) {}
            // The box holds the button the user is about to press, and a soft keyboard raised by
            // its own field would otherwise cover it (a password retry, a failed connection and a
            // closed session all put an action here).
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(LimentyDialogMargin),
        contentAlignment = Alignment.Center,
    ) {
        LimentyDialogSurface(title = title, content = content)
    }
}

@Composable
private fun Host(name: String) {
    Text(name, style = LimentyText.Mono)
}

@Composable
private fun Note(text: String) {
    Text(text, style = LimentyText.Secondary)
}

/** The one thing on screen that has to be read before anything is pressed. */
@Composable
private fun Alarm(text: String) {
    Text(
        text = text,
        style = LimentyText.Secondary,
        color = LimentyColors.Warn,
        modifier = Modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.WarnSurface)
            .padding(LimentySpacing.SurfacePadding),
    )
}

/**
 * Fingerprints, laid out to be compared rather than read.
 *
 * A step darker than the box and in a monospaced face, since comparing two of these against a third
 * on another screen is the whole task, and a proportional face makes that harder than it is.
 *
 * Inset by a small surface's 12 rather than a surface's 16: a SHA-256 fingerprint is 50 characters,
 * 360dp at this size, and the narrower inset is what leaves it one line on the cover display.
 */
@Composable
private fun Evidence(vararg rows: Pair<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .padding(LimentySpacing.SmallPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (name, value) ->
            Text(name, style = LimentyText.MonoMeta)
            Text(value, style = LimentyText.Mono, color = LimentyColors.Text)
        }
    }
}

/** The cause has to be actionable, not generic. */
fun TransportError.describe(): String = when (this) {
    is TransportError.Timeout -> "Connection timed out"
    is TransportError.HostResolution -> "Could not find $host — is it on this network, or is Tailscale connected?"
    is TransportError.ConnectionRefused -> "Connection refused — is SSH enabled on the host?"
    is TransportError.NetworkUnreachable -> "Network unreachable"
    is TransportError.Authentication -> "Authentication failed"
    is TransportError.HostKeyMismatch ->
        "Host key changed.\nExpected $expected\nGot $actual"
    is TransportError.HostKeyRejected -> "Host key was not trusted"
    is TransportError.ConnectionLost -> "Connection lost" + (message?.let { ": $it" } ?: "")
    is TransportError.Protocol -> "SSH protocol error: $message"
    is TransportError.Unknown -> "Unexpected failure: $message"
}
