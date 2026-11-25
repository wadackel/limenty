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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
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
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/** What the editing sheet is opened on. */
internal sealed interface SheetTarget {
    data class Existing(val at: SlotPosition) : SheetTarget

    data object NewKey : SheetTarget

    data object NewGroup : SheetTarget
}

/**
 * A key as it is being edited: its steps, and its own name if it has one.
 *
 * The name is held apart from the label rather than read back off it, because emptying the field
 * is a step on the way to typing a new name, and a key read back from an empty label follows its
 * steps again — which would put the old name straight back into the field being cleared.
 */
@Immutable
internal data class KeyDraft(val ownLabel: String?, val steps: List<KeyStep>) {
    val key: AuxiliaryKey get() = AuxiliaryKey(ownLabel ?: AuxiliaryKey.labelFor(steps), steps)

    val saveable: Boolean get() = key.label.isNotBlank() && sendsEveryStep(steps)

    companion object {
        fun of(key: AuxiliaryKey) = KeyDraft(if (key.hasOwnName()) key.label else null, key.steps)
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
    var members by remember { mutableStateOf((original as? KeyGroup)?.keys.orEmpty().map(KeyDraft::of)) }
    var pinned by remember { mutableStateOf(from?.pinned ?: false) }
    var into by remember { mutableStateOf<SlotPosition?>(null) }
    var movedOut by remember { mutableStateOf(emptyList<AuxiliaryKey>()) }
    // The key of the group being edited, by its place among the members; null on the group's page.
    var page by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf<Question?>(null) }
    var takenOut by remember { mutableStateOf<TakenOut?>(null) }

    val isGroup = original is KeyGroup
    val edited: KeyBarSlot = if (isGroup) KeyGroup(groupName, members.map { it.key }) else key!!.key
    // Read back through the draft, so a label that only repeats what the steps say is not taken
    // for a name the owner gave.
    val baseline: KeyBarSlot = when (original) {
        is KeyGroup -> original.copy(keys = original.keys.map { KeyDraft.of(it).key })
        is AuxiliaryKey -> KeyDraft.of(original).key
    }
    val changed = edited != baseline || pinned != (from?.pinned ?: false) || into != null || movedOut.isNotEmpty()
    val saveable = if (isGroup) edited.label.isNotBlank() && members.all { it.saveable } else key!!.saveable
    val groupLabel = KeyGroup(groupName, members.map { it.key }).label.ifBlank { "Group" }

    val close: () -> Unit = { if (changed) asking = Question.Discard else onDismiss() }
    val back: () -> Unit = {
        page = null
        takenOut = null
    }
    val save: () -> Unit = { onSave(SlotEdit(from, edited, pinned, into.takeIf { !isGroup }, movedOut)) }

    LimentySheet(
        onDismissRequest = { if (page != null) back() else close() },
        onTapOutside = close,
        header = {
            val at = page
            if (at != null) {
                BackToGroup(groupLabel, onClick = back)
                Spacer(modifier = Modifier.weight(1f))
            } else {
                LimentyTextButton("Cancel", close, color = LimentyColors.Text2)
                LimentySheetTitle(if (isGroup) "Group" else "Key")
                LimentyTextButton("Save", save, enabled = saveable)
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
        // Back steps out of a key to its group before it closes anything.
        BackHandler(enabled = page != null && asking == null) { back() }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .verticalScroll(scroll)
                .padding(start = LimentySheetPadding, end = LimentySheetPadding, bottom = LimentySheetPadding),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            val at = page
            when {
                at != null && at in members.indices -> KeyFields(
                    draft = members[at],
                    onChange = { changedKey -> members = members.toMutableList().also { it[at] = changedKey } },
                ) {
                    LimentyTextButton(
                        "Move out of $groupLabel",
                        onClick = {
                            movedOut = movedOut + members[at].key
                            members = members.filterIndexed { index, _ -> index != at }
                            back()
                        },
                        modifier = Modifier.alignedToFields(),
                    )
                    RemoveButton("Remove from group") {
                        val gone = TakenOut(at, members[at])
                        members = members.filterIndexed { index, _ -> index != at }
                        page = null
                        takenOut = gone
                    }
                }
                isGroup -> GroupFields(
                    name = groupName,
                    onNameChange = { groupName = it },
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
                else -> KeyFields(draft = key!!, onChange = { key = it }) {
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

/** A key's label and what it sends, then whatever [placement] adds under them. */
@Composable
private fun KeyFields(draft: KeyDraft, onChange: (KeyDraft) -> Unit, placement: @Composable () -> Unit) {
    LimentyField(
        label = "Label",
        value = draft.key.label,
        onValueChange = { onChange(draft.copy(ownLabel = it)) },
        // Inside the field rather than on a line below it, which would start at the field's
        // outer edge and so sit short of the label it is about.
        trailing = {
            if (draft.ownLabel == null) {
                Text("From keys", style = LimentyText.Secondary, color = LimentyColors.Text3)
            } else {
                // The way back is a button rather than emptying the field, which would put the
                // keys' name straight back and leave nothing to type into.
                Text(
                    "Use keys",
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
        // What the slot shows while the name is empty, so leaving it empty is a choice.
        placeholder = KeyGroup("", members.map { it.key }).label.ifEmpty { null },
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

/** Pulls a text button out by its inset, so its word starts on the edge the fields above it do. */
private fun Modifier.alignedToFields(): Modifier = layout { measurable, constraints ->
    val inset = LimentyTextButtonInset.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width - inset, placeable.height) { placeable.place(-inset, 0) }
}

@Composable
private fun BackToGroup(group: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .widthIn(max = 280.dp)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClickLabel = "Back to $group", onClick = onClick)
            .padding(horizontal = LimentyTextButtonInset),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LimentyChevron(12.dp, 180f, LimentyColors.Accent)
        Text(group, style = LimentyText.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(
            key.label.ifEmpty { "—" },
            color = LimentyColors.Text,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

/** Between the parts of a page, a little more than inside a form, since each is its own question. */
private val SECTION_GAP = LimentyDialogGap + 4.dp
