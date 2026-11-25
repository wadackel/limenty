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
    fun `a key moved out of a group is placed right after it`() {
        val group = KeyGroup("sym", listOf(pipe, dash))
        val set = KeySet("Default", scrolling = listOf(esc, group, tab), pinned = emptyList())

        val moved = set.moveOutOfGroup(group = SlotPosition(false, 1), edited = group, member = 0)

        assertEquals(listOf(esc, KeyGroup("sym", listOf(dash)), pipe, tab), moved.scrolling)
    }

    /** The edit is what was on screen: a renamed group with a key added since it was saved. */
    @Test
    fun `moving a key out keeps the rest of the group's unsaved edit`() {
        val set = KeySet("Default", scrolling = emptyList(), pinned = listOf(sym, left))
        val edited = KeyGroup("symbols", listOf(pipe, dash))

        val moved = set.moveOutOfGroup(group = SlotPosition(true, 0), edited = edited, member = 1)

        assertEquals(listOf(KeyGroup("symbols", listOf(pipe)), dash, left), moved.pinned)
    }
}
