package dev.wadackel.limenty.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How much of the screen the notice takes.
 *
 * It is drawn as a sibling of the screens rather than inside one, and its parent is a `Surface`
 * that hands its children the window's size as their *minimum*. A composable that does not cut
 * that minimum off is measured at full height, so a one-line message would paint an opaque panel
 * over the whole app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNoticeLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aOneLineNoticeStaysAtTheTopInsteadOfCoveringTheScreen() {
        compose.setContent {
            LimentyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNotice(message = "Could not delete this host", onDismiss = {})
                }
            }
        }

        val rootBounds = compose.onRoot().getBoundsInRoot()
        val noticeBounds = compose.onNodeWithText("Could not delete this host").getBoundsInRoot()
        val rootHeight = (rootBounds.bottom - rootBounds.top).value
        val noticeHeight = (noticeBounds.bottom - noticeBounds.top).value

        assertThat(noticeHeight.toDouble()).isLessThan((rootHeight / 4).toDouble())
    }
}
