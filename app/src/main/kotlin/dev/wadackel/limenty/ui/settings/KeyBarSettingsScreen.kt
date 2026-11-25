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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.SlotPosition
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.components.DropInto
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
import dev.wadackel.limenty.ui.terminal.keyGroupMark
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
    val onPinnedChange: (List<KeyBarSlot>) -> Unit = { keys -> onChange { it.updateKeySet(index) { s -> s.copy(pinned = keys) } } }
    val onScrollingChange: (List<KeyBarSlot>) -> Unit = { keys -> onChange { it.updateKeySet(index) { s -> s.copy(scrolling = keys) } } }
    var naming by remember { mutableStateOf<SetNaming?>(null) }
    var deleting by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<SlotPosition?>(null) }
    var removing by remember { mutableStateOf<SlotPosition?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addingGroup by remember { mutableStateOf(false) }
    // Per set: a drag state keeps the slots it measured by position, and the next set's keys are
    // somewhere else.
    val pinnedOrder = key(index) { rememberReorderState<KeyBarSlot>() }
    val scrollingOrder = key(index) { rememberReorderState<KeyBarSlot>() }
    val setOrder = rememberReorderState<KeySet>()
    val intoGroup: (Boolean) -> DropInto<KeyBarSlot> = { pinnedRow ->
        DropInto(
            accepts = { dragged, onto -> dragged is AuxiliaryKey && onto is KeyGroup },
            onDrop = { items, from, onto ->
                onChange {
                    it.updateKeySet(index) { s ->
                        val reordered = if (pinnedRow) s.copy(pinned = items) else s.copy(scrolling = items)
                        reordered.moveIntoGroup(SlotPosition(pinnedRow, from), SlotPosition(pinnedRow, onto))
                    }
                }
            },
        )
    }

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
                    .reorderGroup(pinnedOrder, pinned, dropInto = intoGroup(true), onReorder = onPinnedChange),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pinned.forEachIndexed { index, key ->
                    PinnedRow(
                        key = key,
                        dragged = pinnedOrder.dragging == index,
                        dropTarget = pinnedOrder.dropTarget == index,
                        onEdit = { editing = SlotPosition(pinned = true, index) },
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
                    if (groupsIn(set).isEmpty()) {
                        "Tap a key to change it. Hold to move it."
                    } else {
                        "Tap a key to change it. Hold to move it, or drop it on a group."
                    },
                    style = LimentyText.Secondary,
                    color = LimentyColors.Text3,
                )
            }
            FlowRow(
                modifier = Modifier
                    .padding(horizontal = margin)
                    .padding(bottom = 40.dp)
                    .reorderGroup(scrollingOrder, scrolling, dropInto = intoGroup(false), onReorder = onScrollingChange),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                scrolling.forEachIndexed { index, key ->
                    ScrollingChip(
                        key = key,
                        dragged = scrollingOrder.dragging == index,
                        dropTarget = scrollingOrder.dropTarget == index,
                        onEdit = { editing = SlotPosition(pinned = false, index) },
                        modifier = Modifier.reorderable(scrollingOrder, index, scrolling.size, key.spoken()),
                    )
                }
                AddChip("Add key", onClick = { adding = true })
                AddChip("Add group", onClick = { addingGroup = true })
            }
        }
    }

    editing?.let { slot ->
        val row = if (slot.pinned) pinned else scrolling
        val onRowChange = if (slot.pinned) onPinnedChange else onScrollingChange
        val key = row.getOrNull(slot.index) ?: return@let
        val withoutKey = row.filterIndexed { at, _ -> at != slot.index }
        val onSave: (KeyBarSlot) -> Unit = { updated ->
            onRowChange(row.mapIndexed { at, it -> if (at == slot.index) updated else it })
            editing = null
        }
        val placement = KeyPlacement(
            pinned = slot.pinned,
            onTogglePin = {
                onRowChange(withoutKey)
                if (slot.pinned) onScrollingChange(scrolling + key) else onPinnedChange(pinned + key)
                editing = null
            },
            onRemove = {
                editing = null
                removing = slot
            },
        )
        when (key) {
            is AuxiliaryKey -> AuxiliaryKeyDialog(
                initial = key,
                onDismiss = { editing = null },
                onSave = onSave,
                placement = placement.copy(
                    groups = groupsIn(set).map { (at, group) ->
                        GroupTarget(group.label) {
                            onChange { it.updateKeySet(index) { s -> s.moveIntoGroup(slot, at) } }
                            editing = null
                        }
                    },
                ),
            )
            is KeyGroup -> KeyGroupDialog(
                initial = key,
                onDismiss = { editing = null },
                onSave = onSave,
                placement = placement,
                onMoveOut = { edited, member ->
                    onChange { it.updateKeySet(index) { s -> s.moveOutOfGroup(slot, edited, member) } }
                    editing = null
                },
            )
        }
    }

    removing?.let { slot ->
        val row = if (slot.pinned) pinned else scrolling
        val onRowChange = if (slot.pinned) onPinnedChange else onScrollingChange
        val key = row.getOrNull(slot.index) ?: return@let
        LimentyDialog(title = if (key is KeyGroup) "Remove group" else "Remove key", onDismissRequest = { removing = null }) {
            Text(removalNote(key), style = LimentyText.Body, color = LimentyColors.Text2)
            LimentyDialogActions {
                LimentyTextButton("Cancel", { removing = null }, color = LimentyColors.Text2)
                LimentyTextButton(
                    label = "Remove",
                    onClick = {
                        onRowChange(row.filterIndexed { at, _ -> at != slot.index })
                        removing = null
                    },
                    color = LimentyColors.Danger,
                )
            }
        }
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

    if (addingGroup) {
        KeyGroupDialog(
            initial = KeyGroup(name = "", keys = emptyList()),
            onDismiss = { addingGroup = false },
            onSave = { added ->
                onScrollingChange(scrolling + added)
                addingGroup = false
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

/** A group's keys go with it, so the confirmation says so rather than leaving it to be found out. */
internal fun removalNote(slot: KeyBarSlot): String {
    val name = slot.label.ifBlank { slot.spoken() }
    return when {
        slot !is KeyGroup || slot.keys.isEmpty() -> "$name will be removed."
        slot.keys.size == 1 -> "$name and the key in it will be removed."
        else -> "$name and the ${slot.keys.size} keys in it will be removed."
    }
}

/** Every group in [set], pinned ones first as the settings list them, with where each sits. */
private fun groupsIn(set: KeySet): List<Pair<SlotPosition, KeyGroup>> = listOf(true, false).flatMap { pinned ->
    set.row(pinned).mapIndexedNotNull { at, slot -> (slot as? KeyGroup)?.let { SlotPosition(pinned, at) to it } }
}

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
private fun FitNote(pinned: List<KeyBarSlot>, modifier: Modifier = Modifier) {
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
    val reading = fitReading(run, room)
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

/**
 * The words of [FitNote] for a pinned run of [run] that leaves [room] on the cover display.
 *
 * Pinned keys wider than the cover leave a negative room, and `scroll -90dp` has no meaning to
 * anyone reading it, so a room that rounds to nothing is said in words. Compared after rounding,
 * so the note never reads `scroll 0dp` either.
 */
internal fun fitReading(run: Dp, room: Dp): String {
    val cover = COVER_WIDTH.value.roundToInt()
    val left = room.value.roundToInt()
    val scroll = if (left > 0) "scroll ${left}dp of ${cover}dp" else "no room to scroll in ${cover}dp"
    return "pinned ${run.value.roundToInt()}dp · $scroll"
}

@Composable
private fun PinnedRow(
    key: KeyBarSlot,
    dragged: Boolean,
    dropTarget: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LimentySpacing.TouchTarget)
            .clickable(onClick = onEdit)
            .then(modifier)
            .clip(LimentyShapes.Surface)
            .background(if (dragged) LimentyColors.Surface2 else LimentyColors.Surface)
            .selectedRing(dragged || dropTarget)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A handle now that there is something to grab. It stayed off the row while the order could
        // only be changed by buttons, because a handle that does not drag reads as broken.
        DragGrip()
        // The label in both places said the same word twice and answered nothing; the spoken name
        // is the half the face cannot carry.
        KeySample(key.label, marked = key is KeyGroup)
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
    key: KeyBarSlot,
    dragged: Boolean,
    dropTarget: Boolean,
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
            .then(if (key is KeyGroup) Modifier.keyGroupMark() else Modifier)
            .selectedRing(dragged || dropTarget)
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
private fun AddChip(label: String, onClick: () -> Unit) {
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
        Text(label, style = LimentyText.Label)
    }
}

/** The key drawn as the bar draws it, at the size a list can carry. */
@Composable
private fun KeySample(label: String, marked: Boolean) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .defaultMinSize(minWidth = 30.dp)
            // A label has no length limit, and without a ceiling a long one pushes the spoken name
            // out of the row it is there to explain.
            .widthIn(max = 96.dp)
            .clip(LimentyShapes.Small)
            .background(LimentyColors.Surface3)
            .then(if (marked) Modifier.keyGroupMark() else Modifier)
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
