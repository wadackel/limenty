package dev.wadackel.limenty.terminal

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.ArrowPadSettings
import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Test

class ArrowDropGeometryTest {
    private val metrics = TerminalMetrics(
        cellWidth = 20f,
        cellHeight = 40f,
        baseline = 30f,
        fontSizePx = 36f,
        letterSpacingPx = 0f,
        density = 3f,
    )
    private val deadZoneDp = ArrowPadSettings().deadZoneDp
    private val deadZone = ArrowDropGeometry.deadZone(deadZoneDp, metrics)

    private fun aim(dx: Float, dy: Float, current: TerminalKey? = null) = ArrowDropGeometry.aim(dx, dy, current, deadZone)

    @Test
    fun `a press still inside the dead zone aims at nothing`() {
        assertThat(aim(deadZone, 0f)).isNull()
        assertThat(aim(deadZone * 0.7f, deadZone * 0.7f, TerminalKey.ArrowRight)).isNull()
    }

    @Test
    fun `a press past the dead zone aims along its larger axis`() {
        assertThat(aim(deadZone + 1f, 0f)).isEqualTo(TerminalKey.ArrowRight)
        assertThat(aim(-deadZone - 1f, 10f)).isEqualTo(TerminalKey.ArrowLeft)
        assertThat(aim(10f, deadZone + 1f)).isEqualTo(TerminalKey.ArrowDown)
        assertThat(aim(-10f, -deadZone - 1f)).isEqualTo(TerminalKey.ArrowUp)
    }

    @Test
    fun `a press drifting past the diagonal keeps the arrow it is already sending`() {
        val far = deadZone * 2

        assertThat(aim(far * 0.8f, -far, TerminalKey.ArrowRight)).isEqualTo(TerminalKey.ArrowRight)
        assertThat(aim(far * 0.8f, -far)).isEqualTo(TerminalKey.ArrowUp)
    }

    @Test
    fun `a press exactly on the diagonal or on the keep ratio stays sideways`() {
        val far = deadZone * 2

        assertThat(aim(far, -far)).isEqualTo(TerminalKey.ArrowRight)
        assertThat(aim(far * 0.75f, -far, TerminalKey.ArrowRight)).isEqualTo(TerminalKey.ArrowRight)
    }

    @Test
    fun `a press clearly on another side switches to that arrow`() {
        val far = deadZone * 2

        assertThat(aim(far * 0.7f, -far, TerminalKey.ArrowRight)).isEqualTo(TerminalKey.ArrowUp)
        assertThat(aim(-far, 0f, TerminalKey.ArrowRight)).isEqualTo(TerminalKey.ArrowLeft)
    }

    @Test
    fun `the head stays between its shortest and longest reach`() {
        val density = metrics.density
        val shortest = ArrowDropGeometry.MIN_REACH_DP * density
        val longest = ArrowDropGeometry.MAX_REACH_DP * density

        assertThat(ArrowDropGeometry.headDistance(deadZone, 0f, TerminalKey.ArrowRight, metrics)).isEqualTo(
            (deadZoneDp + ArrowDropGeometry.HEAD_LEAD_DP) * density,
        )
        assertThat(ArrowDropGeometry.headDistance(0f, 0f, TerminalKey.ArrowUp, metrics)).isEqualTo(shortest)
        assertThat(ArrowDropGeometry.headDistance(0f, -1000f, TerminalKey.ArrowUp, metrics)).isEqualTo(longest)
    }

    /** The dots that mark the four directions are drawn here; a dead zone past them would cover one. */
    @Test
    fun `the largest dead zone the settings offer stays inside the direction dots`() {
        assertThat(ArrowPadSettings.DeadZone.max.toFloat()).isLessThan(ArrowDropGeometry.DOT_DISTANCE_DP)
    }

    @Test
    fun `a larger dead zone holds back a press the default would already aim`() {
        val wide = ArrowDropGeometry.deadZone(ArrowPadSettings.DeadZone.max, metrics)

        assertThat(aim(deadZone + 1f, 0f)).isEqualTo(TerminalKey.ArrowRight)
        assertThat(ArrowDropGeometry.aim(deadZone + 1f, 0f, null, wide)).isNull()
    }

    @Test
    fun `a spring settles on its target and says it has stopped`() {
        val spring = Spring(stiffness = 700f, dampingRatio = 0.57f, restDistance = 0.5f)
        spring.target = 100f

        repeat(120) { spring.step(1f / 60f) }

        assertThat(spring.atRest).isTrue()
        assertThat(spring.value).isEqualTo(100f)
    }

    @Test
    fun `a stalled frame does not throw the stiffest spring out of bounds`() {
        val spring = Spring(stiffness = 900f, dampingRatio = 1f, restDistance = 0.002f)
        spring.snapTo(1f)
        spring.target = 0f

        repeat(5) { spring.step(1f) }

        assertThat(spring.value).isAtLeast(-0.1f)
        assertThat(spring.value).isAtMost(1f)
    }
}
