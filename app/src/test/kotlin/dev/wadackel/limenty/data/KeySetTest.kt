package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySetTest {
    private val vim = KeySet("Vim", AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned)
    private val agent = KeySet(
        name = "Agent",
        scrolling = listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape)),
        pinned = listOf(AuxiliaryKey("⏎", key = TerminalKey.Enter)),
    )
    private val tmux = KeySet("tmux", AuxiliaryKey.DefaultScrolling.take(2), emptyList())

    private fun preferences(active: Int) = TerminalPreferences(keySets = listOf(vim, agent, tmux), activeKeySet = active)

    @Test
    fun `switching past the last set comes back to the first`() {
        assertEquals(vim, preferences(active = 2).switchKeySet(1).keySet)
    }

    @Test
    fun `switching back from the first set goes to the last`() {
        assertEquals(tmux, preferences(active = 0).switchKeySet(-1).keySet)
    }

    @Test
    fun `a stored position past the end shows the last set`() {
        val stale = preferences(active = 7)

        assertEquals(tmux, stale.keySet)
        assertEquals(vim, stale.switchKeySet(1).keySet)
    }

    @Test
    fun `removing a set before the active one keeps the same set active`() {
        val removed = preferences(active = 2).removeKeySet(0)

        assertEquals(listOf(agent, tmux), removed.keySets)
        assertEquals(tmux, removed.keySet)
    }

    @Test
    fun `removing the active set makes the one that took its place active`() {
        assertEquals(tmux, preferences(active = 1).removeKeySet(1).keySet)
    }

    @Test
    fun `removing the active set at the end makes the new last set active`() {
        assertEquals(agent, preferences(active = 2).removeKeySet(2).keySet)
    }

    @Test
    fun `the last remaining set cannot be removed`() {
        val single = TerminalPreferences(keySets = listOf(vim), activeKeySet = 0)

        assertEquals(single, single.removeKeySet(0))
    }

    @Test
    fun `a duplicated set starts with the same keys under its own name`() {
        val duplicated = preferences(active = 0).duplicateKeySet(1, "Agent copy")

        assertEquals(4, duplicated.keySets.size)
        assertEquals(agent.copy(name = "Agent copy"), duplicated.keySets.last())
        assertEquals(vim, duplicated.keySet)
    }

    @Test
    fun `changing one set leaves the others as they were`() {
        val changed = preferences(active = 0).updateKeySet(1) { it.copy(pinned = emptyList()) }

        assertEquals(listOf(vim, agent.copy(pinned = emptyList()), tmux), changed.keySets)
    }

    @Test
    fun `a new set goes at the end and leaves the set in use alone`() {
        val added = preferences(active = 1).addKeySet(KeySet.starter("Logs"))

        assertEquals(listOf(vim, agent, tmux, KeySet.starter("Logs")), added.keySets)
        assertEquals(agent, added.keySet)
    }

    @Test
    fun `a new set starts with Escape scrolling and the arrows pinned`() {
        val starter = KeySet.starter("Logs")

        assertEquals("Logs", starter.name)
        assertEquals(listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape)), starter.scrolling)
        assertEquals(AuxiliaryKey.DefaultPinned, starter.pinned)
    }

    @Test
    fun `a fresh install has the shipped bar as its only set`() {
        val fresh = TerminalPreferences()

        assertEquals(
            listOf(KeySet(KeySet.DEFAULT_NAME, AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned)),
            fresh.keySets,
        )
        assertEquals(fresh.keySets.first(), fresh.keySet)
    }
}
