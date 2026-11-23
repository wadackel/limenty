package dev.wadackel.limenty.ui.settings

import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.ui.terminal.AuxiliaryKeyBarMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

/** The words of the note on the key bar settings screen, for the widths it is given. */
class FitReadingTest {
    private val metrics = AuxiliaryKeyBarMetrics

    /** JetBrains Mono at 13sp, as measured in `AuxiliaryKeyBarMetricsTest`. */
    private val perCharacter = 7.8013.dp
    private val tracking = 0.5.dp
    private fun textWidth(label: String) = perCharacter * label.length + tracking * (label.length - 1)

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
