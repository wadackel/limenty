package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Moving a key that is already on the bar into a group, and back out. */
class KeySetGroupMoveTest {
    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val tab = AuxiliaryKey("Tab", key = TerminalKey.Tab)
    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val dash = AuxiliaryKey("-", codePoint = '-'.code)
    private val left = AuxiliaryKey("←", key = TerminalKey.ArrowLeft)
    private val sym = KeyGroup("sym", listOf(pipe))

    @Test
    fun `a key moved into a group goes to the end of it and leaves its row`() {
        val set = KeySet("Default", scrolling = listOf(esc, sym, dash), pinned = listOf(left))

        val moved = set.moveIntoGroup(key = SlotPosition(false, 2), group = SlotPosition(false, 1))

        assertEquals(listOf(esc, KeyGroup("sym", listOf(pipe, dash))), moved.scrolling)
        assertEquals(listOf(left), moved.pinned)
    }

    /** The key sits before the group, so taking it out shifts the group one place left. */
    @Test
    fun `a key before its group in the same row moves into the right group`() {
        val set = KeySet("Default", scrolling = listOf(dash, sym, KeyGroup("nav", emptyList())), pinned = emptyList())

        val moved = set.moveIntoGroup(key = SlotPosition(false, 0), group = SlotPosition(false, 1))

        assertEquals(listOf(KeyGroup("sym", listOf(pipe, dash)), KeyGroup("nav", emptyList())), moved.scrolling)
    }

    @Test
    fun `a pinned key can move into a group in the scrolling run`() {
        val set = KeySet("Default", scrolling = listOf(sym), pinned = listOf(left, tab))

        val moved = set.moveIntoGroup(key = SlotPosition(true, 0), group = SlotPosition(false, 0))

        assertEquals(listOf(KeyGroup("sym", listOf(pipe, left))), moved.scrolling)
        assertEquals(listOf(tab), moved.pinned)
    }

    @Test
    fun `a position that does not hold a key and a group leaves the set as it was`() {
        val set = KeySet("Default", scrolling = listOf(esc, sym), pinned = emptyList())

        assertEquals(set, set.moveIntoGroup(key = SlotPosition(false, 1), group = SlotPosition(false, 1)))
        assertEquals(set, set.moveIntoGroup(key = SlotPosition(false, 0), group = SlotPosition(false, 0)))
        assertEquals(set, set.moveIntoGroup(key = SlotPosition(false, 5), group = SlotPosition(false, 1)))
    }

    @Test
    fun `a pinned key goes to the end of the pinned run`() {
        val set = KeySet("Default", scrolling = listOf(esc, sym, tab), pinned = listOf(left))

        val pinned = set.pin(1)

        assertEquals(listOf(esc, tab), pinned.scrolling)
        assertEquals(listOf(left, sym), pinned.pinned)
    }

    @Test
    fun `pinning a position past the end leaves the set as it was`() {
        val set = KeySet("Default", scrolling = listOf(esc), pinned = emptyList())

        assertEquals(set, set.pin(3))
    }
}
