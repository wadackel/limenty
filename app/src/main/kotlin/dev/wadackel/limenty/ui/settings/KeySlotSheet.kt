package dev.wadackel.limenty.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.data.KeyIcon
import dev.wadackel.limenty.data.KeyIconCategory
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.SlotEdit
import dev.wadackel.limenty.data.SlotPosition
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyDialogGap
import dev.wadackel.limenty.ui.components.LimentyField
import dev.wadackel.limenty.ui.components.LimentySheet
import dev.wadackel.limenty.ui.components.LimentySheetPadding
import dev.wadackel.limenty.ui.components.LimentySheetQuestion
import dev.wadackel.limenty.ui.components.LimentySheetTitle
import dev.wadackel.limenty.ui.components.LimentySnackbar
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.LimentyTextButtonInset
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.components.rememberReorderState
import dev.wadackel.limenty.ui.components.reorderGroup
import dev.wadackel.limenty.ui.components.reorderable
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBarMetrics
import dev.wadackel.limenty.ui.terminal.FacePart
import dev.wadackel.limenty.ui.terminal.KeyFaceContent
import dev.wadackel.limenty.ui.terminal.KeyIconImage
import dev.wadackel.limenty.ui.terminal.face
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlinx.coroutines.launch

/** What the editing sheet is opened on. */
internal sealed interface SheetTarget {
    data class Existing(val at: SlotPosition) : SheetTarget

    data object NewKey : SheetTarget

    data object NewGroup : SheetTarget
}

/**
 * A key as it is being edited: its steps, its icon, and its own name if it has one.
 *
 * The name is held apart from the label rather than read back off it, because emptying the field
 * is a step on the way to typing a new name, and a key read back from an empty label follows its
 * steps again — which would put the old name straight back into the field being cleared.
 *
 * Without a name of its own, a key is named by its icon, and by its steps when it has no icon.
 */
@Immutable
internal data class KeyDraft(val ownLabel: String?, val steps: List<KeyStep>, val icon: KeyIcon? = null) {
    val key: AuxiliaryKey get() = AuxiliaryKey(ownLabel ?: icon?.label ?: AuxiliaryKey.labelFor(steps), steps, icon)

    val saveable: Boolean get() = key.label.isNotBlank() && sendsEveryStep(steps)

    companion object {
        fun of(key: AuxiliaryKey) = KeyDraft(if (key.hasOwnName()) key.label else null, key.steps, key.icon)
    }
}

/**
 * Edits one key or one group of a set, in one sheet that works the same wherever it was opened from.
 *
 * Everything in it is a draft until Save — the key, its row, the group it goes into, the keys moved
 * out of a group — so Cancel can always throw all of it away, and nothing is ever moved while an
 * edit made beside it is silently dropped. Leaving with changes asks first.
 *
 * A group opens into its keys inside the same sheet, with the way back in the header: a key editor
 * raised over the group's editor was two boxes to dismiss and no way to tell which was in front.
 * The icons open the same way, over whichever page asked for them.
 *
 * Removing is the exception to the draft, because Undo is what makes it safe: [onRemove] takes the
 * slot off the bar now, and its caller offers it back. A group with keys in it asks first, since
 * the keys go with it.
 */
