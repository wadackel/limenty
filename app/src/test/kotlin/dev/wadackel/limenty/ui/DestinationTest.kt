package dev.wadackel.limenty.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.data.LanService
import dev.wadackel.limenty.ui.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way a screen change slides, and where Back goes.
 *
 * Asserted on the rule rather than on the animation, because what went wrong was the rule: the
 * direction was read from the destination alone, so every back step that did not land on the host
 * list was played as though it were a step further in.
 */
class DestinationTest {
    @Test
    fun `opening settings from the host list steps in`() {
        assertTrue(Destination.Settings().stepsInFrom(Destination.Hosts))
    }

    @Test
    fun `opening the key bar from settings steps in`() {
        assertTrue(Destination.KeyBar.stepsInFrom(Destination.Settings(SettingsSection.Keyboard)))
    }

    /** The one that was wrong: settings is not the root, and coming back to it is still coming back. */
    @Test
    fun `leaving the key bar steps back`() {
        assertFalse(Destination.Settings(SettingsSection.Keyboard).stepsInFrom(Destination.KeyBar))
    }

    @Test
    fun `leaving settings for the host list steps back`() {
        assertFalse(Destination.Hosts.stepsInFrom(Destination.Settings(SettingsSection.Gestures)))
    }

    @Test
    fun `the terminal and the host form sit alongside settings`() {
        assertTrue(Destination.Terminal(SessionId("s")).stepsInFrom(Destination.Hosts))
        assertFalse(Destination.Hosts.stepsInFrom(Destination.Terminal(SessionId("s"))))
        assertTrue(Destination.HostForm(null).stepsInFrom(Destination.Hosts))
        assertFalse(Destination.Hosts.stepsInFrom(Destination.HostForm(null)))
    }

    /** The unfolded display shows sections in a pane beside the list; sliding the screen would move the list. */
    @Test
    fun `moving between settings sections stays on the settings screen`() {
        assertTrue(Destination.Settings(SettingsSection.Gestures).isScreenOf(Destination.Settings(SettingsSection.Appearance)))
        assertTrue(Destination.Settings(SettingsSection.About).isScreenOf(Destination.Settings()))
        assertFalse(Destination.KeyBar.isScreenOf(Destination.Settings(SettingsSection.Keyboard)))
        assertFalse(Destination.Settings().isScreenOf(Destination.Hosts))
    }

    @Test
    fun `back from the key bar returns to the keyboard section it was opened from`() {
        assertEquals(Destination.Settings(SettingsSection.Keyboard), Destination.KeyBar.backTarget)
    }

    @Test
    fun `back from settings returns to the host list whichever section is open`() {
        assertEquals(Destination.Hosts, Destination.Settings().backTarget)
        SettingsSection.entries.forEach { section ->
            assertEquals(Destination.Hosts, Destination.Settings(section).backTarget)
        }
    }

    private fun roundTrip(destination: Destination): Destination? {
        val encoded = with(DestinationSaver) { SaverScope { true }.save(destination) }
        return encoded?.let(DestinationSaver::restore)
    }

    @Test
    fun `a form opened from a service on the network comes back with that service`() {
        val form = Destination.HostForm(null, LanService(name = "a.b: c’s Mac", address = "192.168.1.20", port = 2222))

        assertEquals(form, roundTrip(form))
    }

    @Test
    fun `a form editing a saved host still comes back as that host`() {
        assertEquals(Destination.HostForm("h1"), roundTrip(Destination.HostForm("h1")))
    }

    @Test
    fun `the settings index comes back as the index after the activity is recreated`() {
        assertEquals(Destination.Settings(), roundTrip(Destination.Settings()))
    }

    @Test
    fun `every settings section comes back as itself after the activity is recreated`() {
        SettingsSection.entries.forEach { section ->
            assertEquals(Destination.Settings(section), roundTrip(Destination.Settings(section)))
        }
    }

    @Test
    fun `the key bar comes back as the key bar after the activity is recreated`() {
        assertEquals(Destination.KeyBar, roundTrip(Destination.KeyBar))
    }

    @Test
    fun `a theme being edited comes back as that theme after the activity is recreated`() {
        assertEquals(Destination.ThemeEditor("custom:a"), roundTrip(Destination.ThemeEditor("custom:a")))
    }

    @Test
    fun `opening the theme editor steps in and back returns to appearance`() {
        val editor = Destination.ThemeEditor("custom:a")

        assertTrue(editor.stepsInFrom(Destination.Settings(SettingsSection.Appearance)))
        assertFalse(Destination.Settings(SettingsSection.Appearance).stepsInFrom(editor))
        assertEquals(Destination.Settings(SettingsSection.Appearance), editor.backTarget)
    }
}
