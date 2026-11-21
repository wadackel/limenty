package dev.wadackel.limenty.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wadackel.limenty.data.NoticeLicense
import dev.wadackel.limenty.data.OpenSourceNotice
import dev.wadackel.limenty.ui.components.LimentyDialogMaxWidth
import dev.wadackel.limenty.ui.components.LimentyMark
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.settings.LocalSettingsFrame
import dev.wadackel.limenty.ui.settings.ROW_HEIGHT
import dev.wadackel.limenty.ui.settings.SettingsGroup
import dev.wadackel.limenty.ui.settings.SettingsHeading
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyShapes
import dev.wadackel.limenty.ui.theme.LimentyText

/** What the build says about itself, filled from BuildConfig by the caller. */
internal data class BuildInfo(
    val version: String,
    val type: String,
    val commit: String,
    val dirty: Boolean,
) {
    val commitLabel: String get() = if (dirty) "$commit-dirty" else commit
}

internal const val SOURCE_URL = "https://github.com/wadackel/limenty"

/** What a sheet is showing: one line of the list, or Limenty's own licence. */
private sealed interface SheetContent {
    data class Notice(val notice: OpenSourceNotice) : SheetContent

    data class Own(val license: NoticeLicense) : SheetContent
}

/**
 * Says what this build is and what it ships with: the About section of settings, under whatever
 * header the settings layout gives it.
 *
 * [notices] is null while the definitions and licence files are still being read, which happens
 * off the main thread; the identity above the list does not wait for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSection(
    build: BuildInfo,
    ownLicense: NoticeLicense?,
    notices: List<OpenSourceNotice>?,
    fontFamilyOf: (OpenSourceNotice) -> FontFamily?,
) {
    var sheet by remember { mutableStateOf<SheetContent?>(null) }
    Identity(build = build, onOpenLicense = { ownLicense?.let { sheet = SheetContent.Own(it) } })
    SettingsHeading("Open source", notices?.size?.toString())
    SettingsGroup {
        notices.orEmpty().forEach { notice ->
            NoticeRow(notice, fontFamilyOf(notice), onClick = { sheet = SheetContent.Notice(notice) })
        }
    }

    sheet?.let { content ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            // Below the status bar: a licence is taller than the screen, and the sheet would
            // otherwise rise under the clock.
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            // The width dialogs stop at, for the same reason: on the unfolded display a licence
            // set full width runs to lines too long to keep your place in.
            sheetMaxWidth = LimentyDialogMaxWidth,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = LimentyColors.Surface,
            scrimColor = LimentyColors.Scrim,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LimentyColors.Line),
                )
            },
        ) {
            when (content) {
                is SheetContent.Notice -> NoticeSheet(content.notice, fontFamilyOf(content.notice))
                is SheetContent.Own -> LicenseSheet(
                    title = "Limenty",
                    subtitle = content.license.name,
                    parts = emptyList(),
                    licenses = listOf(content.license),
                    titleFamily = null,
                )
            }
        }
    }
}

@Composable
private fun Identity(build: BuildInfo, onOpenLicense: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val surface = LocalSettingsFrame.current.surface
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(LimentyShapes.Floating)
                .background(surface),
            contentAlignment = Alignment.Center,
        ) {
            LimentyMark(size = 58.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text("Limenty", style = LimentyText.Heading)
        Spacer(Modifier.height(2.dp))
        Text("An SSH terminal for Android, built for touch.", style = LimentyText.Secondary)
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BuildRow("Version", build.version) { copy(context, "Version", build.version) }
            BuildRow("Build", build.type) { copy(context, "Build", build.type) }
            BuildRow("Commit", build.commitLabel, dirty = build.dirty) { copy(context, "Commit", build.commitLabel) }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalAction("Source ↗", LimentyColors.Accent, surface) { uriHandler.openUri(SOURCE_URL) }
            TonalAction("MIT License", LimentyColors.Text, surface, onOpenLicense)
        }
    }
}

/** A label to the left of the centre line and its value to the right, as an About window lays it out. */
@Composable
private fun BuildRow(label: String, value: String, dirty: Boolean = false, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .width(300.dp)
            .clip(LimentyShapes.Small)
            .clickable(onClickLabel = "Copy $label", onClick = onCopy)
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = LimentyText.Secondary,
            color = LimentyColors.Text3,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Text(
            buildAnnotatedString {
                if (dirty) {
                    append(value.removeSuffix("-dirty"))
                    withStyle(SpanStyle(color = LimentyColors.Warn)) { append("-dirty") }
                } else {
                    append(value)
                }
            },
            style = LimentyText.Mono,
            color = LimentyColors.Text,
            modifier = Modifier.weight(1.25f),
        )
    }
}

@Composable
private fun TonalAction(label: String, color: Color, surface: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(LimentyShapes.Surface)
            .background(surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LimentyText.Label, color = color)
    }
}

@Composable
private fun NoticeRow(notice: OpenSourceNotice, family: FontFamily?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(notice.name, style = LimentyText.Body, fontFamily = family)
            Text(notice.detail, style = LimentyText.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            notice.licenses.forEach { LicenseChip(it.label) }
        }
    }
}

@Composable
private fun LicenseChip(label: String) {
    Text(
        label,
        style = LimentyText.MonoMeta,
        color = LimentyColors.Text2,
        maxLines = 1,
        modifier = Modifier
            .clip(LimentyShapes.Small)
            .background(LimentyColors.Surface3)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun NoticeSheet(notice: OpenSourceNotice, family: FontFamily?) {
    LicenseSheet(
        title = notice.name,
        subtitle = (listOf(notice.detail) + notice.licenses.map { it.name }).joinToString(" · "),
        parts = notice.parts,
        licenses = notice.licenses,
        titleFamily = family,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LicenseSheet(
    title: String,
    subtitle: String,
    parts: List<String>,
    licenses: List<NoticeLicense>,
    titleFamily: FontFamily?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = LimentyText.Heading, fontFamily = titleFamily)
            Text(subtitle, style = LimentyText.Secondary)
        }
        if (parts.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                parts.forEach { part ->
                    Text(
                        part,
                        style = LimentyText.MonoMeta,
                        color = LimentyColors.Text2,
                        modifier = Modifier
                            .clip(LimentyShapes.Small)
                            .background(LimentyColors.Surface2)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
        licenses.forEach { license ->
            if (licenses.size > 1) SectionHeading(license.name)
            Text(
                license.text,
                style = LimentyText.MonoMeta,
                color = LimentyColors.Text2,
                lineHeight = 17.6.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LimentyShapes.Surface)
                    .background(LimentyColors.Surface2)
                    .padding(16.dp),
            )
        }
    }
}

/**
 * Puts [value] on the clipboard and says so where the platform does not: from Android 13 the
 * system shows its own confirmation, and a second one from the app would repeat it.
 */
private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied $value", Toast.LENGTH_SHORT).show()
    }
}
