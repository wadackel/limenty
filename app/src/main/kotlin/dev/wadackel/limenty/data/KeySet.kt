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
     * Takes the key at [member] out of [edited] and places it right after the group at [group].
     *
     * The group is given rather than read from the set because it is the one being edited: the
     * key may exist only in that edit, added or moved since the group was last saved, and the rest
     * of the edit is kept with it.
     */
    fun moveOutOfGroup(group: SlotPosition, edited: KeyGroup, member: Int): KeySet {
        val slots = row(group.pinned)
        if (slots.getOrNull(group.index) !is KeyGroup) return this
        val moving = edited.keys.getOrNull(member) ?: return this
        val shrunk = edited.copy(keys = edited.keys.filterIndexed { at, _ -> at != member })
        val updated = slots.replaced(group.index, shrunk).toMutableList().also { it.add(group.index + 1, moving) }
        return withRow(group.pinned, updated)
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
 * A slot by where it sits in a set rather than by what it holds. Two slots can be equal (a second
 * Esc beside the default one), and finding one by value would act on both.
 */
data class SlotPosition(val pinned: Boolean, val index: Int)

private fun List<KeyBarSlot>.replaced(index: Int, slot: KeyBarSlot): List<KeyBarSlot> = mapIndexed { at, it -> if (at == index) slot else it }
