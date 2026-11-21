package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.terminal.TerminalPalette
import org.json.JSONObject

/**
 * A theme read from text pasted in: a Ghostty theme file or a Windows Terminal colour scheme.
 *
 * These two because they are flat — one value per colour, nothing to resolve — and between them
 * they cover the schemes iTerm2-Color-Schemes publishes, which is where most themes are found.
 * Which one it is comes from the text, so nobody has to say.
 */
sealed interface ThemeImport {
    /** [palette]'s name is the one the text gave, or a stand-in when it gave none. */
    data class Read(val palette: TerminalPalette) : ThemeImport

    /** Recognised, but without colours a theme needs; [missing] uses the text's own key names. */
    data class Incomplete(val missing: List<String>) : ThemeImport

    data object Unrecognised : ThemeImport

    companion object {
        const val UNNAMED = "Imported"

        fun parse(text: String): ThemeImport {
            val trimmed = text.trim()
            return when {
                trimmed.startsWith("{") -> windowsTerminal(trimmed)
                GHOSTTY_KEY.containsMatchIn(trimmed) -> ghostty(trimmed)
                else -> Unrecognised
            }
        }

        private fun windowsTerminal(text: String): ThemeImport {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return Unrecognised
            fun colour(key: String) = json.optString(key).takeIf { it.isNotEmpty() }?.let(::parseHex)
            val ansi = WINDOWS_TERMINAL_ANSI.map(::colour)
            return build(
                name = json.optString("name").ifBlank { UNNAMED },
                foreground = "foreground" to colour("foreground"),
                background = "background" to colour("background"),
                ansi = WINDOWS_TERMINAL_ANSI.zip(ansi),
                cursor = colour("cursorColor"),
                selection = colour("selectionBackground"),
            )
        }

        /**
         * Lines of `key = value`, where the sixteen arrive as `palette = N=#rrggbb`. Keys a theme
         * has no place for, a font or the cursor's text colour, are passed over.
         */
        private fun ghostty(text: String): ThemeImport {
            val values = mutableMapOf<String, String>()
            val palette = arrayOfNulls<Int>(16)
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                if (key == "palette") {
                    val index = value.substringBefore('=').trim().toIntOrNull()
                    if (index != null && index in 0..15) palette[index] = parseHex(value.substringAfter('=', ""))
                } else {
                    values[key] = value
                }
            }
            return build(
                name = UNNAMED,
                foreground = "foreground" to values["foreground"]?.let(::parseHex),
                background = "background" to values["background"]?.let(::parseHex),
                ansi = palette.mapIndexed { index, colour -> "palette $index" to colour },
                cursor = values["cursor-color"]?.let(::parseHex),
                selection = values["selection-background"]?.let(::parseHex),
            )
        }

        private fun build(
            name: String,
            foreground: Pair<String, Int?>,
            background: Pair<String, Int?>,
            ansi: List<Pair<String, Int?>>,
            cursor: Int?,
            selection: Int?,
        ): ThemeImport {
            val required = listOf(background, foreground) + ansi
            val missing = required.filter { it.second == null }.map { it.first }
            if (missing.isNotEmpty()) return Incomplete(missing)
            val fg = foreground.second!!
            val bg = background.second!!
            return Read(
                TerminalPalette(
                    name = name,
                    foreground = fg,
                    background = bg,
                    cursor = cursor ?: fg,
                    selection = selection ?: mix(bg, fg, SELECTION_MIX),
                    ansi = IntArray(16) { ansi[it].second!! },
                ),
            )
        }

        /** [from] moved [amount] of the way towards [to], channel by channel. */
        private fun mix(from: Int, to: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val a = from shr shift and 0xFF
                val b = to shr shift and 0xFF
                return (a + (b - a) * amount).toInt() shl shift
            }
            return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
        }

        private val GHOSTTY_KEY = Regex("""(?m)^\s*(palette|background|foreground)\s*=""")

        private val WINDOWS_TERMINAL_ANSI = listOf(
            "black", "red", "green", "yellow", "blue", "purple", "cyan", "white",
            "brightBlack", "brightRed", "brightGreen", "brightYellow", "brightBlue", "brightPurple", "brightCyan", "brightWhite",
        )

        /** A selection the text did not give, light enough to see over the background and no more. */
        private const val SELECTION_MIX = 0.25f
    }
}
