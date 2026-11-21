package dev.wadackel.limenty.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.LanService
import dev.wadackel.limenty.data.shortHostName
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyField
import dev.wadackel.limenty.ui.components.LimentyIconButton
import dev.wadackel.limenty.ui.components.LimentyPrimaryButton
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.PasswordField
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * Stops an IME from transliterating what is typed.
 *
 * A hostname and a username cannot contain Japanese, but a Japanese keyboard converts `tester`
 * into kana as it goes, and the resulting value fails to authenticate with nothing on screen to
 * explain why. `IME_FLAG_FORCE_ASCII` is only a hint and Samsung's keyboard ignores it; the
 * password variation is what actually suspends conversion, and is the long-standing remedy for
 * identifier fields. The text stays visible because no visual transformation is applied.
 */
private val AsciiField = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

/**
 * Add or edit a host.
 *
 * The port sits behind Advanced because it is 22 in practice, and the password is saved by
 * default because one-tap connect is the product's central promise.
 */

@Composable
fun HostFormScreen(
    initial: HostProfile?,
    initialPassword: String,
    onSave: (HostProfile, String) -> Unit,
    onCancel: () -> Unit,
    /** What the last save came to, when it was not simply done. Null while nothing has gone wrong. */
    notice: String? = null,
    /** The server on this network a new host is being added from, if it came from the list. */
    discovered: LanService? = null,
) {
    val initialPort = initial?.port ?: discovered?.port ?: HostProfile.DEFAULT_PORT
    // Fixed for the life of the form. A service name is shown rather than edited: typed by hand it
    // would have to match the announcement exactly, apostrophe and all, to ever be found.
    val lan = initial?.lan ?: (discovered != null)
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var hostname by remember { mutableStateOf(initial?.hostname ?: discovered?.name.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initialPassword) }
    var revealed by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    var advancedOpen by remember { mutableStateOf(initialPort != HostProfile.DEFAULT_PORT) }
    // Fixed for the life of the form: saving again after a notice is how a failure is retried,
    // and a fresh id per press turned that retry into a second copy of a host already on disk.
    val hostId = remember { initial?.id ?: java.util.UUID.randomUUID().toString() }

    val portValue = port.toIntOrNull()
    // A space carried in by paste or autocomplete is invisible on the form and fatal to the lookup
    // or the login, so everything downstream of the fields — the preview included — sees it gone.
    val address = if (lan) hostname else hostname.trim()
    val user = username.trim()
    val machine = if (lan) address else shortHostName(address)
    val valid = address.isNotEmpty() && user.isNotEmpty() && portValue in 1..65535

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LimentyColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val margin = if (maxWidth >= LimentySpacing.WideLayout) LimentySpacing.ScreenMarginWide else LimentySpacing.ScreenMargin
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Hung past the margin so the chevron, not the reach around it, lines up with the fields.
                LimentyIconButton(
                    contentDescription = "Back",
                    onClick = onCancel,
                    modifier = Modifier.offset(x = -BACK_OVERHANG),
                ) {
                    LimentyChevron(16.dp, 180f, LimentyColors.Text2)
                }
                Text(
                    text = if (initial == null) "Add host" else "Edit host",
                    style = LimentyText.Title,
                    modifier = Modifier.offset(x = -BACK_OVERHANG),
                )
            }

            // Ordered by whose correctness matters: the address has to be right, the label is yours.
            Column(
                modifier = Modifier.padding(horizontal = margin),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Destination")
                    if (lan) {
                        FoundOnNetwork(address)
                    } else {
                        LimentyField(
                            label = "Hostname",
                            value = hostname,
                            onValueChange = { hostname = it },
                            placeholder = "macbook",
                            keyboardOptions = AsciiField,
                        )
                    }
                    LimentyField(
                        label = "Username",
                        value = username,
                        onValueChange = { username = it },
                        keyboardOptions = AsciiField,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Authentication")
                    PasswordField(
                        value = password,
                        revealed = revealed,
                        onValueChange = { password = it },
                        onToggleReveal = { revealed = !revealed },
                    )
                    Text(
                        "Kept in the device's encrypted storage.",
                        style = LimentyText.Secondary,
                        color = LimentyColors.Text3,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Label")
                    // The field stays a field. Showing what the name will become is a placeholder and
                    // a note beside it — replacing the input with a read-only panel, as an earlier
                    // version did, left no way to type a name at all.
                    LimentyField(
                        label = "Name — optional",
                        value = name,
                        onValueChange = { name = it },
                        placeholder = machine.ifBlank { null },
                        trailing = if (name.isBlank() && address.isNotEmpty()) {
                            {
                                Text(
                                    if (lan) "From network" else "From hostname",
                                    style = LimentyText.Secondary,
                                    color = LimentyColors.Text3,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }

                AdvancedSection(
                    open = advancedOpen,
                    onToggle = { advancedOpen = !advancedOpen },
                    port = port,
                    onPortChange = { port = it },
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = margin, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // No command for a LAN host: `ssh` given a Bonjour service name finds nothing, and a
                // command that does not work is worse than none.
                if (lan) {
                    Text(
                        "Looked up on this network each time you connect, so a changed address is followed.",
                        style = LimentyText.Secondary,
                        color = LimentyColors.Text3,
                    )
                } else {
                    CommandPreview(user = user, hostname = address, port = portValue)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LimentyPrimaryButton(
                        label = "Save",
                        enabled = valid,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSave(
                                HostProfile(
                                    id = hostId,
                                    name = name.trim(),
                                    hostname = address,
                                    username = user,
                                    port = portValue ?: HostProfile.DEFAULT_PORT,
                                    lan = lan,
                                ),
                                password,
                            )
                        },
                    )
                    LimentyTextButton("Cancel", onCancel, color = LimentyColors.Text2)
                }
                // Beside Save rather than over the screen: the form stays open and the button stays
                // enabled, because saving the same host again is how both of these are retried.
                if (notice != null) {
                    Text(notice, style = LimentyText.Secondary)
                }
            }
        }
    }
}

@Composable
private fun FoundOnNetwork(serviceName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Found on this network", style = LimentyText.Secondary, color = LimentyColors.Text3)
        Text(serviceName, style = LimentyText.Body, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Closed, it still says what it holds: an expander labelled only `Advanced` gives no reason to
 * open it, and the settings inside are ones you look for by name.
 */
@Composable
private fun AdvancedSection(
    open: Boolean,
    onToggle: () -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LimentySpacing.TouchTarget)
                .clip(LimentyShapes.Surface)
                .clickable(role = Role.Button, onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = LimentyColors.Text2)) { append("Advanced ") }
                    withStyle(SpanStyle(color = LimentyColors.Text3)) { append("— port") }
                },
                style = LimentyText.Body,
            )
            LimentyChevron(
                size = 16.dp,
                rotationDegrees = if (open) 270f else 90f,
                color = LimentyColors.Text2,
            )
        }
        if (open) {
            LimentyField(
                label = "Port",
                value = port,
                onValueChange = onPortChange,
                modifier = Modifier.width(120.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

/**
 * The connection this form describes, written out.
 *
 * The lower half of the screen used to be empty. Showing the command makes the three fields above
 * it legible as one thing, and is where a reachability check will attach.
 */
@Composable
private fun CommandPreview(user: String, hostname: String, port: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface)
            .padding(LimentySpacing.SurfacePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val machine = shortHostName(hostname)
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = LimentyColors.Text2)) {
                    append("ssh ")
                    if (port != null && port != HostProfile.DEFAULT_PORT) append("-p $port ")
                    if (user.isNotEmpty()) append("$user@")
                    append(machine)
                }
                if (machine.length < hostname.length) {
                    withStyle(SpanStyle(color = LimentyColors.Text3)) {
                        append(hostname.substring(machine.length))
                    }
                }
            },
            style = LimentyText.Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** How far the back button's reach extends past its chevron on the leading side. */
private val BACK_OVERHANG = 14.dp
