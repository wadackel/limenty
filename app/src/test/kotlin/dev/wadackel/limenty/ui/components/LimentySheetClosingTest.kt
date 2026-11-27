package dev.wadackel.limenty.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sheet let go of on its way down leaves at the speed the finger left it, within limits. */
class LimentySheetClosingTest {
    private fun millis(distance: Float, velocity: Float) = (closingSpec(distance, velocity) as TweenSpec).durationMillis

    @Test
    fun aFasterFlingLowersTheSheetSooner() {
        assertTrue(millis(1000f, 8000f) < millis(1000f, 4000f))
    }

    @Test
    fun aFlungSheetCarriesOnAtTheFingersSpeed() {
        val spec = closingSpec(1000f, 5000f) as TweenSpec
        assertEquals(200, spec.durationMillis)
        assertEquals(LinearEasing, spec.easing)
    }

    @Test
    fun aVeryFastFlingStillTakesLongEnoughToBeSeen() {
        assertEquals(120, millis(1000f, 100_000f))
    }

    @Test
    fun aSheetLetGoSlowlyOrAtRestTakesTheLongestTime() {
        assertEquals(CLOSING_MAX_MILLIS, millis(1000f, 500f))
        assertEquals(CLOSING_MAX_MILLIS, millis(1000f, 0f))
    }
}
