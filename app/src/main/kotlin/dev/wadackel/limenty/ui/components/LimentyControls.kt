package dev.wadackel.limenty.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlin.math.roundToInt

/**
 * A section heading, in the accent, with the current value beside it.
 *
 * The value on the right is what lets the section be read without opening it — `Theme · Nord`
 * answers the question most visits to this screen are asking. Spaced-out monospaced capitals were
 * tried first and read as a terminal's output rather than as a label for the controls under it.
 *
 * The value is cut to one line: a theme's name has no length limit, and a second line wraps round
 * to sit beside the title on the bottom-aligned row, where the two read as one string.
 */
@Composable
fun SectionHeading(title: String, value: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text = title, style = LimentyText.Section)
        if (value != null) {
            Text(
                text = value,
                style = LimentyText.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = LimentySpacing.SmallPadding),
            )
        }
    }
}

/**
 * A value slider.
 *
 * Material's control underneath, with its thumb and track replaced. Hand-rolling the gesture cost
 * a working drag once already — each step was computed from a value captured when the handler was
 * created, so the thumb moved once and stopped — and it costs the arrow keys and the screen reader
 * every time, which a terminal used with a physical keyboard cannot afford.
 *
 * The track stops at the thumb rather than running past it to a dot, which is what made the stock
 * appearance hard to read: there was no telling the filled part from the rest.
 *
 * [steps] counts the stops between the ends, as Material's does; the arrow keys and the screen
 * reader then move a stop at a time. Disabled, it is drawn in the faint text colour throughout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimentySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueSettled: () -> Unit = {},
    unit: String? = null,
    format: (Float) -> String = { it.roundToInt().toString() },
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val mark = if (enabled) LimentyColors.Accent else LimentyColors.Text3
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, style = LimentyText.Body, color = if (enabled) LimentyColors.Text else LimentyColors.Text3)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 15.sp, color = if (enabled) LimentyColors.Text else LimentyColors.Text3)) {
                        append(format(value))
                    }
                    if (unit != null) {
                        withStyle(
                            SpanStyle(fontSize = 11.sp, color = LimentyColors.Text3),
                        ) { append(" $unit") }
                    }
                },
                fontFamily = FontFamily.Monospace,
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueSettled,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(THUMB_HEIGHT),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                        .clip(RoundedCornerShape(2.dp))
                        .background(mark),
                )
            },
            track = { state ->
                val fraction = if (state.valueRange.endInclusive > state.valueRange.start) {
                    (state.value - state.valueRange.start) /
                        (state.valueRange.endInclusive - state.valueRange.start)
                } else {
                    0f
                }
                Box(modifier = Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TRACK_HEIGHT)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LimentyColors.Surface3),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(TRACK_HEIGHT)
                            .clip(RoundedCornerShape(2.dp))
                            .background(mark),
                    )
                }
            },
        )
    }
}

/**
 * A switch row.
 *
 * The description sits below the label at full width rather than beside it — squeezed next to the
 * switch it wrapped under the control it belonged to.
 */
@Composable
fun SwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // The row is the target, not the 44x26dp pill: a switch that small is below the size a
        // finger is entitled to expect, and the label is what people aim at anyway.
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = LimentyText.Body)
            LimentySwitch(checked = checked)
        }
        if (description != null) {
            Text(
                text = description,
                style = LimentyText.Secondary,
                modifier = Modifier.padding(end = 60.dp),
            )
        }
    }
}

/**
 * The switch face. Material's is 52x32dp and cannot be made smaller, and the design asks for
 * 44x26 — so the visual is drawn here while [SwitchRow] owns the interaction and the semantics. Its
 * round track is the one fully rounded shape in the chrome; see `docs/design.md`.
 */
@Composable
fun LimentySwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val offset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "switch thumb")
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) LimentyColors.Accent else LimentyColors.Surface3),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = offset)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) LimentyColors.OnAccent else LimentyColors.Text2),
        )
    }
}

private val THUMB_WIDTH = 10.dp
private val THUMB_HEIGHT = 20.dp
private val TRACK_HEIGHT = 3.dp

/**
 * A glyph with a target around it.
 *
 * The glyphs this interface uses are 12 to 17dp, which is a fine size to look at and a poor one to
 * hit — and a bare `Modifier.clickable` on a `Canvas` or a `Text` reaches a screen reader as an
 * unlabelled clickable thing. This gives both a name and something to aim at.
 */
@Composable
fun LimentyIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A word that acts, optionally with a glyph in front of it.
 *
 * Same reasoning as [LimentyIconButton]: a role, and room to hit it. Every such word in the app
 * goes through here so they are one thing — one height to hit, one corner to press, one inset
 * between the word and the edge of what responds. Built by hand at a call site they drift, and the
 * one that drifted read as a label someone had made tappable rather than as a button.
 */
@Composable
fun LimentyTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LimentyColors.Accent,
    fontSize: TextUnit = LimentyText.Label.fontSize,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = LimentyTextButtonInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            label,
            style = LimentyText.Label,
            color = if (enabled) color else LimentyColors.Text3,
            fontSize = fontSize,
        )
    }
}

/**
 * A word that commits, filled.
 *
 * The rule the interface follows is that a filled control creates or commits and a plain accent
 * word goes to something that already exists, so the two are not interchangeable.
 */
@Composable
fun LimentyPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .heightIn(min = LimentySpacing.TouchTarget)
            .clip(LimentyShapes.Surface)
            .background(if (enabled) LimentyColors.Accent else LimentyColors.Surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LimentyText.Label,
            color = if (enabled) LimentyColors.OnAccent else LimentyColors.Text3,
        )
    }
}

/**
 * The gap [LimentyTextButton] keeps between its word and the edge of what responds.
 *
 * Public because a button at the end of a row has to hang this far past it: what lines up with the
 * screen's margin is the word, not the reach around it, and a button that starts where the margin
 * does puts its word a whole inset further in than everything above and below it.
 */
val LimentyTextButtonInset = 12.dp

/**
 * Takes [amount] off the top and bottom of what the layout sees, without moving the content.
 *
 * For a 44dp target set into a line of text: the line keeps its own height rather than growing to
 * the target's, and the target still answers over the whole 44dp.
 *
 * The content is offered the trimmed height back. Measured within the line's own height instead, a
 * line whose height is fixed (a row sized by its intrinsics) squeezes the target down to the line,
 * and it then answers over the line's height rather than 44dp.
 */
fun Modifier.overhangVertically(amount: Dp) = layout { measurable, constraints ->
    val trim = amount.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = 2 * trim))
    layout(placeable.width, (placeable.height - 2 * trim).coerceAtLeast(0)) {
        placeable.place(0, -trim)
    }
}
