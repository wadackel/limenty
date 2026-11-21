package dev.wadackel.limenty.ui.settings

import dev.wadackel.limenty.core.terminal.TerminalPalette
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** The rows the theme editor lays the colours out in. */
enum class ThemeColorGroup(val label: String) { Base("Base"), Normal("Normal"), Bright("Bright") }

/**
 * One of the twenty colours a theme holds, with what it is typically used for.
 *
 * The uses are what a shell, `ls` and `git` commonly draw in that colour, not a guarantee: an
 * application picks its own. They are there so a colour can be found by what it looks like on
 * screen rather than by an ANSI number nobody remembers.
 */
enum class ThemeColor(val label: String, val group: ThemeColorGroup, val usage: String, val ansiIndex: Int = -1) {
    Background("Background", ThemeColorGroup.Base, "Screen background"),
    Foreground("Foreground", ThemeColorGroup.Base, "Plain text"),
    Cursor("Cursor", ThemeColorGroup.Base, "Cursor and selection handles"),
    Selection("Selection", ThemeColorGroup.Base, "Selected text background"),
    Black("Black", ThemeColorGroup.Normal, "Dark backgrounds such as status bars", 0),
    Red("Red", ThemeColorGroup.Normal, "Removed lines, uncommitted changes", 1),
    Green("Green", ThemeColorGroup.Normal, "Prompt, executables, added lines", 2),
    Yellow("Yellow", ThemeColorGroup.Normal, "Warnings", 3),
    Blue("Blue", ThemeColorGroup.Normal, "Directories, current path", 4),
    Magenta("Magenta", ThemeColorGroup.Normal, "Git branch", 5),
    Cyan("Cyan", ThemeColorGroup.Normal, "Symbolic links", 6),
    White("White", ThemeColorGroup.Normal, "Muted light text", 7),
    BrightBlack("Bright black", ThemeColorGroup.Bright, "Comments, dimmed hints", 8),
    BrightRed("Bright red", ThemeColorGroup.Bright, "Emphasised errors", 9),
    BrightGreen("Bright green", ThemeColorGroup.Bright, "Emphasised green", 10),
    BrightYellow("Bright yellow", ThemeColorGroup.Bright, "Emphasised yellow", 11),
    BrightBlue("Bright blue", ThemeColorGroup.Bright, "Emphasised blue", 12),
    BrightMagenta("Bright magenta", ThemeColorGroup.Bright, "Emphasised magenta", 13),
    BrightCyan("Bright cyan", ThemeColorGroup.Bright, "Emphasised cyan", 14),
    BrightWhite("Bright white", ThemeColorGroup.Bright, "Brightest text", 15),
    ;

    companion object {
        fun ansi(index: Int): ThemeColor = entries.first { it.ansiIndex == index }
    }
}

fun TerminalPalette.colorOf(color: ThemeColor): Int = when (color) {
    ThemeColor.Background -> background
    ThemeColor.Foreground -> foreground
    ThemeColor.Cursor -> cursor
    ThemeColor.Selection -> selection
    else -> ansi[color.ansiIndex]
}

fun TerminalPalette.with(color: ThemeColor, argb: Int): TerminalPalette = when (color) {
    ThemeColor.Background -> copy(background = argb)
    ThemeColor.Foreground -> copy(foreground = argb)
    ThemeColor.Cursor -> copy(cursor = argb)
    ThemeColor.Selection -> copy(selection = argb)
    else -> copy(ansi = ansi.copyOf().also { it[color.ansiIndex] = argb })
}

/**
 * A colour as OKLCH: lightness 0-1, chroma 0-[MAX_CHROMA], hue in degrees.
 *
 * The editor's sliders move along these rather than along HSL, because OKLCH's lightness is the
 * one a reader sees: moving HSL's from blue to yellow at the same value changes how light the text
 * looks, and a theme tuned for contrast drifts with every hue change.
 */
data class Oklch(val lightness: Double, val chroma: Double, val hue: Double) {
    /** The nearest colour the screen can show; channels outside sRGB are clipped. */
    fun toArgb(): Int {
        val a = chroma * cos(Math.toRadians(hue))
        val b = chroma * sin(Math.toRadians(hue))
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    companion object {
        /** Past this no sRGB colour has more chroma, so the slider stops here. */
        const val MAX_CHROMA = 0.37

        fun of(argb: Int): Oklch {
            val red = linear(argb shr 16 and 0xFF)
            val green = linear(argb shr 8 and 0xFF)
            val blue = linear(argb and 0xFF)
            val l = cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
            val m = cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
            val s = cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
            val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
            val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
            val b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
            return Oklch(lightness, hypot(a, b), hue)
        }

        private fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        private fun channel(linear: Double): Int {
            val c = linear.coerceIn(0.0, 1.0)
            val encoded = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
            return (encoded * 255).let { kotlin.math.round(it).toInt() }.coerceIn(0, 255)
        }
    }
}
