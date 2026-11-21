package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalInputEvent
import dev.wadackel.limenty.core.input.TerminalKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing a button sends.
 *
 * A button sends a list of these rather than a single action, because the shortcuts worth putting
 * on a bar are often two keystrokes: a tmux prefix and the command key after it is one button's
 * worth of intent and two of typing.
 */
sealed interface KeyStep {
    /** A key press: a named key, or a code point, with whatever was held down. */
    data class Press(
        val key: TerminalKey? = null,
        val codePoint: Int = 0,
        val modifiers: Modifiers = Modifiers.None,
    ) : KeyStep

    /** Text, sent as typed. What saves `git status` from being eleven presses. */
    data class Literal(val text: String) : KeyStep

    /**
     * What this step puts on the wire, or null when it is bound to nothing.
     *
     * A code point with a modifier is a chord and without one is text — the same distinction the
     * encoder draws, made here so that what is stored stays the configuration rather than the
     * conclusion drawn from it.
     */
    fun toEvent(): TerminalInputEvent? = when (this) {
        is Literal -> TerminalInputEvent.Text(text).takeIf { text.isNotEmpty() }
        is Press -> when {
            key != null -> TerminalInputEvent.Key(key, modifiers)
            codePoint > 0 && !modifiers.isEmpty -> TerminalInputEvent.Chord(codePoint, modifiers)
            codePoint > 0 -> TerminalInputEvent.Text(String(Character.toChars(codePoint)))
            else -> null
        }
    }
}

/**
 * A button that sends one thing, which most of them do.
 *
 * Written as a function rather than a second constructor so the twenty keys this app ships with,
 * and the tests that describe them, say what they mean without a list around every one.
 */
fun AuxiliaryKey(
    label: String,
    key: TerminalKey? = null,
    codePoint: Int = 0,
    modifiers: Modifiers = Modifiers.None,
): AuxiliaryKey = AuxiliaryKey(label, listOf(KeyStep.Press(key, codePoint, modifiers)))

/**
 * One button on the auxiliary key bar.
 *
 * This is a one-shot send rather than a sticky modifier: tapping `⌃l` sends Ctrl+L
 * immediately, so the bar has no hidden state the user has to track. The label is separate from
 * the steps so a button can read `Clear` while sending Ctrl+L, and so a button that sends several
 * things can be called something shorter than the list of them.
 */
