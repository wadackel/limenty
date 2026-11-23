package dev.wadackel.limenty.ui.theme

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `docs/design.md`: the chrome is dark whatever the Android setting, so the clock and icons in the
 * system bars are drawn light on it in either mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarIconsTest {
    @Test
    @Config(qualifiers = "notnight")
    fun `the system bars draw light icons when Android is in light mode`() {
        val bars = barsAfterEdgeToEdge()

        assertFalse("status bar", bars.isAppearanceLightStatusBars)
        assertFalse("navigation bar", bars.isAppearanceLightNavigationBars)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the system bars draw light icons when Android is in dark mode`() {
        val bars = barsAfterEdgeToEdge()

        assertFalse("status bar", bars.isAppearanceLightStatusBars)
        assertFalse("navigation bar", bars.isAppearanceLightNavigationBars)
    }

    /** Without it three-button navigation sits bare over whatever is drawn under it. */
    @Test
    @Config(qualifiers = "notnight")
    fun `the system keeps its scrim behind three-button navigation`() {
        assertTrue(edgeToEdgeActivity().window.isNavigationBarContrastEnforced)
    }

    private fun barsAfterEdgeToEdge(): WindowInsetsControllerCompat {
        val window = edgeToEdgeActivity().window
        return WindowCompat.getInsetsController(window, window.decorView)
    }

    private fun edgeToEdgeActivity(): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.enableLimentyEdgeToEdge()
        return activity
    }
}
