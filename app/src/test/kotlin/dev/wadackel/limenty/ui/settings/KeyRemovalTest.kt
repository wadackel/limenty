package dev.wadackel.limenty.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Remove in the key dialog deletes a key only once the confirmation that follows says so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyRemovalTest {
    @get:Rule
    val rule = createComposeRule()

    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val tab = AuxiliaryKey("Tab", key = TerminalKey.Tab)
    private val left = AuxiliaryKey("Left", key = TerminalKey.ArrowLeft)

    private var preferences by mutableStateOf(TerminalPreferences())

    private fun show(set: KeySet) {
        preferences = TerminalPreferences(keySets = listOf(set))
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(1200.dp)) {
                    KeyBarSettingsScreen(
                        preferences = preferences,
                        onChange = { transform -> preferences = transform(preferences) },
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(label: String) {
        rule.onAllNodes(hasText(label) and hasClickAction() and isEnabled()).onLast().performClick()
        rule.waitForIdle()
    }

    @Test
    fun removeAsksBeforeTheKeyIsDeleted() {
        show(KeySet("Default", scrolling = listOf(esc, tab), pinned = listOf(left)))

        press("Tab")
        press("Remove")

        rule.onNode(hasText("Tab will be removed.")).assertExists()
        assertEquals(listOf(esc, tab), preferences.keySet.scrolling)

        press("Remove")

        assertEquals(listOf(esc), preferences.keySet.scrolling)
        assertEquals(listOf(left), preferences.keySet.pinned)
    }

    @Test
    fun cancellingTheConfirmationKeepsTheKey() {
        show(KeySet("Default", scrolling = listOf(esc, tab), pinned = listOf(left)))

        press("Left")
        press("Remove")
        press("Cancel")

        rule.onNode(hasText("Left will be removed.")).assertDoesNotExist()
        assertEquals(listOf(left), preferences.keySet.pinned)
        assertEquals(listOf(esc, tab), preferences.keySet.scrolling)
    }
}
