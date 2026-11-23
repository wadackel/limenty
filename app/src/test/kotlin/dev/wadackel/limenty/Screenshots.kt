package dev.wadackel.limenty

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/** What the screenshot tests share: how a screen is drawn to a bitmap, and how close a match must be. */
internal object Screenshots {
    /**
     * Each pixel may drift by a shade or sit one pixel over, which is how text rasterised on
     * macOS and on the Linux CI runner differs; any pixel beyond that fails. A share of
     * differing pixels was the obvious tolerance and was rejected: at 1% a recoloured button
     * passed on every screen where it was small.
     */
    val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            imageComparator = SimpleImageComparator(maxDistance = 0.007f, vShift = 1, hShift = 1),
            changeThreshold = 0f,
        ),
    )

    /**
     * Draws the window holding [node] into a bitmap.
     *
     * Compose's own captureToImage waits for the next frame, which never comes on Robolectric's
     * paused looper; drawing the view directly is what the frame would have done anyway.
     */
    fun capture(compose: AndroidComposeTestRule<*, *>, node: SemanticsNodeInteraction): Bitmap {
        val root = (node.fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        return bitmap
    }
}
