package dev.wadackel.limenty.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.CustomTheme
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.data.ThemeImport
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyMenuAction
import dev.wadackel.limenty.ui.components.LimentyNameDialog
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * The theme grid with what its cards' menus lead to: duplicating, renaming, deleting, and opening
 * the editor, and the new-theme tile's choice between a copy and a theme pasted in.
 *
 * A preset offers only Duplicate. Editing one in place would leave no way back to how it shipped.
 */
@Composable
internal fun ThemePicker(
    preferences: TerminalPreferences,
    mono: FontFamily,
    columns: Int,
    onChange: ((TerminalPreferences) -> TerminalPreferences) -> Unit,
    onEditTheme: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicating by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingNew by rememberSaveable { mutableStateOf(false) }
    // Not saved with the screen: a palette read from the clipboard is cheap to read again.
    var importing by remember { mutableStateOf<TerminalPalette?>(null) }
    var importProblem by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    ThemeGrid(
        preferences = preferences,
        mono = mono,
        columns = columns,
        onSelect = { id -> onChange { it.copy(themeId = id) } },
        onMore = { id -> menuFor = id },
        onNew = { choosingNew = true },
        modifier = modifier,
    )

    menuFor?.let { id ->
        val palette = preferences.themeOf(id) ?: return@let
        val custom = preferences.customThemes.any { it.id == id }
        LimentyDialog(title = palette.name, onDismissRequest = { menuFor = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (custom) {
                    LimentyMenuAction("Edit", {
                        menuFor = null
                        onEditTheme(id)
                    })
                    LimentyMenuAction("Rename", {
                        menuFor = null
                        renaming = id
                    })
                }
                LimentyMenuAction("Duplicate", {
                    menuFor = null
                    duplicating = id
                })
                if (custom) {
                    LimentyMenuAction("Delete", {
                        menuFor = null
                        deleting = id
                    }, color = LimentyColors.Danger)
                }
            }
            LimentyDialogActions {
                LimentyTextButton("Close", { menuFor = null }, color = LimentyColors.Text2)
            }
        }
    }

    if (choosingNew) {
        LimentyDialog(title = "New theme", onDismissRequest = { choosingNew = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LimentyMenuAction("Copy of ${preferences.palette.name}", {
                    choosingNew = false
                    duplicating = preferences.themeId
                })
                LimentyMenuAction("Paste from clipboard", {
                    choosingNew = false
                    when (val read = ThemeImport.parse(clipboardText(context))) {
                        is ThemeImport.Read -> importing = read.palette
                        is ThemeImport.Refused -> importProblem = refusedImportMessage(read)
                        ThemeImport.Unrecognised -> importProblem = "Copy a Ghostty theme or a Windows Terminal color scheme, then try again."
                    }
                })
            }
            LimentyDialogActions {
                LimentyTextButton("Close", { choosingNew = false }, color = LimentyColors.Text2)
            }
        }
    }

    importing?.let { palette ->
        LimentyNameDialog(
            title = "Import theme",
            initial = palette.name,
            onDismiss = { importing = null },
            onSave = { name ->
                importing = null
                val theme = CustomTheme(CustomTheme.newId(), palette.copy(name = name))
                onChange { it.addTheme(theme).copy(themeId = theme.id) }
            },
        )
    }

    importProblem?.let { problem ->
        LimentyDialog(title = "Nothing to import", onDismissRequest = { importProblem = null }) {
            Text(problem, style = LimentyText.Body, color = LimentyColors.Text2)
            LimentyDialogActions {
                LimentyTextButton("Close", { importProblem = null }, color = LimentyColors.Text2)
            }
        }
    }

    duplicating?.let { sourceId ->
        val source = preferences.themeOf(sourceId) ?: TerminalPalette.Default
        LimentyNameDialog(
            title = "Duplicate theme",
            initial = "${source.name} copy",
            onDismiss = { duplicating = null },
            onSave = { name ->
                duplicating = null
                // Selected as well as opened: the copy looks the same as what it was made from, so
                // choosing it changes nothing on screen, and the edits that follow then show in the
                // terminal as they are made.
                val copy = CustomTheme(CustomTheme.newId(), source.copy(name = name))
                onChange { it.addTheme(copy).copy(themeId = copy.id) }
                onEditTheme(copy.id)
            },
        )
    }

    renaming?.let { id ->
        val palette = preferences.themeOf(id) ?: return@let
        LimentyNameDialog(
            title = "Rename theme",
            initial = palette.name,
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                onChange { it.updateTheme(id) { theme -> theme.copy(name = name) } }
            },
        )
    }

    deleting?.let { id ->
        val palette = preferences.themeOf(id) ?: return@let
        val inUse = preferences.themeId == id
        LimentyDialog(title = "Delete theme", onDismissRequest = { deleting = null }) {
            val consequence = if (inUse) " The terminal switches to ${TerminalPalette.Default.name}." else ""
            Text("${palette.name} will be removed.$consequence", style = LimentyText.Body, color = LimentyColors.Text2)
            LimentyDialogActions {
                LimentyTextButton("Cancel", { deleting = null }, color = LimentyColors.Text2)
                LimentyTextButton(
                    label = "Delete",
                    onClick = {
                        deleting = null
                        onChange { it.removeTheme(id) }
                    },
                    color = LimentyColors.Danger,
                )
            }
        }
    }
}

/** The clipboard as text, or nothing. Read on the tap and no sooner: Android tells the user each time an app reads it. */
private fun clipboardText(context: Context): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return ""
    return item.coerceToText(context)?.toString().orEmpty()
}

/** Names each colour that kept a pasted theme out, in the text's own key names, and what is wrong with it. */
internal fun refusedImportMessage(refused: ThemeImport.Refused): String {
    val missing = refused.missing.takeIf { it.isNotEmpty() }?.let { "The theme is missing ${it.joinToString(", ")}." }
    val unreadable = refused.unreadable.takeIf { it.isNotEmpty() }?.let {
        "${it.joinToString(", ")} ${if (it.size == 1) "needs" else "need"} six hex digits, like #7AA2F7."
    }
    return listOfNotNull(missing, unreadable).joinToString(" ")
}
