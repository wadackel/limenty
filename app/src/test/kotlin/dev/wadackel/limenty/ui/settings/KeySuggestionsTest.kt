package dev.wadackel.limenty.ui.settings

import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the field under the steps offers for what was typed into it. */
class KeySuggestionsTest {
    private fun named(vararg keys: TerminalKey) = keys.map { KeyStep.Press(key = it) }

    @Test
    fun `a key is found by the start of its name`() {
        assertEquals(named(TerminalKey.PageDown), keySuggestions("pgd"))
        assertEquals(named(TerminalKey.Escape), keySuggestions("esc"))
    }

    @Test
    fun `a name that matches outright comes before the ones it only begins`() {
        assertEquals(named(TerminalKey.F1, TerminalKey.F10, TerminalKey.F11, TerminalKey.F12), keySuggestions("f1"))
    }

    @Test
    fun `a single character is offered as itself first`() {
        val offered = keySuggestions("e")

        assertEquals(KeyStep.Press(codePoint = 'e'.code), offered.first())
        assertEquals(named(TerminalKey.Enter, TerminalKey.Escape, TerminalKey.End), offered.drop(1))
    }

    @Test
    fun `a key is found by how it is said and by its usual short forms`() {
        assertEquals(named(TerminalKey.PageUp), keySuggestions("Page up"))
        assertEquals(named(TerminalKey.Backspace), keySuggestions("bs"))
    }

    @Test
    fun `nothing is offered for nothing, or for a name no key has`() {
        assertEquals(emptyList<KeyStep>(), keySuggestions(""))
        assertEquals(emptyList<KeyStep>(), keySuggestions("zzz"))
    }
}
