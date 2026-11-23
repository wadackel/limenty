package dev.wadackel.limenty.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Lays [content] out [width] wide, and [height] tall when one is given, on a display of any size.
 *
 * `Modifier.width` alone is clamped to the window, so on a phone-sized emulator the unfolded width
 * comes out under the wide-layout breakpoint and a two-pane capture draws one pane. `ForcedSize`
 * lowers the density until the size fits, but makes the content at least that big rather than
 * exactly, so the box inside cuts it back down. Without a height it is the display's, since a
 * zero one leaves nothing to capture.
 */
@Composable
fun AtSize(width: Dp, height: Dp? = null, content: @Composable () -> Unit) {
    val forced = DpSize(width, height ?: LocalConfiguration.current.screenHeightDp.dp)
    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(forced)) {
        val box = Modifier.wrapContentSize(Alignment.TopStart).width(width)
        Box(if (height == null) box else box.height(height)) { content() }
    }
}
