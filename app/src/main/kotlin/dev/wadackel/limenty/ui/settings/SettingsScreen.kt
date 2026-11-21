package dev.wadackel.limenty.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalTypography
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.components.LimentyChevron
import dev.wadackel.limenty.ui.components.LimentyIconButton
import dev.wadackel.limenty.ui.components.LimentySlider
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.components.SwitchRow
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyMotion
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentySpacing
import dev.wadackel.limenty.ui.theme.LimentyText
import kotlin.math.roundToInt

/**
 * A part of settings: a sub-screen of the index on the folded display, an item in the sidebar on
 * the unfolded one.
 */
enum class SettingsSection(val title: String) {
    Appearance("Appearance"),
    Keyboard("Keyboard & key bar"),
    Gestures("Gestures"),
    Screen("Screen"),
    About("About"),
}

/**
 * What the folded display shows for [section]: a sub-screen, or null for the index.
 *
 * Screen is a single switch, and a sub-screen holding one switch is a tap spent for nothing, so on
 * the folded display that switch sits on the index and the section has no page of its own.
 */
internal fun foldedPage(section: SettingsSection?): SettingsSection? = section?.takeUnless { it == SettingsSection.Screen }

/** What the unfolded display's pane shows. Its sidebar always has something selected. */
internal fun panePage(section: SettingsSection?): SettingsSection = section ?: SettingsSection.Appearance

internal fun isWideSettings(width: Dp): Boolean = width >= LimentySpacing.WideLayout

/**
 * What a settings group is drawn on: the screen on the folded display, the floating pane on the
 * unfolded one. A group in the pane is a surface on a surface, so it takes the next step up.
 */
internal data class SettingsFrame(val surface: Color, val margin: Dp)

internal val LocalSettingsFrame = staticCompositionLocalOf {
    SettingsFrame(surface = LimentyColors.Surface, margin = LimentySpacing.ScreenMargin)
}

/**
 * Global settings.
 *
 * Every control here applies to the live terminal immediately — no apply button, no reconnect —
 * because a setting you cannot see the effect of is a setting you cannot tune.
 *
 * [section] is held by the caller rather than here, so folding or unfolding the device — which
 * swaps the index for the sidebar — keeps the section that was open.
 */
@Composable
fun SettingsScreen(
    preferences: TerminalPreferences,
    typefaces: TerminalTypefaces,
    section: SettingsSection?,
    onSelectSection: (SettingsSection?) -> Unit,
    onChange: ((TerminalPreferences) -> TerminalPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenKeyBar: () -> Unit = {},
    /** Shown beside About, so which build this is can be read without opening it. */
    version: String = "",
    /** The About section's content. Supplied by the caller, which owns reading the licences. */
    about: @Composable () -> Unit = {},
    /** Applies a slider's value without persisting it; [onSettled] does that once at the end. */
    onDrag: ((TerminalPreferences) -> TerminalPreferences) -> Unit = onChange,
    onSettled: () -> Unit = {},
    /** False while the screen slides out after the app has moved on; its own Back step stops then. */
    active: Boolean = true,
) {
    val content = SettingsContent(preferences, typefaces, onChange, onDrag, onSettled, onOpenKeyBar, version, about)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LimentyColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = isWideSettings(maxWidth)
        // On the unfolded display Back leaves settings whatever is selected, which the app's own
        // handler already does; only the folded display has a sub-screen to step back out of.
        BackHandler(enabled = active && !wide && foldedPage(section) != null) { onSelectSection(null) }
        if (wide) {
            content.TwoPane(selected = panePage(section), onSelect = onSelectSection, onBack = onBack)
        } else {
            content.Folded(page = foldedPage(section), onSelect = onSelectSection, onBack = onBack)
        }
    }
}

/** What every part of settings is drawn from, gathered so the two layouts can share the pages. */
private class SettingsContent(
    val preferences: TerminalPreferences,
    val typefaces: TerminalTypefaces,
    val onChange: ((TerminalPreferences) -> TerminalPreferences) -> Unit,
    val onDrag: ((TerminalPreferences) -> TerminalPreferences) -> Unit,
    val onSettled: () -> Unit,
    val onOpenKeyBar: () -> Unit,
    val version: String,
    val about: @Composable () -> Unit,
)

