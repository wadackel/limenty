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
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyDialogGap
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
 * Edits what a key bar button or a gesture sends: a row of steps, and the fields of the selected one.
 *
 * Both editors use this one so a sequence is built the same way wherever it is sent from. What
 * each step can be — a named key, a character, text, with Ctrl, Alt or Shift — is the encoder's
 * vocabulary, offered as a picker rather than typed so nothing here can name what it cannot send.
 *
 * [steps] is never empty; the caller starts from one empty step, which is what nothing chosen yet
 * looks like.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyStepsEditor(steps: List<KeyStep>, onStepsChange: (List<KeyStep>) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    var keyMenuOpen by remember { mutableStateOf(false) }
    val order = rememberReorderState<KeyStep>()

    val at = selected.coerceIn(0, steps.lastIndex)
    val step = steps[at]
    val replace: (KeyStep) -> Unit = { edited -> onStepsChange(steps.toMutableList().also { it[at] = edited }) }

    Column(verticalArrangement = Arrangement.spacedBy(LimentyDialogGap)) {
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
                        onStepsChange(steps.toMutableList().also { it.removeAt(at) })
                        selected = at.coerceAtMost(steps.lastIndex - 1)
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
                        onStepsChange(moved)
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
                        onStepsChange(steps + KeyStep.Press())
                        selected = steps.size
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
    }
}

/**
 * Every step, not just the selected one: a step left unconfigured sends nothing, and a sequence
 * with a gap in the middle is not what anyone meant.
 */
internal fun sendsEveryStep(steps: List<KeyStep>): Boolean = steps.isNotEmpty() && steps.all { it.toEvent() != null }

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
