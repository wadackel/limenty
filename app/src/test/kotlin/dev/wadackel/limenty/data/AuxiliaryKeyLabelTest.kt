package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuxiliaryKeyLabelTest {
    private fun labelOf(key: AuxiliaryKey) = AuxiliaryKey.labelFor(key.steps)

    /**
     * The rule and the bar the app ships with are the same rule.
     *
     * This is the test the convention exists for: those keys were named by hand long before there
     * was a rule, so if the rule disagrees with any of them it is the rule that is wrong, and a key
     * added through the editor would end up named differently from the one beside it.
     */
    @Test
    fun `every key the app ships with is named by this rule`() {
        (AuxiliaryKey.DefaultScrolling + AuxiliaryKey.DefaultPinned).forEach { key ->
            assertEquals(key.label, labelOf(key))
        }
    }

    @Test
    fun `a named key is spelled short enough for its face`() {
        assertEquals("Esc", AuxiliaryKey.labelFor(TerminalKey.Escape, 0, Modifiers.None))
        assertEquals("PgDn", AuxiliaryKey.labelFor(TerminalKey.PageDown, 0, Modifiers.None))
        assertEquals("←", AuxiliaryKey.labelFor(TerminalKey.ArrowLeft, 0, Modifiers.None))
        assertEquals("Home", AuxiliaryKey.labelFor(TerminalKey.Home, 0, Modifiers.None))
        assertEquals("F12", AuxiliaryKey.labelFor(TerminalKey.F12, 0, Modifiers.None))
    }

    /**
     * Two keys that read the same are two keys nobody can tell apart on a bar.
     *
     * The rule above only checks the keys the app ships with, which leaves most of [TerminalKey]
     * unwatched — and `End` and `Tab` are both outside it.
     */
    @Test
    fun `no two keys are named the same`() {
        val names = TerminalKey.entries.associateWith { AuxiliaryKey.labelFor(it, 0, Modifiers.None) }
        names.forEach { (key, name) -> assertTrue("$key has no name", name.isNotEmpty()) }
        names.entries.groupBy({ it.value }, { it.key })
            .filterValues { it.size > 1 }
            .forEach { (name, keys) -> fail("$keys all read as `$name`") }
    }

    @Test
    fun `a character is itself`() {
        assertEquals("|", AuxiliaryKey.labelFor(null, '|'.code, Modifiers.None))
        assertEquals("~", AuxiliaryKey.labelFor(null, '~'.code, Modifiers.None))
    }

    @Test
    fun `modifiers read as a terminal user writes them`() {
        assertEquals("⌃c", AuxiliaryKey.labelFor(null, 'c'.code, Modifiers.Ctrl))
        assertEquals("⌥x", AuxiliaryKey.labelFor(null, 'x'.code, Modifiers.Alt))
        assertEquals("⇧⏎", AuxiliaryKey.labelFor(TerminalKey.Enter, 0, Modifiers.Shift))
        assertEquals(
            "⌃⌥⇧Tab",
            AuxiliaryKey.labelFor(TerminalKey.Tab, 0, Modifiers.Ctrl + Modifiers.Alt + Modifiers.Shift),
        )
    }

    /** The editor asks for a name before there is an action, and there is nothing to answer with. */
    @Test
    fun `nothing chosen has no name`() {
        assertEquals("", AuxiliaryKey.labelFor(null, 0, Modifiers.None))
        assertEquals("", AuxiliaryKey.labelFor(null, 0, Modifiers.Ctrl))
    }

    /**
     * A sequence is named the way tmux and Emacs write one, with a space between the keystrokes.
     *
     * Long for a key face, which is why the editor lets a key like this be named by hand — but a
     * suggestion its owner recognises is a better starting point than an empty field.
     */
    @Test
    fun `a key that sends several things is named after all of them`() {
        val prefixed = AuxiliaryKey(
            label = "win",
            steps = listOf(
                KeyStep.Press(codePoint = 's'.code, modifiers = Modifiers.Ctrl),
                KeyStep.Press(codePoint = 'w'.code),
            ),
        )

        assertEquals("⌃s w", labelOf(prefixed))
    }

    @Test
    fun `a screen reader is given words rather than the face`() {
        assertEquals("Ctrl c", AuxiliaryKey("⌃c", codePoint = 'c'.code, modifiers = Modifiers.Ctrl).spoken())
        assertEquals(
            "Shift Enter",
            AuxiliaryKey("⇧⏎", key = TerminalKey.Enter, modifiers = Modifiers.Shift).spoken(),
        )
        assertEquals("Left", AuxiliaryKey("←", key = TerminalKey.ArrowLeft).spoken())
        assertEquals("Page Down", AuxiliaryKey("PgDn", key = TerminalKey.PageDown).spoken())
        assertEquals(
            "Ctrl Alt Shift Tab",
            AuxiliaryKey.spokenFor(TerminalKey.Tab, 0, Modifiers.Ctrl + Modifiers.Alt + Modifiers.Shift),
        )
    }

    @Test
    fun `a sequence and a literal are spoken in full`() {
        val window = AuxiliaryKey(
            label = "⌃s w",
            steps = listOf(
                KeyStep.Press(codePoint = 's'.code, modifiers = Modifiers.Ctrl),
                KeyStep.Press(codePoint = 'w'.code),
            ),
        )
        assertEquals("Ctrl s w", window.spoken())

        val typed = AuxiliaryKey(
            label = "git status ⏎",
            steps = listOf(KeyStep.Literal("git status"), KeyStep.Press(key = TerminalKey.Enter)),
        )
        assertEquals("git status Enter", typed.spoken())
    }

    /** Someone who calls a key `Clear` wants to hear `Clear`, not the name of what it sends. */
    @Test
    fun `a key with a name of its own is spoken by that name`() {
        val named = AuxiliaryKey("Clear", codePoint = 'l'.code, modifiers = Modifiers.Ctrl)
        assertTrue(named.hasOwnName())
        assertEquals("Clear", named.spoken())

        val following = AuxiliaryKey("⌃l", codePoint = 'l'.code, modifiers = Modifiers.Ctrl)
        assertFalse(following.hasOwnName())
        assertEquals("Ctrl l", following.spoken())
    }

    @Test
    fun `a key bound to nothing still says something`() {
        val empty = AuxiliaryKey(label = "", steps = listOf(KeyStep.Press()))
        assertEquals("", AuxiliaryKey.spokenFor(empty.steps))
        assertEquals("Unassigned", empty.spoken())
    }

    @Test
    fun `literal text names itself`() {
        val typed = AuxiliaryKey(
            label = "status",
            steps = listOf(KeyStep.Literal("git status"), KeyStep.Press(key = TerminalKey.Enter)),
        )

        assertEquals("git status ⏎", labelOf(typed))
    }
}
