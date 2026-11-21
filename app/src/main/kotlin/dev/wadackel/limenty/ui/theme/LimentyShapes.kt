package dev.wadackel.limenty.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corners, decided by how large a surface is rather than by what it is.
 *
 * A single radius was tried and the floating panes read as cards; a fully rounded button was tried
 * and it disagreed with every list item beside it. So three steps: what floats over the screen is
 * the roundest, what sits on it is middling, what sits inside that is the tightest. A switch is the
 * one exception, since its round track is what makes it read as a switch.
 */
object LimentyShapes {
    /** The unfolded display's detail pane, a dialog, a terminal overlay. */
    val Floating = RoundedCornerShape(20.dp)

    /** A card, a settings group, a picker option, a selected item, a key, a button. */
    val Surface = RoundedCornerShape(12.dp)

    /** A chip, and a session preview inside a card. */
    val Small = RoundedCornerShape(6.dp)
}

/** Insets that follow the same steps as [LimentyShapes]: the larger the surface, the more room inside. */
object LimentySpacing {
    val FloatingPadding = 24.dp
    val SurfacePadding = 16.dp
    val SmallPadding = 12.dp

    /** Left and right of a screen on the folded (cover) display. */
    val ScreenMargin = 16.dp

    /** Left and right of a screen on the unfolded display. */
    val ScreenMarginWide = 20.dp

    /**
     * Where a screen switches to the unfolded display's layout. The Fold's inner display is 933dp
     * across and its cover 475dp; the line falls between them.
     */
    val WideLayout = 700.dp

    /** Android asks for 48dp; a terminal's key bar argues its way below that, nothing else does. */
    val TouchTarget = 44.dp
}
