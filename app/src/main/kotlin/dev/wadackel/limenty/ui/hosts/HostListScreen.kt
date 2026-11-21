package dev.wadackel.limenty.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.LanService
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyMark
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.LimentyTextButtonInset
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.components.SettingsGlyph
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * How a host appears in the list: the state of the app's own session with it, nothing more. The
 * host is never probed and Tailscale is never asked about it: Tailscale is how the owner reaches
 * machines, not something the app models, so a MagicDNS name is just a hostname here. A saved LAN
 * host that discovery cannot see gets a separate note on its row, not a status.
 */
enum class HostStatus { Disconnected, Connecting, Connected, Error }

/**
 * The app's entry screen.
 *
 * Sorting, folders, favourites and search are all absent by design: with a handful of hosts
 * they cost more to maintain than they save, and their absence is what keeps a connection one tap
 * away.
 */

@Composable
fun HostListScreen(
    hosts: List<HostProfile>,
    statusOf: (HostProfile) -> HostStatus,
    /**
     * The preview for a host, or null if it has no session. Composable so the caller can watch the
     * session rather than sample it — a value passed in plainly would freeze at whatever the
     * screen held when this list was last drawn.
     */
    previewOf: @Composable (HostProfile) -> SessionPreview?,
    palette: TerminalPalette,
    mono: FontFamily,
    /** SSH servers announcing themselves on this network that are not saved yet. */
    found: List<LanService>,
    absent: (HostProfile) -> Boolean,
    onConnect: (HostProfile) -> Unit,
    onAddFound: (LanService) -> Unit,
    onEdit: (HostProfile) -> Unit,
    onDelete: (HostProfile) -> Unit,
    onDisconnect: (HostProfile) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * The host the unfolded display's detail pane shows, by id so it follows the host through an
     * edit. Held by the caller: this screen leaves the composition whenever another destination is
     * shown, and a selection kept here was gone on the way back from editing the host.
     */
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
) {
    var pendingDeletion by remember { mutableStateOf<HostProfile?>(null) }
    var menuFor by remember { mutableStateOf<HostProfile?>(null) }
    val connected = { host: HostProfile -> statusOf(host) == HostStatus.Connected }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LimentyColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (maxWidth >= LimentySpacing.WideLayout) {
            val selected = selectedHost(hosts, selectedHostId, connected)
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(LIST_PANE_WIDTH).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Header(margin = LimentySpacing.ScreenMarginWide, onOpenSettings = onOpenSettings)
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(hosts, key = { it.id }) { host ->
                                HostListItem(
                                    host = host,
                                    connected = connected(host),
                                    selected = host.id == selected?.id,
                                    mono = mono,
                                    onClick = { onSelectHost(host.id) },
                                )
                            }
                            foundSection(found, mono, onAddFound)
                        }
                    }
                    // Under the list it adds to, so the pane beside it holds only the selected host.
                    AddHostButton(
                        onClick = onAdd,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = LimentySpacing.ScreenMarginWide, bottom = 24.dp),
                    )
                }
                selected?.let { host ->
                    HostDetail(
                        host = host,
                        absent = absent(host),
                        connected = connected(host),
                        preview = previewOf(host),
                        palette = palette,
                        mono = mono,
                        onOpen = { onConnect(host) },
                        onEdit = { onEdit(host) },
                        onDisconnect = { onDisconnect(host) },
                        onDelete = { pendingDeletion = host },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(
                                start = 8.dp,
                                top = 12.dp,
                                end = LimentySpacing.ScreenMarginWide,
                                bottom = 20.dp,
                            ),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(margin = LimentySpacing.ScreenMargin, onOpenSettings = onOpenSettings)
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = LimentySpacing.ScreenMargin,
                        end = LimentySpacing.ScreenMargin,
                        bottom = 120.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostRow(
                            host = host,
                            absent = absent(host),
                            connected = connected(host),
                            preview = previewOf(host),
                            palette = palette,
                            mono = mono,
                            onOpen = { onConnect(host) },
                            onMenu = { menuFor = host },
                        )
                    }
                    foundSection(found, mono, onAddFound)
                }
            }
            // A host with no entry is an empty list, not a special screen: the button is the one move.
            AddHostButton(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = LimentySpacing.ScreenMargin, bottom = 48.dp),
            )
        }
    }

    menuFor?.let { host ->
        HostMenu(
            host = host,
            connected = connected(host),
            onDismiss = { menuFor = null },
            onEdit = {
                menuFor = null
                onEdit(host)
            },
            onDisconnect = {
                menuFor = null
                onDisconnect(host)
            },
            onDelete = {
                menuFor = null
                pendingDeletion = host
            },
        )
    }

    // Deletion is the only destructive action, and the only one that asks.
    pendingDeletion?.let { host ->
        LimentyDialog(
            title = "Delete ${host.displayName}?",
            onDismissRequest = { pendingDeletion = null },
        ) {
            Text("The saved password and trusted host key are removed too.", style = LimentyText.Secondary)
            LimentyDialogActions {
                LimentyTextButton(
                    label = "Cancel",
                    onClick = { pendingDeletion = null },
                    color = LimentyColors.Text2,
                )
                LimentyTextButton(
                    label = "Delete",
                    onClick = {
                        onDelete(host)
                        pendingDeletion = null
                    },
                    color = LimentyColors.Danger,
                )
            }
        }
    }
}

