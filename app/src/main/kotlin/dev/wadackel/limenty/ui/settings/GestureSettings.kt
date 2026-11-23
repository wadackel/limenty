package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.components.SwitchRow
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText

/** Gestures that take the same hand, under one heading, each with the name it has under it. */
internal data class GestureGroup(val title: String, val gestures: List<Pair<Gesture, String>>)

/**
 * The gestures by the hand they take.
 *
 * Spelled out rather than derived: [Gesture] says nothing about how many fingers it takes, and
 * its display names carry the count (`Two-finger swipe up`), which under a `Two fingers` heading
 * says it twice. A test holds this to the enum, so a gesture added there cannot go missing here.
 */
internal val GestureGroups: List<GestureGroup> = listOf(
    GestureGroup(
        "One finger",
        listOf(
            Gesture.Tap to "Tap",
            Gesture.DoubleTap to "Double tap",
            Gesture.LongPress to "Long press",
            Gesture.SwipeUp to "Swipe up",
            Gesture.SwipeDown to "Swipe down",
            Gesture.SwipeLeft to "Swipe left",
            Gesture.SwipeRight to "Swipe right",
        ),
    ),
    GestureGroup(
        "Two fingers",
        listOf(
            Gesture.TwoFingerTap to "Tap",
            Gesture.TwoFingerSwipeUp to "Swipe up",
            Gesture.TwoFingerSwipeDown to "Swipe down",
            Gesture.TwoFingerSwipeLeft to "Swipe left",
            Gesture.TwoFingerSwipeRight to "Swipe right",
        ),
    ),
    GestureGroup("Pinch", listOf(Gesture.PinchIn to "Pinch in", Gesture.PinchOut to "Pinch out")),
)

/**
 * Assigns behaviour to each recognised gesture.
 *
 * The gesture list is fixed and the behaviour list is a picker, so a binding cannot name something
 * the app is unable to detect or unable to do. Keys are the exception to a fixed list: besides the
 * presets, a gesture can send any keys built the way a key bar button's are, because a list of
 * keys only ever grew one missing key at a time.
 *
 * [sideBySide] puts one finger in a column of its own and the rest beside it, which is where the
 * unfolded display's pane has the room; one column there leaves half the pane empty beside a list
 * that runs off its bottom.
 */
@Composable
fun GestureSettings(
    bindings: GestureBindings,
    onChange: (GestureBindings) -> Unit,
    doubleTapDragZoom: Boolean,
    onDoubleTapDragZoomChange: (Boolean) -> Unit,
    sideBySide: Boolean = false,
) {
    var editing by remember { mutableStateOf<Gesture?>(null) }
    var composing by remember { mutableStateOf<Gesture?>(null) }
    val (first, rest) = GestureGroups.first() to GestureGroups.drop(1)

    if (sideBySide) {
        val frame = LocalSettingsFrame.current
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = frame.margin),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The row carries the margin, so the groups inside it start on its edges.
            CompositionLocalProvider(LocalSettingsFrame provides frame.copy(margin = 0.dp)) {
                Column(modifier = Modifier.weight(1f)) { Group(first, bindings) { editing = it } }
                Column(modifier = Modifier.weight(1f)) {
                    rest.forEach { group -> Group(group, bindings) { editing = it } }
                    PinchAlternative(doubleTapDragZoom, onDoubleTapDragZoomChange)
                }
            }
        }
    } else {
        GestureGroups.forEach { group -> Group(group, bindings) { editing = it } }
        PinchAlternative(doubleTapDragZoom, onDoubleTapDragZoomChange)
    }

    composing?.let { gesture ->
        GestureKeysDialog(
            gesture = gesture,
            initial = (bindings[gesture] as? GestureBehaviour.SendKeys)?.steps.orEmpty(),
            // Back to the picker rather than out of both, so a mistaken tap on Custom keys… costs
            // one step back instead of the whole way in again.
            onDismiss = { composing = null },
            onSave = { steps ->
                onChange(bindings.with(gesture, GestureBehaviour.SendKeys(steps)))
                composing = null
                editing = null
            },
        )
    }

    editing?.takeIf { composing == null }?.let { gesture ->
        val current = bindings[gesture]
        LimentyDialog(title = gesture.displayName, onDismissRequest = { editing = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CustomKeysRow(current = current, onClick = { composing = gesture })
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(LimentyColors.Line))
                GestureBehaviour.Assignable.forEach { candidate ->
                    // By what it does rather than how it was picked: keys built by hand that match
                    // a preset are that preset, so the same binding never shows up two ways.
                    val chosen = candidate == current
                    Text(
                        text = candidate.label(),
                        style = LimentyText.Body,
                        color = if (chosen) LimentyColors.Accent else LimentyColors.Text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LimentyShapes.Surface)
                            .selectable(
                                selected = chosen,
                                role = Role.RadioButton,
                                onClick = {
                                    onChange(bindings.with(gesture, candidate))
                                    editing = null
                                },
                            )
                            .semantics { contentDescription = candidate.spoken() }
                            // Aligned with the gesture it is a choice for, not inset from it.
                            .padding(vertical = 14.dp),
                    )
                }
            }
            LimentyDialogActions {
                LimentyTextButton("Close", { editing = null }, color = LimentyColors.Text2)
            }
        }
    }
}

