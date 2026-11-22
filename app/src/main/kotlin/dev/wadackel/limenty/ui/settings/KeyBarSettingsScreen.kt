package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyNameDialog
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.LimentyTextButtonInset
import dev.wadackel.limenty.ui.components.rememberReorderState
import dev.wadackel.limenty.ui.components.reorderGroup
import dev.wadackel.limenty.ui.components.reorderable
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBar
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBarMetrics
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlin.math.roundToInt

/**
 * Which keys stay put and which scroll.
 *
 * The real bar sits at the top and updates as the lists below it change, because the question this
 * screen answers — will the keys I need be reachable? — is about what the bar looks like, not
 * about a list of names.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyBarSettingsScreen(
    preferences: TerminalPreferences,
    onChange: ((TerminalPreferences) -> TerminalPreferences) -> Unit,
    onBack: () -> Unit,
) {
    // Starts on the set the terminal shows, but choosing another here only chooses what to edit:
    // opening a set to change it should not swap the bar out from under a running session.
    var selected by rememberSaveable { mutableIntStateOf(preferences.keySetIndex) }
    val sets = preferences.keySets
    val index = selected.coerceIn(0, sets.lastIndex)
    val set = sets[index]
    val pinned = set.pinned
    val scrolling = set.scrolling
    val onPinnedChange: (List<AuxiliaryKey>) -> Unit = { keys -> onChange { it.updateKeySet(index) { s -> s.copy(pinned = keys) } } }
    val onScrollingChange: (List<AuxiliaryKey>) -> Unit = { keys -> onChange { it.updateKeySet(index) { s -> s.copy(scrolling = keys) } } }
    var naming by remember { mutableStateOf<SetNaming?>(null) }
    var deleting by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<AuxiliaryKey?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Per set: a drag state keeps the slots it measured by position, and the next set's keys are
    // somewhere else.
    val pinnedOrder = key(index) { rememberReorderState<AuxiliaryKey>() }
    val scrollingOrder = key(index) { rememberReorderState<AuxiliaryKey>() }
    val setOrder = rememberReorderState<KeySet>()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LimentyColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val margin = if (isWideSettings(maxWidth)) LimentySpacing.ScreenMarginWide else LimentySpacing.ScreenMargin
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader(onBack = onBack)
            Text(
                "Key bar",
                style = LimentyText.Title,
                modifier = Modifier.padding(start = margin, end = margin, top = 8.dp, bottom = 20.dp),
            )

            FlowRow(
                modifier = Modifier
                    .padding(horizontal = margin)
                    .reorderGroup(setOrder, sets) { reordered ->
                        // The tab being edited follows its set rather than its position, or dragging
                        // one set would silently start editing another.
                        selected = reordered.indexOfFirst { it === sets[index] }.coerceAtLeast(0)
                        onChange { it.reorderKeySets(reordered) }
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sets.forEachIndexed { at, candidate ->
                    SetTab(
                        name = candidate.name,
                        selected = at == index,
                        dragged = setOrder.dragging == at,
                        onClick = { selected = at },
                        modifier = Modifier.reorderable(setOrder, at, sets.size, candidate.name),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(start = margin - LimentyTextButtonInset, end = margin, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LimentyTextButton("Rename", onClick = { naming = SetNaming.Rename })
                LimentyTextButton("Duplicate", onClick = { naming = SetNaming.Duplicate })
                // Absent rather than disabled: the bar needs one set, and a greyed word with no reason
                // given reads as a fault.
                if (sets.size > 1) {
                    LimentyTextButton("Delete", onClick = { deleting = true }, color = LimentyColors.Danger)
                }
            }
            Text(
                "Hold a set to move it. Swipe the key bar up or down to switch sets.",
                style = LimentyText.Secondary,
                color = LimentyColors.Text3,
                modifier = Modifier.padding(start = margin, end = margin, bottom = 16.dp),
            )

            // The thing being configured, configured in front of you.
            key(index) { AuxiliaryKeyBar(keys = scrolling, pinnedKeys = pinned, onKeyPressed = {}, interactive = false) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 22.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("Pinned right", style = LimentyText.Section)
                FitNote(pinned, Modifier.weight(1f, fill = false))
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = margin)
                    .reorderGroup(pinnedOrder, pinned, onPinnedChange),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pinned.forEachIndexed { index, key ->
                    PinnedRow(
                        key = key,
                        dragged = pinnedOrder.dragging == index,
                        onEdit = { editing = key },
                        modifier = Modifier.reorderable(pinnedOrder, index, pinned.size, key.spoken()),
                    )
                }
                if (pinned.isEmpty()) {
                    DropHint("Pin a key from the row below to keep it always in reach")
                }
            }

            Column(modifier = Modifier.padding(start = margin, end = margin, top = 26.dp, bottom = 10.dp)) {
                Text("Scrolling", style = LimentyText.Section)
                Text(
                    "Tap a key to change it. Hold to move it.",
                    style = LimentyText.Secondary,
                    color = LimentyColors.Text3,
                )
            }
            FlowRow(
                modifier = Modifier
                    .padding(horizontal = margin)
                    .padding(bottom = 40.dp)
                    .reorderGroup(scrollingOrder, scrolling, onScrollingChange),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                scrolling.forEachIndexed { index, key ->
                    ScrollingChip(
                        key = key,
                        dragged = scrollingOrder.dragging == index,
                        onEdit = { editing = key },
                        modifier = Modifier.reorderable(scrollingOrder, index, scrolling.size, key.spoken()),
                    )
                }
                AddChip(onClick = { adding = true })
            }
        }
    }

    editing?.let { key ->
        val isPinned = pinned.contains(key)
        AuxiliaryKeyDialog(
            initial = key,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (isPinned) {
                    onPinnedChange(pinned.map { if (it == key) updated else it })
                } else {
                    onScrollingChange(scrolling.map { if (it == key) updated else it })
                }
                editing = null
            },
            placement = KeyPlacement(
                pinned = isPinned,
                onTogglePin = {
                    if (isPinned) {
                        onPinnedChange(pinned - key)
                        onScrollingChange(scrolling + key)
                    } else {
                        onScrollingChange(scrolling - key)
                        onPinnedChange(pinned + key)
                    }
                    editing = null
                },
                onRemove = {
                    if (isPinned) onPinnedChange(pinned - key) else onScrollingChange(scrolling - key)
                    editing = null
                },
            ),
        )
    }

    if (adding) {
        AuxiliaryKeyDialog(
            // Nameless, so the editor opens with the action's own name in the field rather than
            // with a placeholder the key would then be stuck carrying.
            initial = AuxiliaryKey(label = "", key = TerminalKey.Escape),
            onDismiss = { adding = false },
            onSave = { added ->
                onScrollingChange(scrolling + added)
                adding = false
            },
        )
    }

    naming?.let { mode ->
        LimentyNameDialog(
            title = if (mode == SetNaming.Rename) "Rename key set" else "Duplicate key set",
            initial = if (mode == SetNaming.Rename) set.name else "${set.name} copy",
            onDismiss = { naming = null },
            onSave = { name ->
                when (mode) {
                    SetNaming.Rename -> onChange { it.updateKeySet(index) { s -> s.copy(name = name) } }
                    SetNaming.Duplicate -> {
                        onChange { it.duplicateKeySet(index, name) }
                        selected = sets.size
                    }
                }
                naming = null
            },
        )
    }

    if (deleting) {
        LimentyDialog(title = "Delete key set", onDismissRequest = { deleting = false }) {
            Text("${set.name} and its keys will be removed.", style = LimentyText.Body, color = LimentyColors.Text2)
            LimentyDialogActions {
                LimentyTextButton("Cancel", { deleting = false }, color = LimentyColors.Text2)
                LimentyTextButton(
                    label = "Delete",
                    onClick = {
                        // `selected` is left as it is: clamped against the shorter list it already
                        // names the set that slid into this place, or the new last one.
                        onChange { it.removeKeySet(index) }
                        deleting = false
                    },
                    color = LimentyColors.Danger,
                )
            }
        }
    }
}

private enum class SetNaming { Rename, Duplicate }

@Composable
private fun SetTab(
    name: String,
    selected: Boolean,
    dragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            // Selectable outside the minimum size, so the 48dp target answers while the tab is drawn at 36.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .then(modifier)
            .minimumInteractiveComponentSize()
            .height(36.dp)
            // A name has no length limit, and one long one would push every other tab off the row.
            .widthIn(max = 200.dp)
            .clip(LimentyShapes.Surface)
            .background(if (selected) LimentyColors.AccentSelection else LimentyColors.Surface)
            .selectedRing(dragged)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = if (selected) LimentyColors.Accent else LimentyColors.Text2,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How much of the cover display the pinned keys claim, and what that leaves to scroll in.
 *
 * Measured rather than counted, because a key is as wide as its label — four arrows and four
 * `PgDn`s are not the same demand. Measured in the bar's own [AuxiliaryKeyBarMetrics.labelStyle]
 * rather than in a style built here, because a second style is a second answer to how wide a key
 * is, which is the `34dp` this note used to claim while the key was 32dp.
 *
 * Over the limit is a warning, not a refusal: the main display has room the cover does not.
 */
