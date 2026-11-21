package dev.wadackel.limenty.core.input

/**
 * Platform-independent identity of a non-text key.
 *
 * Android's `KeyEvent` codes are deliberately kept out of this module so key encoding stays
 * unit-testable on the JVM and so the encoding rules are not entangled with framework quirks.
 */
enum class TerminalKey {
    Enter,
    Tab,
    Backspace,
    Escape,
    Space,

    ArrowUp,
    ArrowDown,
    ArrowRight,
    ArrowLeft,

    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,

    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}