data class AuxiliaryKey(
    val label: String,
    val steps: List<KeyStep>,
) {
    fun toEvents(): List<TerminalInputEvent> = steps.mapNotNull { it.toEvent() }

    /**
     * Whether the owner named this key, rather than letting its steps name it.
     *
     * Read off the key instead of stored, so a bar written by hand behaves like one edited in the
     * app. Blank counts as following, because a nameless key has nothing of its own to keep.
     */
    fun hasOwnName(): Boolean = label.isNotBlank() && label != labelFor(steps)

    /**
     * What a screen reader should say, which is not what the face shows.
     *
     * A face is `←` because it is read at a glance; a screen reader saying "leftwards arrow" is
     * worse than saying "Left". The substitution stops at a key the owner named, though — someone
     * who calls a key `Clear` wants to hear `Clear`, not `Escape`.
     */
    fun spoken(): String = if (hasOwnName()) label else spokenFor(steps).ifEmpty { "Unassigned" }

    companion object {
        /**
         * The keys that scroll. Chosen from what a tmux and nvim user reaches for first, since
         * a preset has to be usable out of the box rather than leave an empty bar to configure.
         */
        val DefaultScrolling: List<AuxiliaryKey> = listOf(
            AuxiliaryKey("Esc", key = TerminalKey.Escape),
            AuxiliaryKey("Tab", key = TerminalKey.Tab),
            AuxiliaryKey("⌃c", codePoint = 'c'.code, modifiers = Modifiers.Ctrl),
            AuxiliaryKey("⌃d", codePoint = 'd'.code, modifiers = Modifiers.Ctrl),
            AuxiliaryKey("⌃l", codePoint = 'l'.code, modifiers = Modifiers.Ctrl),
            AuxiliaryKey("⌃r", codePoint = 'r'.code, modifiers = Modifiers.Ctrl),
            AuxiliaryKey("⌃b", codePoint = 'b'.code, modifiers = Modifiers.Ctrl),
            AuxiliaryKey("⌃z", codePoint = 'z'.code, modifiers = Modifiers.Ctrl),
            AuxiliaryKey("|", codePoint = '|'.code),
            AuxiliaryKey("-", codePoint = '-'.code),
            AuxiliaryKey("/", codePoint = '/'.code),
            AuxiliaryKey("~", codePoint = '~'.code),
            AuxiliaryKey("Home", key = TerminalKey.Home),
            AuxiliaryKey("End", key = TerminalKey.End),
            AuxiliaryKey("PgUp", key = TerminalKey.PageUp),
            AuxiliaryKey("PgDn", key = TerminalKey.PageDown),
        )

        /**
         * The keys held out of the scroll, at the right.
         *
         * The arrows by default because they are what the scroll used to hide: on the cover
         * display only ten of twenty keys fit, and the ten that fell off the end were the ones
         * an editor needs constantly.
         */
        val DefaultPinned: List<AuxiliaryKey> = listOf(
            AuxiliaryKey("←", key = TerminalKey.ArrowLeft),
            AuxiliaryKey("↓", key = TerminalKey.ArrowDown),
            AuxiliaryKey("↑", key = TerminalKey.ArrowUp),
            AuxiliaryKey("→", key = TerminalKey.ArrowRight),
        )

        @Deprecated("Split into DefaultScrolling and DefaultPinned", ReplaceWith("DefaultScrolling"))
        val DefaultBar: List<AuxiliaryKey> get() = DefaultScrolling + DefaultPinned

        /**
         * What a key calls itself when its owner has not said otherwise.
         *
         * The convention is not invented here — it is read off the bar this app ships with, where
         * Escape is `Esc`, PageDown is `PgDn`, Ctrl and the letter c is `⌃c` and a bare character
         * is itself. A test holds the two together, so a key added by hand and one added through
         * the editor cannot end up named by different rules.
         *
         * Empty when there is no action yet, which is the one case with nothing to name.
         */
        fun labelFor(key: TerminalKey?, codePoint: Int, modifiers: Modifiers): String {
            val action = when {
                key != null -> key.shortName()
                codePoint > 0 -> String(Character.toChars(codePoint))
                else -> return ""
            }
            return modifiers.prefix() + action
        }

        /**
         * What a key that sends several things calls itself, from the same convention.
         *
         * Joined with a space, the way tmux and Emacs write a sequence: `⌃s w`. Long enough that
         * a key like this usually wants a shorter name of its own, which is why the editor lets it
         * be given one — but a suggestion the owner recognises beats an empty field.
         */
        fun labelFor(steps: List<KeyStep>): String = steps
            .map { step ->
                when (step) {
                    is KeyStep.Literal -> step.text
                    is KeyStep.Press -> labelFor(step.key, step.codePoint, step.modifiers)
                }
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        /**
         * The same rule as [labelFor], in words a screen reader can pronounce.
         *
         * Empty when there is nothing bound yet, so the caller decides what an unbound step is
         * called.
         */
        fun spokenFor(steps: List<KeyStep>): String = steps
            .map { step ->
                when (step) {
                    is KeyStep.Literal -> step.text
                    is KeyStep.Press -> spokenFor(step.key, step.codePoint, step.modifiers)
                }
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        /** Empty for the same case [labelFor] is: nothing bound yet, so there is nothing to say. */
        fun spokenFor(key: TerminalKey?, codePoint: Int, modifiers: Modifiers): String {
            val action = when {
                key != null -> key.spokenName()
                codePoint > 0 -> String(Character.toChars(codePoint))
                else -> return ""
            }
            return modifiers.spokenPrefix() + action
        }

        private fun TerminalKey.spokenName(): String = when (this) {
            TerminalKey.PageUp -> "Page Up"
            TerminalKey.PageDown -> "Page Down"
            TerminalKey.ArrowUp -> "Up"
            TerminalKey.ArrowDown -> "Down"
            TerminalKey.ArrowLeft -> "Left"
            TerminalKey.ArrowRight -> "Right"
            else -> name
        }

        /** Spaced rather than hyphenated: `Ctrl c` is read as two words, `Ctrl-c` as a stumble. */
        private fun Modifiers.spokenPrefix(): String = buildString {
            if (ctrl) append("Ctrl ")
            if (alt) append("Alt ")
            if (meta) append("Meta ")
            if (shift) append("Shift ")
        }

        /**
         * The symbol a keycap carries, where it has one, and a word where it does not.
         *
         * `Esc` and `PgUp` keep their words on purpose. `⎋` and `⇞` are no quicker to read and are
         * Apple keyboard notation rather than anything a terminal user ever types, so the exchange
         * would cost legibility and buy nothing — the symbols below are all shorter than the word
         * they replace.
         */
        private fun TerminalKey.shortName(): String = when (this) {
            TerminalKey.Escape -> "Esc"
            TerminalKey.Insert -> "Ins"
            TerminalKey.PageUp -> "PgUp"
            TerminalKey.PageDown -> "PgDn"
            TerminalKey.Enter -> "⏎"
            TerminalKey.Backspace -> "⌫"
            TerminalKey.Delete -> "⌦"
            TerminalKey.Space -> "␣"
            TerminalKey.ArrowUp -> "↑"
            TerminalKey.ArrowDown -> "↓"
            TerminalKey.ArrowLeft -> "←"
            TerminalKey.ArrowRight -> "→"
            else -> name
        }

        /**
         * The modifiers as a keycap prints them.
         *
         * `C-` and `M-` are what tmux and Emacs take, and matching the config file would be the
         * obvious choice — but a key face is read at a glance rather than typed, and every one of
         * these is narrower than the two characters it replaces. Nothing sets meta on an auxiliary
         * key, so `⌘` is there for a bar written by hand rather than for anything the editor makes.
         */
        private fun Modifiers.prefix(): String = buildString {
            if (ctrl) append("⌃")
            if (alt) append("⌥")
            if (meta) append("⌘")
            if (shift) append("⇧")
        }

        /** JSON rather than a delimited string, so a label can contain any character at all. */
        fun encodeBar(keys: List<AuxiliaryKey>): String {
            val array = JSONArray()
            keys.forEach { entry ->
                val steps = JSONArray()
                entry.steps.forEach { step ->
                    steps.put(
                        when (step) {
                            is KeyStep.Literal -> JSONObject().put("text", step.text)
                            is KeyStep.Press -> JSONObject()
                                .put("key", step.key?.name ?: JSONObject.NULL)
                                .put("codePoint", step.codePoint)
                                .put("modifiers", step.modifiers.bits)
                        },
                    )
                }
                array.put(JSONObject().put("label", entry.label).put("steps", steps))
            }
            return array.toString()
        }

        /**
         * Reads both shapes, because the older one is what is on the owner's phone.
         *
         * A bar that fails to decode is discarded whole — the caller falls back to the defaults —
         * so a format that could not read what came before would quietly replace a configured bar
         * with a stock one on first launch. Three lines is a cheap price for not doing that.
         */
        fun decodeBar(encoded: String): List<AuxiliaryKey>? = runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val stored = item.optJSONArray("steps")
                AuxiliaryKey(
                    label = item.getString("label"),
                    steps = if (stored == null) {
                        listOf(decodeStep(item))
                    } else {
                        (0 until stored.length()).map { decodeStep(stored.getJSONObject(it)) }
                    },
                )
            }
        }.getOrNull()

        private fun decodeStep(item: JSONObject): KeyStep {
            if (item.has("text")) return KeyStep.Literal(item.getString("text"))
            return KeyStep.Press(
                key = item.optString("key").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let(TerminalKey::valueOf),
                codePoint = item.optInt("codePoint"),
                modifiers = Modifiers(item.optInt("modifiers")),
            )
        }
    }
}
