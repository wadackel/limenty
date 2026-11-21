package dev.wadackel.limenty.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The seven text roles. Every piece of chrome text is one of these.
 *
 * Sizes used to be picked per call site, and eleven of them accumulated between 10 and 28sp, close
 * enough to each other that none of them read as a level. Monospaced roles carry
 * [FontFamily.Monospace]; a call site that shows terminal content swaps in the terminal's own face.
 */
object LimentyText {
    /** A screen's or a pane's name: Settings, Appearance, a host in the detail pane. */
    val Title = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal, color = LimentyColors.Text)

    /** A host card's name, a dialog's title. */
    val Heading = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = LimentyColors.Text)

    /** A row's label, a menu item. */
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = LimentyColors.Text)

    /** Anything pressed: a button, Resume, Add. */
    val Label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = LimentyColors.Accent)

    /** A current value, a description, a chip. */
    val Secondary = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = LimentyColors.Text2)

    /** A section heading: the secondary size, weighted and in the accent. */
    val Section = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = LimentyColors.Accent)

    /** An address, a version. */
    val Mono = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, color = LimentyColors.Text2)

    /** A session's meta line: `nvim · 2h 14m`. */
    val MonoMeta = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = LimentyColors.Text3)
}
