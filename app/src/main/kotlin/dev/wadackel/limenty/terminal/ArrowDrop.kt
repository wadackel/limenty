package dev.wadackel.limenty.terminal

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.animation.AnimationUtils
import dev.wadackel.limenty.core.input.TerminalKey
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The drop drawn under a held long press: a bead under the thumb that stretches towards the arrow
 * being sent, pinching at the neck like a drop of water, with the other directions marked by dots.
 *
 * Drawn by [TerminalView] over the terminal rather than in a window of its own, so it shares the
 * touch coordinates it follows.
 */
internal class ArrowDrop {
    var isOpen = false
        private set

    private var originX = 0f
    private var originY = 0f
    private var lastFrameMillis = 0L

    private val appear = Spring(OPEN_STIFFNESS, OPEN_DAMPING, UNIT_REST)
    private val headX = Spring(HEAD_STIFFNESS, HEAD_DAMPING, PIXEL_REST)
    private val headY = Spring(HEAD_STIFFNESS, HEAD_DAMPING, PIXEL_REST)
    private val dots = ArrowDropGeometry.Arrows.associateWith { Spring(DOT_STIFFNESS, DOT_DAMPING, UNIT_REST) }
    private val springs = listOf(appear, headX, headY) + dots.values

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val body = Path()
    private val chevron = Path()
    private val oval = RectF()

    fun open(x: Float, y: Float) {
        isOpen = true
        originX = x
        originY = y
        lastFrameMillis = 0L
        appear.tune(OPEN_STIFFNESS, OPEN_DAMPING)
        appear.snapTo(0f)
        appear.target = 1f
        headX.snapTo(0f)
        headY.snapTo(0f)
        dots.values.forEach { it.snapTo(0f) }
    }

    /** Points the drop along [key], or draws it back under the thumb when there is none. */
    fun aimAt(dx: Float, dy: Float, key: TerminalKey?, metrics: TerminalMetrics) {
        val reach = key?.let { ArrowDropGeometry.headDistance(dx, dy, it, metrics) } ?: 0f
        val (ax, ay) = key?.let { ArrowDropGeometry.axis(it) } ?: (0 to 0)
        headX.target = ax * reach
        headY.target = ay * reach
        dots.forEach { (arrow, spring) -> spring.target = if (arrow == key) 1f else 0f }
    }

    /**
     * Draws the drop back under the thumb as it fades, or with [animate] false takes it away at
     * once: a view that has been detached draws nothing, and would bring a half-faded drop back
     * when it is attached again.
     */
    fun close(animate: Boolean = true) {
        isOpen = false
        appear.tune(CLOSE_STIFFNESS, CLOSE_DAMPING)
        appear.target = 0f
        headX.target = 0f
        headY.target = 0f
        dots.values.forEach { it.target = 0f }
        if (!animate) settle()
    }

    /** Draws the drop and says whether it needs another frame. */
    fun draw(canvas: Canvas, density: Float): Boolean {
        if (!isOpen && appear.value <= 0f && appear.atRest) return false
        val now = AnimationUtils.currentAnimationTimeMillis()
        val seconds = if (lastFrameMillis == 0L) 0f else (now - lastFrameMillis) / 1000f
        lastFrameMillis = now
        if (ValueAnimator.areAnimatorsEnabled()) springs.forEach { it.step(seconds) } else settle()
        render(canvas, density)
        val moving = springs.any { !it.atRest }
        // No frame is asked for once everything rests, so the next one may come much later; it
        // starts the clock again rather than jumping a capped 1/30s in its first step.
        if (!moving) lastFrameMillis = 0L
        return moving
    }

    private fun settle() = springs.forEach { it.snapTo(it.target) }

