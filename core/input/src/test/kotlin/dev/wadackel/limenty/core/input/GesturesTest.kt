package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GesturesTest {
    @Test
    fun `every assignable behaviour is a real object`() {
        // Regression: building this list during class initialisation produced a list of nulls,
        // which only surfaced as a crash when the settings picker rendered it.
        val ids = GestureBehaviour.Assignable.map { it.id }

        assertThat(ids).hasSize(GestureBehaviour.Assignable.size)
        assertThat(ids.none { it.isEmpty() }).isTrue()
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `every assignable behaviour round-trips through its id`() {
        GestureBehaviour.Assignable.forEach { behaviour ->
            assertThat(GestureBehaviour.byId(behaviour.id).id).isEqualTo(behaviour.id)
        }
    }

    @Test
    fun `an unknown id falls back to doing nothing`() {
        assertThat(GestureBehaviour.byId("removed-in-a-later-version")).isEqualTo(GestureBehaviour.None)
    }

    @Test
    fun `every gesture resolves to a behaviour`() {
        val bindings = GestureBindings.Initial
        Gesture.entries.forEach { gesture ->
            assertThat(bindings[gesture].displayName).isNotEmpty()
        }
    }

    @Test
    fun `the initial preset shows the keyboard on tap, selects on long press, scrolls on a vertical swipe and zooms on pinch`() {
        val bindings = GestureBindings.Initial

        assertThat(bindings[Gesture.Tap]).isEqualTo(GestureBehaviour.ShowKeyboard)
        assertThat(bindings[Gesture.DoubleTap]).isEqualTo(GestureBehaviour.None)
        assertThat(bindings[Gesture.LongPress]).isEqualTo(GestureBehaviour.SelectText)
        assertThat(bindings[Gesture.SwipeUp]).isEqualTo(GestureBehaviour.Scroll)
        assertThat(bindings[Gesture.SwipeDown]).isEqualTo(GestureBehaviour.Scroll)
        assertThat(bindings[Gesture.SwipeLeft]).isEqualTo(GestureBehaviour.None)
        assertThat(bindings[Gesture.TwoFingerTap]).isEqualTo(GestureBehaviour.None)
        assertThat(bindings[Gesture.PinchIn]).isEqualTo(GestureBehaviour.FontZoom)
        assertThat(bindings[Gesture.PinchOut]).isEqualTo(GestureBehaviour.FontZoom)
    }

    @Test
    fun `rebinding one gesture leaves the others alone`() {
        val updated = GestureBindings.Initial.with(Gesture.DoubleTap, GestureBehaviour.Paste)

        assertThat(updated[Gesture.DoubleTap]).isEqualTo(GestureBehaviour.Paste)
        assertThat(updated[Gesture.Tap]).isEqualTo(GestureBehaviour.ShowKeyboard)
    }
}
