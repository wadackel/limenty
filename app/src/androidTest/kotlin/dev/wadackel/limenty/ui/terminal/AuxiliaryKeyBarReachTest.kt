package dev.wadackel.limenty.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Where on the bar a tap lands, measured by tapping.
 *
 * The positions are written out in dp rather than worked out from [AuxiliaryKeyBarMetrics.keyGap]:
 * a test that derives its expectations from the gap moves with the gap, and would pass whatever the
 * gap were. Compose's own touch-target expansion is left at its default, because that is what the
 * app runs with and it already carries a 32dp key most of the way across a narrow gap.
 */
class AuxiliaryKeyBarReachTest {
    @get:Rule
    val rule = createComposeRule()

    private var pressed: String? = null

    private fun show(keys: List<AuxiliaryKey>) {
        rule.setContent {
            Box(modifier = Modifier.width(460.dp)) {
                AuxiliaryKeyBar(keys = keys, pinnedKeys = emptyList(), onKeyPressed = { pressed = it.label })
            }
        }
    }

    private fun tapAt(x: Dp): String? {
        pressed = null
        val pxPerDp = rule.density.density
        rule.onRoot().performTouchInput {
            click(Offset(x.value * pxPerDp, AuxiliaryKeyBarMetrics.rowHeight.value / 2 * pxPerDp))
        }
        rule.waitForIdle()
        return pressed
    }

    private val singles = listOf(
        AuxiliaryKey("|", codePoint = '|'.code),
        AuxiliaryKey("~", codePoint = '~'.code),
        AuxiliaryKey("-", codePoint = '-'.code),
    )

    /** `|` is drawn from 12dp to 44dp. With a 6dp gap, 49dp is nearer the next key's face. */
    @Test
    fun `a tap just past the face of a key still goes to that key rather than its neighbour`() {
        show(singles)
        assertEquals("|", tapAt(49.dp))
    }

    /**
     * Measured on the middle key, because an end key is widened by the bar's edge padding as well
     * and would read wide whatever the gap between keys.
     */
    @Test
    fun `a key between two others can be hit across the full distance to its neighbours`() {
        show(singles)
        val reach = (0..140).count { x -> tapAt(x.dp + 0.5.dp) == "~" }
        assertTrue("`~` answers across ${reach}dp", reach >= 43)
    }

    /**
     * Keys wider than 48dp get nothing from Compose's touch-target expansion, so between two of
     * them only the bar itself decides whether a tap in the gap lands anywhere.
     */
    @Test
    fun `a tap between two word keys lands on one of them`() {
        var faceEnd = 0.dp
        rule.setContent {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val metrics = AuxiliaryKeyBarMetrics
            val text = with(density) { measurer.measure("PgUp", metrics.labelStyle()).size.width.toDp() }
            faceEnd = metrics.edgePadding + metrics.keyWidth("PgUp", text)
            Box(modifier = Modifier.width(460.dp)) {
                AuxiliaryKeyBar(
                    keys = listOf(
                        AuxiliaryKey("PgUp", key = TerminalKey.PageUp),
                        AuxiliaryKey("PgDn", key = TerminalKey.PageDown),
                        AuxiliaryKey("Home", key = TerminalKey.Home),
                    ),
                    pinnedKeys = emptyList(),
                    onKeyPressed = { pressed = it.label },
                )
            }
        }
        rule.waitForIdle()
        assertEquals("PgUp", tapAt(faceEnd + 5.dp))
    }
}