@Composable
internal fun KeySlotSheet(
    set: KeySet,
    target: SheetTarget,
    onSave: (SlotEdit) -> Unit,
    onRemove: (SlotPosition) -> Unit,
    onDismiss: () -> Unit,
) {
    val from = (target as? SheetTarget.Existing)?.at
    val original: KeyBarSlot = when (target) {
        is SheetTarget.Existing -> set.row(target.at.pinned).getOrNull(target.at.index) ?: return
        // Nothing yet, so the first key pressed is the key rather than a replacement for one.
        SheetTarget.NewKey -> AuxiliaryKey(label = "", steps = emptyList())
        SheetTarget.NewGroup -> KeyGroup(name = "", keys = emptyList())
    }
    var key by remember { mutableStateOf((original as? AuxiliaryKey)?.let(KeyDraft::of)) }
    var groupName by remember { mutableStateOf((original as? KeyGroup)?.name.orEmpty()) }
    var groupIcon by remember { mutableStateOf((original as? KeyGroup)?.icon) }
    var members by remember { mutableStateOf((original as? KeyGroup)?.keys.orEmpty().map(KeyDraft::of)) }
    var pinned by remember { mutableStateOf(from?.pinned ?: false) }
    var into by remember { mutableStateOf<SlotPosition?>(null) }
    var movedOut by remember { mutableStateOf(emptyList<AuxiliaryKey>()) }
    // The key of the group being edited, by its place among the members; null on the group's page.
    var page by remember { mutableStateOf<Int?>(null) }
    // The icon page, over whichever of the pages above is showing.
    var picking by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Question?>(null) }
    var takenOut by remember { mutableStateOf<TakenOut?>(null) }

    val isGroup = original is KeyGroup
    val edited: KeyBarSlot = if (isGroup) KeyGroup(groupName, members.map { it.key }, groupIcon) else key!!.key
    // Read back through the draft, so a label that only repeats what the steps say is not taken
    // for a name the owner gave.
    val baseline: KeyBarSlot = when (original) {
        is KeyGroup -> original.copy(keys = original.keys.map { KeyDraft.of(it).key })
        is AuxiliaryKey -> KeyDraft.of(original).key
    }
    val changed = edited != baseline || pinned != (from?.pinned ?: false) || into != null || movedOut.isNotEmpty()
    val saveable = if (isGroup) edited.label.isNotBlank() && members.all { it.saveable } else key!!.saveable
    val groupLabel = KeyGroup(groupName, members.map { it.key }, groupIcon).label.ifBlank { "Group" }
    val member = page?.takeIf { it in members.indices }

    val close: () -> Unit = { if (changed) asking = Question.Discard else onDismiss() }
    val back: () -> Unit = {
        page = null
        takenOut = null
    }
    val save: () -> Unit = { onSave(SlotEdit(from, edited, pinned, into.takeIf { !isGroup }, movedOut)) }
    val openIcons: () -> Unit = {
        takenOut = null
        picking = true
    }

    LimentySheet(
        onDismissRequest = {
            when {
                picking -> picking = false
                page != null -> back()
                else -> close()
            }
        },
        onTapOutside = close,
        header = {
            when {
                picking -> {
                    val pickingFor = when {
                        member != null -> members[member].key.label.ifBlank { "Key" }
                        isGroup -> groupLabel
                        else -> key!!.key.label.ifBlank { "Key" }
                    }
                    BackTo(pickingFor, onClick = { picking = false })
                    LimentySheetTitle("Icon")
                }
                page != null -> {
                    BackTo(groupLabel, onClick = back)
                    Spacer(modifier = Modifier.weight(1f))
                }
                else -> {
                    LimentyTextButton("Cancel", close, color = LimentyColors.Text2)
                    LimentySheetTitle(if (isGroup) "Group" else "Key")
                    LimentyTextButton("Save", save, enabled = saveable)
                }
            }
        },
        overlay = {
            when (asking) {
                Question.Discard -> LimentySheetQuestion(
                    title = "Discard changes?",
                    body = "Your changes to ${original.label.ifBlank { if (isGroup) "this group" else "this key" }} have not been saved.",
                ) {
                    LimentyTextButton("Keep editing", { asking = null }, color = LimentyColors.Text2)
                    LimentyTextButton("Discard", onDismiss, color = LimentyColors.Danger)
                }
                Question.RemoveGroup -> LimentySheetQuestion(title = "Remove group", body = removalNote(original)) {
                    LimentyTextButton("Cancel", { asking = null }, color = LimentyColors.Text2)
                    LimentyTextButton("Remove", { from?.let(onRemove) }, color = LimentyColors.Danger)
                }
                null -> Unit
            }
            takenOut?.let { gone ->
                LimentySnackbar(
                    message = "Removed ${gone.key.key.label.ifBlank { "key" }}",
                    action = "Undo",
                    onAction = {
                        members = members.toMutableList().also { it.add(gone.index.coerceIn(0, it.size), gone.key) }
                        takenOut = null
                    },
                    onTimeout = { takenOut = null },
                    key = gone,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) {
        // Back steps out of the icons, then out of a key to its group, before it closes anything.
        BackHandler(enabled = (picking || page != null) && asking == null) { if (picking) picking = false else back() }
        // Read here, inside the sheet's own window: the focus manager outside it cannot reach the
        // Label field. That field stays composed under the icons, and left focused it would keep
        // the keyboard up over them, typing into a field no one can see.
        val focus = LocalFocusManager.current
        LaunchedEffect(picking) { if (picking) focus.clearFocus() }
        if (picking) {
            val chosen = when {
                member != null -> members[member].icon
                isGroup -> groupIcon
                else -> key!!.icon
            }
            IconPage(chosen) { icon ->
                when {
                    member != null -> members = members.toMutableList().also { it[member] = it[member].copy(icon = icon) }
                    isGroup -> groupIcon = icon
                    else -> key = key!!.copy(icon = icon)
                }
                picking = false
            }
        }
        // Kept composed under the icon page rather than left, so what the keys editor holds on to
        // (a held Ctrl, a selected step, a half-typed key, its tab) is still there coming back.
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .then(if (picking) Modifier.hiddenUnder() else Modifier)
                .verticalScroll(scroll)
                .padding(start = LimentySheetPadding, end = LimentySheetPadding, bottom = LimentySheetPadding),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            when {
                member != null -> KeyFields(
                    draft = members[member],
                    onChange = { changedKey -> members = members.toMutableList().also { it[member] = changedKey } },
                    onPickIcon = openIcons,
                ) {
                    LimentyTextButton(
                        "Move out of $groupLabel",
                        onClick = {
                            movedOut = movedOut + members[member].key
                            members = members.filterIndexed { index, _ -> index != member }
                            back()
                        },
                        modifier = Modifier.alignedToFields(),
                    )
                    RemoveButton("Remove from group") {
                        val gone = TakenOut(member, members[member])
                        members = members.filterIndexed { index, _ -> index != member }
                        page = null
                        takenOut = gone
                    }
                }
                isGroup -> GroupFields(
                    name = groupName,
                    onNameChange = { groupName = it },
                    icon = groupIcon,
                    onPickIcon = openIcons,
                    members = members,
                    onMembersChange = { members = it },
                    onOpen = { index ->
                        takenOut = null
                        page = index
                    },
                    onAdd = {
                        val added = members.size
                        members = members + KeyDraft(ownLabel = null, steps = emptyList())
                        takenOut = null
                        page = added
                    },
                ) {
                    PositionChoice(pinned = pinned, onPinnedChange = { pinned = it })
                    if (from != null) {
                        RemoveButton("Remove group") {
                            if ((original as KeyGroup).keys.isEmpty()) onRemove(from) else asking = Question.RemoveGroup
                        }
                    }
                }
                else -> KeyFields(draft = key!!, onChange = { key = it }, onPickIcon = openIcons) {
                    PositionChoice(
                        pinned = pinned,
                        onPinnedChange = {
                            pinned = it
                            into = null
                        },
                        inGroup = into != null,
                        groups = groupsIn(set),
                        into = into,
                        onIntoChange = { into = it },
                    )
                    if (from != null) RemoveButton("Remove key") { onRemove(from) }
                }
            }
        }
    }
}

private enum class Question { Discard, RemoveGroup }

/** A key taken out of the group being edited, kept to be put back where it was. */
private class TakenOut(val index: Int, val key: KeyDraft)

/** A key's icon, label and what it sends, then whatever [placement] adds under them. */
@Composable
private fun KeyFields(draft: KeyDraft, onChange: (KeyDraft) -> Unit, onPickIcon: () -> Unit, placement: @Composable () -> Unit) {
    // Where the name comes from when the owner has not typed one, which is also what it goes back to.
    val source = if (draft.icon == null) "keys" else "icon"
    LimentyField(
        label = "Label",
        value = draft.key.label,
        onValueChange = { onChange(draft.copy(ownLabel = it)) },
        leading = { IconWell(draft.icon, onPickIcon) },
        // Inside the field rather than on a line below it, which would start at the field's
        // outer edge and so sit short of the label it is about.
        trailing = {
            if (draft.ownLabel == null) {
                Text("From $source", style = LimentyText.Secondary, color = LimentyColors.Text3)
            } else {
                // The way back is a button rather than emptying the field, which would put the
                // icon's or the keys' name straight back and leave nothing to type into.
                Text(
                    "Use $source",
                    style = LimentyText.Label,
                    modifier = Modifier.clickable(role = Role.Button) { onChange(draft.copy(ownLabel = null)) },
                )
            }
        },
    )
    KeyStepsEditor(steps = draft.steps, onStepsChange = { onChange(draft.copy(steps = it)) })
    placement()
}

@Composable
private fun GroupFields(
    name: String,
    onNameChange: (String) -> Unit,
    icon: KeyIcon?,
    onPickIcon: () -> Unit,
    members: List<KeyDraft>,
    onMembersChange: (List<KeyDraft>) -> Unit,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    placement: @Composable () -> Unit,
) {
    val order = rememberReorderState<KeyDraft>()
    LimentyField(
        label = "Name",
        value = name,
        onValueChange = onNameChange,
        // What the group is called while the name is empty, so leaving it empty is a choice: the
        // icon's name when it has one, as a key's is.
        placeholder = KeyGroup("", members.map { it.key }, icon).label.ifEmpty { null },
        leading = { IconWell(icon, onPickIcon) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Keys")
        FlowRow(
            modifier = Modifier.fillMaxWidth().reorderGroup(order, members, onReorder = onMembersChange),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            members.forEachIndexed { index, member ->
                MemberTile(
                    key = member.key,
                    lifted = order.dragging == index,
                    onClick = { onOpen(index) },
                    modifier = Modifier.reorderable(order, index, members.size, member.key.spoken()),
                )
            }
            AddMemberTile(onClick = onAdd)
        }
    }
    placement()
}

/**
 * Which row the slot ends in, and for a key, which group instead.
 *
 * Choices rather than buttons that act at once: Pin right used to move the key the moment it was
 * pressed, which dropped every other change made in the same dialog.
 */
@Composable
private fun PositionChoice(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    inGroup: Boolean = false,
    groups: List<Pair<SlotPosition, KeyGroup>> = emptyList(),
    into: SlotPosition? = null,
    onIntoChange: (SlotPosition?) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Position")
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Choice("Scrolling", selected = !inGroup && !pinned, onClick = { onPinnedChange(false) }, modifier = Modifier.weight(1f))
            Choice("Pinned", selected = !inGroup && pinned, onClick = { onPinnedChange(true) }, modifier = Modifier.weight(1f))
        }
        if (groups.isNotEmpty()) {
            Text("Or inside a group", style = LimentyText.Secondary, color = LimentyColors.Text3, modifier = Modifier.padding(top = 4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { (at, group) ->
                    Choice(
                        group.label,
                        selected = into == at,
                        onClick = { onIntoChange(if (into == at) null else at) },
                        mono = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, mono: Boolean = false) {
    Box(
        modifier = modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .defaultMinSize(minWidth = 44.dp)
            .widthIn(max = 200.dp)
            .clip(LimentyShapes.Surface)
            .background(if (selected) LimentyColors.AccentSelection else LimentyColors.Surface2)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) LimentyColors.Accent else LimentyColors.Text2,
            style = if (mono) AuxiliaryKeyBarMetrics.labelStyle() else LimentyText.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Removing goes last and is the only thing in `Danger`, on the same edge in every page. */
@Composable
private fun RemoveButton(label: String, onClick: () -> Unit) {
    LimentyTextButton(label, onClick, color = LimentyColors.Danger, modifier = Modifier.alignedToFields())
}

/** Takes no room, draws nothing and says nothing, while staying composed with all it remembers. */
private fun Modifier.hiddenUnder(): Modifier = clearAndSetSemantics {}.layout { measurable, constraints ->
    measurable.measure(constraints)
    layout(0, 0) {}
}

/** Pulls a text button out by its inset, so its word starts on the edge the fields above it do. */
private fun Modifier.alignedToFields(): Modifier = layout { measurable, constraints ->
    val inset = LimentyTextButtonInset.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width - inset, placeable.height) { placeable.place(-inset, 0) }
}

/** `‹ <name>`, back to the page named. */
@Composable
private fun BackTo(page: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .widthIn(max = 280.dp)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClickLabel = "Back to $page", onClick = onClick)
            .padding(horizontal = LimentyTextButtonInset),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LimentyChevron(12.dp, 180f, LimentyColors.Accent)
        Text(page, style = LimentyText.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One key in the group, drawn as the key it is. */
@Composable
private fun MemberTile(key: AuxiliaryKey, lifted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .then(modifier)
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .widthIn(max = 200.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .selectedRing(lifted)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = key.spoken() },
        contentAlignment = Alignment.Center,
    ) {
        KeyFaceContent(
            if (key.icon == null && key.label.isEmpty()) listOf(FacePart.Words("—")) else key.face(),
            LimentyColors.Text,
            ellipsize = true,
        )
    }
}

/** Dashed, so it reads as a place for a key rather than a key that sends `+`. */
@Composable
private fun AddMemberTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .dashedOutline(LimentyColors.Line, LimentyShapes.Surface)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Add key" }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = LimentyText.Label)
    }
}

/**
 * The icon of a key or a group, at the left of its Label or Name field, and the way to the icons.
 * Empty, it is dashed like the place a key is added, so it reads as somewhere to put something.
 */
@Composable
private fun IconWell(icon: KeyIcon?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(LimentySpacing.TouchTarget)
            .then(
                if (icon == null) {
                    Modifier.dashedOutline(LimentyColors.Line, LimentyShapes.Surface)
                } else {
                    Modifier.background(LimentyColors.Surface3, LimentyShapes.Surface)
                },
            )
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Icon, ${icon?.label ?: "none"}" },
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) {
            Text("+", style = LimentyText.Label, color = LimentyColors.Text3)
        } else {
            KeyIconImage(icon, LimentyColors.Text, WELL_ICON)
        }
    }
}

/**
 * Every icon a key or a group can show, under its heading, after None. Tapping one chooses it and
 * goes back to what asked for it at once, since there is nothing else on the page to do.
 *
 * Opens with the chosen icon in view, so changing it starts from where it is. The tiles keep their
 * size at any font scale, so eight still fit a row on the cover display; the name of the chosen icon
 * is in the Label field, in text that does grow.
 */
@Composable
private fun IconPage(chosen: KeyIcon?, onChoose: (KeyIcon?) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val content = remember { PlacedContent() }
    val headingRoom = with(LocalDensity.current) { ICON_HEADING_ROOM.roundToPx() }
    Column(
        modifier = Modifier
            .verticalScroll(scroll)
            .onGloballyPositioned { content.coordinates = it }
            .padding(start = LimentySheetPadding, end = LimentySheetPadding, bottom = LimentySheetPadding),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        Box(modifier = Modifier.selectableGroup()) {
            IconChoice(selected = chosen == null, spoken = "None", onClick = { onChoose(null) }) {
                Text("None", style = LimentyText.Secondary, color = if (chosen == null) LimentyColors.Accent else LimentyColors.Text2)
            }
        }
        KeyIconCategory.entries.forEach { category ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(category.title, style = LimentyText.Secondary, color = LimentyColors.Text2)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ICON_TILE_GAP),
                    verticalArrangement = Arrangement.spacedBy(ICON_TILE_GAP),
                ) {
                    KeyIcon.entries.filter { it.category == category }.forEach { icon ->
                        val selected = icon == chosen
                        IconChoice(
                            selected = selected,
                            spoken = icon.label,
                            onClick = { onChoose(icon) },
                            modifier = if (!selected) {
                                Modifier
                            } else {
                                Modifier.onGloballyPositioned { tile ->
                                    val column = content.coordinates
                                    if (content.scrolled || column == null || !column.isAttached) return@onGloballyPositioned
                                    content.scrolled = true
                                    // From the content's own top, which moves with the scroll, so this is
                                    // where the tile is however far the page has already moved.
                                    val top = column.localPositionOf(tile, Offset.Zero).y.toInt()
                                    scope.launch { scroll.scrollTo(top - headingRoom) }
                                }
                            },
                        ) {
                            KeyIconImage(icon, if (selected) LimentyColors.Accent else LimentyColors.Text, ICON_TILE_ICON)
                        }
                    }
                }
            }
        }
    }
}

/** Where the icon page's content was placed, and whether it has been scrolled to the chosen icon yet. Read only once. */
private class PlacedContent {
    var coordinates: LayoutCoordinates? = null
    var scrolled = false
}

@Composable
private fun IconChoice(selected: Boolean, spoken: String, onClick: () -> Unit, modifier: Modifier = Modifier, face: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = ICON_TILE)
            .defaultMinSize(minWidth = ICON_TILE)
            .clip(LimentyShapes.Surface)
            .background(if (selected) LimentyColors.AccentSelection else LimentyColors.Surface2)
            .selectedRing(selected)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        face()
    }
}

/** Between the parts of a page, a little more than inside a form, since each is its own question. */
private val SECTION_GAP = LimentyDialogGap + 4.dp

private val WELL_ICON = 20.dp

/**
 * A tile on the icon page and the icon in it. In dp rather than sp, so a row holds as many at any
 * font scale: eight across the cover display's sheet.
 */
private val ICON_TILE = 44.dp
private val ICON_TILE_ICON = 23.dp
private val ICON_TILE_GAP = 10.dp

/** Left above the chosen icon when the page opens at it, so its heading is still in view. */
private val ICON_HEADING_ROOM = 36.dp
