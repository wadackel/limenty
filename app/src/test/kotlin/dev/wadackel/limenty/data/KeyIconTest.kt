package dev.wadackel.limenty.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A key's or a group's icon: what it calls the slot, and how a saved bar keeps it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KeyIconTest {
    private val ctrlB = KeyStep.Press(null, 'b'.code, Modifiers.Ctrl)
    private val c = KeyStep.Press(null, 'c'.code, Modifiers.None)
    private val newWindow = AuxiliaryKey("New window", listOf(ctrlB, c), KeyIcon.APP_WINDOW)

    @Test
    fun `a key with an icon and no name of its own is called by the icon`() {
        assertThat(newWindow.hasOwnName()).isFalse()
        assertThat(newWindow.spoken()).isEqualTo("New window")
    }

    @Test
    fun `a name the owner typed is kept beside an icon and is what a screen reader says`() {
        val named = AuxiliaryKey("new win", listOf(ctrlB, c), KeyIcon.APP_WINDOW)

        assertThat(named.hasOwnName()).isTrue()
        assertThat(named.spoken()).isEqualTo("new win")
    }

    @Test
    fun `a group with an icon and no name is called by the icon rather than by its keys`() {
        val group = KeyGroup("", listOf(newWindow), KeyIcon.ZAP)

        assertThat(group.label).isEqualTo("Bolt")
        assertThat(group.spoken()).isEqualTo("Bolt, 1 key")
        assertThat(KeyGroup("", listOf(newWindow)).label).isEqualTo("New window")
    }

    @Test
    fun `icons survive a saved bar, on keys and on groups`() {
        val bar = listOf(newWindow, KeyGroup("", listOf(newWindow, AuxiliaryKey("⌃b", listOf(ctrlB))), KeyIcon.ZAP))

        assertThat(AuxiliaryKey.decodeBar(AuxiliaryKey.encodeBar(bar))).isEqualTo(bar)
    }

    /** A build without icons reads only `label`, so the name has to be there for it to show. */
    @Test
    fun `the name is still written beside the icon, for a build that does not read icons`() {
        val saved = JSONArray(AuxiliaryKey.encodeBar(listOf(newWindow))).getJSONObject(0)

        assertThat(saved.getString("label")).isEqualTo("New window")
        assertThat(saved.getString("icon")).isEqualTo("app-window")
    }

    @Test
    fun `an icon this build does not ship shows the key's name and loses nothing else`() {
        val saved = """[
            {"label":"Deploy","icon":"rocket-from-the-future","steps":[{"codePoint":100}]},
            {"label":"Esc","steps":[{"key":"Escape"}]}
        ]"""

        val bar = AuxiliaryKey.decodeBar(saved)

        assertThat(bar).isNotNull()
        assertThat(bar!!.map { it.label }).containsExactly("Deploy", "Esc").inOrder()
        assertThat(bar[0].icon).isNull()
        assertThat((bar[0] as AuxiliaryKey).spoken()).isEqualTo("Deploy")
    }

    /** An id is what a saved bar holds, so two icons sharing one would turn into each other. */
    @Test
    fun `every icon has an id and a name of its own, under one of the headings`() {
        val icons = KeyIcon.entries

        assertThat(icons).hasSize(65)
        assertThat(icons.map { it.id }.toSet()).hasSize(icons.size)
        assertThat(icons.map { it.label }.toSet()).hasSize(icons.size)
        assertThat(KeyIconCategory.entries.filter { category -> icons.none { it.category == category } }).isEmpty()
        icons.forEach { assertThat(KeyIcon.byId(it.id)).isEqualTo(it) }
    }
}
