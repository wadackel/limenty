package dev.wadackel.limenty.data

/**
 * What one place on the key bar holds: a key, or a group of them.
 *
 * Sealed so the bar and the settings handle both or fail to compile, and so a group's members can be
 * typed as keys alone: a group inside a group is not something the bar can open.
 */
sealed interface KeyBarSlot {
    /** What the slot's face shows. */
    val label: String

    /** What a screen reader says for the slot, which is not always what the face shows. */
    fun spoken(): String
}

/**
 * Several keys behind one slot, opened into a popup above the bar.
 *
 * For keys that share an intent but are not needed often enough for a slot each: one tap slower
 * than a key on the bar, and never at the far end of the scroll.
 */
data class KeyGroup(
    val name: String,
    val keys: List<AuxiliaryKey>,
) : KeyBarSlot {
    /** The members' own labels when the owner gave no name, so a fresh group still says what is in it. */
    override val label: String
        get() = name.ifBlank { keys.joinToString(" ") { it.label } }

    /** The count is said because the face cannot show it, and it is what tells a group from a key. */
    override fun spoken(): String {
        val called = name.ifBlank { keys.joinToString(" ") { it.spoken() } }
        val count = when (keys.size) {
            0 -> "no keys"
            1 -> "1 key"
            else -> "${keys.size} keys"
        }
        return if (called.isEmpty()) count else "$called, $count"
    }
}