@Composable
private fun SettingsContent.Folded(page: SettingsSection?, onSelect: (SettingsSection?) -> Unit, onBack: () -> Unit) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != null
            val enterFrom: (Int) -> Int = { width -> if (forward) width else -width }
            val exitTo: (Int) -> Int = { width -> if (forward) -width else width }
            (slideInHorizontally(tween(LimentyMotion.TRANSITION_MILLIS), enterFrom) + fadeIn(tween(LimentyMotion.TRANSITION_MILLIS)))
                .togetherWith(slideOutHorizontally(tween(LimentyMotion.TRANSITION_MILLIS), exitTo) + fadeOut(tween(LimentyMotion.TRANSITION_MILLIS)))
        },
        label = "settings page",
    ) { current ->
        if (current == null) {
            Index(onOpen = onSelect, onBack = onBack)
        } else {
            Page(
                page = current,
                header = {
                    SettingsHeader(onBack = { onSelect(null) })
                    PageTitle(current.title)
                },
                themeColumns = 2,
                gesturesSideBySide = false,
            )
        }
    }
}

@Composable
private fun SettingsContent.Index(onOpen: (SettingsSection) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsHeader(onBack = onBack)
        PageTitle("Settings")
        SettingsHeading("Terminal")
        SettingsGroup {
            SettingsRow("Appearance", onClick = { onOpen(SettingsSection.Appearance) }, secondary = appearanceSummary())
            SettingsRow("Keyboard & key bar", onClick = { onOpen(SettingsSection.Keyboard) }, secondary = keySetSummary())
            SettingsRow("Gestures", onClick = { onOpen(SettingsSection.Gestures) }, secondary = gestureSummary())
        }
        SettingsHeading("Screen")
        SettingsGroup { KeepAwakeRow() }
        SettingsHeading("About")
        SettingsGroup {
            SettingsRow("About Limenty", onClick = { onOpen(SettingsSection.About) }) {
                Text(version, style = LimentyText.Mono)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The sidebar, and the selected section in a floating pane beside it. */
@Composable
private fun SettingsContent.TwoPane(selected: SettingsSection, onSelect: (SettingsSection?) -> Unit, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(SIDEBAR_WIDTH)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, bottom = 24.dp),
        ) {
            Row(modifier = Modifier.heightIn(min = 56.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
            }
            Text("Settings", style = LimentyText.Title, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
            NavHeading("Terminal")
            NavItem(SettingsSection.Appearance, appearanceSummary(), selected, onSelect)
            NavItem(SettingsSection.Keyboard, keySetSummary(), selected, onSelect)
            NavItem(SettingsSection.Gestures, gestureSummary(), selected, onSelect)
            NavHeading("General")
            NavItem(SettingsSection.Screen, "Keep awake: ${if (preferences.keepScreenAwake) "on" else "off"}", selected, onSelect)
            NavItem(SettingsSection.About, version, selected, onSelect)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
                .clip(LimentyShapes.Floating)
                .background(LimentyColors.Surface),
        ) {
            CompositionLocalProvider(
                LocalSettingsFrame provides SettingsFrame(surface = LimentyColors.Surface2, margin = LimentySpacing.ScreenMarginWide),
            ) {
                // Keyed so each section starts at its own top rather than at the last one's scroll.
                key(selected) {
                    Page(
                        page = selected,
                        header = { PageTitle(selected.title, modifier = Modifier.padding(top = 12.dp)) },
                        themeColumns = 3,
                        gesturesSideBySide = true,
                    )
                }
            }
        }
    }
}

/**
 * One section's page, with [header] above it.
 *
 * Appearance keeps its preview pinned with the header: the sliders and pickers below it are what
 * change it, and a preview scrolled out of sight while they are being moved shows nothing.
 */
@Composable
private fun SettingsContent.Page(
    page: SettingsSection,
    header: @Composable ColumnScope.() -> Unit,
    themeColumns: Int,
    gesturesSideBySide: Boolean,
) {
    val pinned = page == SettingsSection.Appearance
    Column(modifier = Modifier.fillMaxSize()) {
        if (pinned) {
            header()
            MetricsSample(
                preferences,
                typefaces,
                modifier = Modifier.padding(horizontal = LocalSettingsFrame.current.margin).padding(bottom = 6.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (!pinned) header()
            when (page) {
                SettingsSection.Appearance -> AppearanceBody(themeColumns)
                SettingsSection.Keyboard -> KeyboardBody()
                SettingsSection.Gestures -> GestureSettings(
                    bindings = preferences.gestures,
                    onChange = { bindings -> onChange { it.copy(gestures = bindings) } },
                    doubleTapDragZoom = preferences.doubleTapDragZoom,
                    onDoubleTapDragZoomChange = { enabled -> onChange { it.copy(doubleTapDragZoom = enabled) } },
                    sideBySide = gesturesSideBySide,
                )
                SettingsSection.Screen -> SettingsGroup(modifier = Modifier.padding(top = 8.dp)) { KeepAwakeRow() }
                SettingsSection.About -> about()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsContent.AppearanceBody(themeColumns: Int) {
    val font = TerminalFont.byId(preferences.typography.fontId)
    SettingsHeading("Theme", preferences.palette.name)
    ThemeGrid(
        selected = preferences.palette.name,
        mono = typefaces.family(font),
        columns = themeColumns,
        onSelect = { name -> onChange { it.copy(paletteName = name) } },
        modifier = Modifier.padding(horizontal = LocalSettingsFrame.current.margin),
    )
    SettingsHeading("Font", font.displayName)
    Column(
        modifier = Modifier.padding(horizontal = LocalSettingsFrame.current.margin),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalFont.All.forEach { candidate ->
            FontRow(
                font = candidate,
                family = typefaces.family(candidate),
                selected = candidate.id == font.id,
                onSelect = { onChange { it.copy(typography = it.typography.copy(fontId = candidate.id)) } },
            )
        }
    }
    SettingsHeading("Metrics")
    SettingsGroup {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LimentySlider(
                label = "Font size",
                value = preferences.typography.fontSizeSp,
                range = TerminalTypography.MIN_FONT_SIZE_SP..TerminalTypography.MAX_FONT_SIZE_SP,
                unit = "sp",
                onValueChange = { size -> onDrag { it.copy(typography = it.typography.withFontSize(size)) } },
                onValueSettled = onSettled,
            )
            LimentySlider(
                label = "Line height",
                value = preferences.typography.lineHeight,
                range = TerminalTypography.MIN_LINE_HEIGHT..TerminalTypography.MAX_LINE_HEIGHT,
                format = { "%.2f".format(java.util.Locale.ROOT, it) },
                onValueChange = { height -> onDrag { it.copy(typography = it.typography.copy(lineHeight = height)) } },
                onValueSettled = onSettled,
            )
            LimentySlider(
                label = "Letter spacing",
                value = preferences.typography.letterSpacingSp,
                range = TerminalTypography.MIN_LETTER_SPACING_SP..TerminalTypography.MAX_LETTER_SPACING_SP,
                unit = "sp",
                format = { "%.1f".format(java.util.Locale.ROOT, it) },
                onValueChange = { spacing -> onDrag { it.copy(typography = it.typography.copy(letterSpacingSp = spacing)) } },
                onValueSettled = onSettled,
            )
        }
    }
}

@Composable
private fun SettingsContent.KeyboardBody() {
    SettingsHeading("Keyboard")
    SettingsGroup {
        SwitchRow(
            label = "Restore keyboard on return",
            description = "When off, the keyboard always opens on coming back to the terminal.",
            checked = preferences.restoreKeyboardOnReturn,
            onCheckedChange = { enabled -> onChange { it.copy(restoreKeyboardOnReturn = enabled) } },
        )
        SwitchRow(
            label = "Share with the keyboard",
            description = "Lets the keyboard keep copies in its clipboard history, and learn " +
                "from what you type here — including at a password prompt.",
            checked = preferences.allowPersonalizedLearning,
            onCheckedChange = { enabled -> onChange { it.copy(allowPersonalizedLearning = enabled) } },
        )
    }
    SettingsHeading("Key bar")
    SettingsGroup {
        SettingsRow("Key sets", onClick = onOpenKeyBar, secondary = keySetSummary()) {
            val count = preferences.keySets.size
            if (count > 1) Text("$count sets", style = LimentyText.Secondary)
        }
    }
}

@Composable
private fun SettingsContent.KeepAwakeRow() {
    SwitchRow(
        label = "Keep screen awake",
        description = "Only while the terminal is visible.",
        checked = preferences.keepScreenAwake,
        onCheckedChange = { enabled -> onChange { it.copy(keepScreenAwake = enabled) } },
    )
}

private fun SettingsContent.appearanceSummary(): String {
    val font = TerminalFont.byId(preferences.typography.fontId)
    return "${preferences.palette.name} · ${font.displayName} · ${preferences.typography.fontSizeSp.roundToInt()}sp"
}

private fun SettingsContent.keySetSummary(): String {
    val set = preferences.keySet
    return "${set.name} · ${set.pinned.size} pinned · ${set.scrolling.size} scrolling"
}

private fun SettingsContent.gestureSummary(): String {
    val assigned = preferences.gestures.entries().count { (_, behaviour) -> behaviour !is GestureBehaviour.None }
    return "$assigned of ${Gesture.entries.size} assigned"
}

/** The back arrow at the top of every settings screen, the key bar editor's included. */
@Composable
internal fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.heightIn(min = 56.dp).padding(start = 8.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    LimentyIconButton(contentDescription = "Back", onClick = onBack) {
        LimentyChevron(16.dp, 180f, LimentyColors.Text2)
    }
}

/** A screen's or a pane's name, on the edge the section headings below it start from. */
@Composable
internal fun PageTitle(text: String, modifier: Modifier = Modifier) {
    val margin = LocalSettingsFrame.current.margin + GROUP_TEXT_INSET
    Text(
        text = text,
        style = LimentyText.Title,
        modifier = modifier.padding(start = margin, end = margin, top = 8.dp, bottom = 6.dp),
    )
}

/** A section heading over a group, set in from the group's edge towards the labels inside it. */
@Composable
internal fun SettingsHeading(title: String, value: String? = null) {
    val margin = LocalSettingsFrame.current.margin + GROUP_TEXT_INSET
    SectionHeading(title, value, modifier = Modifier.padding(start = margin, end = margin, top = 22.dp, bottom = 8.dp))
}

/** Rows that belong together, on one surface and with no lines between them. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val frame = LocalSettingsFrame.current
    Column(
        modifier = modifier
            .padding(horizontal = frame.margin)
            .fillMaxWidth()
            .clip(LimentyShapes.Surface)
            .background(frame.surface)
            .padding(horizontal = LimentySpacing.SurfacePadding),
        content = content,
    )
}

/** A row that opens something, with what it currently holds on a second line. */
@Composable
internal fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = LimentyText.Body)
            if (secondary != null) Text(secondary, style = LimentyText.Secondary)
        }
        trailing()
        LimentyChevron(14.dp, 0f, LimentyColors.Text3)
    }
}

@Composable
private fun NavHeading(title: String) {
    Text(title, style = LimentyText.Section, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
}

/** One sidebar item. No box around the list: the selected item's tint is the only shape in it. */
@Composable
private fun NavItem(
    section: SettingsSection,
    value: String,
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
) {
    val isSelected = section == selected
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clip(LimentyShapes.Surface)
            .background(if (isSelected) LimentyColors.AccentSelection else Color.Transparent)
            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(section) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            section.title,
            style = LimentyText.Body,
            color = if (isSelected) LimentyColors.Accent else LimentyColors.Text,
            fontWeight = if (isSelected) FontWeight.Medium else null,
        )
        Text(value, style = LimentyText.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal val ROW_HEIGHT = 56.dp

/** How far headings and titles sit in from a group's edge. */
internal val GROUP_TEXT_INSET = 8.dp

private val SIDEBAR_WIDTH = 290.dp
