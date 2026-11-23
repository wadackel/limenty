package dev.wadackel.limenty.ui.hosts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.shortHostName
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyIconButton
import dev.wadackel.limenty.ui.components.LimentyTextButtonInset
import dev.wadackel.limenty.ui.components.overhangVertically
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import java.time.Duration

/** What a connected host has running, for the row to show without opening it. */
data class SessionPreview(
    val lines: List<String>,
    val meta: String,
)

/**
 * Watches a session closely enough for its row to stay true, and no closer.
 *
 * The screen is re-read when the terminal reports a change, but no more often than
 * [PREVIEW_INTERVAL_MILLIS] — a build printing thousands of lines would otherwise recompose the
 * list at the rate of the output. The uptime advances on its own clock, since nothing on screen
 * changes when a minute passes.
 */
@Composable
fun rememberSessionPreview(session: TerminalSession): SessionPreview {
    val title by session.title.collectAsState()
    var lines by remember(session) { mutableStateOf(session.visibleLines(PREVIEW_LINES)) }
    var uptime by remember(session) { mutableStateOf(session.connectedFor()) }

    LaunchedEffect(session) {
        session.previewLines().collect { lines = it }
    }
    LaunchedEffect(session) {
        while (true) {
            uptime = session.connectedFor()
            delay(UPTIME_INTERVAL_MILLIS)
        }
    }

    val meta = buildList {
        title.takeIf { it.isNotBlank() }?.let(::add)
        uptime?.let { add(formatUptime(it)) }
    }.joinToString(" · ")
    return SessionPreview(lines = lines, meta = meta)
}

/**
 * A duration at the granularity a glance needs: minutes up to an hour, then hours.
 *
 * The minutes part is arithmetic rather than `Duration.toMinutesPart`, which arrived in API 31 and
 * would throw on the oldest device this app supports.
 */
private fun formatUptime(duration: Duration): String = when {
    duration.toMinutes() < 1 -> "just now"
    duration.toHours() < 1 -> "${duration.toMinutes()}m"
    duration.toDays() < 1 -> "${duration.toHours()}h ${duration.toMinutes() % 60}m"
    else -> "${duration.toDays()}d"
}

/**
 * The session's visible lines, no more often than [interval].
 *
 * The pause is inside the flow rather than beside a `LaunchedEffect(session, revision)`, which is
 * what this used to be: keying the effect on the revision made every change cancel the effect
 * mid-pause and start a new one, so the pause never once limited anything and the preview was
 * re-read at the rate the remote produced output. Each read takes the terminal lock and builds
 * seven strings, on the thread drawing the list.
 *
 * A revision arriving during the pause is not lost — the state flow hands the collector its
 * latest value on the way round — so the final screen is shown once the output stops.
 */
internal fun TerminalSession.previewLines(): Flow<List<String>> = revision.transform {
    emit(visibleLines(PREVIEW_LINES))
    delay(PREVIEW_INTERVAL_MILLIS)
}

/** Enough lines to recognise the session; the row's fixed height cuts anything beyond. */
private const val PREVIEW_LINES = 7
internal const val PREVIEW_INTERVAL_MILLIS = 400L
private const val UPTIME_INTERVAL_MILLIS = 30_000L

/**
 * One host, as a card on the cover display.
 *
 * There is a single card shape: connected or not, the same parts in the same order, with the
 * preview and the resume line simply absent when there is no session. Nothing branches on how
 * many hosts there are, so a list of one and a list of six are laid out the same way.
 *
 * A live session is a dot before the name, the mark the unfolded display's list uses. A chip at
 * the end of the name row sat against `···` and read as part of the menu, and a lighter card for
 * a connected host said again what the preview already shows. A host without a session has no
 * mark: an empty ring on every other card would only repeat that absence.
 */
