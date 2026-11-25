package dev.wadackel.limenty.data

import android.app.Application
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Groups on the bar: what they are called, and how they are stored next to keys. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KeyGroupTest {
    private val pipe = AuxiliaryKey("|", codePoint = '|'.code)
    private val dash = AuxiliaryKey("-", codePoint = '-'.code)
    private val ctrlC = AuxiliaryKey("⌃c", codePoint = 'c'.code, modifiers = Modifiers.Ctrl)

    @Test
    fun `a named group shows its name and says how many keys it holds`() {
        val group = KeyGroup("sym", listOf(pipe, dash))

        assertEquals("sym", group.label)
        assertEquals("sym, 2 keys", group.spoken())
    }

    @Test
    fun `a group without a name shows its keys' labels joined`() {
        val group = KeyGroup("", listOf(pipe, ctrlC))

        assertEquals("| ⌃c", group.label)
        assertEquals("| Ctrl c, 2 keys", group.spoken())
    }

    @Test
    fun `an empty group says it holds nothing`() {
        assertEquals("sym, no keys", KeyGroup("sym", emptyList()).spoken())
        assertEquals("ctl, 1 key", KeyGroup("ctl", listOf(ctrlC)).spoken())
    }

    @Test
    fun `a set holding groups reads back as it was written`() {
        val sets = listOf(
            KeySet(
                name = "Default",
                scrolling = listOf(KeyGroup("sym", listOf(pipe, dash)), AuxiliaryKey("Esc", key = TerminalKey.Escape)),
                pinned = listOf(KeyGroup("", listOf(ctrlC)), KeyGroup("empty", emptyList())),
            ),
        )

        assertEquals(sets, KeySet.decode(KeySet.encode(sets)))
    }

    /**
     * The shape a build without groups can read: the name under `label` and no `steps`, which it
     * takes for a key with that name that sends nothing, instead of failing on the whole bar.
     */
    @Test
    fun `a group is stored with its name as a label and no steps of its own`() {
        val item = JSONArray(AuxiliaryKey.encodeBar(listOf(KeyGroup("sym", listOf(pipe))))).getJSONObject(0)

        assertEquals("sym", item.getString("label"))
        assertFalse(item.has("steps"))
        assertTrue(item.has("group"))
    }

    @Test
    fun `a bar written before groups reads back unchanged`() {
        val stored = """[{"label":"Esc","steps":[{"key":"Escape","codePoint":0,"modifiers":0}]},""" +
            """{"label":"|","key":null,"codePoint":124,"modifiers":0}]"""

        assertEquals(
            listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape), pipe),
            AuxiliaryKey.decodeBar(stored),
        )
    }
}
