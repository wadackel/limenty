package dev.wadackel.limenty.terminal

import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.TerminalTypography
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The renderer and the measurement share one [Paint], so measuring has to state the state it
 * depends on. A pinch zoom re-measures through a paint the renderer last used, and inheriting the
 * spacing from it would widen every cell a little more on each gesture.
 */
@RunWith(AndroidJUnit4::class)
class TerminalMetricsTest {
    private val typography = TerminalTypography(letterSpacingSp = 1.5f)

    @Test
    fun remeasuringThroughAUsedPaintGivesTheSameCell() {
        val paint = Paint()
        val first = TerminalMetrics.measure(paint, typography, density = 3f)

        // What the renderer leaves behind after drawing a frame.
        paint.letterSpacing = first.letterSpacingPx / first.fontSizePx
        paint.textScaleX = 1.4f

        val second = TerminalMetrics.measure(paint, typography, density = 3f)

        assertThat(second.cellWidth).isEqualTo(first.cellWidth)
        assertThat(second.cellHeight).isEqualTo(first.cellHeight)
    }

    @Test
    fun theCellIsWiderThanTheGlyphByExactlyTheRequestedSpacing() {
        val paint = Paint()
        val spaced = TerminalMetrics.measure(paint, typography, density = 3f)
        val unspaced = TerminalMetrics.measure(Paint(), typography.copy(letterSpacingSp = 0f), density = 3f)

        assertThat(spaced.cellWidth - unspaced.cellWidth).isWithin(0.01f).of(spaced.letterSpacingPx)
    }
}
