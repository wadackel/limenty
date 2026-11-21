package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModifiersTest {
    @Test
    fun `empty modifiers have no xterm parameter`() {
        assertThat(Modifiers.None.xtermParameter()).isNull()
        assertThat(Modifiers.None.isEmpty).isTrue()
    }

    @Test
    fun `single modifiers map to the xterm table`() {
        assertThat(Modifiers.Shift.xtermParameter()).isEqualTo(2)
        assertThat(Modifiers.Alt.xtermParameter()).isEqualTo(3)
        assertThat(Modifiers.Ctrl.xtermParameter()).isEqualTo(5)
        assertThat(Modifiers.Meta.xtermParameter()).isEqualTo(9)
    }

    @Test
    fun `combinations add up`() {
        assertThat((Modifiers.Ctrl + Modifiers.Shift).xtermParameter()).isEqualTo(6)
        assertThat((Modifiers.Ctrl + Modifiers.Alt).xtermParameter()).isEqualTo(7)
        assertThat((Modifiers.Ctrl + Modifiers.Alt + Modifiers.Shift).xtermParameter()).isEqualTo(8)
    }

    @Test
    fun `plus is idempotent`() {
        assertThat(Modifiers.Ctrl + Modifiers.Ctrl).isEqualTo(Modifiers.Ctrl)
    }

    @Test
    fun `toString names the held modifiers`() {
        assertThat(Modifiers.None.toString()).isEqualTo("none")
        assertThat((Modifiers.Ctrl + Modifiers.Alt).toString()).isEqualTo("ctrl+alt")
    }
}
