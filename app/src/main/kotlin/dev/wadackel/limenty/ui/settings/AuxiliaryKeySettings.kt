package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyStep
import dev.wadackel.limenty.ui.components.LimentyChevron
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
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * Edits one auxiliary key: its label and what it sends.
 *
 * What it sends is a list, because the shortcuts worth a button are often two keystrokes — a tmux
 * prefix and the command after it. The steps sit in a row above the divider and the fields below it
 * belong to whichever one is selected, so the form stays the size it was for the single-step key
 * that most of them still are.
 *
 * The action is built from a picker rather than typed, so a button cannot be configured to send
 * something the encoder does not understand.
 *
 * Label and steps stay separable — that is what lets a button read "Clear" while sending Ctrl+L —
 * but the label follows the steps until someone says otherwise. Separable and independent are not
 * the same thing, and independent is what it was: picking a key left the label as it was, so every
 * key had to be named by hand even though almost none of them wanted a name of their own. A key
 * that sends a sequence usually does want one, since `⌃s w` claims half again the width of the
 * key beside it.
 *
 * Built from this app's own controls rather than from a stock dialog. The stock one put its parts
 * on four different left edges — a field inset by its own padding, a heading at the margin, a text
 * button inset by a third amount — and drew a field whose border was too dark to make out against
 * the surface behind it, so the form read as a column of loose words rather than as a form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuxiliaryKeyDialog(
    initial: AuxiliaryKey,
    onDismiss: () -> Unit,
    onSave: (AuxiliaryKey) -> Unit,
    /** Null while adding, when there is nothing yet to pin or delete. */
    placement: KeyPlacement? = null,
) {
    // Never empty: an editor with nothing to edit has no fields to show, and a key stored with no
    // steps is a key that does nothing, which is the state a fresh empty step already represents.
    var steps by remember { mutableStateOf(initial.steps.ifEmpty { listOf(KeyStep.Press()) }) }
    var selected by remember { mutableIntStateOf(0) }
    var keyMenuOpen by remember { mutableStateOf(false) }
    val order = rememberReorderState<KeyStep>()

    // Null while the label is the steps' to give.
    var ownLabel by remember {
        mutableStateOf(if (initial.hasOwnName()) initial.label else null)
    }
    val label = ownLabel ?: AuxiliaryKey.labelFor(steps)

    val at = selected.coerceIn(0, steps.lastIndex)
    val step = steps[at]
    val replace: (KeyStep) -> Unit = { edited -> steps = steps.toMutableList().also { it[at] = edited } }

    LimentyDialog(title = "Auxiliary key", onDismissRequest = onDismiss) {
        LimentyField(
            label = "Label",
            value = label,
            onValueChange = { ownLabel = it },
            // Inside the field rather than on a line below it, which would start at the field's
            // outer edge and so sit short of the label it is about.
            trailing = {
                if (ownLabel == null) {
                    Text("Follows keys", style = LimentyText.Secondary, color = LimentyColors.Text3)
                } else {
                    // The way back is a button rather than emptying the field, which would put
                    // the action's name straight back and leave nothing to type into.
                    Text(
                        "Use key name",
                        style = LimentyText.Label,
                        modifier = Modifier.clickable(role = Role.Button) { ownLabel = null },
                    )
                }
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                // Centred rather than bottom-aligned: the button beside the heading is a 44dp
                // touch target and the heading is eleven point text, so a shared baseline leaves
                // the two reading as separate rows.
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeading("Sends", modifier = Modifier.weight(1f))
                LimentyTextButton(
                    label = "Remove step",
                    onClick = {
                        steps = steps.toMutableList().also { it.removeAt(at) }
                        selected = at.coerceAtMost(steps.lastIndex)
                    },
                    // Not Danger: the step only leaves a draft the dialog's Cancel still throws away.
                    color = LimentyColors.Text2,
                    enabled = steps.size > 1,
                )
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .reorderGroup(order, steps) { moved ->
                        // The selection follows the step it was on rather than the position it
                        // occupied, or dragging one step would silently start editing another.
                        selected = moved.indexOfFirst { it === steps[at] }.coerceAtLeast(0)
                        steps = moved
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                steps.forEachIndexed { index, entry ->
                    StepChip(
                        name = stepName(entry),
                        spoken = spokenStep(entry),
                        selected = index == at,
                        dragged = order.dragging == index,
                        onClick = { selected = index },
                        modifier = Modifier.reorderable(order, index, steps.size, spokenStep(entry)),
                    )
                }
                AddStepChip(
                    onClick = {
                        steps = steps + KeyStep.Press()
                        selected = steps.lastIndex
                    },
                )
            }
        }

        Box {
            PickerRow(
                label = "Action",
                value = actionName(step),
                onClick = { keyMenuOpen = true },
            )
            DropdownMenu(expanded = keyMenuOpen, onDismissRequest = { keyMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Character") },
                    onClick = {
                        replace(KeyStep.Press(codePoint = codePointOf(step), modifiers = modifiersOf(step)))
                        keyMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Text") },
                    onClick = {
                        replace(KeyStep.Literal((step as? KeyStep.Literal)?.text ?: ""))
                        keyMenuOpen = false
                    },
                )
                TerminalKey.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.name) },
                        onClick = {
                            replace(KeyStep.Press(key = candidate, modifiers = modifiersOf(step)))
                            keyMenuOpen = false
                        },
                    )
                }
            }
        }

        when {
            step is KeyStep.Literal -> LimentyField(
                label = "Text",
                value = step.text,
                onValueChange = { replace(KeyStep.Literal(it)) },
                placeholder = "sent as typed",
            )
            step is KeyStep.Press && step.key == null -> LimentyField(
                label = "Character",
                value = if (step.codePoint > 0) String(Character.toChars(step.codePoint)) else "",
                // Two, for a character that takes two units to write rather than for two
                // characters: what is stored is the one code point they spell.
                onValueChange = { typed ->
                    val text = typed.take(2)
                    replace(step.copy(codePoint = if (text.isEmpty()) 0 else text.codePointAt(0)))
                },
                placeholder = "one character",
            )
        }

        if (step is KeyStep.Press) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Held with it")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModifierChip("Ctrl", Modifiers.CTRL, step.modifiers) { replace(step.copy(modifiers = it)) }
                    ModifierChip("Alt", Modifiers.ALT, step.modifiers) { replace(step.copy(modifiers = it)) }
                    ModifierChip("Shift", Modifiers.SHIFT, step.modifiers) { replace(step.copy(modifiers = it)) }
                }
            }
        }

        // Where the key lives and whether it lives at all, next to what it does. These used to
        // be buttons crowded into the key's own face on the list behind this dialog, which left
        // that face too small to read and nothing but a face to drag.
        placement?.let { where ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LimentyTextButton(
                    label = if (where.pinned) "Unpin" else "Pin right",
                    onClick = where.onTogglePin,
                )
                LimentyTextButton(
                    label = "Remove",
                    onClick = where.onRemove,
                    color = LimentyColors.Danger,
                )
            }
        }

        LimentyDialogActions {
            LimentyTextButton("Cancel", onDismiss, color = LimentyColors.Text2)
            LimentyTextButton(
                label = "Save",
                onClick = { onSave(AuxiliaryKey(label = label, steps = steps)) },
                // Every step, not just the selected one: a step left unconfigured sends nothing,
                // and a key with a gap in the middle of its sequence is not what anyone meant.
                enabled = label.isNotBlank() && steps.all { it.toEvent() != null },
            )
        }
    }
}

