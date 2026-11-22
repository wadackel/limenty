package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlinx.coroutines.launch

/**
 * A text field.
 *
 * The label sits above the value inside the same surface rather than floating over the border, so
 * a field reads as one object and the four of them on a form do not look like four identical
 * boxes. A field is a well a step darker than what holds it, with no border at rest; focus draws the
 * accent around it and turns the label, the same ring a selected picker option wears. A filled
 * background on focus would compete with the value being typed.
 *
 * The surface, the label and [error] are drawn in the text field's decoration box rather than
 * around it. While the keyboard comes up, a scrolling screen keeps only the focused node in view,
 * and that node is the decoration box: drawn outside it, they were left under the keyboard.
 */
@Composable
fun LimentyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    /** Why the value is not accepted, said under the field. Null while there is nothing to say. */
    error: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = LimentyShapes.Surface
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(LimentyColors.Accent),
        textStyle = TextStyle(
            color = LimentyColors.Text,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            // An error appearing under the field being typed into changes no viewport, so nothing
            // else scrolls it out from under the keyboard.
            .onSizeChanged { if (focused) scope.launch { requester.bringIntoView() } },
    ) { innerTextField ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(LimentyColors.Surface2)
                    .then(if (focused) Modifier.border(1.5.dp, LimentyColors.Accent, shape) else Modifier)
                    .padding(horizontal = LimentySpacing.SurfacePadding, vertical = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = label,
                        style = LimentyText.Secondary,
                        color = if (focused) LimentyColors.Accent else LimentyColors.Text2,
                    )
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                color = LimentyColors.Text3,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        innerTextField()
                    }
                }
                if (trailing != null) {
                    Box(modifier = Modifier.padding(start = 12.dp)) { trailing() }
                }
            }
            if (error != null) {
                Text(error, style = LimentyText.Secondary, color = LimentyColors.Danger)
            }
        }
    }
}

/** A password field, with the reveal beside it rather than as an icon inside the value. */
@Composable
fun PasswordField(
    value: String,
    revealed: Boolean,
    onValueChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LimentyField(
        label = "Password",
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        // Fixed here rather than passed in, so no caller can leave it out: without the password
        // type a Japanese keyboard converts the letters into kana and the password then fails.
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation('•'),
        trailing = {
            Text(
                text = if (revealed) "Hide" else "Show",
                style = LimentyText.Label,
                modifier = Modifier.clickableText(onToggleReveal),
            )
        },
    )
}

private fun Modifier.clickableText(onClick: () -> Unit) = this.clickable(onClick = onClick)
