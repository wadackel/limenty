package dev.wadackel.limenty.core.terminal.graphics

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.Pen
import dev.wadackel.limenty.core.terminal.TerminalBuffer
import org.junit.Test

/** How placements follow the lines they were put on, with no emulator in the way. */
class GraphicsPlacementTest {
    private val buffer = TerminalBuffer(columns = 20, rows = 5, maxScrollback = 3)

    private fun place(row: Int, column: Int = 0, rows: Int = 1, imageId: Int = 1): Placement {
        val placement = Placement(imageId = imageId, placementId = imageId, row = row, column = column, columns = 2, rows = rows)
        buffer.place(placement)
        return placement
    }

    private fun rows(): List<Int> = buffer.placements.map { it.row }

    @Test
    fun `a full screen scroll moves a placement up one line with the text`() {
        place(row = 3)
        buffer.scrollUp(0, 4, 1, Pen.Default, retain = true)
        assertThat(rows()).containsExactly(2)
    }

    @Test
    fun `a placement scrolled off the top lives on in scrollback until the history drops it`() {
        place(row = 0)
        buffer.scrollUp(0, 4, 1, Pen.Default, retain = true)
        assertThat(rows()).containsExactly(-1)
        buffer.scrollUp(0, 4, 2, Pen.Default, retain = true)
        assertThat(rows()).containsExactly(-3)
        buffer.scrollUp(0, 4, 1, Pen.Default, retain = true)
        assertThat(buffer.placements).isEmpty()
    }

    @Test
    fun `without scrollback a placement scrolled off the top is gone at once`() {
        val alt = TerminalBuffer(columns = 20, rows = 5, maxScrollback = 0)
        alt.place(Placement(1, 1, row = 0, column = 0, columns = 2, rows = 1))
        alt.scrollUp(0, 4, 1, Pen.Default, retain = true)
        assertThat(alt.placements).isEmpty()
    }

    @Test
    fun `a region scroll moves only the placements inside the region and drops what leaves it`() {
        place(row = 1, imageId = 1)
        place(row = 2, imageId = 2)
        place(row = 4, imageId = 3)
        buffer.scrollUp(1, 3, 1, Pen.Default, retain = false)
        assertThat(buffer.placements.map { it.imageId to it.row }).containsExactly(2 to 1, 3 to 4)
    }

    @Test
    fun `a scroll down moves placements down and drops what falls out of the region`() {
        place(row = 0, imageId = 1)
        place(row = 3, imageId = 2)
        buffer.scrollDown(0, 3, 1, Pen.Default)
        assertThat(buffer.placements.map { it.imageId to it.row }).containsExactly(1 to 1)
    }

    @Test
    fun `clearing the screen removes placements that reach it and leaves scrollback alone`() {
        place(row = -2, imageId = 1)
        place(row = -1, rows = 2, imageId = 2)
        place(row = 3, imageId = 3)
        buffer.clearScreen(Pen.Default)
        assertThat(buffer.placements.map { it.imageId }).containsExactly(1)
    }

    @Test
    fun `clearing scrollback removes only the placements that never reach the screen`() {
        place(row = -1, imageId = 1)
        place(row = -1, rows = 2, imageId = 2)
        place(row = 2, imageId = 3)
        val released = buffer.clearScrollback()
        assertThat(buffer.placements.map { it.imageId }).containsExactly(2, 3)
        assertThat(released).containsExactly(1)
    }

    @Test
    fun `clearing the screen reports which images lost a placement`() {
        place(row = 0, imageId = 5)
        place(row = -3, imageId = 6)
        assertThat(buffer.clearScreen(Pen.Default)).containsExactly(5)
    }

    @Test
    fun `the placement list is capped by dropping the oldest`() {
        repeat(TerminalBuffer.MAX_PLACEMENTS + 1) { place(row = 0, imageId = it) }
        assertThat(buffer.placements).hasSize(TerminalBuffer.MAX_PLACEMENTS)
        assertThat(buffer.placements.first().imageId).isEqualTo(1)
    }

    @Test
    fun `shrinking pushes a placement into scrollback and growing brings it back`() {
        buffer.screenRow(0).setCell(0, 'x'.code, Pen.Default)
        buffer.screenRow(4).setCell(0, 'y'.code, Pen.Default)
        place(row = 0)
        buffer.resize(20, 3, cursorRow = 4, pen = Pen.Default)
        assertThat(rows()).containsExactly(-2)
        buffer.resize(20, 5, cursorRow = 2, pen = Pen.Default)
        assertThat(rows()).containsExactly(0)
    }

    @Test
    fun `a placement on a blank trailing line dropped by a shrink goes with it`() {
        place(row = 4)
        buffer.resize(20, 4, cursorRow = 0, pen = Pen.Default)
        assertThat(buffer.placements).isEmpty()
    }

    @Test
    fun `a placement beyond the new width is dropped`() {
        place(row = 0, column = 15)
        buffer.resize(10, 5, cursorRow = 0, pen = Pen.Default)
        assertThat(buffer.placements).isEmpty()
    }

    @Test
    fun `placements can be removed by image and reported as present`() {
        place(row = 0, imageId = 7)
        place(row = 1, imageId = 8)
        assertThat(buffer.hasPlacementOf(7)).isTrue()
        assertThat(buffer.removePlacements { it.imageId == 7 }).isTrue()
        assertThat(buffer.hasPlacementOf(7)).isFalse()
        assertThat(buffer.removePlacements { it.imageId == 7 }).isFalse()
    }
}
