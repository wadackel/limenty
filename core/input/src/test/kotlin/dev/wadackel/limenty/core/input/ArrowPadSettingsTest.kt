package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArrowPadSettingsTest {
    @Test
    fun `the defaults are the timing the pad had before it could be set`() {
        val defaults = ArrowPadSettings()

        assertThat(defaults.pressTimeMillis).isNull()
        assertThat(defaults.repeatDelayMillis).isEqualTo(400)
        assertThat(defaults.repeatIntervalMillis).isEqualTo(220)
        assertThat(defaults.deadZoneDp).isEqualTo(25)
        assertThat(defaults.isDefault).isTrue()
    }

    @Test
    fun `every default sits on its own range and step`() {
        val defaults = ArrowPadSettings(pressTimeMillis = 400)

        assertThat(defaults.snapped()).isEqualTo(defaults)
    }

    @Test
    fun `a value between steps snaps to the nearest one`() {
        assertThat(ArrowPadSettings.PressTime.snap(424)).isEqualTo(400)
        assertThat(ArrowPadSettings.PressTime.snap(426)).isEqualTo(450)
        assertThat(ArrowPadSettings.RepeatInterval.snap(33)).isEqualTo(30)
        assertThat(ArrowPadSettings.DeadZone.snap(27)).isEqualTo(25)
    }

    @Test
    fun `a value past either end is pulled back onto the range`() {
        val wild = ArrowPadSettings(pressTimeMillis = 10, repeatDelayMillis = 5000, repeatIntervalMillis = -3, deadZoneDp = 200)

        assertThat(wild.snapped()).isEqualTo(
            ArrowPadSettings(pressTimeMillis = 200, repeatDelayMillis = 1000, repeatIntervalMillis = 30, deadZoneDp = 45),
        )
    }

    @Test
    fun `following the system stays following the system when snapped`() {
        assertThat(ArrowPadSettings(pressTimeMillis = null, deadZoneDp = 3).snapped().pressTimeMillis).isNull()
    }

    @Test
    fun `a slider over a range stops on every step between its ends`() {
        assertThat(ArrowPadSettings.PressTime.stopsBetween).isEqualTo(25)
        assertThat(ArrowPadSettings.RepeatDelay.stopsBetween).isEqualTo(17)
        assertThat(ArrowPadSettings.RepeatInterval.stopsBetween).isEqualTo(46)
        assertThat(ArrowPadSettings.DeadZone.stopsBetween).isEqualTo(6)
    }
}
