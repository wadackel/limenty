package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * A panel that rises from the bottom edge, for editing something in place of the screen behind it.
 *
 * Its own window rather than Material's bottom sheet, for two reasons. The stock one decides for
 * itself what a swipe down or a tap outside does, and here both have to ask before throwing an edit
 * away, which is [onTapOutside]'s to decide. And a question it asks — discard these changes? — is
 * drawn inside it through [overlay], so no box is ever raised over another box.
 *
 * As wide as a box at most, so on the unfolded display it sits under the middle of the screen
 * rather than stretching across both halves.
 */
@Composable
fun LimentySheet(
    /** Back, which the caller may take to mean "one page up" rather than "close". */
    onDismissRequest: () -> Unit,
    onTapOutside: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .pointerInput(onTapOutside) { detectTapGestures { onTapOutside() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = LimentyDialogMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight - SHEET_TOP_GAP)
                    .clip(SHEET_SHAPE)
                    .background(LimentyColors.Surface3)
                    // A tap on the sheet's own background is not a tap outside it.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = SHEET_PADDING - LimentyTextButtonInset, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = header,
                    )
                    Column(modifier = Modifier.weight(1f, fill = false), content = content)
                }
                overlay?.invoke(this)
            }
        }
    }
}

/** The sheet's title, between what its header holds at either end. */
@Composable
fun RowScope.LimentySheetTitle(text: String) {
    Text(
        text,
        style = LimentyText.Heading,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * A question the sheet asks about itself, drawn over it rather than in a box of its own: a box
 * raised over the sheet is the stacking the sheet is there to end.
 */
@Composable
fun BoxScope.LimentySheetQuestion(
    title: String,
    body: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(SCRIM)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(LimentySpacing.FloatingPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(LimentyShapes.Floating)
                .background(LimentyColors.Surface4)
                .padding(LimentySpacing.FloatingPadding),
            verticalArrangement = Arrangement.spacedBy(LimentyDialogGap),
        ) {
            Text(title, style = LimentyText.Heading)
            Text(body, style = LimentyText.Body, color = LimentyColors.Text2)
            LimentyDialogActions(actions)
        }
    }
}

/** Between the sheet's content and its sides. */
val LimentySheetPadding = 20.dp

private val SHEET_PADDING = LimentySheetPadding
private val SHEET_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** What the sheet leaves of the screen above it at its tallest, so it still reads as over something. */
private val SHEET_TOP_GAP = 48.dp

private val SCRIM = Color(0x99060608)
