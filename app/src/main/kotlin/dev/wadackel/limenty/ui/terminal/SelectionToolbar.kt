package dev.wadackel.limenty.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.terminal.SelectionBounds
import dev.wadackel.limenty.terminal.SelectionGeometry
import dev.wadackel.limenty.terminal.ToolbarFit
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes

/**
 * The floating action bar shown over a selection.
 *
 * Copy is the action a selection exists for; Paste is included because the selection is
 * also where a user reaches for it, and Select All is deliberately absent. Open appears only while
 * the selection is a web link, which is what a long press on one selects.
 *
 * It floats over the session rather than sitting in the chrome, so it keeps a shadow where the rest
 * of the chrome has none: the terminal behind it can be any colour a theme makes it, and a bar told
 * apart by lightness alone sinks into some of them. Its corner is a key's rather than an
 * overlay's, since it is a row of buttons rather than a pane.
 *
 * [anchor] is the selection in the terminal view's pixels; the caller places this in a box that
 * covers exactly that view, and the bar sits wherever [SelectionGeometry.toolbarPosition] puts it.
 */
@Composable
fun SelectionToolbar(anchor: SelectionBounds, onOpen: (() -> Unit)?, onCopy: () -> Unit, onPaste: () -> Unit) {
    val shape = LimentyShapes.Surface
    Row(
        modifier = Modifier
            .layout { measurable, constraints ->
                val bar = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val position = SelectionGeometry.toolbarPosition(
                    ToolbarFit(
                        anchor = anchor,
                        width = bar.width,
                        height = bar.height,
                        containerWidth = constraints.maxWidth,
                        containerHeight = constraints.maxHeight,
                        gap = 8.dp.toPx(),
                    ),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { bar.place(position.x, position.y) }
            }
            .shadow(10.dp, shape)
            .clip(shape)
            .background(LimentyColors.Surface3)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onOpen != null) {
            LimentyTextButton("Open", onOpen)
        }
        LimentyTextButton("Copy", onCopy)
        LimentyTextButton("Paste", onPaste)
    }
}