/**
 * The host the detail pane shows: the one chosen, or — while none is, or once the chosen one has
 * been deleted — the first with a session, since that is the likeliest one to go back to.
 */
internal fun selectedHost(
    hosts: List<HostProfile>,
    selectedId: String?,
    connected: (HostProfile) -> Boolean,
): HostProfile? = hosts.firstOrNull { it.id == selectedId }
    ?: hosts.firstOrNull(connected)
    ?: hosts.firstOrNull()

@Composable
private fun Header(margin: Dp, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = margin, end = margin, top = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mark alone: the app's name is already on the launcher and in the switcher.
        LimentyMark(size = 34.dp)
        LimentyTextButton(
            label = "Settings",
            onClick = onOpenSettings,
            color = LimentyColors.Text2,
            leading = { SettingsGlyph() },
            // Hung past the row's margin so the word still lines up with it; see
            // LimentyTextButtonInset.
            modifier = Modifier.offset(x = LimentyTextButtonInset),
        )
    }
}

private fun LazyListScope.foundSection(
    found: List<LanService>,
    mono: FontFamily,
    onAddFound: (LanService) -> Unit,
) {
    if (found.isEmpty()) return
    item(key = "found") {
        SectionHeading(
            "On this network",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )
    }
    items(found, key = { "found:${it.name}" }) { service ->
        FoundRow(service = service, mono = mono, onClick = { onAddFound(service) })
    }
}

/**
 * Filled accent, because it makes something new — the rule the rest of the interface follows is
 * that a filled control creates or commits and a plain accent word goes to something that exists.
 *
 * Not `LimentyPrimaryButton`: this one floats over the list, so it carries the shadow that lifts
 * it off whichever card scrolls under it.
 */
@Composable
private fun AddHostButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(52.dp)
            .shadow(12.dp, LimentyShapes.Surface)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+", style = LimentyText.Label, color = LimentyColors.OnAccent)
        Text("Add host", style = LimentyText.Label, color = LimentyColors.OnAccent)
    }
}

/**
 * A server on this network that is not saved yet. Lighter than a host row, and it adds rather than
 * connects: a username is still needed, and the form is where it is asked for.
 */
@Composable
private fun FoundRow(service: LanService, mono: FontFamily, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                service.name,
                style = LimentyText.Body,
                color = LimentyColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(service.address, style = LimentyText.Mono, color = LimentyColors.Text3, fontFamily = mono)
        }
        Text("Add", style = LimentyText.Label)
    }
}

@Composable
private fun HostMenu(
    host: HostProfile,
    connected: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    LimentyDialog(title = host.displayName, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MenuAction("Edit", onEdit)
            if (connected) MenuAction("Disconnect", onDisconnect)
            MenuAction("Delete", onDelete, color = LimentyColors.Danger)
        }
        LimentyDialogActions {
            LimentyTextButton("Close", onDismiss, color = LimentyColors.Text2)
        }
    }
}

/**
 * A row of the menu: the whole width responds, and it is tall enough to hit without aiming.
 *
 * No inset of its own, so the word starts where the title above it does. An inset would buy the
 * press a margin at the cost of putting every choice a step in from the thing they are choices for.
 */
@Composable
private fun MenuAction(label: String, onClick: () -> Unit, color: Color = LimentyColors.Text) {
    Text(
        text = label,
        style = LimentyText.Body,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

private val LIST_PANE_WIDTH = 340.dp
