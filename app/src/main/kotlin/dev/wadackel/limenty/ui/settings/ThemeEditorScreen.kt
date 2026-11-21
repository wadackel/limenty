package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.data.hexOf
import dev.wadackel.limenty.data.parseHex
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyField
import dev.wadackel.limenty.ui.components.LimentyIconButton
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import java.util.Locale

/**
 * Edits one custom theme, colour by colour.
 *
 * A colour is found by where it appears: tapping text or the background in the sample at the top
 * picks the colour that draws it, and the rows below reach the ones the sample does not show.
 * Every change is saved and applied at once, like every other setting, so the way back is the
 * values the screen opened with: per colour, or all of them.
 *
 * Opening it does not select the theme. Looking at a theme should not recolour the sessions in use.
 */
@Composable
fun ThemeEditorScreen(
    preferences: TerminalPreferences,
    themeId: String,
    typefaces: TerminalTypefaces,
    onChange: ((TerminalPreferences) -> TerminalPreferences) -> Unit,
    onBack: () -> Unit,
    /** Applies a slider's value without persisting it; [onSettled] does that once at the end. */
    onDrag: ((TerminalPreferences) -> TerminalPreferences) -> Unit = onChange,
    onSettled: () -> Unit = {},
) {
    val theme = preferences.customThemes.firstOrNull { it.id == themeId }
    if (theme == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val palette = theme.palette
    // Saved with the screen, so folding the device midway keeps the values Revert goes back to.
    val opened = rememberSaveable(themeId, saver = PaletteSaver) { palette }
    var selected by rememberSaveable { mutableStateOf(ThemeColor.Blue) }
    val changed = ThemeColor.entries.filterTo(mutableSetOf()) { palette.colorOf(it) != opened.colorOf(it) }

    fun set(color: ThemeColor, argb: Int, dragging: Boolean) {
        val apply = if (dragging) onDrag else onChange
        apply { it.updateTheme(themeId) { current -> current.with(color, argb) } }
    }
    val revert: (ThemeColor) -> Unit = { color -> set(color, opened.colorOf(color), dragging = false) }
    val revertAll = { onChange { it.updateTheme(themeId) { current -> opened.copy(name = current.name) } } }
    val mono = typefaces.family(TerminalFont.byId(preferences.typography.fontId))

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LimentyColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = isWideSettings(maxWidth)
        // The cover display held sideways is wide enough for two columns at 751dp, but a fixed
        // 400dp panel left the sample under 300dp and cut its lines off; both give way there.
        val panelWidth = (maxWidth * 0.48f).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
        val sampleSize = if (maxWidth < ROOMY_WIDTH) 12.sp else 14.sp
        val margin = if (wide) LimentySpacing.ScreenMarginWide else LimentySpacing.ScreenMargin
        val controls: @Composable () -> Unit = {
            ColorRows(palette, selected, changed, onPick = { selected = it }, modifier = Modifier.padding(horizontal = margin))
            ColorControls(
                color = selected,
                argb = palette.colorOf(selected),
                changed = selected in changed,
                onSet = { argb, dragging -> set(selected, argb, dragging) },
                onSettled = onSettled,
                onRevert = { revert(selected) },
                modifier = Modifier.padding(start = margin, end = margin, top = 18.dp, bottom = 24.dp),
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            EditorHeader(name = palette.name, changedCount = changed.size, onRevertAll = revertAll, onBack = onBack)
            if (wide) {
                Row(
                    modifier = Modifier.weight(1f).padding(start = margin, end = margin, bottom = margin),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ThemeSample(palette, selected, mono, sampleSize, onPick = { selected = it }, modifier = Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .clip(LimentyShapes.Floating)
                            .background(LimentyColors.Surface)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 16.dp),
                    ) { controls() }
                }
            } else {
                // Fixed rather than scrolled with the controls: it is what a change is judged by, and
                // it has to stay in view while the keyboard is up for the hex field.
                ThemeSample(palette, selected, mono, 12.sp, onPick = { selected = it }, modifier = Modifier.padding(horizontal = margin))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp)) { controls() }
            }
        }
    }
}

