package dev.wadackel.limenty.ui.terminal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.ui.settings.fitReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    @Test
    fun `the note reads how much of the cover a run leaves`() {
        val run = metrics.pinnedRun(List(4) { metrics.keyWidth("←", textWidth("←")) })
        assertEquals("pinned 164dp · scroll 251dp of 460dp", fitReading(run, metrics.scrollRoom(metrics.coverWidth, run)))
    }

    @Test
    fun `a run wider than the cover reads as no room rather than a negative width`() {
        assertEquals("pinned 505dp · no room to scroll in 460dp", fitReading(505.dp, metrics.scrollRoom(metrics.coverWidth, 505.dp)))
    }

    /** Compared after rounding, so `scroll 0dp` never reaches the screen. */
    @Test
    fun `a room that rounds to nothing reads as no room`() {
        assertEquals("pinned 415dp · no room to scroll in 460dp", fitReading(415.dp, 0.4.dp))
        assertEquals("pinned 415dp · no room to scroll in 460dp", fitReading(415.dp, (-0.4).dp))
        assertEquals("pinned 414dp · scroll 1dp of 460dp", fitReading(414.dp, 1.dp))
    }
}

/** How the name of the set just switched to fits the width of the bar it floats above. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w475dp-h900dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuxiliaryKeyBarSetNameLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var index by mutableIntStateOf(0)

    /** Switches to the second set, named [name], and stops the clock while its name is showing. */
    private fun switchTo(name: String) {
        compose.setContent {
            Column(modifier = Modifier.width(BAR_WIDTH)) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp))
                AuxiliaryKeyBar(
                    keys = AuxiliaryKey.DefaultScrolling,
                    pinnedKeys = AuxiliaryKey.DefaultPinned,
                    onKeyPressed = {},
                    switcher = KeySetSwitcher(index = index, count = 2, name = name, onSwitch = {}),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        index = 1
        // Without this the switch is composed only at the end of the jump below, so the name's
        // timer starts there and the name is not yet drawn when the test looks for it.
        compose.waitForIdle()
        // Past the slide, well short of the name fading out.
        compose.mainClock.advanceTimeBy(300)
    }

    @Test
    fun `a long set name ends in an ellipsis inside the bar's edges rather than being clipped at them`() {
        val name = "A-very-long-key-set-name-that-has-no-spaces-and-goes-on-for-a-long-while-until-the-screen-is-exceeded-1234567890"
        switchTo(name)

        val layout = layoutOf(name)
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.isLineEllipsized(0)).isTrue()
        // The node is the text alone; the pill drawn behind it reaches its padding further out.
        val bounds = compose.onNodeWithText(name).getBoundsInRoot()
        assertThat(bounds.left - PILL_PADDING).isAtLeast(AuxiliaryKeyBarMetrics.edgePadding)
        assertThat(bounds.right + PILL_PADDING).isAtMost(BAR_WIDTH - AuxiliaryKeyBarMetrics.edgePadding)
    }

    @Test
    fun `a short set name is shown whole in the middle of the bar`() {
        switchTo("Vim")

        assertThat(layoutOf("Vim").isLineEllipsized(0)).isFalse()
        val bounds = compose.onNodeWithText("Vim").getBoundsInRoot()
        assertThat((bounds.left + bounds.right).value / 2).isWithin(1f).of(BAR_WIDTH.value / 2)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }
}

private val BAR_WIDTH = 460.dp
private val PILL_PADDING = 12.dp
