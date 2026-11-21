package dev.wadackel.limenty.ui.hosts

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.data.HostProfile
import org.junit.Test

/** Which host the unfolded display's detail pane shows. */
class SelectedHostTest {
    private val first = HostProfile("a", "First", "first.example", "someone")
    private val second = HostProfile("b", "Second", "second.example", "someone")
    private val third = HostProfile("c", "Third", "third.example", "someone")
    private val hosts = listOf(first, second, third)

    @Test
    fun `with nothing chosen and nothing connected the first host is shown`() {
        assertThat(selectedHost(hosts, selectedId = null, connected = { false })).isEqualTo(first)
    }

    @Test
    fun `with nothing chosen the first connected host is shown`() {
        assertThat(selectedHost(hosts, selectedId = null, connected = { it == third })).isEqualTo(third)
    }

    @Test
    fun `a chosen host is shown even while another is connected`() {
        assertThat(selectedHost(hosts, selectedId = "b", connected = { it == third })).isEqualTo(second)
    }

    @Test
    fun `a chosen host that was deleted falls back to the first connected host`() {
        val remaining = listOf(first, third)
        assertThat(selectedHost(remaining, selectedId = "b", connected = { it == third })).isEqualTo(third)
    }

    @Test
    fun `the selection follows a host through an edit that replaces its profile`() {
        val renamed = second.copy(name = "Renamed")
        assertThat(selectedHost(listOf(first, renamed, third), selectedId = "b", connected = { false }))
            .isEqualTo(renamed)
    }

    @Test
    fun `an empty list shows nothing`() {
        assertThat(selectedHost(emptyList(), selectedId = "a", connected = { true })).isNull()
    }
}
