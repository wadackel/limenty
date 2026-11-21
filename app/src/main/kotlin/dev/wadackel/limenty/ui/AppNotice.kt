package dev.wadackel.limenty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes

/**
 * A failure that has nowhere else to appear, held until it is read.
 *
 * Deleting a host, forgetting its trusted key and re-storing a password after a retry all run
 * away from the screen that started them, and until now each of them failed in silence. It does
 * not fade on a timer: a message about something that did not happen is worth less than nothing
 * if the person it concerns was looking elsewhere for the three seconds it was up.
 */
@Composable
fun AppNotice(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    Text(
        message,
        color = LimentyColors.Text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = modifier
            .fillMaxWidth()
            // The parent Surface hands its children the window's size as their *minimum*, and
            // padding narrows a minimum without removing it. Without this the one line below is
            // measured at full height and paints an opaque panel over the whole app.
            .wrapContentHeight(Alignment.Top)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(LimentyColors.WarnSurface, LimentyShapes.Surface)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
