package dev.wadackel.limenty.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DragReorderTest {
    private val letters = listOf("a", "b", "c", "d")

    @Test
    fun `moving forward carries the item past the ones it passes`() {
        assertEquals(listOf("b", "c", "a", "d"), letters.moved(0, 2))
    }

    @Test
    fun `moving back does the same in the other direction`() {
        assertEquals(listOf("a", "d", "b", "c"), letters.moved(3, 1))
    }

    @Test
    fun `moving to where it already is changes nothing`() {
        assertEquals(letters, letters.moved(2, 2))
    }

    /** A drag can outrun the list it is over, and a reorder is not the place to throw. */
    @Test
    fun `an index outside the list leaves it alone`() {
        assertEquals(letters, letters.moved(0, 9))
        assertEquals(letters, letters.moved(-1, 0))
        assertEquals(emptyList<String>(), emptyList<String>().moved(0, 0))
    }
}
