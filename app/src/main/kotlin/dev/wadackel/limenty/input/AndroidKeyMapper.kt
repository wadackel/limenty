package dev.wadackel.limenty.input

import android.view.KeyEvent
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey

/**
 * Translates Android key codes into the platform-independent vocabulary of `:core:input`.
 *
 * The functions take plain ints rather than a `KeyEvent` so the whole mapping table stays
 * unit-testable on the JVM, where a real `KeyEvent` cannot be constructed.
 */
object AndroidKeyMapper {
    fun terminalKey(keyCode: Int): TerminalKey? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.Enter
        KeyEvent.KEYCODE_TAB -> TerminalKey.Tab
        KeyEvent.KEYCODE_DEL -> TerminalKey.Backspace
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.Delete
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.Escape
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.ArrowUp
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.ArrowDown
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.ArrowLeft
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.ArrowRight
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.Home
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.End
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PageDown
        KeyEvent.KEYCODE_INSERT -> TerminalKey.Insert
        KeyEvent.KEYCODE_F1 -> TerminalKey.F1
        KeyEvent.KEYCODE_F2 -> TerminalKey.F2
        KeyEvent.KEYCODE_F3 -> TerminalKey.F3
        KeyEvent.KEYCODE_F4 -> TerminalKey.F4
        KeyEvent.KEYCODE_F5 -> TerminalKey.F5
        KeyEvent.KEYCODE_F6 -> TerminalKey.F6
        KeyEvent.KEYCODE_F7 -> TerminalKey.F7
        KeyEvent.KEYCODE_F8 -> TerminalKey.F8
        KeyEvent.KEYCODE_F9 -> TerminalKey.F9
        KeyEvent.KEYCODE_F10 -> TerminalKey.F10
        KeyEvent.KEYCODE_F11 -> TerminalKey.F11
        KeyEvent.KEYCODE_F12 -> TerminalKey.F12
        else -> null
    }

    /**
     * The `*_MASK` constants are used rather than the `*_ON` bits because a device can report a
     * side-specific bit (`META_CTRL_LEFT_ON`) without the generic one, and missing that would
     * silently turn Ctrl-C into a plain `c`.
     */
    fun modifiers(metaState: Int): Modifiers {
        var bits = 0
        if (metaState and KeyEvent.META_SHIFT_MASK != 0) bits = bits or Modifiers.SHIFT
        if (metaState and KeyEvent.META_ALT_MASK != 0) bits = bits or Modifiers.ALT
        if (metaState and KeyEvent.META_CTRL_MASK != 0) bits = bits or Modifiers.CTRL
        if (metaState and KeyEvent.META_META_MASK != 0) bits = bits or Modifiers.META
        return Modifiers(bits)
    }

    /**
     * Keys the app must not claim: the system owns them, and swallowing them would trap the user
     * in the terminal. Back is excluded here because it has app-level meaning.
     */
    fun isSystemKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_SEARCH,
        -> true
        else -> false
    }

    /** True when the key only reports modifier state and carries no character of its own. */
    fun isModifierKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
        -> true
        else -> false
    }

    /**
     * The character a key would produce ignoring Ctrl and Alt.
     *
     * Android folds Ctrl into `getUnicodeChar()` by returning 0 for most control chords, which
     * would lose the letter the chord is built from; callers therefore pass the unicode value
     * computed with those modifiers masked out.
     */
    fun effectiveMetaStateForCharacter(metaState: Int): Int = metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
}
