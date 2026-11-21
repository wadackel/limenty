package dev.wadackel.limenty.ui.terminal

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic behind the note on the key bar settings screen.
 *
 * That note is the only place a user is told that pinning one more key leaves the cover display
 * nothing to scroll in, so the numbers behind it are worth pinning down here rather than reading
 * off a screenshot.
 */
class AuxiliaryKeyBarMetricsTest {
    private val metrics = AuxiliaryKeyBarMetrics

    /**
     * JetBrains Mono at 13sp is 7.8013dp a character, with 0.5dp of tracking between characters.
     *
     * Between, not after: tracking the last character too overstates a four-character key by 0.7dp.
     * What the screen finally shows can still be a dp under this, because a measured width is a
     * whole number of pixels and there are only 2.6 of those to a dp. The bundled face is
     * monospaced across the symbols too, so a `⌃` counts the same as a `c`.
     */
    private val perCharacter = 7.8013.dp
    private val tracking = 0.5.dp
    private fun textWidth(label: String) = perCharacter * label.length + tracking * (label.length - 1)

    private fun assertDp(expected: Dp, actual: Dp) = assertTrue("expected ${expected.value}dp, was ${actual.value}dp", abs(expected.value - actual.value) < 0.01f)

    @Test
    fun `a one character label is square whatever it measured`() {
        assertEquals(metrics.keyHeight, metrics.keyWidth("←", textWidth("←")))
        assertEquals(metrics.keyHeight, metrics.keyWidth("↑", textWidth("↑")))
        assertEquals(metrics.keyHeight, metrics.keyWidth("⌫", textWidth("⌫")))
    }

    @Test
    fun `a longer label takes its own width plus the padding`() {
        assertDp(44.4039.dp, metrics.keyWidth("Esc", textWidth("Esc")))
        assertDp(52.7052.dp, metrics.keyWidth("PgDn", textWidth("PgDn")))
        assertDp(36.1026.dp, metrics.keyWidth("⌃c", textWidth("⌃c")))
    }

    @Test
    fun `a label narrower than the floor still gets the floor`() {
        assertEquals(metrics.keyMinWidth, metrics.keyWidth("F1", 2.dp))
    }

    @Test
    fun `an empty run claims nothing, and a gap needs two keys to sit between`() {
        assertEquals(0.dp, metrics.pinnedRun(emptyList()))
        assertEquals(32.dp, metrics.pinnedRun(listOf(32.dp)))
        assertEquals(76.dp, metrics.pinnedRun(listOf(32.dp, 32.dp)))
    }

    @Test
    fun `four arrows leave the cover display room to scroll in`() {
        val run = metrics.pinnedRun(List(4) { metrics.keyWidth("←", textWidth("←")) })
        assertDp(164.dp, run)
        assertDp(251.dp, metrics.scrollRoom(metrics.coverWidth, run))
    }

    /** The case the note exists to catch: four word-labelled keys is a legal thing to pin. */
    @Test
    fun `four word labelled keys leave it too little`() {
        val run = metrics.pinnedRun(List(4) { metrics.keyWidth("PgDn", textWidth("PgDn")) })
        assertDp(246.8208.dp, run)

        val room = metrics.scrollRoom(metrics.coverWidth, run)
        assertDp(168.1792.dp, room)
        assertTrue("${room.value}dp should read as too tight", room < metrics.minScrollRoom)

        // Rounded rather than truncated: the note would otherwise read 246 for a run of 246.82.
        assertEquals(247, run.value.roundToInt())
    }

    /** With nothing pinned there is no divider, so its block is not taken out of the row. */
    @Test
    fun `an empty right hand side is not charged for the divider`() {
        assertDp(436.dp, metrics.scrollRoom(metrics.coverWidth, 0.dp))
        // One pinned dp brings the divider back: 460 − 24 − 21 − 1.
        assertDp(414.dp, metrics.scrollRoom(metrics.coverWidth, 1.dp))
    }
}
