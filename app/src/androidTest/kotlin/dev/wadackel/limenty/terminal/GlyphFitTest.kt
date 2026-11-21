package dev.wadackel.limenty.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalTypography
import org.junit.Test
import org.junit.runner.RunWith
import dev.wadackel.limenty.core.terminal.TerminalFont as TF

/**
 * How far each glyph's natural advance sits from the cell it has to occupy.
 *
 * Measured on a device because the numbers come from the platform's own font resolution. They are
 * the reason [TerminalRenderer] refuses to force a glyph onto the cell along one axis: at the
 * default typography a kanji advances 37px into a 46px pair of cells and a Nerd Font icon advances
 * 39px into a 23px cell, so stretching to fit flattened both.
 */
@RunWith(AndroidJUnit4::class)
class GlyphFitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val typography = TerminalTypography()

    private data class Sample(val name: String, val text: String, val cells: Int)

    private val samples = listOf(
        Sample("ascii M", "M", 1),
        Sample("ascii i", "i", 1),
        Sample("kanji 日", "日", 2),
        Sample("kana あ", "あ", 2),
        Sample("katakana ア", "ア", 2),
        Sample("fullwidth ｎ", "ｎ", 2),
        Sample("punct 。", "。", 2),
        Sample("powerline U+E0B0", "", 1),
        Sample("nerd branch U+E0A0", "", 1),
        Sample("nerd folder U+F07B", "", 1),
        Sample("nerd clock U+F017", "", 1),
    )

    /** Which face actually owns each glyph, and at what advance, independent of the fallback chain. */
    @Test
    fun reportWhichFaceOwnsEachGlyph() {
        val symbolsOnly = Typeface.CustomFallbackBuilder(
            FontFamily.Builder(Font.Builder(context.assets, TF.SYMBOLS_ASSET).build()).build(),
        ).build()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = symbolsOnly
        paint.textSize = 13f * 3f

        for (s in samples) {
            Log.i("GlyphFit", "%-20s inSymbolsFace=%-5s advance=%.2f".format(s.name, paint.hasGlyph(s.text), paint.measureText(s.text)))
        }
    }

    @Test
    fun reportHowFarEachGlyphIsStretched() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = TerminalTypefaces(context).regular(TerminalFont.byId(typography.fontId))
        val metrics = TerminalMetrics.measure(paint, typography, density = 3f)
        paint.textSize = metrics.fontSizePx

        val report = StringBuilder("\ncell=${metrics.cellWidth}x${metrics.cellHeight} baseline=${metrics.baseline}\n")
        val ratios = mutableMapOf<String, Float>()
        for (s in samples) {
            paint.textScaleX = 1f
            paint.letterSpacing = 0f
            val natural = paint.measureText(s.text)
            val target = metrics.cellWidth * s.cells
            val ratio = if (natural > 0.1f) target / natural else Float.NaN
            ratios[s.name] = ratio
            report.append("%-20s natural=%7.2f target=%7.2f scaleX=%.3f\n".format(s.name, natural, target, ratio))
        }
        report.toString().lines().forEach { Log.i("GlyphFit", it) }

        // The cell is defined by the text face, so its own glyphs must land on it exactly.
        assertThat(ratios["ascii M"]).isWithin(0.001f).of(1f)
        assertThat(ratios["ascii i"]).isWithin(0.001f).of(1f)

        // Nothing else does, which is why the renderer scales both axes or neither: the symbols
        // face advances a full em per icon and the CJK fallback is proportional, so forcing either
        // onto the cell with textScaleX alone distorts it.
        assertThat(ratios.getValue("kanji 日")).isGreaterThan(1.1f)
        assertThat(ratios.getValue("nerd folder U+F07B")).isLessThan(0.9f)
    }
}
