package dev.wadackel.limenty.core.input

/**
 * The gestures the app recognises.
 *
 * The user assigns behaviour to a fixed set rather than defining recognition itself:
 * a gesture the app cannot reliably detect is worse than one it does not offer, and a closed set
 * keeps conflicts between bindings decidable.
 */
enum class Gesture(val displayName: String) {
    Tap("Tap"),
    DoubleTap("Double tap"),
    LongPress("Long press"),
    SwipeUp("Swipe up"),
    SwipeDown("Swipe down"),
    SwipeLeft("Swipe left"),
    SwipeRight("Swipe right"),
    TwoFingerTap("Two-finger tap"),
    TwoFingerSwipeUp("Two-finger swipe up"),
    TwoFingerSwipeDown("Two-finger swipe down"),
    TwoFingerSwipeLeft("Two-finger swipe left"),
    TwoFingerSwipeRight("Two-finger swipe right"),
    PinchIn("Pinch in"),
    PinchOut("Pinch out"),
}

/**
 * What a gesture can be bound to.
 *
 * One-shot actions are separate from interactions that track the finger: scrolling and
 * zooming need the whole drag, not a single event, and collapsing them into "an action" is what
 * makes gesture systems feel detached from the finger.
 */
sealed interface GestureBehaviour {
    /** Nothing happens. The default for gestures the initial preset leaves free. */
    data object None : GestureBehaviour

    /** Fires once when the gesture is recognised. */
    data class SendKey(val key: TerminalKey, val modifiers: Modifiers = Modifiers.None) : GestureBehaviour

    data class SendChord(val codePoint: Int, val modifiers: Modifiers) : GestureBehaviour

    data object ShowKeyboard : GestureBehaviour

    data object HideKeyboard : GestureBehaviour

    data object ToggleKeyboard : GestureBehaviour

    data object Paste : GestureBehaviour

    data object Copy : GestureBehaviour

    /** Tracks the drag: start, deltas, then end. */
    data object Scroll : GestureBehaviour

    /** Tracks the pinch and adjusts the global font size. */
    data object FontZoom : GestureBehaviour

    /** Begins a selection at the touch point and follows the finger. */
    data object SelectText : GestureBehaviour

    val id: String
        get() = when (this) {
            is None -> "none"
            is SendKey -> "key:${key.name}:${modifiers.bits}"
            is SendChord -> "chord:$codePoint:${modifiers.bits}"
            is ShowKeyboard -> "keyboard.show"
            is HideKeyboard -> "keyboard.hide"
            is ToggleKeyboard -> "keyboard.toggle"
            is Paste -> "paste"
            is Copy -> "copy"
            is Scroll -> "scroll"
            is FontZoom -> "font.zoom"
            is SelectText -> "select"
        }

    val displayName: String
        get() = when (this) {
            is None -> "None"
            is SendKey -> if (modifiers.isEmpty) key.name else "$modifiers+${key.name}"
            is SendChord -> "$modifiers+${String(Character.toChars(codePoint))}"
            is ShowKeyboard -> "Show keyboard"
            is HideKeyboard -> "Hide keyboard"
            is ToggleKeyboard -> "Toggle keyboard"
            is Paste -> "Paste"
            is Copy -> "Copy"
            is Scroll -> "Scroll"
            is FontZoom -> "Font zoom"
            is SelectText -> "Select text"
        }

    companion object {
        /**
         * Everything a user may pick in settings, in the order the picker shows them.
         *
         * Built lazily: the entries are objects implementing this same interface, so constructing
         * the list during class initialisation reads fields that are still being initialised and
         * yields a list of nulls.
         */
        val Assignable: List<GestureBehaviour> by lazy {
            listOf(
                None,
                ShowKeyboard,
                HideKeyboard,
                ToggleKeyboard,
                Scroll,
                FontZoom,
                SelectText,
                Paste,
                Copy,
                // On the alternate screen there is no local scrollback, so a swipe bound to an
                // arrow key is the only way tmux and nvim can be scrolled by touch.
                SendKey(TerminalKey.ArrowUp),
                SendKey(TerminalKey.ArrowDown),
                SendKey(TerminalKey.ArrowLeft),
                SendKey(TerminalKey.ArrowRight),
                SendKey(TerminalKey.Escape),
                SendKey(TerminalKey.Tab),
                SendKey(TerminalKey.Enter),
                SendKey(TerminalKey.PageUp),
                SendKey(TerminalKey.PageDown),
                SendChord('c'.code, Modifiers.Ctrl),
                SendChord('d'.code, Modifiers.Ctrl),
                SendChord('l'.code, Modifiers.Ctrl),
            )
        }

        fun byId(id: String): GestureBehaviour = Assignable.firstOrNull { it.id == id } ?: None
    }
}

/**
 * The gesture-to-behaviour map.
 *
 * The initial preset is chosen so the terminal is usable by touch
 * before anyone opens settings.
 */
data class GestureBindings(private val bindings: Map<Gesture, GestureBehaviour> = Default) {
    operator fun get(gesture: Gesture): GestureBehaviour = bindings[gesture] ?: GestureBehaviour.None

    fun with(gesture: Gesture, behaviour: GestureBehaviour): GestureBindings = GestureBindings(bindings + (gesture to behaviour))

    fun entries(): List<Pair<Gesture, GestureBehaviour>> = Gesture.entries.map { it to get(it) }

    companion object {
        /** Lazy for the same reason as [GestureBehaviour.Assignable]. */
        val Default: Map<Gesture, GestureBehaviour> by lazy {
            mapOf(
                Gesture.Tap to GestureBehaviour.ShowKeyboard,
                Gesture.DoubleTap to GestureBehaviour.None,
                Gesture.LongPress to GestureBehaviour.SelectText,
                Gesture.SwipeUp to GestureBehaviour.Scroll,
                Gesture.SwipeDown to GestureBehaviour.Scroll,
                Gesture.SwipeLeft to GestureBehaviour.None,
                Gesture.SwipeRight to GestureBehaviour.None,
                Gesture.TwoFingerTap to GestureBehaviour.None,
                Gesture.TwoFingerSwipeUp to GestureBehaviour.None,
                Gesture.TwoFingerSwipeDown to GestureBehaviour.None,
                Gesture.TwoFingerSwipeLeft to GestureBehaviour.None,
                Gesture.TwoFingerSwipeRight to GestureBehaviour.None,
                Gesture.PinchIn to GestureBehaviour.FontZoom,
                Gesture.PinchOut to GestureBehaviour.FontZoom,
            )
        }

        val Initial: GestureBindings by lazy { GestureBindings() }
    }
}