@Composable
private fun EditorHeader(name: String, changedCount: Int, onRevertAll: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LimentyIconButton(contentDescription = "Back", onClick = onBack) {
            LimentyChevron(16.dp, 180f, LimentyColors.Text2)
        }
        Text(
            name,
            style = LimentyText.Heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (changedCount > 0) {
            Text("$changedCount changed", style = LimentyText.Secondary, color = LimentyColors.Text3)
        }
        LimentyTextButton("Revert all", onClick = onRevertAll, enabled = changedCount > 0)
    }
}

/**
 * A few lines of terminal output in the theme's colours, every span of it a way to its colour.
 *
 * The lines are chosen so most colours appear where they usually do — a path in blue, a removed
 * line in red — and the two rows of blocks at the end hold all sixteen, so none is out of reach.
 */
@Composable
private fun ThemeSample(
    palette: TerminalPalette,
    selected: ThemeColor,
    mono: FontFamily,
    fontSize: TextUnit,
    onPick: (ThemeColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Light outlines vanish on a light theme; the mark has to show on whatever the theme is.
    val mark = if (Oklch.of(palette.background).lightness > 0.6) Color.Black else Color.White
    val lineHeight = fontSize * 1.5f
    val lineHeightDp = with(LocalDensity.current) { lineHeight.toDp() }
    Column(
        modifier = modifier
            .clip(LimentyShapes.Surface)
            .background(Color(palette.background))
            .then(if (selected == ThemeColor.Background) Modifier.border(2.dp, LimentyColors.Accent, LimentyShapes.Surface) else Modifier)
            .pick(ThemeColor.Background, onPick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        SAMPLE.forEach { line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = lineHeightDp)
                    .then(
                        if (line.selected) {
                            Modifier
                                .background(Color(palette.selection))
                                .outlined(selected == ThemeColor.Selection, mark)
                                .pick(ThemeColor.Selection, onPick)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                line.spans.forEach { span ->
                    val color = Color(palette.colorOf(span.color))
                    val spanModifier = Modifier.outlined(selected == span.color, mark).pick(span.color, onPick)
                    when (span.kind) {
                        SpanKind.Text -> Text(
                            span.text,
                            color = color,
                            fontFamily = mono,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            modifier = spanModifier,
                        )
                        SpanKind.Block -> Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(fontSize.value.dp, fontSize.value.dp)
                                .then(spanModifier)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color),
                        )
                        SpanKind.Cursor -> Box(
                            modifier = Modifier
                                .size(width = (fontSize.value * 0.6f).dp, height = (fontSize.value * 1.2f).dp)
                                .then(spanModifier)
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.pick(color: ThemeColor, onPick: (ThemeColor) -> Unit): Modifier = clickable(
    interactionSource = null,
    indication = null,
    role = Role.Button,
    onClickLabel = color.label,
) { onPick(color) }

private fun Modifier.outlined(on: Boolean, mark: Color): Modifier = if (on) border(1.5.dp, mark, RoundedCornerShape(2.dp)) else this

private enum class SpanKind { Text, Block, Cursor }

private class Span(val color: ThemeColor, val text: String = "", val kind: SpanKind = SpanKind.Text, val bold: Boolean = false)

private class SampleLine(val spans: List<Span>, val selected: Boolean = false)

private val SAMPLE = listOf(
    SampleLine(listOf(Span(ThemeColor.Green, "❯ "), Span(ThemeColor.Blue, "~/dotfiles"), Span(ThemeColor.BrightBlack, " on "), Span(ThemeColor.Magenta, " main"), Span(ThemeColor.Red, " ✗"))),
    SampleLine(listOf(Span(ThemeColor.Green, "❯ "), Span(ThemeColor.Foreground, "ls -F"))),
    SampleLine(
        listOf(
            Span(ThemeColor.Blue, "bin/"),
            Span(ThemeColor.Foreground, "  "),
            Span(ThemeColor.Blue, "nvim/"),
            Span(ThemeColor.Foreground, "  "),
            Span(ThemeColor.Green, "install.sh*"),
            Span(ThemeColor.Foreground, "  "),
            Span(ThemeColor.Cyan, ".config@"),
        ),
    ),
    SampleLine(listOf(Span(ThemeColor.Green, "❯ "), Span(ThemeColor.Foreground, "git diff"))),
    SampleLine(listOf(Span(ThemeColor.Red, "-  theme = \"nord\""))),
    SampleLine(listOf(Span(ThemeColor.Green, "+  theme = \"custom\""))),
    SampleLine(listOf(Span(ThemeColor.BrightBlack, "# fonts are set in wezterm.lua"))),
    SampleLine(listOf(Span(ThemeColor.Yellow, "warning:"), Span(ThemeColor.Foreground, " LF will be replaced by CRLF"))),
    SampleLine(listOf(Span(ThemeColor.BrightRed, "error:", bold = true), Span(ThemeColor.Foreground, " failed to push some refs"))),
    SampleLine(listOf(Span(ThemeColor.Foreground, "this line is selected")), selected = true),
    SampleLine(listOf(Span(ThemeColor.White, "normal ")) + (0..7).map { Span(ThemeColor.ansi(it), kind = SpanKind.Block) }),
    SampleLine(listOf(Span(ThemeColor.BrightWhite, "bright ")) + (8..15).map { Span(ThemeColor.ansi(it), kind = SpanKind.Block) }),
    SampleLine(listOf(Span(ThemeColor.Green, "❯ "), Span(ThemeColor.Cursor, kind = SpanKind.Cursor))),
)

/** The twenty colours in their three rows, each marked when it differs from when the screen opened. */
@Composable
private fun ColorRows(
    palette: TerminalPalette,
    selected: ThemeColor,
    changed: Set<ThemeColor>,
    onPick: (ThemeColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ThemeColorGroup.entries.forEach { group ->
            val colors = ThemeColor.entries.filter { it.group == group }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.label, style = LimentyText.Secondary, color = LimentyColors.Text3, modifier = Modifier.width(56.dp))
                Row(modifier = Modifier.weight(1f)) {
                    colors.forEach { color ->
                        Swatch(
                            argb = palette.colorOf(color),
                            label = color.label,
                            selected = color == selected,
                            changed = color in changed,
                            onClick = { onPick(color) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Base has four; the rest of its row stays empty so its swatches line up above the others.
                    repeat(ROW_LENGTH - colors.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(argb: Int, label: String, selected: Boolean, changed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(LimentySpacing.TouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (changed) "$label, changed" else label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(if (selected) Modifier.border(2.dp, LimentyColors.Accent, RoundedCornerShape(10.dp)) else Modifier)
                .padding(4.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(argb))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp)),
        )
        if (changed) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 30.dp, bottom = 30.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(LimentyColors.Accent),
            )
        }
    }
}

/** The chosen colour: what it is, its exact value, and three sliders along OKLCH. */
@Composable
private fun ColorControls(
    color: ThemeColor,
    argb: Int,
    changed: Boolean,
    onSet: (argb: Int, dragging: Boolean) -> Unit,
    onSettled: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held here rather than derived from the colour each time: a grey has no hue, and deriving it
    // back would snap the hue slider to zero as soon as saturation reached the bottom.
    var lch by remember(color) { mutableStateOf(Oklch.of(argb)) }
    LaunchedEffect(argb) { if (lch.toArgb() != argb) lch = Oklch.of(argb) }
    var hex by remember(color) { mutableStateOf(hexOf(argb)) }
    LaunchedEffect(argb) { if (parseHex(hex) != argb) hex = hexOf(argb) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(argb))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(color.label, style = LimentyText.Body, fontWeight = FontWeight.Medium)
                Text(color.usage, style = LimentyText.Secondary)
                if (color.ansiIndex >= 0) Text("ANSI ${color.ansiIndex}", style = LimentyText.MonoMeta)
            }
            LimentyTextButton("Revert", onClick = onRevert, enabled = changed)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LimentyField(
                label = "Hex",
                value = hex,
                onValueChange = { text ->
                    hex = text
                    parseHex(text)?.let { value -> if (value != argb) onSet(value, false) }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            )
            if (looksMistyped(hex)) {
                Text("Enter six hex digits, like #7AA2F7", style = LimentyText.Secondary, color = LimentyColors.Danger)
            }
        }
        ColorSlider(
            label = "Lightness",
            value = lch.lightness.toFloat(),
            range = 0f..1f,
            format = { String.format(Locale.ROOT, "%.1f%%", it * 100) },
            track = { value -> lch.copy(lightness = value.toDouble()) },
            onValueChange = { value ->
                lch = lch.copy(lightness = value.toDouble())
                onSet(lch.toArgb(), true)
            },
            onValueSettled = onSettled,
        )
        ColorSlider(
            label = "Saturation",
            value = lch.chroma.toFloat(),
            range = 0f..Oklch.MAX_CHROMA.toFloat(),
            format = { String.format(Locale.ROOT, "%.1f", it * 100) },
            track = { value -> lch.copy(chroma = value.toDouble()) },
            onValueChange = { value ->
                lch = lch.copy(chroma = value.toDouble())
                onSet(lch.toArgb(), true)
            },
            onValueSettled = onSettled,
        )
        ColorSlider(
            label = "Hue",
            value = lch.hue.toFloat(),
            range = 0f..360f,
            format = { "${it.toInt()}°" },
            track = { value -> lch.copy(hue = value.toDouble()) },
            onValueChange = { value ->
                lch = lch.copy(hue = value.toDouble())
                onSet(lch.toArgb(), true)
            },
            onValueSettled = onSettled,
        )
    }
}

/** Said only once there is enough to be wrong about, not while the first digits are going in. */
private fun looksMistyped(text: String): Boolean {
    val digits = text.trim().removePrefix("#")
    if (parseHex(text) != null || digits.isEmpty()) return false
    return digits.length >= 6 || digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }
}

/**
 * A slider whose track is painted with the colours it would produce.
 *
 * Material's control underneath, for the same reason as [dev.wadackel.limenty.ui.components.LimentySlider]:
 * its gesture handling and its keyboard and screen-reader support are what a hand-rolled one lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    track: (Float) -> Oklch,
    onValueChange: (Float) -> Unit,
    onValueSettled: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = LimentyText.Secondary)
            Text(format(value), style = LimentyText.Mono, color = LimentyColors.Text)
        }
        val stops = (0..TRACK_STOPS).map { step ->
            val at = range.start + (range.endInclusive - range.start) * step / TRACK_STOPS
            Color(track(at).toArgb())
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueSettled,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(SLIDER_HEIGHT - 2.dp)
                        .border(1.dp, Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(1.dp)
                        .border(3.dp, Color.White, CircleShape),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SLIDER_HEIGHT)
                        .clip(RoundedCornerShape(SLIDER_HEIGHT / 2))
                        .background(Brush.horizontalGradient(stops)),
                )
            },
        )
    }
}

/** A palette as plain values, so the colours the editor opened with survive a fold. */
private val PaletteSaver = Saver<TerminalPalette, List<Any>>(
    save = { listOf(it.name, it.foreground, it.background, it.cursor, it.selection) + it.ansi.toList() },
    restore = { saved ->
        TerminalPalette(
            name = saved[0] as String,
            foreground = saved[1] as Int,
            background = saved[2] as Int,
            cursor = saved[3] as Int,
            selection = saved[4] as Int,
            ansi = IntArray(16) { saved[5 + it] as Int },
        )
    },
)

private const val ROW_LENGTH = 8
private const val TRACK_STOPS = 12
private val SLIDER_HEIGHT = 28.dp
private val MIN_PANEL_WIDTH = 340.dp
private val MAX_PANEL_WIDTH = 400.dp
private val ROOMY_WIDTH = 900.dp
