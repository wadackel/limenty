package dev.wadackel.limenty.data

import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Save in the editing sheet does to a set, given everything the sheet left of one slot. */
class KeySetEditTest {
    private val esc = AuxiliaryKey("Esc", key = TerminalKey.Escape)
    private val tab = AuxiliaryKey("Tab", key = TerminalKey.Tab)
    private val home = AuxiliaryKey("Home", key = TerminalKey.Home)
    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val dash = AuxiliaryKey("-", codePoint = '-'.code)
    private val left = AuxiliaryKey("←", key = TerminalKey.ArrowLeft)
    private val sym = KeyGroup("sym", listOf(pipe, dash))

    private val set = KeySet("Default", scrolling = listOf(esc, sym, tab), pinned = listOf(left))

    @Test
    fun `an edited slot that stays in its row keeps its place`() {
        val (edited, at) = set.applying(SlotEdit(from = SlotPosition(false, 2), slot = home, pinned = false))

        assertEquals(listOf(esc, sym, home), edited.scrolling)
        assertEquals(SlotPosition(false, 2), at)
    }

    @Test
    fun `a slot moved to the other row goes to its end with its edits`() {
        val (edited, at) = set.applying(SlotEdit(from = SlotPosition(false, 0), slot = home, pinned = true))

        assertEquals(listOf(sym, tab), edited.scrolling)
        assertEquals(listOf(left, home), edited.pinned)
        assertEquals(SlotPosition(true, 1), at)
    }

    @Test
    fun `a new slot goes to the end of the row chosen for it`() {
        val (edited, at) = set.applying(SlotEdit(from = null, slot = home, pinned = false))

        assertEquals(listOf(esc, sym, tab, home), edited.scrolling)
        assertEquals(SlotPosition(false, 3), at)
    }

    /** The group sits after the key, so taking the key out moves the group one place forward. */
    @Test
    fun `a key put into a group goes to its end with its edits and leaves its row`() {
        val (edited, at) = set.applying(SlotEdit(from = SlotPosition(false, 0), slot = home, pinned = false, into = SlotPosition(false, 1)))

        assertEquals(listOf(KeyGroup("sym", listOf(pipe, dash, home)), tab), edited.scrolling)
        assertEquals(SlotPosition(false, 0), at)
    }

    @Test
    fun `a pinned key can go into a scrolling group`() {
        val (edited, _) = set.applying(SlotEdit(from = SlotPosition(true, 0), slot = left, pinned = true, into = SlotPosition(false, 1)))

        assertEquals(listOf(esc, KeyGroup("sym", listOf(pipe, dash, left)), tab), edited.scrolling)
        assertEquals(emptyList<KeyBarSlot>(), edited.pinned)
    }

    /** The group is saved as edited, renamed here, and the key taken out of it lands right after it. */
    @Test
    fun `keys taken out of a group land right after it`() {
        val edit = SlotEdit(from = SlotPosition(false, 1), slot = KeyGroup("symbols", listOf(dash)), pinned = false, movedOut = listOf(pipe))

        val (edited, at) = set.applying(edit)

        assertEquals(listOf(esc, KeyGroup("symbols", listOf(dash)), pipe, tab), edited.scrolling)
        assertEquals(SlotPosition(false, 1), at)
    }

    @Test
    fun `keys taken out of a group that is pinned in the same edit go with it`() {
        val edit = SlotEdit(from = SlotPosition(false, 1), slot = KeyGroup("sym", listOf(dash)), pinned = true, movedOut = listOf(pipe))

        val (edited, _) = set.applying(edit)

        assertEquals(listOf(esc, tab), edited.scrolling)
        assertEquals(listOf(left, KeyGroup("sym", listOf(dash)), pipe), edited.pinned)
    }

    @Test
    fun `a removed slot comes back to the place it was taken from`() {
        val at = SlotPosition(false, 1)

        assertEquals(set, set.removed(at).restored(at, sym))
    }

    @Test
    fun `a slot restored to a row that has since grown shorter goes at its end`() {
        val restored = set.removed(SlotPosition(false, 2)).removed(SlotPosition(false, 1)).restored(SlotPosition(false, 2), tab)

        assertEquals(listOf(esc, tab), restored.scrolling)
    }
}
