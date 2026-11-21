package dev.wadackel.limenty.ui.settings

import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each display shows for a settings section, and the gesture groups the screen lists. */
class SettingsLayoutTest {
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
}
