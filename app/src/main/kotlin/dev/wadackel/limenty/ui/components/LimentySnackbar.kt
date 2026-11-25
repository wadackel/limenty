package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlinx.coroutines.delay

/**
 * A line that says what was just done, with the way to take it back, for as long as it is likely to
 * be wanted. What makes removing something without asking first safe to offer.
 *
 * [key] restarts the wait, so a second removal gets its own full time rather than what was left of
 * the first one's.
 */
@Composable
fun LimentySnackbar(
    message: String,
    action: String,
    onAction: () -> Unit,
    onTimeout: () -> Unit,
    key: Any,
    modifier: Modifier = Modifier,
) {
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(key) {
        delay(SNACKBAR_MILLIS)
        timeout()
    }
    Row(
        modifier = modifier
            .widthIn(max = LimentyDialogMaxWidth)
            .fillMaxWidth()
            .padding(12.dp)
            .shadow(8.dp, LimentyShapes.Surface)
            .clip(LimentyShapes.Surface)
            .background(LimentyColors.Surface5)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 16.dp - LimentyTextButtonInset)
            // Said as it appears, since the one thing it offers is gone in a few seconds.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = LimentyText.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LimentyTextButton(action, onAction)
    }
}

/** Long enough to read the line and reach for the button; Android's own long duration. */
private const val SNACKBAR_MILLIS = 6_000L
