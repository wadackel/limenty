package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GesturesTest {
    @Test
    fun `every assignable behaviour is a real object`() {
        // Regression: building this list during class initialisation produced a list of nulls,
        // which only surfaced as a crash when the settings picker rendered it.
        val assignable = GestureBehaviour.Assignable

        assertThat(assignable.none { it == null }).isTrue()
        assertThat(assignable).containsNoDuplicates()
        assertThat(assignable.filterNot { it is GestureBehaviour.SendKeys }.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `every fixed behaviour round-trips through its id`() {
        GestureBehaviour.Assignable.filterNot { it is GestureBehaviour.SendKeys }.forEach { behaviour ->
            assertThat(GestureBehaviour.byId(behaviour.id)).isEqualTo(behaviour)
        }
    }

    /** These ids are on the owner's phone, written before a gesture could send several steps. */
    @Test
    fun `a single-key id stored by an earlier version reads back as the key it named`() {
        assertThat(GestureBehaviour.byId("key:Tab:0"))
            .isEqualTo(GestureBehaviour.SendKeys(listOf(KeyStep.Press(key = TerminalKey.Tab))))
        assertThat(GestureBehaviour.byId("key:Tab:${Modifiers.SHIFT}"))
            .isEqualTo(GestureBehaviour.SendKeys(listOf(KeyStep.Press(key = TerminalKey.Tab, modifiers = Modifiers.Shift))))
        assertThat(GestureBehaviour.byId("chord:99:${Modifiers.CTRL}"))
            .isEqualTo(GestureBehaviour.SendKeys(listOf(KeyStep.Press(codePoint = 'c'.code, modifiers = Modifiers.Ctrl))))
    }

    @Test
    fun `a stored key that reads back is the preset it was picked as`() {
        GestureBehaviour.Assignable.filterIsInstance<GestureBehaviour.SendKeys>().forEach { preset ->
            val step = preset.steps.single() as KeyStep.Press
            val stored = if (step.key != null) {
                "key:${step.key.name}:${step.modifiers.bits}"
            } else {
                "chord:${step.codePoint}:${step.modifiers.bits}"
            }
            assertThat(GestureBehaviour.byId(stored)).isEqualTo(preset)
        }
    }

    @Test
    fun `a malformed single-key id falls back to doing nothing`() {
        assertThat(GestureBehaviour.byId("key:NoSuchKey:0")).isEqualTo(GestureBehaviour.None)
        assertThat(GestureBehaviour.byId("chord:0:4")).isEqualTo(GestureBehaviour.None)
        assertThat(GestureBehaviour.byId("chord:1114112:4")).isEqualTo(GestureBehaviour.None)
        assertThat(GestureBehaviour.byId("key:Tab")).isEqualTo(GestureBehaviour.None)
        assertThat(GestureBehaviour.byId("keys")).isEqualTo(GestureBehaviour.None)
    }

    @Test
    fun `an unknown id falls back to doing nothing`() {
        assertThat(GestureBehaviour.byId("removed-in-a-later-version")).isEqualTo(GestureBehaviour.None)
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
