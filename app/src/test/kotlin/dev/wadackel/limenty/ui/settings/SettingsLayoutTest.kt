package dev.wadackel.limenty.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.ui.components.SectionHeading
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What each display shows for a settings section, the gesture groups the screen lists, and how a
 * section's heading keeps to its one line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w475dp-h900dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the folded display opens a section on a page of its own`() {
        assertEquals(SettingsSection.Gestures, foldedPage(SettingsSection.Gestures))
        assertEquals(SettingsSection.About, foldedPage(SettingsSection.About))
    }

    /** Selected on the unfolded display and then folded, Screen lands where its switch is. */
    @Test
    fun `the folded display shows the screen section on the index, where its switch is`() {
        assertNull(foldedPage(SettingsSection.Screen))
        assertNull(foldedPage(null))
    }

    @Test
    fun `the unfolded display shows appearance until something else is chosen`() {
        assertEquals(SettingsSection.Appearance, panePage(null))
        assertEquals(SettingsSection.Screen, panePage(SettingsSection.Screen))
    }

    @Test
    fun `the cover display is laid out folded and the inner one unfolded`() {
        assertFalse(isWideSettings(475.dp))
        assertTrue(isWideSettings(933.dp))
    }

    /** The groups are spelled out by hand; a gesture added to the enum must not fall out of settings. */
    @Test
    fun `every gesture is listed exactly once`() {
        val listed = GestureGroups.flatMap { group -> group.gestures.map { it.first } }

        assertEquals(Gesture.entries.toSet(), listed.toSet())
        assertEquals(Gesture.entries.size, listed.size)
    }

    /** A theme's name has no length limit, and a second line set under "Theme" reads as part of it. */
    @Test
    fun `a long theme name is cut to one line beside the heading rather than wrapping under it`() {
        val name = "Averyveryveryverylongthemename and then some more words that keep going on and on " +
            "until the dialog title and the card label must wrap or clip somewhere 1234567890"
        compose.setContent { LimentyTheme { SectionHeading("Theme", name) } }

        val value = layoutOf(name)
        assertThat(value.lineCount).isEqualTo(1)
        assertThat(value.isLineEllipsized(0)).isTrue()
        assertThat(compose.onNodeWithText(name).getBoundsInRoot().left)
            .isGreaterThan(compose.onNodeWithText("Theme").getBoundsInRoot().right)
    }

    @Test
    fun `a short theme name sits whole at the end of the heading`() {
        compose.setContent { LimentyTheme { SectionHeading("Theme", "Nord") } }

        val value = layoutOf("Nord")
        assertThat(value.lineCount).isEqualTo(1)
        assertThat(value.isLineEllipsized(0)).isFalse()
        assertThat(compose.onNodeWithText("Nord").getBoundsInRoot().right)
            .isEqualTo(compose.onRoot().getBoundsInRoot().right)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }
}
