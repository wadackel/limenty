package dev.wadackel.limenty.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionRangeTest {
    /**
     * Buffer line numbers slide as output arrives, so a range kept in the numbers of the moment
     * it was made ends up pointing at whatever text has since taken that position.
     */
    @Test
    fun `a range follows its text as output pushes it into scrollback`() {
        val selected = SelectionRange(startLine = 5, startColumn = 0, endLine = 5, endColumn = 9, scrolledAt = 100)

        val afterThreeLines = selected.shiftedTo(103)

        assertThat(afterThreeLines.startLine).isEqualTo(2)
        assertThat(afterThreeLines.endLine).isEqualTo(2)
        assertThat(afterThreeLines.startColumn).isEqualTo(0)
        assertThat(afterThreeLines.endColumn).isEqualTo(9)
    }

    @Test
    fun `a range already in the current numbers is left alone`() {
        val selected = SelectionRange(3, 1, 4, 2, scrolledAt = 7)

        assertThat(selected.shiftedTo(7)).isSameInstanceAs(selected)
    }

    @Test
    fun `shifting twice matches shifting once by the total`() {
        val selected = SelectionRange(9, 0, 9, 4, scrolledAt = 50)

        assertThat(selected.shiftedTo(55).shiftedTo(60)).isEqualTo(selected.shiftedTo(60))
    }

    @Test
    fun `a range that scrolls off the screen keeps addressing scrollback`() {
        val onScreen = SelectionRange(0, 0, 0, 3, scrolledAt = 10)

        assertThat(onScreen.shiftedTo(14).startLine).isEqualTo(-4)
    }

    @Test
    fun `containment is unaffected by which end was dragged`() {
        val forwards = SelectionRange(2, 4, 5, 1)
        val backwards = SelectionRange(5, 1, 2, 4)

        for (line in 1..6) {
            for (column in 0..6) {
                assertThat(forwards.contains(line, column)).isEqualTo(backwards.contains(line, column))
            }
        }
    }
}
