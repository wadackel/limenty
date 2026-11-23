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

    /**
     * Sends its steps once when the gesture is recognised.
     *
     * The same steps a key bar button sends, so a gesture can send whatever a button can rather than
     * only the keys a preset names: each missing key used to mean another entry in [Assignable].
     */
    data class SendKeys(val steps: List<KeyStep>) : GestureBehaviour

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
            // A kind rather than an identity: the steps are stored beside it, and this is what the log
            // records, which must not carry the text a step sends.
            is SendKeys -> "keys"
            is ShowKeyboard -> "keyboard.show"
            is HideKeyboard -> "keyboard.hide"
            is ToggleKeyboard -> "keyboard.toggle"
            is Paste -> "paste"
            is Copy -> "copy"
            is Scroll -> "scroll"
            is FontZoom -> "font.zoom"
            is SelectText -> "select"
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
                key(TerminalKey.ArrowUp),
                key(TerminalKey.ArrowDown),
                key(TerminalKey.ArrowLeft),
                key(TerminalKey.ArrowRight),
                key(TerminalKey.Escape),
                key(TerminalKey.Tab),
                key(TerminalKey.Enter),
                key(TerminalKey.PageUp),
                key(TerminalKey.PageDown),
                ctrl('c'),
                ctrl('d'),
                ctrl('l'),
            )
        }

        /**
         * The behaviour an id names: one of the fixed behaviours, or a single key stored as
         * `key:Tab:0` or `chord:99:4`.
         *
         * Those are the ids earlier versions wrote, and the owner's phone holds them. A single key
         * is still written this way, so that an earlier version installed over this one reads its
         * presets rather than nothing.
         */
        fun byId(id: String): GestureBehaviour = Assignable.firstOrNull { it !is SendKeys && it.id == id }
            ?: singleKey(id)
            ?: None

        private fun key(key: TerminalKey) = SendKeys(listOf(KeyStep.Press(key = key)))

        private fun ctrl(char: Char) = SendKeys(listOf(KeyStep.Press(codePoint = char.code, modifiers = Modifiers.Ctrl)))

        private fun singleKey(id: String): SendKeys? {
            val (kind, value, bits) = id.split(':').takeIf { it.size == 3 } ?: return null
            val modifiers = Modifiers(bits.toIntOrNull() ?: return null)
            val step = when (kind) {
                "key" -> KeyStep.Press(key = TerminalKey.entries.firstOrNull { it.name == value } ?: return null, modifiers = modifiers)
                "chord" -> KeyStep.Press(codePoint = value.toIntOrNull()?.takeIf { it > 0 && Character.isValidCodePoint(it) } ?: return null, modifiers = modifiers)
                else -> return null
            }
            return SendKeys(listOf(step))
        }
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