/**
 * The way into building keys, first in the picker so it is found without scrolling past twenty
 * presets. It reads as chosen, with what it sends, when the binding is keys no preset matches.
 */
@Composable
private fun CustomKeysRow(current: GestureBehaviour, onClick: () -> Unit) {
    val custom = (current as? GestureBehaviour.SendKeys)?.takeIf { it !in GestureBehaviour.Assignable }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .selectable(selected = custom != null, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Custom keys…",
                style = LimentyText.Body,
                color = if (custom != null) LimentyColors.Accent else LimentyColors.Text,
            )
            Text("Keys, characters and text, sent in order", style = LimentyText.Secondary, color = LimentyColors.Text3)
        }
        custom?.let {
            Text(
                it.label(),
                style = LimentyText.Secondary,
                color = LimentyColors.Accent,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { contentDescription = it.spoken() },
            )
        }
        LimentyChevron(12.dp, 0f, LimentyColors.Text3)
    }
}

/**
 * Builds the keys a gesture sends, with the key bar's own editor.
 *
 * No label and no placement, which are what a button needs to be drawn on the bar and a gesture
 * has nothing to draw.
 */
@Composable
private fun GestureKeysDialog(
    gesture: Gesture,
    initial: List<KeyStep>,
    onDismiss: () -> Unit,
    onSave: (List<KeyStep>) -> Unit,
) {
    var steps by remember { mutableStateOf(initial.ifEmpty { listOf(KeyStep.Press()) }) }
    LimentyDialog(title = gesture.displayName, onDismissRequest = onDismiss) {
        Text("Custom keys", style = LimentyText.Secondary, color = LimentyColors.Text2)
        KeyStepsEditor(steps = steps, onStepsChange = { steps = it })
        LimentyDialogActions {
            LimentyTextButton("Cancel", onDismiss, color = LimentyColors.Text2)
            LimentyTextButton(label = "Save", onClick = { onSave(steps) }, enabled = sendsEveryStep(steps))
        }
    }
}

/**
 * What the list and the picker call a behaviour.
 *
 * Keys are named by the key bar's rule, so `⌃c` on a gesture and `⌃c` on a button are the same
 * thing written the same way.
 */
internal fun GestureBehaviour.label(): String = when (this) {
    is GestureBehaviour.SendKeys -> AuxiliaryKey.labelFor(steps)
    is GestureBehaviour.None -> "None"
    is GestureBehaviour.ShowKeyboard -> "Show keyboard"
    is GestureBehaviour.HideKeyboard -> "Hide keyboard"
    is GestureBehaviour.ToggleKeyboard -> "Toggle keyboard"
    is GestureBehaviour.Paste -> "Paste"
    is GestureBehaviour.Copy -> "Copy"
    is GestureBehaviour.Scroll -> "Scroll"
    is GestureBehaviour.FontZoom -> "Font zoom"
    is GestureBehaviour.SelectText -> "Select text"
}

/** `⌃c` is read at a glance; a screen reader should say `Ctrl c`. */
private fun GestureBehaviour.spoken(): String = if (this is GestureBehaviour.SendKeys) AuxiliaryKey.spokenFor(steps) else label()

@Composable
private fun Group(group: GestureGroup, bindings: GestureBindings, onEdit: (Gesture) -> Unit) {
    SettingsHeading(group.title)
    SettingsGroup {
        group.gestures.forEach { (gesture, name) ->
            val behaviour = bindings[gesture]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_HEIGHT)
                    .clickable(role = Role.Button) { onEdit(gesture) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = LimentyText.Body, modifier = Modifier.weight(1f))
                // Keys built by hand can run to a whole command, which is cut short at half the row
                // rather than pushing the gesture's name off it.
                Text(
                    behaviour.label(),
                    style = LimentyText.Secondary,
                    color = if (behaviour is GestureBehaviour.None) LimentyColors.Text3 else LimentyColors.Accent,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = behaviour.spoken() },
                )
            }
        }
    }
}

@Composable
private fun PinchAlternative(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsHeading("Pinch alternative")
    SettingsGroup {
        SwitchRow(
            label = "Double-tap and drag to pinch",
            description = null,
            checked = enabled,
            onCheckedChange = onChange,
        )
    }
    val margin = LocalSettingsFrame.current.margin + GROUP_TEXT_INSET
    Text(
        "Tap, then hold the second tap and drag up or down. Does what the pinch gestures do. " +
            "Easy to set off by scrolling just after a tap.",
        style = LimentyText.Secondary,
        color = LimentyColors.Text3,
        modifier = Modifier.padding(start = margin, end = margin, top = 8.dp),
    )
}