@Composable
fun HostRow(
    host: HostProfile,
    /** A LAN host that discovery, having looked, does not see on this network. */
    absent: Boolean,
    connected: Boolean,
    preview: SessionPreview?,
    palette: TerminalPalette,
    mono: FontFamily,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface)
            // The whole card opens the host — `Resume` is a signal inside it, not a separate button.
            .clickable(onClick = onOpen)
            .padding(LimentySpacing.SurfacePadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connected) StatusDot(connected = true)
            Text(
                text = host.displayName,
                style = LimentyText.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LimentyIconButton(
                contentDescription = "More for ${host.displayName}",
                onClick = onMenu,
                // Hung into the card's padding: the glyph lines up with the card's edge and the
                // name keeps a line its own height, rather than one as tall as the target.
                modifier = Modifier.offset(x = 12.dp).overhangVertically(8.dp),
            ) {
                Text("···", color = LimentyColors.Text3, fontSize = 20.sp)
            }
        }
        Address(host, mono, modifier = Modifier.padding(top = 4.dp))
        if (absent) AbsentNote(modifier = Modifier.padding(top = 6.dp))
        if (preview != null) {
            PreviewLines(
                preview = preview,
                palette = palette,
                mono = mono,
                textStyle = CardPreviewText,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(LimentyShapes.Small)
                    .background(Color(palette.background))
                    .padding(LimentySpacing.SmallPadding),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewMeta(preview.meta, mono, modifier = Modifier.weight(1f, fill = false))
                OpenHint("Resume")
            }
        }
    }
}

/**
 * One host in the list beside the detail pane on the unfolded display: a name and an address,
 * with the session's state as a dot, since the preview is the pane's to show.
 */
@Composable
internal fun HostListItem(
    host: HostProfile,
    connected: Boolean,
    selected: Boolean,
    mono: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(LimentyShapes.Surface)
            .background(if (selected) LimentyColors.AccentSelection else Color.Transparent)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(connected)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // In the text colour, unlike the settings index: the same host's card on the cover
            // display keeps its name white, and the background already says which one is chosen.
            Text(
                text = host.displayName,
                style = LimentyText.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Address(host, mono)
        }
    }
}

/**
 * The selected host on the unfolded display.
 *
 * The large preview is the way back into the session, and the empty area in its place is the way
 * to start one: neither is a filled button, because `Add host` is already the screen's one, and
 * opening something that exists is not the same act as creating something.
 */
@Composable
internal fun HostDetail(
    host: HostProfile,
    absent: Boolean,
    connected: Boolean,
    preview: SessionPreview?,
    palette: TerminalPalette,
    mono: FontFamily,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(LimentyShapes.Floating)
            .background(LimentyColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(LimentySpacing.FloatingPadding),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connected) StatusDot(connected = true)
                Text(
                    text = host.displayName,
                    style = LimentyText.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(
                modifier = Modifier.offset(x = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LimentyIconButton(contentDescription = "Edit", onClick = onEdit) {
                    StrokeGlyph(EditGlyph)
                }
                if (connected) {
                    LimentyIconButton(contentDescription = "Disconnect", onClick = onDisconnect) {
                        StrokeGlyph(UnplugGlyph)
                    }
                }
                LimentyIconButton(contentDescription = "Delete", onClick = onDelete) {
                    StrokeGlyph(TrashGlyph)
                }
            }
        }
        Address(host, mono)
        if (preview != null) {
            // The area below says it when there is no session; with one, it shows the session.
            if (absent) AbsentNote(modifier = Modifier.padding(top = 6.dp))
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(DETAIL_AREA_HEIGHT)
                    .clip(LimentyShapes.Surface)
                    .background(Color(palette.background))
                    .clickable(role = Role.Button, onClick = onOpen),
            ) {
                PreviewLines(
                    preview = preview,
                    palette = palette,
                    mono = mono,
                    textStyle = DetailPreviewText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
            // Under the preview, as on the cover display's card, rather than over its corner: a
            // pill there covered the session it was inviting back into.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewMeta(preview.meta, mono, modifier = Modifier.weight(1f, fill = false))
                // Pressable on its own: outside the preview, nothing around it opens the session,
                // unlike the cover display's card, which is pressed as a whole.
                OpenHint(
                    "Resume",
                    modifier = Modifier
                        .offset(x = LimentyTextButtonInset)
                        .overhangVertically((LimentySpacing.TouchTarget - 24.dp) / 2)
                        .heightIn(min = LimentySpacing.TouchTarget)
                        .clip(LimentyShapes.Surface)
                        .clickable(role = Role.Button, onClick = onOpen)
                        .padding(horizontal = LimentyTextButtonInset),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(DETAIL_AREA_HEIGHT)
                    .clip(LimentyShapes.Surface)
                    .background(LimentyColors.Surface2)
                    .clickable(role = Role.Button, onClick = onOpen),
            ) {
                Text(
                    text = if (absent) "Not on this network" else "No session",
                    style = LimentyText.Body,
                    color = LimentyColors.Text3,
                    modifier = Modifier.align(Alignment.Center),
                )
                // Inside the area rather than under it, where Resume sits: the area is what is
                // pressed, and there is no session here for the word to cover.
                OpenHint(
                    "Connect",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                )
            }
        }
    }
}

/**
 * The session's title and uptime beside the word that opens it.
 *
 * Cut to one line: the title is whatever the remote shell or tmux last set, often a long working
 * directory. Wrapped, it took the row's width from `Resume`, which was left one letter a line on
 * the card and not drawn at all in the detail pane.
 */
@Composable
private fun PreviewMeta(meta: String, mono: FontFamily, modifier: Modifier = Modifier) {
    Text(
        text = meta,
        style = LimentyText.MonoMeta,
        fontFamily = mono,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(end = LimentySpacing.SmallPadding),
    )
}

/** The accent word that says what pressing the surface around it does. */
@Composable
private fun OpenHint(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LimentyText.Label)
        LimentyChevron(size = 12.dp, rotationDegrees = 0f, color = LimentyColors.Accent)
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    val dot = Modifier.size(8.dp).clip(CircleShape)
    Box(
        modifier = if (connected) {
            // The dot is the only place the word is left, so a screen reader still hears it.
            dot.background(LimentyColors.Ok).semantics { contentDescription = "Connected" }
        } else {
            dot.border(1.5.dp, LimentyColors.Text3, CircleShape)
        },
    )
}

