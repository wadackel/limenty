package dev.wadackel.limenty.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The chrome's palette, named by role. `docs/design.md` says when each role is used.
 *
 * Fixed rather than derived from the terminal theme: the terminal's colours changing is meant to be
 * the only signal that a theme was switched, and Solarized Dark is low-contrast by design — a chrome
 * matched to it sinks the other themes. The background sits darker than every preset's background
 * (the darkest is Night Owl's `#011627`), so the terminal stays in front whichever theme is showing.
 *
 * Depth is told by lightness, not by borders: the nearer a surface, the lighter it is. The greys
 * lean slightly towards the accent's hue (oklch hue 295) so the violet does not sit on them as a
 * foreign colour. Status colours share the accent's lightness and chroma and differ only in hue,
 * which is what keeps four coloured things on one screen from reading as four palettes.
 */
object LimentyColors {
    /** The screen. The darkest thing the chrome draws. */
    val Bg = Color(0xFF0C0C0F)

    /** Cards, settings groups, the unfolded display's detail pane, the key bar. */
    val Surface = Color(0xFF17161B)

    /** A surface placed on a surface (a group inside the detail pane), a pinned key, a field. */
    val Surface2 = Color(0xFF1E1D22)

    /** The nearest surface: a connected host, a dialog, a key that scrolls. */
    val Surface3 = Color(0xFF25242A)

    /** A key on a [Surface3]: a key in a key group's popup. */
    val Surface4 = Color(0xFF302F36)

    /** A key group's slot while its popup is open. */
    val Surface5 = Color(0xFF3E3C46)

    /**
     * The key under a finger sliding across a key group's popup. Lighter rather than accented,
     * because the popup is drawn over the terminal and the accent stays off keys there.
     */
    val KeyUnderFinger = Color(0xFF5A5866)

    /** A hairline between things that sit on the same surface: the key bar's pinned edge. */
    val Line = Color(0xFF36343B)

    /** What the terminal is dimmed to while something in front of it needs answering. */
    val Scrim = Color(0xCC0C0C0F)

    val Text = Color(0xFFEAE9EE)

    /** Values, descriptions, addresses. */
    val Text2 = Color(0xFFADABB4)

    /** The quietest text: an empty value, a tail domain, a note. */
    val Text3 = Color(0xFF807F88)

    /** The launcher mark's upper arm, so the icon and the app it opens share one colour. */
    val Accent = Color(0xFFA99BEA)

    /** Text and glyphs on an accent fill. */
    val OnAccent = Color(0xFF1E1838)

    /** The selected item in a list: the accent at 18%. */
    val AccentSelection = Color(0x2EA99BEA)

    /** A live session. */
    val Ok = Color(0xFF73C786)

    /** Something to read before going on: a changed host key, a key bar too tight to scroll. */
    val Warn = Color(0xFFECC16B)
    val WarnSurface = Color(0xFF312408)

    /** Something that cannot be undone: a deletion, trusting a new host key. */
    val Danger = Color(0xFFF8818D)
    val DangerSurface = Color(0xFF3B1B1E)
}
