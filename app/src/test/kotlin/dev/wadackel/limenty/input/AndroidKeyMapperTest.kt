package dev.wadackel.limenty.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Test

class AndroidKeyMapperTest {
    @Test
    fun `escape, tab, enter and the arrow keys are mapped`() {
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_ESCAPE)).isEqualTo(TerminalKey.Escape)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_TAB)).isEqualTo(TerminalKey.Tab)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_ENTER)).isEqualTo(TerminalKey.Enter)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(TerminalKey.ArrowUp)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(TerminalKey.ArrowDown)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(TerminalKey.ArrowLeft)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_DPAD_RIGHT)).isEqualTo(TerminalKey.ArrowRight)
    }

    @Test
    fun `DEL is backspace and FORWARD_DEL is delete`() {
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_DEL)).isEqualTo(TerminalKey.Backspace)
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_FORWARD_DEL)).isEqualTo(TerminalKey.Delete)
    }

    @Test
    fun `the numeric keypad enter is still enter`() {
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_NUMPAD_ENTER)).isEqualTo(TerminalKey.Enter)
    }

    @Test
    fun `all twelve function keys are mapped`() {
        val mapped = (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).mapNotNull(AndroidKeyMapper::terminalKey)
        assertThat(mapped).hasSize(12)
        assertThat(mapped.toSet()).hasSize(12)
    }

    @Test
    fun `printable keys have no terminal key identity`() {
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_A)).isNull()
        assertThat(AndroidKeyMapper.terminalKey(KeyEvent.KEYCODE_1)).isNull()
    }

    @Test
    fun `meta state maps onto modifiers`() {
        assertThat(AndroidKeyMapper.modifiers(0)).isEqualTo(Modifiers.None)
        assertThat(AndroidKeyMapper.modifiers(KeyEvent.META_CTRL_ON)).isEqualTo(Modifiers.Ctrl)
        assertThat(AndroidKeyMapper.modifiers(KeyEvent.META_ALT_ON)).isEqualTo(Modifiers.Alt)
        assertThat(AndroidKeyMapper.modifiers(KeyEvent.META_SHIFT_ON)).isEqualTo(Modifiers.Shift)
        assertThat(AndroidKeyMapper.modifiers(KeyEvent.META_META_ON)).isEqualTo(Modifiers.Meta)
    }

    @Test
    fun `left and right modifier keys both register`() {
        assertThat(AndroidKeyMapper.modifiers(KeyEvent.META_CTRL_LEFT_ON)).isEqualTo(Modifiers.Ctrl)
        assertThat(AndroidKeyMapper.modifiers(KeyEvent.META_CTRL_RIGHT_ON)).isEqualTo(Modifiers.Ctrl)
    }

    @Test
    fun `combined meta state yields combined modifiers`() {
        val combined = AndroidKeyMapper.modifiers(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertThat(combined).isEqualTo(Modifiers.Ctrl + Modifiers.Alt)
    }

    @Test
    fun `system keys are never claimed`() {
        assertThat(AndroidKeyMapper.isSystemKey(KeyEvent.KEYCODE_VOLUME_UP)).isTrue()
        assertThat(AndroidKeyMapper.isSystemKey(KeyEvent.KEYCODE_HOME)).isTrue()
        assertThat(AndroidKeyMapper.isSystemKey(KeyEvent.KEYCODE_A)).isFalse()
    }

    @Test
    fun `back is not treated as a system key because the app gives it meaning`() {
        assertThat(AndroidKeyMapper.isSystemKey(KeyEvent.KEYCODE_BACK)).isFalse()
    }

    @Test
    fun `modifier keys are recognised so they do not encode on their own`() {
        assertThat(AndroidKeyMapper.isModifierKey(KeyEvent.KEYCODE_CTRL_LEFT)).isTrue()
        assertThat(AndroidKeyMapper.isModifierKey(KeyEvent.KEYCODE_SHIFT_RIGHT)).isTrue()
        assertThat(AndroidKeyMapper.isModifierKey(KeyEvent.KEYCODE_CAPS_LOCK)).isTrue()
        assertThat(AndroidKeyMapper.isModifierKey(KeyEvent.KEYCODE_A)).isFalse()
    }

    @Test
    fun `ctrl and alt are masked out when resolving the character of a chord`() {
        val metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON
        val effective = AndroidKeyMapper.effectiveMetaStateForCharacter(metaState)

        assertThat(effective and KeyEvent.META_CTRL_MASK).isEqualTo(0)
        assertThat(effective and KeyEvent.META_SHIFT_ON).isNotEqualTo(0)
    }
}
