package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.components.LimentyIconButton
import dev.wadackel.limenty.ui.components.overhangVertically
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * The presets, then the themes made here under their own heading, [columns] to a row, each card an
 * equal share of the width. The tile that makes a new theme ends the grid, where the new one will
 * appear.
 */
@Composable
internal fun ThemeGrid(
    preferences: TerminalPreferences,
    mono: FontFamily,
    columns: Int,
    onSelect: (String) -> Unit,
    onMore: (String) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = TerminalPalette.All.map { ThemeTile.Theme(it.name, it) }
    val custom = preferences.customThemes.map { ThemeTile.Theme(it.id, it.palette) } + ThemeTile.New
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        TileRows(presets, columns) { tile -> ThemeTileContent(tile, preferences.themeId, mono, onSelect, onMore, onNew) }
        Text(
            "Your themes",
            style = LimentyText.Secondary,
            color = LimentyColors.Text3,
            modifier = Modifier.padding(top = 6.dp),
        )
        TileRows(custom, columns) { tile -> ThemeTileContent(tile, preferences.themeId, mono, onSelect, onMore, onNew) }
    }
}

private sealed interface ThemeTile {
    data class Theme(val id: String, val palette: TerminalPalette) : ThemeTile

    data object New : ThemeTile
}

@Composable
private fun TileRows(tiles: List<ThemeTile>, columns: Int, tile: @Composable (ThemeTile) -> Unit) {
    tiles.chunked(columns).forEach { row ->
        // As tall as the tallest, so the new-theme tile matches the cards beside it.
        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            row.forEach { Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile(it) } }
            // A short last row keeps the others' width rather than stretching to fill.
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ThemeTileContent(
    tile: ThemeTile,
    selectedId: String,
    mono: FontFamily,
    onSelect: (String) -> Unit,
    onMore: (String) -> Unit,
    onNew: () -> Unit,
) {
    when (tile) {
        is ThemeTile.Theme -> ThemeCard(
            palette = tile.palette,
            mono = mono,
            selected = tile.id == selectedId,
            onSelect = { onSelect(tile.id) },
            onMore = { onMore(tile.id) },
            modifier = Modifier.fillMaxSize(),
        )
        ThemeTile.New -> NewThemeTile(onClick = onNew, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The way to a theme of one's own: dashed, because it is a place for a card rather than one.
 */
@Composable
private fun NewThemeTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = LimentyShapes.Surface
    Column(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(shape)
            .drawBehind {
                val radius = 12.dp.toPx()
                drawRoundRect(
                    color = LimentyColors.Line,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("+", style = LimentyText.Title, color = LimentyColors.Accent)
        Text("New theme", style = LimentyText.Label)
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
    onMore: () -> Unit,
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
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                palette.name,
                style = LimentyText.Secondary,
                color = if (selected) LimentyColors.Accent else LimentyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // In place of the six colour swatches the row used to end on: the sample above already
            // shows the colours, and the row has room for one of the two.
            LimentyIconButton(
                contentDescription = "More for ${palette.name}",
                onClick = onMore,
                modifier = Modifier.overhangVertically(12.dp),
            ) {
                Text("···", color = LimentyColors.Text3, fontSize = 18.sp)
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