@Composable
private fun FitNote(pinned: List<AuxiliaryKey>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val metrics = AuxiliaryKeyBarMetrics
    val style = metrics.labelStyle()
    val run = remember(pinned, style, density) {
        metrics.pinnedRun(
            pinned.map { key ->
                val text = with(density) { measurer.measure(key.label, style).size.width.toDp() }
                metrics.keyWidth(key.label, text)
            },
        )
    }
    val room = metrics.scrollRoom(COVER_WIDTH, run)
    val tight = room < MIN_SCROLLING
    val reading = "pinned ${run.value.roundToInt()}dp · scroll ${room.value.roundToInt()}dp " +
        "of ${COVER_WIDTH.value.roundToInt()}dp"
    Text(
        // Wraps rather than clips. Held to one line it lost its second half at a raised font scale,
        // and a note that reads `pinned 349dp ·` has kept the half nobody needed.
        modifier = modifier
            .padding(start = 12.dp)
            // Colour is the only thing that says the run is too tight, and colour reaches neither
            // a screen reader nor everyone looking at it.
            .semantics { if (tight) contentDescription = "$reading, too tight to scroll" },
        text = reading,
        style = LimentyText.MonoMeta,
        color = if (tight) LimentyColors.Warn else LimentyColors.Ok,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun PinnedRow(
    key: AuxiliaryKey,
    dragged: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .then(modifier)
            .clip(LimentyShapes.Surface)
            .background(if (dragged) LimentyColors.Surface2 else LimentyColors.Surface)
            .selectedRing(dragged)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A handle now that there is something to grab. It stayed off the row while the order could
        // only be changed by buttons, because a handle that does not drag reads as broken.
        DragGrip()
        // The label in both places said the same word twice and answered nothing; the spoken name
        // is the half the face cannot carry.
        KeySample(key.label)
        Text(
            key.spoken(),
            style = LimentyText.Body,
            color = LimentyColors.Text2,
            modifier = Modifier.weight(1f).padding(vertical = 13.dp),
        )
    }
}

/** Three rails: the shape a thing you can pick up has. */
@Composable
private fun DragGrip() {
    Canvas(modifier = Modifier.size(width = 14.dp, height = 12.dp)) {
        val unit = size.height / 12f
        repeat(3) { row ->
            drawRect(
                color = LimentyColors.Text3,
                topLeft = Offset(0f, row * 5f * unit),
                size = Size(size.width, 2f * unit),
            )
        }
    }
}

/**
 * One key in the scrolling run, drawn as the key it is.
 *
 * It carries no buttons of its own. It used to hold a pin and a delete beside its label, which left
 * three targets inside 44dp — too small to hit accurately, and with no part of the key left over to
 * press and hold. Both actions moved into the editor the key opens.
 */
@Composable
private fun ScrollingChip(
    key: AuxiliaryKey,
    dragged: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onEdit)
            .then(modifier)
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface3)
            .selectedRing(dragged)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = key.spoken() },
        contentAlignment = Alignment.Center,
    ) {
        Text(key.label, color = LimentyColors.Text, style = AuxiliaryKeyBarMetrics.labelStyle())
    }
}

