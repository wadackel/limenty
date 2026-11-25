package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.SlotPosition
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.components.DropInto
import dev.wadackel.limenty.ui.components.DropOut
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyMenuAction
import dev.wadackel.limenty.ui.components.LimentyNameDialog
import dev.wadackel.limenty.ui.components.LimentySnackbar
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.rememberReorderState
import dev.wadackel.limenty.ui.components.reorderGroup
import dev.wadackel.limenty.ui.components.reorderable
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBar
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBarMetrics
import dev.wadackel.limenty.ui.terminal.KeyBarEditing
import dev.wadackel.limenty.ui.terminal.KeySetSwitcher
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * The key sets and the keys in them.
 *
 * The real bar sits at the top, because the question this screen answers — will the keys I need be
 * reachable? — is about what the bar looks like rather than about a list of names. Below it every
 * scrolling key is listed once, so the whole run can be seen without scrolling the bar. Pinned keys
 * are seen only on the bar: a list of them as well showed each one twice, in two different shapes.
 *
 * The two are one selection. A key picked in the list is marked on the bar and scrolled into view
 * there, so a key low in a long list can be found on the bar without hunting for it.
 */
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
    var chosen by remember(index) { mutableStateOf<SlotPosition?>(null) }
    // A position past the end of its row is what a removal leaves behind; it names nothing.
    val marked = chosen?.takeIf { it.index in set.row(it.pinned).indices }
    val onRowChange: (Boolean, List<KeyBarSlot>) -> Unit = { pinnedRow, keys ->
        chosen = null
        onChange { it.updateKeySet(index) { s -> if (pinnedRow) s.copy(pinned = keys) else s.copy(scrolling = keys) } }
    }
    val onPinnedChange: (List<KeyBarSlot>) -> Unit = { onRowChange(true, it) }
    val onScrollingChange: (List<KeyBarSlot>) -> Unit = { onRowChange(false, it) }
    var naming by remember { mutableStateOf<SetNaming?>(null) }
    var choosingSet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<SheetTarget?>(null) }
    var removed by remember { mutableStateOf<Removed?>(null) }
    val open: (SlotPosition) -> Unit = { at ->
        chosen = at
        editing = SheetTarget.Existing(at)
    }
    // Per set: a drag state keeps the slots it measured by position, and the next set's keys are
    // somewhere else.
    val pinnedOrder = key(index) { rememberReorderState<KeyBarSlot>() }
    val scrollingOrder = key(index) { rememberReorderState<KeyBarSlot>() }
    val pinZone = remember { PinZone() }
    val intoGroup: (Boolean) -> DropInto<KeyBarSlot> = { pinnedRow ->
        DropInto(
            accepts = { dragged, onto -> dragged is AuxiliaryKey && onto is KeyGroup },
            onDrop = { items, from, onto ->
                chosen = null
                onChange {
                    it.updateKeySet(index) { s ->
                        val reordered = if (pinnedRow) s.copy(pinned = items) else s.copy(scrolling = items)
                        reordered.moveIntoGroup(SlotPosition(pinnedRow, from), SlotPosition(pinnedRow, onto))
                    }
                }
            },
        )
    }
    val ontoPinned = DropOut<KeyBarSlot>(
        zone = { pinZone.rect },
        onDrop = { items, from ->
            chosen = null
            onChange { it.updateKeySet(index) { s -> s.copy(scrolling = items).pin(from) } }
        },
    )

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
                modifier = Modifier.padding(start = margin, end = margin, top = 8.dp, bottom = 12.dp),
            )

            Row(
                modifier = Modifier.padding(start = margin, end = margin, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SetChooser(set.name, onClick = { choosingSet = true }, modifier = Modifier.weight(1f, fill = false))
                if (index == preferences.keySetIndex) {
                    Text("In use", style = LimentyText.Secondary, color = LimentyColors.Text3)
                }
            }

            // The thing being configured, configured in front of you.
            AuxiliaryKeyBar(
                keys = scrolling,
                pinnedKeys = pinned,
                onKeyPressed = {},
                interactive = false,
                switcher = KeySetSwitcher(index, sets.size, set.name) { delta -> selected = Math.floorMod(index + delta, sets.size) },
                editing = KeyBarEditing(
                    selected = marked,
                    onSelect = open,
                    pinnedOrder = pinnedOrder,
                    onPinnedReorder = onPinnedChange,
                    pinnedDropInto = intoGroup(true),
                    pinning = scrollingOrder.overOut,
                    onPinZone = { pinZone.rect = it },
                ),
            )

            Text(
                "Keys",
                style = LimentyText.Section,
                modifier = Modifier.padding(start = margin, end = margin, top = 24.dp, bottom = 10.dp),
            )
            FlowRow(
                modifier = Modifier
                    .padding(horizontal = margin)
                    .reorderGroup(scrollingOrder, scrolling, dropInto = intoGroup(false), dropOut = ontoPinned, onReorder = onScrollingChange),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                scrolling.forEachIndexed { at, slot ->
                    KeyTile(
                        slot = slot,
                        selected = marked == SlotPosition(pinned = false, at),
                        lifted = scrollingOrder.dragging == at || scrollingOrder.dropTarget == at,
                        onClick = { open(SlotPosition(pinned = false, at)) },
                        modifier = Modifier.reorderable(scrollingOrder, at, scrolling.size, slot.spoken()),
                    )
                }
                AddTile("+", spoken = "Add key", onClick = { editing = SheetTarget.NewKey })
                AddTile("+ Group", spoken = "Add group", onClick = { editing = SheetTarget.NewGroup })
            }
            Text(
                "Tap a key to change it. Hold it to move it, onto a group to put it in, or onto the right end of the bar to pin it.",
                style = LimentyText.Secondary,
                color = LimentyColors.Text3,
                modifier = Modifier.padding(start = margin, end = margin, top = 12.dp, bottom = 40.dp),
            )
        }

        removed?.let { gone ->
            LimentySnackbar(
                message = "Removed ${gone.slot.label.ifBlank { gone.slot.spoken() }}",
                action = "Undo",
                onAction = {
                    onChange { it.updateKeySet(gone.set) { s -> s.restored(gone.at, gone.slot) } }
                    if (gone.set == index) chosen = gone.at
                    removed = null
                },
                onTimeout = { removed = null },
                key = gone,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    editing?.let { target ->
        // A fresh draft for every slot opened, rather than the last one's left in place.
        key(index, target) {
            KeySlotSheet(
                set = set,
                target = target,
                onSave = { edit ->
                    val (_, landed) = set.applying(edit)
                    onChange { it.updateKeySet(index) { s -> s.applying(edit).first } }
                    chosen = landed
                    editing = null
                },
                onRemove = { at ->
                    set.row(at.pinned).getOrNull(at.index)?.let { slot -> removed = Removed(index, at, slot) }
                    onChange { it.updateKeySet(index) { s -> s.removed(at) } }
                    chosen = null
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }

    if (choosingSet) {
        SetMenu(
            sets = sets,
            editing = index,
            inUse = preferences.keySetIndex,
            onChoose = { at ->
                selected = at
                choosingSet = false
            },
            onAction = { action ->
                choosingSet = false
                when (action) {
                    SetAction.Rename -> naming = SetNaming.Rename
                    SetAction.Duplicate -> naming = SetNaming.Duplicate
                    SetAction.New -> naming = SetNaming.New
                    SetAction.Delete -> deleting = true
                }
            },
            onDismiss = { choosingSet = false },
        )
    }

    naming?.let { mode ->
        LimentyNameDialog(
            title = when (mode) {
                SetNaming.Rename -> "Rename key set"
                SetNaming.Duplicate -> "Duplicate key set"
                SetNaming.New -> "New key set"
            },
            initial = when (mode) {
                SetNaming.Rename -> set.name
                SetNaming.Duplicate -> "${set.name} copy"
                SetNaming.New -> ""
            },
            onDismiss = { naming = null },
            onSave = { name ->
                when (mode) {
                    SetNaming.Rename -> onChange { it.updateKeySet(index) { s -> s.copy(name = name) } }
                    SetNaming.Duplicate -> {
                        onChange { it.duplicateKeySet(index, name) }
                        selected = sets.size
                    }
                    SetNaming.New -> {
                        onChange { it.addKeySet(KeySet.starter(name)) }
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
                        // Undo names its set by place, and the places after this one have just moved.
                        removed = null
                        deleting = false
                    },
                    color = LimentyColors.Danger,
                )
            }
        }
    }
}

/** A slot just removed, kept for as long as Undo is offered, with the set and the place it came from. */
private class Removed(val set: Int, val at: SlotPosition, val slot: KeyBarSlot)

/** Where a key carried from the list is pinned, as the bar last reported it. Read only by the drag. */
private class PinZone {
    var rect: Rect? = null
}

private enum class SetNaming { Rename, Duplicate, New }

private enum class SetAction { Rename, Duplicate, New, Delete }

/** A group's keys go with it, so the question before removing it says so rather than leaving it to be found out. */
internal fun removalNote(slot: KeyBarSlot): String {
    val name = slot.label.ifBlank { slot.spoken() }
    return when {
        slot !is KeyGroup || slot.keys.isEmpty() -> "$name will be removed."
        slot.keys.size == 1 -> "$name and the key in it will be removed."
        else -> "$name and the ${slot.keys.size} keys in it will be removed."
    }
}

/** Every group in [set], pinned ones first, with where each sits. */
internal fun groupsIn(set: KeySet): List<Pair<SlotPosition, KeyGroup>> = listOf(true, false).flatMap { pinned ->
    set.row(pinned).mapIndexedNotNull { at, slot -> (slot as? KeyGroup)?.let { SlotPosition(pinned, at) to it } }
}

/**
 * The set being edited, named where its keys are, and the way to every other set.
 *
 * A menu rather than a row of tabs: the tabs took a line of their own and a second line for what
 * could be done to the selected one, above the keys this screen is for, and they wrapped once there
 * were a few sets with long names.
 */
@Composable
private fun SetChooser(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface)
            .clickable(role = Role.Button, onClickLabel = "Choose key set", onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = LimentyText.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        LimentyChevron(10.dp, 90f, LimentyColors.Text3)
    }
}

/**
 * Every set, to edit one of them, then what can be done to the one being edited. Choosing a set
 * here does not put it on the terminal's bar; "In use" says which one is there.
 */
@Composable
private fun SetMenu(
    sets: List<KeySet>,
    editing: Int,
    inUse: Int,
    onChoose: (Int) -> Unit,
    onAction: (SetAction) -> Unit,
    onDismiss: () -> Unit,
) {
    LimentyDialog(title = "Key sets", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sets.forEachIndexed { at, candidate ->
                SetMenuRow(
                    name = candidate.name,
                    current = at == editing,
                    inUse = at == inUse,
                    onClick = { onChoose(at) },
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(LimentyColors.Line))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LimentyMenuAction("Rename", { onAction(SetAction.Rename) })
            LimentyMenuAction("Duplicate", { onAction(SetAction.Duplicate) })
            LimentyMenuAction("New set", { onAction(SetAction.New) })
            // Absent rather than disabled: the bar needs one set, and a greyed word with no reason
            // given reads as a fault.
            if (sets.size > 1) LimentyMenuAction("Delete", { onAction(SetAction.Delete) }, color = LimentyColors.Danger)
        }
        LimentyDialogActions {
            LimentyTextButton("Close", onDismiss, color = LimentyColors.Text2)
        }
    }
}

@Composable
private fun SetMenuRow(name: String, current: Boolean, inUse: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .selectable(selected = current, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = LimentyText.Body,
            color = if (current) LimentyColors.Accent else LimentyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (inUse) Text("In use", style = LimentyText.Secondary, color = LimentyColors.Text3)
    }
}

/**
 * One slot of the scrolling run, drawn as the key it is.
 *
 * A group is closed, with how many keys it holds beside its name: drawn open, its keys sat among
 * the bar's own and nothing said they were a group.
 */
@Composable
private fun KeyTile(
    slot: KeyBarSlot,
    selected: Boolean,
    lifted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .then(modifier)
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            // A label has no length limit, and one long one would take a line of the list to itself.
            .widthIn(max = 200.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface3)
            .selectedRing(selected || lifted)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = slot.spoken()
                this.selected = selected
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            slot.label,
            color = LimentyColors.Text,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (slot is KeyGroup) KeyCount(slot.keys.size)
    }
}

/** A group's size, in a well of its own so it does not read as part of the name. */
@Composable
private fun KeyCount(count: Int) {
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .defaultMinSize(minWidth = 20.dp)
            .clip(LimentyShapes.Small)
            .background(LimentyColors.Surface5)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = LimentyText.MonoMeta, color = LimentyColors.Text2)
    }
}

/** Dashed, so it reads as the place the next key goes rather than as a key that sends `+`. */
@Composable
private fun AddTile(label: String, spoken: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .dashedOutline(LimentyColors.Line, LimentyShapes.Surface)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LimentyText.Label)
    }
}

/** A hairline in dashes around [shape], for a place that something goes rather than a thing. */
internal fun Modifier.dashedOutline(color: Color, shape: Shape): Modifier = drawWithCache {
    val stroke = 1.dp.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    val dash = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    onDrawBehind { drawOutline(outline, color, style = dash) }
}
