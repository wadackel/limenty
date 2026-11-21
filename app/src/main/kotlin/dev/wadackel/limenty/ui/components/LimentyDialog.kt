package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText

/** What every box leaves clear on each side of it, whoever draws the window around it. */
val LimentyDialogMargin = 20.dp

/**
 * The widest a box grows, however wide the screen behind it.
 *
 * Full width on the unfolded display puts a fingerprint and a sentence on lines wide enough to lose
 * your place in.
 */
val LimentyDialogMaxWidth = 560.dp

/**
 * Everything this app asks in a box of its own.
 *
 * One definition because there are several of them — a key to edit, a host to delete, a gesture to
 * bind, a connection that failed — and built by hand they drifted: each one arranged its contents
 * on a different set of left edges, and the stock dialog they were built from put its title, its
 * body and its buttons on three more.
 *
 * Its width is not the platform's default, which is a share of the screen: on the 475dp cover
 * display that left 78dp empty on each side, and it made these a different width from the
 * terminal's overlays, which are the same box. The margin it is given instead is part of the
 * window's content, so a tap on it no longer dismisses; one beyond it still does.
 */
@Composable
fun LimentyDialog(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LimentyDialogSurface(
            title = title,
            modifier = Modifier.padding(LimentyDialogMargin),
            content = content,
        )
    }
}

/**
 * The box on its own, for the places that supply their own window.
 *
 * The terminal's overlays are drawn over the session rather than raised above it, so they cannot be
 * dialogs, but there is no reason for them to be a different shape.
 */
@Composable
fun LimentyDialogSurface(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = LimentyShapes.Floating
    Column(
        modifier = modifier
            .widthIn(max = LimentyDialogMaxWidth)
            .fillMaxWidth()
            .clip(shape)
            // The nearest surface there is: it floats over the screen, and the fields it holds sit
            // a step darker inside it, as wells rather than as more cards.
            .background(LimentyColors.Surface3)
            .verticalScroll(rememberScrollState())
            .padding(LimentySpacing.FloatingPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = title, style = LimentyText.Heading)
        content()
    }
}

/** The row a box ends on. Trailing, because that is the corner a thumb comes back to. */
@Composable
fun LimentyDialogActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        content()
    }
}
