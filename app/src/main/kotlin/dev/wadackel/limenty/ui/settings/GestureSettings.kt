package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
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
 * the app is unable to detect or unable to do.
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

    editing?.let { gesture ->
        LimentyDialog(title = gesture.displayName, onDismissRequest = { editing = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GestureBehaviour.Assignable.forEach { candidate ->
                    val chosen = candidate.id == bindings[gesture].id
                    Text(
                        text = candidate.displayName,
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
                Text(
                    behaviour.displayName,
                    style = LimentyText.Secondary,
                    color = if (behaviour is GestureBehaviour.None) LimentyColors.Text3 else LimentyColors.Accent,
                    maxLines = 1,
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
