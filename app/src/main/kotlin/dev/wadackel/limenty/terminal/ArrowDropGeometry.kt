package dev.wadackel.limenty.terminal

import dev.wadackel.limenty.core.input.TerminalKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Which arrow a held long press is aiming at, and how far the drop drawn under it stretches.
 *
 * The arithmetic lives here, apart from the view, for the same reason as [SelectionGeometry]: a
 * threshold that is off by a little only shows up as an arrow nobody meant, on a device.
 */
object ArrowDropGeometry {
    /**
     * Back inside this circle the repeat stops, and the first arrow waits until the press leaves it.
     * Starting at the touch slop instead would make a press that has just been stopped start again
     * the moment the thumb drifts.
     */
    const val DEAD_ZONE_DP = 25f

    /**
     * Near a diagonal the arrow already being sent is kept while its axis carries at least this share
     * of the larger one. Without it a thumb resting on the diagonal flips between two arrows.
     */
    const val KEEP_RATIO = 0.75f

    const val BEAD_RADIUS_DP = 17f
    const val HEAD_RADIUS_DP = 15f

    /** The head sits this far past the finger, so the thumb covers the neck rather than the arrow. */
    const val HEAD_LEAD_DP = 17f
    const val MIN_REACH_DP = 34f
    const val MAX_REACH_DP = 46f

    const val DOT_DISTANCE_DP = 48f
    const val DOT_RADIUS_DP = 2.5f

    fun deadZone(metrics: TerminalMetrics): Float = DEAD_ZONE_DP * metrics.density

    /** The arrow for a press that has moved [dx], [dy] from where it landed, or null inside the dead zone. */
    fun aim(dx: Float, dy: Float, current: TerminalKey?, deadZone: Float): TerminalKey? {
        if (dx * dx + dy * dy <= deadZone * deadZone) return null
        if (current != null) {
            val (ax, ay) = axis(current)
            if (dx * ax + dy * ay >= KEEP_RATIO * max(abs(dx), abs(dy))) return current
        }
        return when {
            abs(dx) >= abs(dy) -> if (dx > 0) TerminalKey.ArrowRight else TerminalKey.ArrowLeft
            else -> if (dy > 0) TerminalKey.ArrowDown else TerminalKey.ArrowUp
        }
    }

    /** How far from the press the head's centre sits, along [key]'s axis. */
    fun headDistance(dx: Float, dy: Float, key: TerminalKey, metrics: TerminalMetrics): Float {
        val (ax, ay) = axis(key)
        val density = metrics.density
        return (dx * ax + dy * ay + HEAD_LEAD_DP * density).coerceIn(MIN_REACH_DP * density, MAX_REACH_DP * density)
    }

    /** The screen direction an arrow points in, as a unit step. */
    fun axis(key: TerminalKey): Pair<Int, Int> = when (key) {
        TerminalKey.ArrowUp -> 0 to -1
        TerminalKey.ArrowDown -> 0 to 1
        TerminalKey.ArrowLeft -> -1 to 0
        TerminalKey.ArrowRight -> 1 to 0
        else -> error("$key is not an arrow")
    }

    val Arrows = listOf(TerminalKey.ArrowUp, TerminalKey.ArrowDown, TerminalKey.ArrowLeft, TerminalKey.ArrowRight)
}

/**
 * A damped spring stepped once a frame.
 *
 * `SpringAnimation` would bring in `androidx.dynamicanimation` and one animation object per moving
 * part, seven for the drop; the step itself is two lines.
 */
internal class Spring(stiffness: Float, dampingRatio: Float, private val restDistance: Float) {
    private var stiffness = stiffness
    private var damping = damping(stiffness, dampingRatio)

    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f

    val atRest: Boolean get() = abs(target - value) <= restDistance && abs(velocity) <= restDistance * REST_VELOCITY_PER_DISTANCE

    fun tune(stiffness: Float, dampingRatio: Float) {
        this.stiffness = stiffness
        damping = damping(stiffness, dampingRatio)
    }

    fun step(seconds: Float) {
        if (atRest) {
            snapTo(target)
            return
        }
        // Stepped in slices: the stiffest spring, critically damped, diverges under this
        // integration once a step reaches 1/30s, and a stalled frame is longer still.
        var left = seconds.coerceIn(0f, MAX_FRAME_SECONDS)
        while (left > 0f) {
            val dt = minOf(left, MAX_SLICE_SECONDS)
            velocity += (stiffness * (target - value) - damping * velocity) * dt
            value += velocity * dt
            left -= dt
        }
        if (atRest) snapTo(target)
    }

    fun snapTo(to: Float) {
        target = to
        value = to
        velocity = 0f
    }

    private companion object {
        const val MAX_FRAME_SECONDS = 1f / 30f
        const val MAX_SLICE_SECONDS = 1f / 120f
        const val REST_VELOCITY_PER_DISTANCE = 10f

        fun damping(stiffness: Float, dampingRatio: Float): Float = 2f * dampingRatio * sqrt(stiffness)
    }
}
