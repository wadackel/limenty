package dev.wadackel.limenty.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a notification's small icon has to be, asserted from the pixels it draws.
 *
 * Both of these were wrong while the launcher icon stood in for this one, and neither is visible
 * in the source: the two-tone art is a distinction the system's tinting throws away, and the ink
 * drawn for a masked 108dp canvas lands at a third of the size every other app's icon is.
 */
@RunWith(AndroidJUnit4::class)
class NotificationIconTest {
    private val size = 96

    private fun render(id: Int): Bitmap {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawable = requireNotNull(context.getDrawable(id))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** A silhouette: the shape lives in the alpha channel, and the system supplies the colour. */
    @Test
    fun theNotificationIconIsOneColour() {
        val bitmap = render(R.drawable.ic_notification)
        val hues = mutableSetOf<Int>()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0) hues += pixel or (0xFF shl 24)
            }
        }

        assertThat(hues).hasSize(1)
    }

    /**
     * And it is sized like the icons beside it.
     *
     * Both ends of this have been wrong. Drawn at a third of the canvas — which is what the
     * launcher art measures, since that canvas is mostly margin a mask eats — it reads as a
     * shrunken version of every other app's. Drawn to the edges it is the biggest thing in the
     * shade and leaves nothing for the circular badge some launchers crop to. Measured on the
     * ink's own bounds rather than on the viewport, since a vector can claim any canvas it likes.
     */
    @Test
    fun theNotificationIconFillsItsCanvas() {
        val bitmap = render(R.drawable.ic_notification)
        var top = size
        var bottom = -1
        var left = size
        var right = -1
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }

        val height = bottom - top + 1
        assertThat(height).isAtLeast((size * 0.70f).toInt())
        assertThat(height).isAtMost((size * 0.88f).toInt())
        // Centred, so it does not sit against one edge of the slot it is given.
        assertThat(top).isWithin(2).of(size - 1 - bottom)
        assertThat(left).isWithin(2).of(size - 1 - right)
    }
}
