package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.TerminalPalette
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A theme someone made, by duplicating another or by importing one.
 *
 * Known by [id] rather than by name: names repeat, a copy of Nord can be called Nord, and a
 * selection stored by name would then be unable to tell the two apart.
 */
data class CustomTheme(val id: String, val palette: TerminalPalette) {
    val name: String get() = palette.name

    companion object {
        /** Presets are known by their names, which cannot start with this. */
        private const val ID_PREFIX = "custom:"

        private val log = TermLog.logger(LogTags.STORAGE)

        fun newId(): String = ID_PREFIX + UUID.randomUUID()

        fun encode(themes: List<CustomTheme>): String {
            val array = JSONArray()
            themes.forEach { theme ->
                val palette = theme.palette
                array.put(
                    JSONObject()
                        .put("id", theme.id)
                        .put("name", palette.name)
                        .put("foreground", hexOf(palette.foreground))
                        .put("background", hexOf(palette.background))
                        .put("cursor", hexOf(palette.cursor))
                        .put("selection", hexOf(palette.selection))
                        .put("ansi", JSONArray(palette.ansi.map(::hexOf))),
                )
            }
            return array.toString()
        }

        /**
         * The themes that read back, skipping any that do not, so one damaged entry costs that theme
         * rather than every theme the device holds. Null only when the whole value is unreadable.
         */
        fun decode(encoded: String): List<CustomTheme>? {
            val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return null
            return (0 until array.length()).mapNotNull { index ->
                runCatching { decodeOne(array.getJSONObject(index)) }
                    .onFailure { log.w("customTheme.unreadable", "index" to index) }
                    .getOrNull()
            }
        }

        private fun decodeOne(item: JSONObject): CustomTheme {
            val ansi = item.getJSONArray("ansi")
            return CustomTheme(
                id = item.getString("id"),
                palette = TerminalPalette(
                    name = item.getString("name"),
                    foreground = parse(item.getString("foreground")),
                    background = parse(item.getString("background")),
                    cursor = parse(item.getString("cursor")),
                    selection = parse(item.getString("selection")),
                    ansi = IntArray(ansi.length()) { parse(ansi.getString(it)) },
                ),
            )
        }

        private fun parse(hex: String): Int = requireNotNull(parseHex(hex)) { "not a #rrggbb colour" }
    }
}

/** `#RRGGBB`, the form every terminal's theme file writes a colour in. */
fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** A colour from `#RRGGBB` or `RRGGBB`, or null for anything else. */
fun parseHex(text: String): Int? {
    val digits = hexDigits(text)
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF shl 24) or digits.toInt(16)
}

/**
 * What was typed, without the hash and in half-width form.
 *
 * A Japanese keyboard in kana mode types `０００` for 000 whatever the field asks for, and the
 * value reads the same to the person typing it.
 */
fun hexDigits(text: String): String = text.trim()
    .map { if (it in '\uFF01'..'\uFF5E') it - FULL_WIDTH_OFFSET else it }
    .joinToString("")
    .removePrefix("#")

private const val FULL_WIDTH_OFFSET = 0xFEE0
