package dev.wadackel.limenty.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sheet let go of on its way down falls on from the finger's speed and keeps speeding up. */
class LimentySheetClosingTest {
    private val height = 1000f

    @Test
    fun aSheetStartsFallingAtTheSpeedTheFingerLetGoAt() {
        val firstFrame = 0.004f
        assertEquals(5000f * firstFrame, fallenBy(firstFrame, 5000f, height), 1f)
    }

    @Test
    fun aFasterFlickFallsFurtherInTheSameTime() {
        assertTrue(fallenBy(0.05f, 8000f, height) > fallenBy(0.05f, 2000f, height))
    }

    @Test
    fun aSheetLetGoAtRestFallsItsHeightInAQuarterSecond() {
        assertEquals(height, fallenBy(0.25f, 0f, height), 1f)
    }

    @Test
    fun aFingerMovingUpWhenLetGoIsTakenAsAtRest() {
        assertEquals(fallenBy(0.1f, 0f, height), fallenBy(0.1f, -3000f, height), 0.01f)
    }
}