    private fun render(canvas: Canvas, density: Float) {
        val shown = appear.value.coerceAtLeast(0f)
        val alpha = min(1f, shown)
        if (alpha <= 0f) return
        val bead = ArrowDropGeometry.BEAD_RADIUS_DP * density

        canvas.save()
        val grow = 0.7f + 0.3f * shown
        canvas.scale(grow, grow, originX, originY)

        val dotDistance = ArrowDropGeometry.DOT_DISTANCE_DP * density
        for ((arrow, spring) in dots) {
            val fade = 1f - spring.value.coerceIn(0f, 1f)
            if (fade <= 0f) continue
            val (ax, ay) = ArrowDropGeometry.axis(arrow)
            dotPaint.color = withAlpha(DOT_COLOR, DOT_ALPHA * fade * alpha)
            canvas.drawCircle(originX + ax * dotDistance, originY + ay * dotDistance, ArrowDropGeometry.DOT_RADIUS_DP * density, dotPaint)
        }

        val glow = withAlpha(ACCENT, GLOW_ALPHA * alpha)
        glowPaint.shader = RadialGradient(
            originX,
            originY,
            bead * 2,
            intArrayOf(glow, glow, withAlpha(ACCENT, 0f)),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(originX, originY, bead * 2, glowPaint)

        val tx = headX.value
        val ty = headY.value
        val length = hypot(tx, ty)
        val ux = if (length > 0f) tx / length else 1f
        val uy = if (length > 0f) ty / length else 0f
        outline(density)

        val start = withAlpha(BODY_COLOR, BODY_ALPHA * alpha)
        val end = withAlpha(ACCENT, alpha)
        bodyPaint.shader = if (length >= 1f) {
            LinearGradient(originX, originY, originX + tx, originY + ty, start, end, Shader.TileMode.CLAMP)
        } else {
            null
        }
        bodyPaint.color = start
        bodyPaint.setShadowLayer(SHADOW_RADIUS_DP * density, 0f, SHADOW_DY_DP * density, withAlpha(SHADOW_COLOR, SHADOW_ALPHA * alpha))
        canvas.drawPath(body, bodyPaint)

        if (length > bead) {
            val size = CHEVRON_SIZE_DP * density
            chevron.reset()
            chevron.moveTo(-0.45f * size, -0.8f * size)
            chevron.lineTo(0.45f * size, 0f)
            chevron.lineTo(-0.45f * size, 0.8f * size)
            chevronPaint.color = withAlpha(CHEVRON_COLOR, alpha)
            chevronPaint.strokeWidth = CHEVRON_STROKE_DP * density
            canvas.save()
            val nudge = CHEVRON_NUDGE_DP * density
            canvas.translate(originX + tx + ux * nudge, originY + ty + uy * nudge)
            canvas.rotate(degrees(atan2(uy, ux)))
            canvas.drawPath(chevron, chevronPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    /**
     * One closed outline of bead, neck and head, so the shadow and the gradient run across a single
     * body; three shapes filled separately would darken where they overlap.
     */
    private fun outline(density: Float) {
        body.reset()
        val bead = ArrowDropGeometry.BEAD_RADIUS_DP * density
        val tx = headX.value
        val ty = headY.value
        val length = hypot(tx, ty)
        val pull = min(1f, length / (ArrowDropGeometry.MAX_REACH_DP * density))
        val beadRadius = bead * (1f - 0.25f * pull)
        val headRadius = beadRadius + (ArrowDropGeometry.HEAD_RADIUS_DP * density - beadRadius) * min(1f, length / (bead * 1.4f))
        if (length < beadRadius - headRadius + 2f * density) {
            body.addCircle(originX, originY, beadRadius, Path.Direction.CW)
            return
        }
        val ux = tx / length
        val uy = ty / length
        val ex = originX + tx
        val ey = originY + ty
        val nx = -uy
        val ny = ux
        val c = cos(NECK_ANGLE)
        val s = sin(NECK_ANGLE)
        val neck = min(beadRadius, headRadius) * (0.95f - 0.6f * pull)
        val apX = originX + beadRadius * (c * ux + s * nx)
        val apY = originY + beadRadius * (c * uy + s * ny)
        val amX = originX + beadRadius * (c * ux - s * nx)
        val amY = originY + beadRadius * (c * uy - s * ny)
        val bpX = ex + headRadius * (-c * ux + s * nx)
        val bpY = ey + headRadius * (-c * uy + s * ny)
        val bmX = ex + headRadius * (-c * ux - s * nx)
        val bmY = ey + headRadius * (-c * uy - s * ny)
        val midX = (originX + ex) / 2f
        val midY = (originY + ey) / 2f
        // A quadratic's midpoint is (start + 2 * control + end) / 4, so these controls put the
        // narrowest point of the neck at [neck] from the axis.
        val qpX = (4f * (midX + nx * neck) - apX - bpX) / 2f
        val qpY = (4f * (midY + ny * neck) - apY - bpY) / 2f
        val qmX = (4f * (midX - nx * neck) - bmX - amX) / 2f
        val qmY = (4f * (midY - ny * neck) - bmY - amY) / 2f
        val phi = atan2(uy, ux)
        val sweep = -degrees(2f * PI.toFloat() - 2f * NECK_ANGLE)

        body.moveTo(apX, apY)
        body.quadTo(qpX, qpY, bpX, bpY)
        oval.set(ex - headRadius, ey - headRadius, ex + headRadius, ey + headRadius)
        body.arcTo(oval, degrees(phi + PI.toFloat() - NECK_ANGLE), sweep, false)
        body.quadTo(qmX, qmY, amX, amY)
        oval.set(originX - beadRadius, originY - beadRadius, originX + beadRadius, originY + beadRadius)
        body.arcTo(oval, degrees(phi - NECK_ANGLE), sweep, false)
        body.close()
    }

    private companion object {
        // Copies of LimentyColors rather than imports: terminal/ draws for ui/ and does not depend
        // on it. ACCENT is Accent, DOT_COLOR Text, CHEVRON_COLOR OnAccent, since the chevron sits on
        // the accent end of the body; BODY_COLOR is Accent mixed 35% into #2C363F, the surface the
        // mock was tuned on.
        const val ACCENT = 0xFFA99BEA.toInt()
        const val BODY_COLOR = 0xFF58597B.toInt()
        const val DOT_COLOR = 0xFFEAE9EE.toInt()
        const val CHEVRON_COLOR = 0xFF1E1838.toInt()
        const val SHADOW_COLOR = 0xFF000000.toInt()

        const val BODY_ALPHA = 0.8f
        const val DOT_ALPHA = 0.4f
        const val GLOW_ALPHA = 0.176f
        const val SHADOW_ALPHA = 0.5f

        const val SHADOW_RADIUS_DP = 5.6f
        const val SHADOW_DY_DP = 1.5f
        const val CHEVRON_SIZE_DP = 5.5f
        const val CHEVRON_STROKE_DP = 2.2f
        const val CHEVRON_NUDGE_DP = 1.3f

        /** Where the neck leaves each circle, measured from the axis. */
        const val NECK_ANGLE = 1f

        const val OPEN_STIFFNESS = 380f
        const val OPEN_DAMPING = 0.56f
        const val CLOSE_STIFFNESS = 900f
        const val CLOSE_DAMPING = 1f
        const val HEAD_STIFFNESS = 700f
        const val HEAD_DAMPING = 0.57f
        const val DOT_STIFFNESS = 600f
        const val DOT_DAMPING = 0.77f

        const val UNIT_REST = 0.002f
        const val PIXEL_REST = 0.5f

        fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

        fun degrees(radians: Float): Float = radians * 180f / PI.toFloat()
    }
}
