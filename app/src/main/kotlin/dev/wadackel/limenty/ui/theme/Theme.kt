package dev.wadackel.limenty.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app chrome is always dark: the terminal theme is an explicit choice, independent of the
 * Android light/dark setting, so following the system here would make
 * the surrounding UI disagree with the terminal on a light-mode device.
 *
 * The scheme is filled from [LimentyColors] rather than left at Material's defaults, which is
 * what put a generic purple on every control regardless of the terminal theme behind it. Most of
 * the UI reads [LimentyColors] directly; the scheme exists so a stock Material component that
 * slips through still lands somewhere deliberate.
 */
private val LimentyColorScheme = darkColorScheme(
    primary = LimentyColors.Accent,
    onPrimary = LimentyColors.OnAccent,
    secondary = LimentyColors.Accent,
    onSecondary = LimentyColors.OnAccent,
    background = LimentyColors.Bg,
    onBackground = LimentyColors.Text,
    surface = LimentyColors.Surface,
    // Menus and the like raise themselves off the surface; without this they raise into a tint
    // Material derives from its own default and land somewhere lavender.
    surfaceContainer = LimentyColors.Surface3,
    onSurface = LimentyColors.Text,
    surfaceVariant = LimentyColors.Surface2,
    onSurfaceVariant = LimentyColors.Text2,
    outline = LimentyColors.Line,
    outlineVariant = LimentyColors.Surface3,
    error = LimentyColors.Danger,
    onError = LimentyColors.Bg,
)

@Composable
fun LimentyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LimentyColorScheme, content = content)
}

/**
 * Edge-to-edge with the system bars drawn for the chrome, which is dark whatever the Android setting.
 * The default style reads the system's night mode instead, and in light mode draws dark icons on it.
 */
fun ComponentActivity.enableLimentyEdgeToEdge() {
    // `auto` told the background is always dark, not `SystemBarStyle.dark`: that one also turns off
    // the scrim the system draws behind three-button navigation.
    val dark = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { true }
    enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
}
