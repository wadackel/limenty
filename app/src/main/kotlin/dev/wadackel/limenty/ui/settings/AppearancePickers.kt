package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText

/** The themes, [columns] to a row, each card an equal share of the width. */
@Composable
internal fun ThemeGrid(
    selected: String,
    mono: FontFamily,
    columns: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        TerminalPalette.All.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                row.forEach { palette ->
                    ThemeCard(
                        palette = palette,
                        mono = mono,
                        selected = palette.name == selected,
                        onSelect = { onSelect(palette.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // A short last row keeps the others' width rather than stretching to fill.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A theme, shown as itself.
 *
 * The picker used to be a row of text chips: `Gruvbox Dark` is a name, not an appearance, and for
 * an app whose themes *are* its look, choosing one blind was the largest miss in the interface.
 * The card paints a few lines of terminal in the theme's own colours and lists the six ANSI
 * colours an application will actually reach for.
 */
@Composable
fun ThemeCard(
    palette: TerminalPalette,
    mono: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = LimentyShapes.Surface
    Column(
        modifier = modifier
            .clip(shape)
            .background(LocalSettingsFrame.current.surface)
            .selectedRing(selected)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            // The sample is a picture of a theme, not text to read out; the name is the answer.
            .semantics(mergeDescendants = true) { contentDescription = palette.name },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(palette.background))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SampleLine("❯ git status", Color(palette.resolve(2)), mono)
            Row {
                SampleLine("On branch ", Color(palette.foreground), mono)
                SampleLine("main", Color(palette.resolve(4)), mono)
            }
            SampleLine("  modified: Bar.kt", Color(palette.resolve(1)), mono)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SampleLine("❯ ", Color(palette.foreground), mono)
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 9.dp)
                        .background(Color(palette.cursor)),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                palette.name,
                style = LimentyText.Secondary,
                color = if (selected) LimentyColors.Accent else LimentyColors.Text,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(modifier = Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (index in 1..6) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(palette.resolve(index))),
                    )
                }
            }
        }
    }
}

@Composable
private fun SampleLine(text: String, color: Color, mono: FontFamily) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        lineHeight = 15.sp,
        // The bundled face, not the platform's monospace: the prompt glyph U+276F is missing from
        // the system font and falls back to a wide CJK bracket.
        fontFamily = mono,
        maxLines = 1,
    )
}

/**
 * A font, set in itself.
 *
 * The sample is chosen so the choice can be made from it: `0O1lI` is where monospaced faces differ
 * most, and the bracket run shows how the symbols a shell is full of are drawn.
 */
@Composable
fun FontRow(
    font: TerminalFont,
    family: FontFamily,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(LocalSettingsFrame.current.surface)
            .selectedRing(selected)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = FONT_SAMPLE,
            style = LimentyText.Body,
            fontFamily = family,
            maxLines = 1,
        )
        Text(
            text = font.displayName,
            style = LimentyText.Secondary,
            color = if (selected) LimentyColors.Accent else LimentyColors.Text2,
        )
    }
}

/**
 * The metrics preview: the sliders change this, so their effect is visible without leaving the
 * screen. A Japanese line is included because CJK does not sit on the monospaced cell — that
 * disagreement is a property of the typography, and worth seeing while choosing it.
 */
@Composable
internal fun MetricsSample(preferences: TerminalPreferences, typefaces: TerminalTypefaces, modifier: Modifier = Modifier) {
    val palette = preferences.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(Color(palette.background))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val size = preferences.typography.fontSizeSp.sp
        val height = (preferences.typography.fontSizeSp * preferences.typography.lineHeight).sp
        val tracking = preferences.typography.letterSpacingSp.sp
        listOf(
            "drwxr-xr-x  20 someone  640 Aug 15 dotfiles/" to palette.foreground,
            "-rw-r--r--   1 someone  189 May 23 zshenv" to palette.foreground,
            "❯ 日本語もこの行で確認できる" to palette.resolve(2),
        ).forEach { (line, colour) ->
            Text(
                text = line,
                color = Color(colour),
                fontSize = size,
                lineHeight = height,
                letterSpacing = tracking,
                fontFamily = typefaces.family(TerminalFont.byId(preferences.typography.fontId)),
                maxLines = 1,
            )
        }
    }
}

/**
 * The mark of a chosen option: the accent around it, drawn inside its edge. A tint or a heavier
 * surface for the chosen one was the alternative, and on a theme card it would have recoloured
 * the very colours being chosen between.
 */
internal fun Modifier.selectedRing(selected: Boolean, shape: Shape = LimentyShapes.Surface): Modifier = if (selected) border(2.dp, LimentyColors.Accent, shape) else this

private const val FONT_SAMPLE = "❯ ls -laF  0O1lI  {}[]<>=~"

private val CARD_GAP = 10.dp
