package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.ui.terminal.DisconnectedOverlay
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How wide the box is that the app asks its questions in.
 *
 * The window is measured from a box behind the dialog rather than from the root, because an open
 * dialog is a second window and a second root. The box itself is the parent of its title: the
 * scrolling column the title sits in is the whole surface.
 */
@RunWith(AndroidJUnit4::class)
class LimentyDialogWidthTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDialogLeavesTheMarginOnEachSideUpToTheWidestBoxAllowed() {
        compose.setContent {
            LimentyTheme {
                Box(modifier = Modifier.fillMaxSize().testTag(WINDOW))
                LimentyDialog(title = "Rename", onDismissRequest = {}) {
                    Text("Body")
                }
            }
        }

        assertThat(boxWidth("Rename")).isWithin(TOLERANCE).of(expectedWidth())
    }

    @Test
    fun anOverlayOverTheTerminalIsTheSameWidthAsADialog() {
        compose.setContent {
            LimentyTheme {
                Box(modifier = Modifier.fillMaxSize().testTag(WINDOW)) {
                    DisconnectedOverlay(hostName = "example", onReconnect = {}, onBack = {})
                }
            }
        }

        assertThat(boxWidth("Connection closed")).isWithin(TOLERANCE).of(expectedWidth())
    }

    private fun boxWidth(title: String): Float = compose.onNodeWithText(title).onParent().getBoundsInRoot().widthValue()

    private fun expectedWidth(): Float {
        val window = compose.onNodeWithTag(WINDOW).getBoundsInRoot().widthValue()
        return minOf(window - 2 * LimentyDialogMargin.value, LimentyDialogMaxWidth.value)
    }

    private fun DpRect.widthValue(): Float = (right - left).value

    private companion object {
        const val WINDOW = "window"

        // Bounds come back in dp converted from whole pixels, so an exact match would fail on
        // rounding alone at densities like 2.625.
        const val TOLERANCE = 1f
    }
}
