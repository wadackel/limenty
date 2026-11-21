package dev.wadackel.limenty.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.ui.theme.LimentyColors
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The app mark: two rounded arms that meet in a prompt chevron, lit where they overlap.
 *
 * Drawn rather than loaded so it can take a size at each use. The geometry is the launcher
 * foreground's, in its 108-unit canvas, seen through a 58-unit window from (25, 25): the launcher
 * art sits in a canvas that is mostly margin a mask eats, so drawing all 108 units here would shrink
 * the ink to a third of the box.
 */
@Composable
fun LimentyMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / MARK_WINDOW
        val upper = markArm(MARK_UPPER_END, unit)
        val lower = markArm(MARK_LOWER_END, unit)
        drawPath(lower, MarkLowerArm)
        drawPath(upper, LimentyColors.Accent)
        // The lower arm clipped to the upper, as in the launcher art, rather than a third shape
        // that could drift off the intersection when the arms are retuned.
        clipPath(upper) { drawPath(lower, MarkOverlap) }
    }
}

/** One arm: a capsule from [end] to the vertex, in launcher-canvas units scaled by [unit]. */
private fun markArm(end: Offset, unit: Float): Path {
    val dx = MARK_VERTEX.x - end.x
    val dy = MARK_VERTEX.y - end.y
    val half = (hypot(dx, dy) + MARK_ARM_WIDTH) / 2f
    val cx = ((end.x + MARK_VERTEX.x) / 2f - MARK_WINDOW_ORIGIN) * unit
    val cy = ((end.y + MARK_VERTEX.y) / 2f - MARK_WINDOW_ORIGIN) * unit
    val r = MARK_ARM_WIDTH / 2f * unit
    return Path().apply {
        addRoundRect(RoundRect(Rect(cx - half * unit, cy - r, cx + half * unit, cy + r), CornerRadius(r)))
        transform(
            Matrix().apply {
                translate(cx, cy)
                rotateZ(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                translate(-cx, -cy)
            },
        )
    }
}

// The launcher foreground's construction; `ic_launcher_foreground.xml` explains the numbers.
private const val MARK_WINDOW = 58f
private const val MARK_WINDOW_ORIGIN = 25f
private const val MARK_ARM_WIDTH = 13f
private val MARK_VERTEX = Offset(63.6035f, 54f)
private val MARK_UPPER_END = Offset(45.3965f, 37.6063f)
private val MARK_LOWER_END = Offset(45.3965f, 70.3937f)

// Written out to match the launcher art exactly; Compose's lerp mixes in Oklab and would land on
// different tints from the ones the icon was tuned with.
private val MarkLowerArm = Color(0xFF67608E)
private val MarkOverlap = Color(0xFFE5E1F9)

/**
 * The chevron on its own, at the angle the caller needs.
 *
 * Every arrow in the interface is this one shape rotated, so they cannot drift apart. The shape
 * points right at zero: a row that leads somewhere passes 0, back passes 180, and an expander
 * passes 90 closed and 270 open.
 */
@Composable
fun LimentyChevron(
    size: Dp,
    rotationDegrees: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeDp: Dp = 2.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        rotate(rotationDegrees) { drawChevron(color, strokeDp.toPx()) }
    }
}

private fun DrawScope.drawChevron(color: Color, stroke: Float) {
    // Proportions of the handoff's inline chevron — a 12 box holding an 8 square whose centre sits
    // at (5, 6). The horizontal offset is the point of it: the turned square's ink lies entirely
    // right of its own centre, so centring the square puts the ink flush against the box edge.
    val side = size.minDimension * (8f / 12f)
    val cx = size.width * (5f / 12f)
    val cy = size.height / 2f
    rotate(-45f, pivot = Offset(cx, cy)) {
        val left = cx - side / 2f
        val top = cy - side / 2f
        // Only the right and bottom edges of a square, which is what makes the corner meet.
        drawRect(color, Offset(left + side - stroke, top), Size(stroke, side))
        drawRect(color, Offset(left, top + side - stroke), Size(side, stroke))
    }
}

/**
 * The settings glyph: two rails with a thumb on each, which is what a set of adjustments looks
 * like. Drawn for the same reason as the mark — one definition, any size.
 */
@Composable
fun SettingsGlyph(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(width = 18.dp, height = 17.dp)) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 17.dp)) {
            val unit = size.width / 18f
            drawRect(LimentyColors.Text3, Offset(0f, 4 * unit), Size(18 * unit, 2 * unit))
            drawRect(LimentyColors.Text3, Offset(0f, 11 * unit), Size(18 * unit, 2 * unit))
            drawRect(LimentyColors.Text2, Offset(4 * unit, 2 * unit), Size(5 * unit, 6 * unit))
            drawRect(LimentyColors.Text2, Offset(11 * unit, 9 * unit), Size(5 * unit, 6 * unit))
        }
    }
}
