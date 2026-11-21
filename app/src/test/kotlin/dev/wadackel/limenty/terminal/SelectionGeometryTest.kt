package dev.wadackel.limenty.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionGeometryTest {
    private val metrics = TerminalMetrics(
        cellWidth = 20f,
        cellHeight = 40f,
        baseline = 30f,
        fontSizePx = 36f,
        letterSpacingPx = 0f,
        density = 3f,
    )
    private val reach = SelectionGeometry.handleReach(metrics)

    private fun grabAt(x: Float, y: Float, selection: SelectionRange, scrollOffset: Int = 0) = SelectionGeometry.grabAt(x, y, selection, scrollOffset, metrics, reach)

    private fun centre(line: Int, column: Int, scrollOffset: Int = 0) = SelectionGeometry.handleCentreX(column, metrics) to
        SelectionGeometry.handleCentreY(line, scrollOffset, metrics)

    @Test
    fun `grabbing the end handle pins the start`() {
        val selection = SelectionRange(2, 3, 2, 8)
        val (x, y) = centre(2, 8)

        val grab = grabAt(x, y, selection)!!

        assertThat(grab.movingColumn).isEqualTo(8)
        assertThat(grab.anchor.startLine).isEqualTo(2)
        assertThat(grab.anchor.startColumn).isEqualTo(3)
    }

    @Test
    fun `grabbing the start handle pins the end`() {
        val selection = SelectionRange(2, 3, 5, 8)
        val (x, y) = centre(2, 3)

        val grab = grabAt(x, y, selection)!!

        assertThat(grab.movingLine).isEqualTo(2)
        assertThat(grab.anchor.startLine).isEqualTo(5)
        assertThat(grab.anchor.startColumn).isEqualTo(8)
    }

    @Test
    fun `a touch away from either handle grabs nothing`() {
        val selection = SelectionRange(2, 3, 2, 8)

        assertThat(grabAt(500f, 500f, selection)).isNull()
    }

    @Test
    fun `the anchor keeps the scroll count so it addresses the same text later`() {
        val selection = SelectionRange(1, 0, 1, 4, scrolledAt = 42)
        val (x, y) = centre(1, 4)

        assertThat(grabAt(x, y, selection)!!.anchor.scrolledAt).isEqualTo(42)
    }

    /** Both handles sit on the same spot, so the nearer-wins rule has to break the tie. */
    @Test
    fun `a selection collapsed onto one cell still yields a handle`() {
        val selection = SelectionRange(3, 6, 3, 6)
        val (x, y) = centre(3, 6)

        assertThat(grabAt(x, y, selection)).isNotNull()
    }

    @Test
    fun `the nearer handle wins when both are within reach`() {
        val selection = SelectionRange(4, 2, 4, 3)
        val (nearX, nearY) = centre(4, 3)

        val grab = grabAt(nearX, nearY, selection)!!

        assertThat(grab.movingColumn).isEqualTo(3)
    }

    @Test
    fun `handles follow the viewport when the screen is scrolled back`() {
        val selection = SelectionRange(-5, 1, -5, 4)
        val (x, y) = centre(-5, 4, scrollOffset = 7)

        assertThat(grabAt(x, y, selection, scrollOffset = 7)).isNotNull()
        // The same selection with the screen elsewhere is not under that finger.
        assertThat(grabAt(x, y, selection, scrollOffset = 0)).isNull()
    }

    /**
     * The handle used to be a fraction of the cell, which made it about six density-independent
     * pixels across on a dense screen and smaller still once the font was pinched down.
     */
    @Test
    fun `the handle is a touch sized target rather than a fraction of a cell`() {
        val tinyCells = metrics.copy(cellWidth = 4f, cellHeight = 8f)

        assertThat(SelectionGeometry.handleRadius(tinyCells))
            .isEqualTo(SelectionGeometry.handleRadius(metrics))
        // Comfortably inside Android's 48dp guidance once doubled, and independent of font size.
        assertThat(SelectionGeometry.handleReach(metrics) / metrics.density).isAtLeast(20f)
        assertThat(SelectionGeometry.handleRadius(metrics) / metrics.density).isAtLeast(6f)
    }

    @Test
    fun `a handle stays grabbable at the smallest font the user can choose`() {
        val pinchedDown = metrics.copy(cellWidth = 5f, cellHeight = 10f)
        val selection = SelectionRange(1, 3, 1, 6)
        val x = SelectionGeometry.handleCentreX(6, pinchedDown)
        val y = SelectionGeometry.handleCentreY(1, 0, pinchedDown)

        val grab = SelectionGeometry.grabAt(
            x,
            y,
            selection,
            0,
            pinchedDown,
            SelectionGeometry.handleReach(pinchedDown),
        )

        assertThat(grab).isNotNull()
        assertThat(grab!!.movingColumn).isEqualTo(6)
    }

    @Test
    fun `a handle sits below the row it marks, not on it`() {
        val rowBottom = (2 + 1) * metrics.cellHeight

        assertThat(SelectionGeometry.handleCentreY(2, 0, metrics)).isGreaterThan(rowBottom)
    }

    @Test
    fun `a grab remembers how far the touch was from the centre of the grabbed cell`() {
        val selection = SelectionRange(2, 3, 2, 8)
        val (x, y) = centre(2, 8)

        val grab = grabAt(x - 5f, y, selection)!!

        assertThat(grab.offsetX).isEqualTo(5f)
        // The handle hangs below the row, so the cell the finger means is above it.
        assertThat(grab.offsetY).isEqualTo((2 + 0.5f) * metrics.cellHeight - y)
        assertThat(grab.offsetY).isLessThan(0f)
    }

    @Test
    fun `a dragged end keeps its offset away from the edges`() {
        assertThat(SelectionGeometry.carry(finger = 300f, offset = 30f, extent = 600f)).isEqualTo(330f)
        assertThat(SelectionGeometry.carry(finger = 300f, offset = -30f, extent = 600f)).isEqualTo(270f)
    }

    @Test
    fun `a dragged end reaches the edge the offset points away from`() {
        assertThat(SelectionGeometry.carry(finger = 0f, offset = 30f, extent = 600f)).isEqualTo(0f)
        assertThat(SelectionGeometry.carry(finger = 600f, offset = -30f, extent = 600f)).isEqualTo(600f)
        // Where the fade begins it meets the plain offset, so the end does not jump there.
        assertThat(SelectionGeometry.carry(finger = 30f, offset = 30f, extent = 600f)).isEqualTo(60f)
    }

    @Test
    fun `the magnifier shows a row at the same height whatever the font size`() {
        val zoom = SelectionGeometry.magnifierZoom(metrics)

        assertThat(metrics.cellHeight * zoom / metrics.density).isWithin(0.01f).of(SelectionGeometry.MAGNIFIED_ROW_DP)
    }

    private fun bounds(selection: SelectionRange, scrollOffset: Int = 0) = SelectionGeometry.selectionBounds(selection, scrollOffset, COLUMNS, metrics)

    private fun toolbarAt(anchor: SelectionBounds) = SelectionGeometry.toolbarPosition(
        ToolbarFit(
            anchor = anchor,
            width = TOOLBAR_WIDTH,
            height = TOOLBAR_HEIGHT,
            containerWidth = COLUMNS * 20,
            containerHeight = CONTAINER_HEIGHT,
            gap = GAP,
        ),
    )

    @Test
    fun `a selection on one row is bounded by its cells and the handles hanging below them`() {
        val selected = bounds(SelectionRange(10, 5, 10, 9))

        assertThat(selected.left).isEqualTo(100f)
        assertThat(selected.right).isEqualTo(200f)
        assertThat(selected.top).isEqualTo(400f)
        // The row ends at 440; the handle centre hangs 0.6 radii below that and its dot reaches one more.
        assertThat(selected.bottom).isWithin(0.01f).of(440f + 21f * 0.6f + 21f)
    }

    @Test
    fun `a selection over several rows spans the full width`() {
        val selected = bounds(SelectionRange(3, 30, 5, 2))

        assertThat(selected.left).isEqualTo(0f)
        assertThat(selected.right).isEqualTo(COLUMNS * 20f)
        assertThat(selected.top).isEqualTo(120f)
    }

    @Test
    fun `a selection dragged backwards is bounded the same as one dragged forwards`() {
        assertThat(bounds(SelectionRange(5, 2, 3, 30))).isEqualTo(bounds(SelectionRange(3, 30, 5, 2)))
    }

    @Test
    fun `scrollback lines are bounded where the view draws them`() {
        assertThat(bounds(SelectionRange(-2, 0, -2, 3), scrollOffset = 5).top).isEqualTo(120f)
    }

    @Test
    fun `the toolbar sits above a selection with room above it, centred on it`() {
        val position = toolbarAt(bounds(SelectionRange(10, 5, 10, 9)))

        assertThat(position).isEqualTo(ToolbarPosition(x = 150 - TOOLBAR_WIDTH / 2, y = 400 - GAP.toInt() - TOOLBAR_HEIGHT))
    }

    @Test
    fun `the toolbar drops below the handles of a selection on the top row`() {
        val selected = bounds(SelectionRange(0, 5, 0, 9))

        val position = toolbarAt(selected)

        assertThat(position.y.toFloat()).isAtLeast(selected.bottom + GAP)
        assertThat(position.y.toFloat()).isLessThan(selected.bottom + GAP + 1f)
    }

    @Test
    fun `the toolbar stays inside the terminal for a selection at either side`() {
        val atRight = toolbarAt(bounds(SelectionRange(10, COLUMNS - 2, 10, COLUMNS - 1)))
        val atLeft = toolbarAt(bounds(SelectionRange(10, 0, 10, 0)))

        assertThat(atRight.x).isEqualTo(COLUMNS * 20 - TOOLBAR_WIDTH)
        assertThat(atLeft.x).isEqualTo(0)
    }

    @Test
    fun `the toolbar waits at the nearer edge while the selection is scrolled out of view`() {
        val belowView = toolbarAt(bounds(SelectionRange(40, 0, 40, 3)))
        val aboveView = toolbarAt(bounds(SelectionRange(-5, 0, -5, 3)))

        assertThat(belowView.y).isEqualTo(CONTAINER_HEIGHT - TOOLBAR_HEIGHT)
        assertThat(aboveView.y).isEqualTo(0)
    }

    private companion object {
        const val COLUMNS = 50
        const val CONTAINER_HEIGHT = 1000
        const val TOOLBAR_WIDTH = 60
        const val TOOLBAR_HEIGHT = 30
        const val GAP = 10f
    }
}
