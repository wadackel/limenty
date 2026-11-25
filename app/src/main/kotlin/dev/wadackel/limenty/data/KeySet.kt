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
    companion object {
        const val DEFAULT_NAME = "Default"

        val Default = KeySet(DEFAULT_NAME, AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned)

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