/**
 * The address, on one line.
 *
 * Tailnet names are long enough to wrap by nature, and a wrapped one pushed the overflow menu out
 * of line with the name it belongs to. It is truncated instead, and the tail domain — the part
 * that is the same for every host on a tailnet — is dimmed so the part that differs reads first.
 */
@Composable
private fun Address(host: HostProfile, mono: FontFamily, modifier: Modifier = Modifier) {
    val hostname = host.hostname
    val machine = if (host.lan) hostname else shortHostName(hostname)
    Text(
        text = buildAnnotatedString {
            append(if (host.username.isEmpty()) "" else "${host.username}@")
            append(machine)
            if (machine.length < hostname.length) {
                withStyle(SpanStyle(color = LimentyColors.Text3)) {
                    append(hostname.substring(machine.length))
                }
            }
        },
        style = LimentyText.Mono,
        fontFamily = mono,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Under the address rather than after it: the address is cut to one line, and a long service name
 * would cut the note off with it.
 */
@Composable
private fun AbsentNote(modifier: Modifier = Modifier) {
    Text(
        text = "Not on this network",
        style = LimentyText.Secondary,
        color = LimentyColors.Text3,
        modifier = modifier,
    )
}

/**
 * The last of what the session was showing.
 *
 * Inside a fixed height: content that does not fill it leaves space, content that overruns is cut.
 * Sizing to the content would make the list jump as output arrives.
 */
@Composable
private fun PreviewLines(
    preview: SessionPreview,
    palette: TerminalPalette,
    mono: FontFamily,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        preview.lines.forEach { line ->
            Text(
                text = line,
                style = textStyle,
                color = Color(palette.foreground),
                fontFamily = mono,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
            )
        }
    }
}

/**
 * A glyph drawn from 24-unit path data, the way the design's icons are specified.
 *
 * There is no icon library in the app, and one would bring hundreds of glyphs for three.
 */
@Composable
private fun StrokeGlyph(pathData: List<String>, color: Color = LimentyColors.Text2) {
    val paths = remember(pathData) { pathData.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier = Modifier.size(GLYPH_SIZE)) {
        scale(size.width / GLYPH_UNITS, pivot = Offset.Zero) {
            val stroke = Stroke(width = GLYPH_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            paths.forEach { drawPath(it, color, style = stroke) }
        }
    }
}

private val CardPreviewText = TextStyle(fontSize = 10.sp, lineHeight = 17.5.sp)
private val DetailPreviewText = TextStyle(fontSize = 12.sp, lineHeight = 20.sp)

/** The preview and the empty area alike, so choosing another host does not move what is below. */
private val DETAIL_AREA_HEIGHT = 300.dp

private val GLYPH_SIZE = 20.dp
private const val GLYPH_UNITS = 24f
private const val GLYPH_STROKE = 1.8f

private val EditGlyph = listOf("M4 20h4L19 9l-4-4L4 16v4z", "M13.5 6.5l4 4")
private val UnplugGlyph = listOf(
    "M7 12l-2 2a3.5 3.5 0 0 0 5 5l2-2",
    "M17 12l2-2a3.5 3.5 0 0 0-5-5l-2 2",
    "M4 4l16 16",
)
private val TrashGlyph = listOf("M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
