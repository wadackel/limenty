package dev.wadackel.limenty.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One named bar: the keys that scroll and the keys pinned at the right.
 *
 * The pinned keys belong to the set rather than to the app, because what has to stay in reach is
 * the part that differs most between uses — the arrows for an editor, `⏎` and the two vertical
 * arrows for an agent's menu — and a set that pins fewer keys leaves the scrolling run more room.
 */
data class KeySet(
    val name: String,
    val scrolling: List<KeyBarSlot>,
    val pinned: List<KeyBarSlot>,
) {
    fun row(pinned: Boolean): List<KeyBarSlot> = if (pinned) this.pinned else scrolling

    private fun withRow(pinned: Boolean, slots: List<KeyBarSlot>): KeySet = if (pinned) copy(pinned = slots) else copy(scrolling = slots)

    /**
     * Moves the key at [key] to the end of the group at [group], from either row into either row.
     * Unchanged when either position does not hold what it should.
     */
    fun moveIntoGroup(key: SlotPosition, group: SlotPosition): KeySet {
        val moving = row(key.pinned).getOrNull(key.index) as? AuxiliaryKey ?: return this
        val target = row(group.pinned).getOrNull(group.index) as? KeyGroup ?: return this
        // Replaced before the key is taken out, so the group's index still means the same slot.
        val grown = withRow(group.pinned, row(group.pinned).replaced(group.index, target.copy(keys = target.keys + moving)))
        return grown.withRow(key.pinned, grown.row(key.pinned).filterIndexed { at, _ -> at != key.index })
    }

    /**
     * Applies what the editing sheet left of one slot, and says where the slot ended up: the group
     * it went into when it went into one.
     *
     * A slot that stays in its row keeps its place there, with any keys taken out of it placed
     * right after it. One that changes rows goes to the end of the other, as a pin always has.
     */
    fun applying(edit: SlotEdit): Pair<KeySet, SlotPosition> {
        val from = edit.from?.takeIf { it.index in row(it.pinned).indices }
        val key = edit.slot as? AuxiliaryKey
        val into = edit.into
        val group = into?.let { row(it.pinned).getOrNull(it.index) as? KeyGroup }
        if (key != null && into != null && group != null) {
            // Grown before the key is taken out, so the group's index still means the same slot.
            val grown = withRow(into.pinned, row(into.pinned).replaced(into.index, group.copy(keys = group.keys + key)))
            if (from == null) return grown to into
            val landed = if (from.pinned == into.pinned && from.index < into.index) into.copy(index = into.index - 1) else into
            return grown.removed(from) to landed
        }
        val placed = listOf(edit.slot) + edit.movedOut
        if (from != null && from.pinned == edit.pinned) {
            val slots = row(from.pinned).toMutableList().also {
                it.removeAt(from.index)
                it.addAll(from.index, placed)
            }
            return withRow(from.pinned, slots) to from
        }
        val emptied = if (from != null) removed(from) else this
        val end = emptied.row(edit.pinned).size
        return emptied.withRow(edit.pinned, emptied.row(edit.pinned) + placed) to SlotPosition(edit.pinned, end)
    }

    fun removed(at: SlotPosition): KeySet = withRow(at.pinned, row(at.pinned).filterIndexed { index, _ -> index != at.index })

    /** Puts [slot] back at [at], or at the end of its row when the row has since grown shorter. */
    fun restored(at: SlotPosition, slot: KeyBarSlot): KeySet {
        val slots = row(at.pinned).toMutableList()
        slots.add(at.index.coerceIn(0, slots.size), slot)
        return withRow(at.pinned, slots)
    }

    /** Moves the scrolling slot at [index] to the end of the pinned run, where it is drawn last. */
    fun pin(index: Int): KeySet {
        val moving = scrolling.getOrNull(index) ?: return this
        return copy(scrolling = scrolling.filterIndexed { at, _ -> at != index }, pinned = pinned + moving)
    }

    companion object {
        const val DEFAULT_NAME = "Default"

        val Default = KeySet(DEFAULT_NAME, AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned)

        /**
         * A new set with Escape and the arrows, rather than nothing: an empty bar is a strip with
         * nothing on it to tap, and these are the keys almost any set ends up wanting.
         */
        fun starter(name: String): KeySet = KeySet(name, AuxiliaryKey.DefaultScrolling.take(1), AuxiliaryKey.DefaultPinned)

        fun encode(sets: List<KeySet>): String {
            val array = JSONArray()
            sets.forEach { set ->
                array.put(
                    JSONObject()
                        .put("name", set.name)
                        .put("scrolling", JSONArray(AuxiliaryKey.encodeBar(set.scrolling)))
                        .put("pinned", JSONArray(AuxiliaryKey.encodeBar(set.pinned))),
                )
            }
            return array.toString()
        }

        /** Null for anything that does not hold at least one set, so the caller falls back whole. */
        fun decode(encoded: String): List<KeySet>? = runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                KeySet(
                    name = item.getString("name"),
                    scrolling = checkNotNull(AuxiliaryKey.decodeBar(item.getJSONArray("scrolling").toString())),
                    pinned = checkNotNull(AuxiliaryKey.decodeBar(item.getJSONArray("pinned").toString())),
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * One slot as the editing sheet leaves it, applied all at once on Save so that Cancel can throw
 * every part of it away: what the slot is, which row it ends in, and the keys taken out of it.
 */
data class SlotEdit(
    /** Where the slot was, or null for one being added. */
    val from: SlotPosition?,
    val slot: KeyBarSlot,
    /** The row it ends in, unless [into] names a group for a key to go into instead. */
    val pinned: Boolean,
    val into: SlotPosition? = null,
    /** Keys taken out of a group in the same edit, placed right after it. */
    val movedOut: List<AuxiliaryKey> = emptyList(),
)

/**
 * A slot by where it sits in a set rather than by what it holds. Two slots can be equal (a second
 * Esc beside the default one), and finding one by value would act on both.
 */
data class SlotPosition(val pinned: Boolean, val index: Int)

private fun List<KeyBarSlot>.replaced(index: Int, slot: KeyBarSlot): List<KeyBarSlot> = mapIndexed { at, it -> if (at == index) slot else it }
