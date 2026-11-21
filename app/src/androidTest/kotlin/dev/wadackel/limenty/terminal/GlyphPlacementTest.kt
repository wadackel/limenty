package dev.wadackel.limenty.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalTypography
import dev.wadackel.limenty.core.transport.ConnectionConfig
import dev.wadackel.limenty.core.transport.ConnectionState
import dev.wadackel.limenty.core.transport.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Where glyphs that the text face does not own land inside their cell.
 *
 * Drawn through the real typeface chain rather than the platform monospace the other rendering
 * tests use: the placement being checked only exists once a glyph resolves to the bundled symbols
 * face, whose one-em advance is what forces the renderer to shrink it.
 */
@RunWith(AndroidJUnit4::class)
class GlyphPlacementTest {
    private val e = "\u001B"
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theTerminalDrawsWithTheBundledFaceRatherThanThePlatformMonospace() {
        // TerminalTypefaces falls back to the platform monospace when an asset fails to load, and
        // every placement below would then be measuring the wrong font without failing.
        assertThat(TerminalTypefaces(context).regular(TerminalFont.Default)).isNotSameInstanceAs(Typeface.MONOSPACE)
    }

    @Test
    fun aRoundedSeparatorReachesTheTopAndBottomOfItsCell() {
        val target = view()
        write(target, "$e[38;2;255;0;0m\uE0B6")
        val bitmap = render(target)

        val metrics = target.metrics
        val x = metrics.cellWidth.toInt() - 1
        assertThat(near(bitmap.getPixel(x, 1), RED)).isTrue()
        assertThat(near(bitmap.getPixel(x, metrics.cellHeight.toInt() - 2), RED)).isTrue()
    }

    @Test
    fun aRoundedSeparatorMeetsTheSegmentBesideItWithoutASeam() {
        // At the default typography the advance is a whole number of pixels and every boundary is
        // on one; letter spacing is what puts a boundary inside a pixel. Past the middle of that
        // pixel is where the neighbour's unsmoothed background leaves it uncovered.
        val target = view(TerminalTypography(letterSpacingSp = 0.25f))
        val metrics = target.metrics
        val column = (0 until 30).first { c ->
            val fraction = ((c + 1) * metrics.cellWidth) % 1f
            fraction in 0.55f..0.85f
        }
        write(target, "$e[1;${column + 1}H$e[38;2;255;0;0m\uE0B6$e[48;2;255;0;0m  $e[0m")
        val bitmap = render(target)

        val boundary = ((column + 1) * metrics.cellWidth).toInt()
        for (y in 0 until metrics.cellHeight.toInt()) {
            for (x in boundary - 1..boundary) {
                assertWithMessage("pixel ($x, $y) at the boundary after column $column")
                    .that(near(bitmap.getPixel(x, y), RED)).isTrue()
            }
        }
    }

    @Test
    fun aShrunkIconSitsInTheVerticalMiddleOfItsCell() {
        val target = view()
        // The folder's ink is symmetric about the symbols face's own line, so where its middle
        // lands is where the renderer put that line, not a quirk of this one outline.
        write(target, "$e[38;2;255;0;0m\uF07B")
        val bitmap = render(target)
        val metrics = target.metrics
        val background = target.palette.background

        val inked = (0 until metrics.cellHeight.toInt()).filter { y ->
            (0 until metrics.cellWidth.toInt()).any { x -> !near(bitmap.getPixel(x, y), background) }
        }
        val middle = (inked.first() + inked.last()) / 2f

        assertThat(middle).isWithin(metrics.cellHeight * 0.06f).of(metrics.cellHeight / 2f)
    }

    @Test
    fun anArrowStaysTwoCharactersRatherThanBecomingALigature() {
        val target = view()
        write(target, "$e[38;2;255;0;0m->")
        val together = inkOfFirstCells(target, cells = 2)

        // A colour change splits the run, so these two reach the canvas as separate glyphs that
        // no font feature can join; they are what the pair looks like unshaped.
        write(target, "$e[2J$e[H$e[38;2;255;0;0m-$e[38;2;0;255;0m>")
        val apart = inkOfFirstCells(target, cells = 2)

        val differing = together.indices.count { together[it] != apart[it] }
        assertThat(differing).isAtMost(together.size / 50)
    }

    /** Which pixels of the first [cells] cells of row 0 differ from the background. */
    private fun inkOfFirstCells(target: TerminalView, cells: Int): BooleanArray {
        val bitmap = render(target)
        val metrics = target.metrics
        val width = (metrics.cellWidth * cells).toInt()
        val height = metrics.cellHeight.toInt()
        val background = target.palette.background
        return BooleanArray(width * height) { i -> !near(bitmap.getPixel(i % width, i / width), background) }
    }

    private fun view(typography: TerminalTypography = TerminalTypography()): TerminalView {
        lateinit var created: TerminalView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val typefaces = TerminalTypefaces(context)
            created = TerminalView(context).apply {
                session = offlineSession()
                setTypefaces(typefaces.regular(TerminalFont.Default), typefaces.bold(TerminalFont.Default))
                refreshTypography(typography)
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return created
    }

    private fun render(target: TerminalView): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            target.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun write(target: TerminalView, text: String) {
        val session = requireNotNull(target.session)
        synchronized(session.lock) { session.emulator.write(text) }
    }

    /** Within [TOLERANCE] on every channel: anti-aliasing and colour space rounding move a few steps. */
    private fun near(pixel: Int, colour: Int): Boolean = abs(Color.red(pixel) - Color.red(colour)) <= TOLERANCE &&
        abs(Color.green(pixel) - Color.green(colour)) <= TOLERANCE &&
        abs(Color.blue(pixel) - Color.blue(colour)) <= TOLERANCE

    private fun offlineSession(): TerminalSession = TerminalSession(
        id = SessionId("glyph-placement-test"),
        hostId = "test",
        hostName = "test",
        transport = NullTransport(),
        columns = 40,
        rows = 12,
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private class NullTransport : TerminalTransport {
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val output: Flow<ByteArray> = emptyFlow()
        override suspend fun connect(config: ConnectionConfig) = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
        override suspend fun disconnect() = Unit
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 600
        const val TOLERANCE = 40
        val RED = Color.rgb(255, 0, 0)
    }
}
