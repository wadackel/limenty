package dev.wadackel.limenty.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.hosts.HostListScreen
import dev.wadackel.limenty.ui.hosts.HostStatus
import dev.wadackel.limenty.ui.settings.SettingsScreen
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test

/**
 * The word at the end of a header is a button, on every screen that has one.
 *
 * `Settings` was built by hand where it stood rather than from the shared control: it had four
 * points of slack around a fourteen-point word and square corners, next to a `Done` on the screen
 * it opened that had neither problem. Nothing about that is visible in a screenshot — the reach
 * around a word and the shape its press takes are both measurements — so they are asserted here.
 */
class HeaderActionTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun action(label: String) = rule.onNode(hasText(label) and hasClickAction())

    private fun assertIsAButtonYouCanHit(label: String) {
        action(label).assertHeightIsAtLeast(TOUCH_TARGET)
        // Wider than its word by the inset on each side, which is the padding that was missing.
        action(label).assertWidthIsAtLeast(TOUCH_TARGET)
        action(label).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        )
    }

    @Test
    fun theHostListOpensSettingsWithAButton() {
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(800.dp)) {
                    HostListScreen(
                        hosts = listOf(HostProfile("a", "Box", "box.example", "someone")),
                        statusOf = { HostStatus.Disconnected },
                        previewOf = { null },
                        palette = TerminalPalette.Default,
                        mono = TerminalTypefaces(context).family(TerminalFont.Default),
                        found = emptyList(),
                        absent = { false },
                        onConnect = {},
                        onAddFound = {},
                        onEdit = {},
                        onDelete = {},
                        onDisconnect = {},
                        onAdd = {},
                        onOpenSettings = {},
                        selectedHostId = null,
                        onSelectHost = {},
                    )
                }
            }
        }
        assertIsAButtonYouCanHit("Settings")
    }

    /** Settings leaves by its back arrow rather than by a word, and the arrow is held to the same reach. */
    @Test
    fun settingsGoesBackWithAButtonYouCanHit() {
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(800.dp)) {
                    SettingsScreen(
                        preferences = TerminalPreferences(),
                        typefaces = TerminalTypefaces(context),
                        section = null,
                        onSelectSection = {},
                        onChange = {},
                        onBack = {},
                    )
                }
            }
        }
        val back = rule.onNode(hasContentDescription("Back") and hasClickAction())
        back.assertHeightIsAtLeast(TOUCH_TARGET)
        back.assertWidthIsAtLeast(TOUCH_TARGET)
        back.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }
}

/** What the rest of the app calls a target worth hitting. */
private val TOUCH_TARGET = 44.dp