/**
 * Shaped like a key but a step darker than one, so it sits among the keys as the place the next
 * one goes rather than as a key that sends `+`.
 */
@Composable
private fun AddChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+", style = LimentyText.Label)
        Text("Add key", style = LimentyText.Label)
    }
}

/** The key drawn as the bar draws it, at the size a list can carry. */
@Composable
private fun KeySample(label: String) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .defaultMinSize(minWidth = 30.dp)
            // A label has no length limit, and without a ceiling a long one pushes the spoken name
            // out of the row it is there to explain.
            .widthIn(max = 96.dp)
            .clip(LimentyShapes.Small)
            .background(LimentyColors.Surface3)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = LimentyColors.Text,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
            maxLines = 1,
        )
    }
}

/**
 * Where pinned keys would be, outlined rather than filled: a surface here would read as a pinned
 * key with nothing written on it, and the dash is what says the space is waiting for something.
 */
@Composable
private fun DropHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .dashedOutline(LimentyColors.Line, LimentyShapes.Surface)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LimentyText.Secondary, color = LimentyColors.Text3, textAlign = TextAlign.Center)
    }
}

/** A hairline in dashes around [shape], for a place that something goes rather than a thing. */
internal fun Modifier.dashedOutline(color: Color, shape: Shape): Modifier = drawWithCache {
    val stroke = 1.dp.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    val dash = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    onDrawBehind { drawOutline(outline, color, style = dash) }
}

/**
 * The narrowest display this app runs on — a Galaxy Z Fold's cover panel — rather than whatever is
 * in front of the user. The question the note answers is whether the keys will still be there when
 * the device is folded, and measuring the unfolded screen would answer it only while it did not
 * matter. Nothing exposes the cover panel's width while the device is open, either.
 */
private val COVER_WIDTH = AuxiliaryKeyBarMetrics.coverWidth

/** Room for about four scrolling keys, below which the run stops being worth scrolling. */
private val MIN_SCROLLING = AuxiliaryKeyBarMetrics.minScrollRoom
