package dev.wadackel.limenty.terminal

import android.content.Context
import android.os.Handler
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Recognises a long press after a time the app chooses.
 *
 * `GestureDetector` cannot be given one: it reads Android's Touch & hold delay and nothing else.
 * Otherwise this keeps the detector's rules, so a press set to the system's time behaves as it did
 * when the detector timed it: moving past the touch slop or a second finger loses the press, a
 * press the classifier finds ambiguous waits longer, and a deep press fires at once.
 *
 * One departure: the detector skips the slop while a double tap is held, so dragging the second tap
 * used to open the pad partway through the drag. Here the drag loses the press as any other does.
 */
internal class LongPressTimer(
    context: Context,
    private val handler: Handler,
    private val onLongPress: (down: MotionEvent) -> Unit,
) {
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop.toFloat()

    /** A copy, because the platform recycles the event it handed over once dispatch returns. */
    private var down: MotionEvent? = null
    private var timeoutMillis = 0L
    private var pending = false

    private val fire = Runnable {
        pending = false
        down?.let(onLongPress)
    }

    fun start(event: MotionEvent, timeoutMillis: Long) {
        cancel()
        down?.recycle()
        down = MotionEvent.obtain(event)
        this.timeoutMillis = timeoutMillis
        pending = true
        // From the down's own time, as the detector does, so a press that sat in the queue is not
        // timed from when it was finally read.
        handler.postAtTime(fire, event.downTime + timeoutMillis)
    }

    /**
     * Follows the finger, and says whether the detector has to be kept from seeing this move.
     *
     * The detector held back its scroll while an ambiguous press waited, and it no longer knows a
     * press is waiting; given the move, it would scroll and the press would still fire after.
     */
    fun move(event: MotionEvent): Boolean {
        val origin = down ?: return false
        if (!pending) return false
        val dx = event.x - origin.x
        val dy = event.y - origin.y
        val distanceSquare = dx * dx + dy * dy
        var slopSquare = touchSlop * touchSlop
        when (event.classification) {
            MotionEvent.CLASSIFICATION_DEEP_PRESS -> {
                handler.removeCallbacks(fire)
                fire.run()
                return false
            }
            MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE -> {
                val multiplier = configuration.scaledAmbiguousGestureMultiplier
                if (distanceSquare > slopSquare) {
                    handler.removeCallbacks(fire)
                    handler.postAtTime(fire, origin.downTime + (timeoutMillis * multiplier).toLong())
                }
                slopSquare *= multiplier * multiplier
                if (distanceSquare <= slopSquare) return true
            }
            else -> Unit
        }
        if (distanceSquare > slopSquare) cancel()
        return false
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        pending = false
    }
}
