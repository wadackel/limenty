package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.CustomTheme
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.components.LimentyDialog
import dev.wadackel.limenty.ui.components.LimentyDialogActions
import dev.wadackel.limenty.ui.components.LimentyMenuAction
import dev.wadackel.limenty.ui.components.LimentyNameDialog
import dev.wadackel.limenty.ui.components.LimentyTextButton
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyText

/**
 * The theme grid with what its cards' menus lead to: duplicating, renaming, deleting, and opening
 * the editor.
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

    ThemeGrid(
        preferences = preferences,
        mono = mono,
        columns = columns,
        onSelect = { id -> onChange { it.copy(themeId = id) } },
        onMore = { id -> menuFor = id },
        onNew = { duplicating = preferences.themeId },
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
