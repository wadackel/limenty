package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyField
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.components.rememberReorderState
import dev.wadackel.limenty.ui.components.reorderGroup
import dev.wadackel.limenty.ui.components.reorderable
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBarMetrics
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * Edits one key group: its name and the keys it opens into.
 *
 * The keys are edited the way the bar's own are — tap one to change it, hold it to move it, add
 * another at the end — and each one opens the same key editor, so a key in a group can be anything
 * a key on the bar can. Nothing is stored until Save, like the key editor: a member removed here is
 * only gone from the draft that Cancel still throws away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyGroupDialog(
    initial: KeyGroup,
    onDismiss: () -> Unit,
    onSave: (KeyGroup) -> Unit,
    /** Null while adding, when there is nothing yet to pin or delete. */
    placement: KeyPlacement? = null,
    /**
     * Takes a key out of the group, given the group as edited so far and the key's place in it.
     * Null while adding, when the group is not on the bar yet and has nowhere to put a key after it.
     */
    onMoveOut: ((edited: KeyGroup, member: Int) -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial.name) }
    var keys by remember { mutableStateOf(initial.keys) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    val order = rememberReorderState<AuxiliaryKey>()
    val draft = KeyGroup(name, keys)

    LimentyDialog(title = "Key group", onDismissRequest = onDismiss) {
        LimentyField(
            label = "Name",
            value = name,
            onValueChange = { name = it },
            // What the slot shows while the name is empty, so leaving it empty is a choice.
            placeholder = KeyGroup("", keys).label.ifEmpty { null },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Keys")
            FlowRow(
                modifier = Modifier.fillMaxWidth().reorderGroup(order, keys) { keys = it },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keys.forEachIndexed { index, key ->
                    MemberChip(
                        key = key,
                        dragged = order.dragging == index,
                        onClick = { editing = index },
                        modifier = Modifier.reorderable(order, index, keys.size, key.spoken()),
                    )
                }
                AddMemberChip(onClick = { adding = true })
            }
            Text(
                "Tap a key to change it. Hold to move it.",
                style = LimentyText.Secondary,
                color = LimentyColors.Text3,
            )
        }

        placement?.let { where -> PlacementActions(where) }

        LimentyDialogActions {
            LimentyTextButton("Cancel", onDismiss, color = LimentyColors.Text2)
            LimentyTextButton(
                label = "Save",
                onClick = { onSave(draft) },
                // A group with neither a name nor a key has nothing to put on its face.
                enabled = draft.label.isNotBlank(),
            )
        }
    }

    editing?.let { at ->
        val key = keys.getOrNull(at) ?: return@let
        AuxiliaryKeyDialog(
            initial = key,
            onDismiss = { editing = null },
            onSave = { updated ->
                keys = keys.mapIndexed { index, it -> if (index == at) updated else it }
                editing = null
            },
            placement = KeyPlacement(
                pinned = false,
                onTogglePin = null,
                onRemove = {
                    keys = keys.filterIndexed { index, _ -> index != at }
                    editing = null
                },
                onMoveOutOfGroup = onMoveOut?.let { move -> { move(KeyGroup(name, keys), at) } },
            ),
        )
    }

    if (adding) {
        AuxiliaryKeyDialog(
            initial = AuxiliaryKey(label = "", key = TerminalKey.Escape),
            onDismiss = { adding = false },
            onSave = { added ->
                keys = keys + added
                adding = false
            },
        )
    }
}

/** One key in the group, drawn as the key it is, like a key in the bar's scrolling run. */
@Composable
private fun MemberChip(
    key: AuxiliaryKey,
    dragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .then(modifier)
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .selectedRing(dragged)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = key.spoken() },
        contentAlignment = Alignment.Center,
    ) {
        Text(key.label, color = LimentyColors.Text, style = AuxiliaryKeyBarMetrics.labelStyle(), maxLines = 1)
    }
}

/** Dashed, as the step editor's is, so it reads as a place for a key rather than a key that sends `+`. */
@Composable
private fun AddMemberChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .dashedOutline(LimentyColors.Line, LimentyShapes.Surface)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClickLabel = "Add key", onClick = onClick)
            .semantics { contentDescription = "Add key" }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = LimentyText.Label, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
    }
}
