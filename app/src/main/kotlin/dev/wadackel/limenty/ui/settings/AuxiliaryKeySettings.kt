package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
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
 * Builds what a key bar key or a gesture sends, by pressing keys rather than choosing from a list.
 *
 * The steps are pressed on a small keyboard of the terminal's own keys, with Ctrl, Alt and Shift
 * held for the next key the way extra-keys rows do it: `⌃b c` is Ctrl, b, c. A list of every action
 * asked what kind of thing a step was — a named key, a character, text — before it asked which, and
 * then wanted the character typed in a second field and its modifiers in a third. What kind it is
 * now follows from what was pressed.
 *
 * Above the keyboard, a field takes a character or a key's name, for when typing `pgd` is quicker
 * than finding the key. Text goes in through "Text…", to one side, because it is rarely wanted.
 *
 * Both editors use this one, so a sequence is built the same way wherever it is sent from.
 */
@Composable
internal fun KeyStepsEditor(steps: List<KeyStep>, onStepsChange: (List<KeyStep>) -> Unit) {
    // The step the next key replaces; null adds the next key at the end.
    var selected by remember { mutableStateOf<Int?>(null) }
    // The text step just added from "Text…", whose field stays open while it is typed into. Held
    // apart from the selection, so the key pressed after the text goes after it: `ls` then ⏎ is
    // what text is usually for, and a selection would have let the ⏎ replace the `ls`.
    var writing by remember { mutableStateOf<Int?>(null) }
    var held by remember { mutableStateOf(Modifiers.None) }
    var tab by remember { mutableStateOf(KeyTab.Keys) }
    var typed by remember { mutableStateOf("") }
    val order = rememberReorderState<KeyStep>()
    val at = selected?.takeIf { it in steps.indices }

    val put: (KeyStep) -> Unit = { step ->
        onStepsChange(if (at != null) steps.toMutableList().also { it[at] = step } else steps + step)
        selected = null
        writing = null
        held = Modifiers.None
    }
    val press: (TerminalKey?, Int) -> Unit = { key, codePoint -> put(KeyStep.Press(key, codePoint, held)) }
    val suggestions = keySuggestions(typed)
    val takeSuggestion: (KeyStep.Press) -> Unit = { suggestion ->
        press(suggestion.key, suggestion.codePoint)
        typed = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("Sends")
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                modifier = Modifier
                    .weight(1f)
                    .reorderGroup(order, steps) { moved ->
                        // The selection follows the step it was on rather than the place it held.
                        selected = at?.let { from -> moved.indexOfFirst { it === steps[from] }.takeIf { it >= 0 } }
                        onStepsChange(moved)
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (steps.isEmpty()) {
                    Box(modifier = Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Press keys below", style = LimentyText.Secondary, color = LimentyColors.Text3)
                    }
                }
                steps.forEachIndexed { index, step ->
                    StepChip(
                        name = stepName(step),
                        spoken = spokenStep(step),
                        selected = index == at,
                        lifted = order.dragging == index,
                        onClick = {
                            selected = if (index == at) null else index
                            writing = null
                        },
                        modifier = Modifier.reorderable(order, index, steps.size, spokenStep(step)),
                    )
                }
            }
            DeleteStep(
                which = if (at != null) "selected" else "last",
                enabled = steps.isNotEmpty(),
                onClick = {
                    val gone = at ?: steps.lastIndex
                    onStepsChange(steps.filterIndexed { index, _ -> index != gone })
                    selected = null
                    writing = null
                },
            )
        }
        if (steps.isNotEmpty()) {
            Text(
                "Sends " + steps.joinToString(", then ") { spokenStep(it) },
                style = LimentyText.Secondary,
                color = LimentyColors.Text3,
            )
        }

        val textAt = (writing ?: at)?.takeIf { steps.getOrNull(it) is KeyStep.Literal }
        if (textAt != null) {
            LimentyField(
                label = "Text, sent as typed",
                value = (steps[textAt] as KeyStep.Literal).text,
                onValueChange = { text -> onStepsChange(steps.toMutableList().also { it[textAt] = KeyStep.Literal(text) }) },
            )
        }

        LimentyField(
            label = "Type a key or character",
            value = typed,
            onValueChange = { typed = it },
            placeholder = "c, pgd, f5, esc",
            // Ascii asks the keyboard for its Latin layout, where a name like `pgd` is typed, rather
            // than for a composing one that holds letters back until they are converted.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { suggestions.firstOrNull()?.let(takeSuggestion) }),
        )
        if (typed.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (suggestions.isEmpty()) {
                    Box(modifier = Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                        Text("No key by that name", style = LimentyText.Secondary, color = LimentyColors.Text3)
                    }
                }
                suggestions.forEachIndexed { index, suggestion ->
                    val step = suggestion.copy(modifiers = held)
                    KeyCap(
                        label = stepName(step),
                        spoken = "Add ${spokenStep(step)}",
                        ringed = index == 0,
                        onClick = { takeSuggestion(suggestion) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeldModifier("Ctrl", Modifiers.CTRL, held) { held = it }
            HeldModifier("Alt", Modifiers.ALT, held) { held = it }
            HeldModifier("Shift", Modifiers.SHIFT, held) { held = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                KeyTab.entries.forEach { entry -> TabChip(entry.title, selected = entry == tab, onClick = { tab = entry }) }
            }
            LimentyTextButton("Text…", onClick = {
                val into = at ?: steps.size
                put(KeyStep.Literal(""))
                writing = into
            })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tab) {
                KeyTab.Keys, KeyTab.Function -> tab.keys.forEach { key ->
                    val step = KeyStep.Press(key = key, modifiers = held)
                    KeyCap(stepName(step), spokenStep(step), onClick = { press(key, 0) })
                }
                KeyTab.Letters, KeyTab.Symbols -> tab.characters.forEach { character ->
                    val step = KeyStep.Press(codePoint = character.code, modifiers = held)
                    KeyCap(stepName(step), spokenStep(step), onClick = { press(null, character.code) })
                }
            }
        }
    }
}

/** The keyboard's pages. Letters and symbols are characters; the rest are the terminal's named keys. */
private enum class KeyTab(val title: String, val keys: List<TerminalKey> = emptyList(), val characters: List<Char> = emptyList()) {
    Keys(
        "Keys",
        keys = listOf(
            TerminalKey.Escape, TerminalKey.Tab, TerminalKey.Enter, TerminalKey.Backspace, TerminalKey.Space,
            TerminalKey.Insert, TerminalKey.Delete, TerminalKey.Home, TerminalKey.End, TerminalKey.PageUp,
            TerminalKey.PageDown, TerminalKey.ArrowLeft, TerminalKey.ArrowUp, TerminalKey.ArrowDown, TerminalKey.ArrowRight,
        ),
    ),
    Letters("abc", characters = ('a'..'z').toList()),
    Symbols("123 #", characters = ('0'..'9').toList() + "`-=[]\\;',./~!@#$%^&*()_+{}|:\"<>?".toList()),
    Function("F1–12", keys = TerminalKey.entries.filter { it.name.matches(Regex("F\\d+")) }),
}

/**
 * What the field offers for [typed]: the character itself when one character was typed, then every
 * named key one of whose names starts with it — `pgd` for PgDn, `f1` for F1 and F10 to F12, `esc`
 * for Escape. A key whose name matches outright comes first.
 */
internal fun keySuggestions(typed: String): List<KeyStep.Press> {
    if (typed.isEmpty()) return emptyList()
    val single = typed.codePointCount(0, typed.length) == 1
    val query = typed.trim().lowercase().replace(" ", "")
    val named = if (query.isEmpty()) {
        emptyList()
    } else {
        TerminalKey.entries
            .mapNotNull { key ->
                val names = namesOf(key)
                when {
                    query in names -> key to 0
                    names.any { it.startsWith(query) } -> key to 1
                    else -> null
                }
            }
            .sortedBy { it.second }
            .map { KeyStep.Press(key = it.first) }
    }
    val character = if (single && typed.isNotBlank()) listOf(KeyStep.Press(codePoint = typed.codePointAt(0))) else emptyList()
    return character + named
}

/** Every name a key answers to in the field: its own, its face, how it is said, and the usual short forms. */
private fun namesOf(key: TerminalKey): Set<String> = buildSet {
    add(key.name.lowercase())
    add(AuxiliaryKey.labelFor(key, 0, Modifiers.None).lowercase())
    add(AuxiliaryKey.spokenFor(key, 0, Modifiers.None).lowercase().replace(" ", ""))
    addAll(ALIASES[key].orEmpty())
}

private val ALIASES = mapOf(
    TerminalKey.Enter to listOf("return", "ret", "cr"),
    TerminalKey.Backspace to listOf("bs", "bksp"),
    TerminalKey.Delete to listOf("del"),
    TerminalKey.Space to listOf("spc"),
    TerminalKey.PageUp to listOf("pgup"),
    TerminalKey.PageDown to listOf("pgdn"),
    TerminalKey.ArrowUp to listOf("up"),
    TerminalKey.ArrowDown to listOf("down"),
    TerminalKey.ArrowLeft to listOf("left"),
    TerminalKey.ArrowRight to listOf("right"),
)

/**
 * Every step, not just the selected one: a step left unconfigured sends nothing, and a sequence
 * with a gap in the middle is not what anyone meant.
 */
internal fun sendsEveryStep(steps: List<KeyStep>): Boolean = steps.isNotEmpty() && steps.all { it.toEvent() != null }

/** Text with nothing in it yet still needs a chip to be selected by. */
private fun stepName(step: KeyStep): String = AuxiliaryKey.labelFor(listOf(step)).ifEmpty { "—" }

/** The chip shows `⌃s`; a screen reader should hear `Ctrl s`. */
private fun spokenStep(step: KeyStep): String = AuxiliaryKey.spokenFor(listOf(step)).ifEmpty { if (step is KeyStep.Literal) "Empty text" else "—" }

/** One step, in the order it is sent. The selected one is what the next key pressed replaces. */
@Composable
private fun StepChip(
    name: String,
    spoken: String,
    selected: Boolean,
    lifted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .then(modifier)
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .widthIn(max = 200.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface2)
            .selectedRing(selected || lifted)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            color = if (selected) LimentyColors.Accent else LimentyColors.Text,
            style = AuxiliaryKeyBarMetrics.labelStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeleteStep(which: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .defaultMinSize(minWidth = LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Delete $which step" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌫", style = AuxiliaryKeyBarMetrics.labelStyle(), color = if (enabled) LimentyColors.Text2 else LimentyColors.Text3)
    }
}

/** A key on the editor's keyboard, drawn as a key. */
@Composable
private fun KeyCap(label: String, spoken: String, onClick: () -> Unit, ringed: Boolean = false) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface4)
            .selectedRing(ringed)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = LimentyColors.Text, style = AuxiliaryKeyBarMetrics.labelStyle(), maxLines = 1)
    }
}

/**
 * A modifier held for the next key and let go after it, the way a phone's shift key works: pressed
 * first, so the key it goes with is chosen the same way whether it is held or not.
 */
@Composable
private fun HeldModifier(label: String, bit: Int, current: Modifiers, onChange: (Modifiers) -> Unit) {
    val held = current.bits and bit != 0
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .defaultMinSize(minWidth = 64.dp)
            .clip(LimentyShapes.Surface)
            .background(if (held) LimentyColors.AccentSelection else LimentyColors.Surface4)
            .selectedRing(held)
            .toggleable(value = held, role = Role.Checkbox, onValueChange = { onChange(Modifiers(current.bits xor bit)) })
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (held) LimentyColors.Accent else LimentyColors.Text2, style = AuxiliaryKeyBarMetrics.labelStyle())
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .defaultMinSize(minWidth = LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .background(if (selected) LimentyColors.Surface5 else LimentyColors.Surface3)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LimentyText.Label, color = if (selected) LimentyColors.Text else LimentyColors.Text2, maxLines = 1)
    }
}