/** What the picker calls the step it is editing. */
private fun actionName(step: KeyStep): String = when (step) {
    is KeyStep.Literal -> "Text"
    is KeyStep.Press -> step.key?.name ?: "Character"
}

private fun codePointOf(step: KeyStep): Int = (step as? KeyStep.Press)?.codePoint ?: 0

private fun modifiersOf(step: KeyStep): Modifiers = (step as? KeyStep.Press)?.modifiers ?: Modifiers.None

/** A step with nothing chosen yet has no name; it still needs a chip to be selected by. */
private fun stepName(step: KeyStep): String = AuxiliaryKey.labelFor(listOf(step)).ifEmpty { "—" }

/** The chip shows `⌃s`; a screen reader should hear `Ctrl s`. */
private fun spokenStep(step: KeyStep): String = AuxiliaryKey.spokenFor(listOf(step)).ifEmpty { "—" }

/**
 * One step, in the order it is sent.
 *
 * Drawn as a key, like the modifier chips and like the keys on the bar itself, because that is what
 * it stands for. The selected one is the one the fields below the row are about, so it is marked
 * the same way a held modifier is.
 */
@Composable
private fun StepChip(
    name: String,
    spoken: String,
    selected: Boolean,
    dragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .selectedRing(selected || dragged)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = if (selected) LimentyColors.Accent else LimentyColors.Text2,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
        )
    }
}

/** Dashed, so it reads as a place for a step rather than as a step that sends `+`. */
@Composable
private fun AddStepChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .dashedOutline(LimentyColors.Line, LimentyShapes.Surface)
            .clip(LimentyShapes.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = LimentyText.Label, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
    }
}

/** What can be done with a key that already exists, for the dialog to offer alongside its fields. */
data class KeyPlacement(
    val pinned: Boolean,
    val onTogglePin: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * A choice, shaped like the field above it.
 *
 * Every measurement here is [LimentyField]'s, because the two sit one under the other and a picker
 * that merely resembles a field is worse than one that does not: the eye reads the difference as a
 * mistake rather than as a distinction.
 */
@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LimentySpacing.SurfacePadding, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = LimentyText.Secondary)
            Text(
                value,
                color = LimentyColors.Text,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            LimentyChevron(12.dp, 90f, LimentyColors.Text3)
        }
    }
}

/**
 * One modifier, held or not.
 *
 * Drawn as a key rather than written as a word: what it stands for is a key held down, and three
 * bare words said nothing about which of them were on beyond the colour they were printed in.
 */
@Composable
private fun ModifierChip(label: String, bit: Int, current: Modifiers, onChange: (Modifiers) -> Unit) {
    val held = current.bits and bit != 0
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 72.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .selectedRing(held)
            .toggleable(
                value = held,
                role = Role.Checkbox,
                onValueChange = { onChange(Modifiers(current.bits xor bit)) },
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (held) LimentyColors.Accent else LimentyColors.Text2,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
