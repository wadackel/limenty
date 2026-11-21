package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.graphics.CellPixelSize
import dev.wadackel.limenty.core.terminal.graphics.Placement
import org.junit.Test

/** The kitty graphics protocol as the screen applies it: placement, cursor, erase and replies. */
class TerminalGraphicsTest {
    private val responses = mutableListOf<String>()
    private val observer = object : TerminalObserver {
        override fun respond(bytes: ByteArray) {
            responses += String(bytes, Charsets.US_ASCII)
        }
    }
    private val terminal = TerminalEmulator(columns = 20, rows = 5, observer = observer, maxScrollback = 3).apply {
        cellPixelSize = CellPixelSize(10, 20)
    }
    private val e = "\u001B"

    private fun write(text: String) = terminal.write(text)

    private fun placements(): List<Placement> = terminal.buffer.placements

    /** 4x2 opaque red RGBA pixels, base64 as one run (padding only at the end). */
    private val redPixels = java.util.Base64.getEncoder().encodeToString(
        ByteArray(4 * 2 * 4) { index -> if (index % 4 == 0 || index % 4 == 3) 0xFF.toByte() else 0 },
    )

    /** A 4x2 opaque red RGBA image, id 1. */
    private fun transmit(id: Int = 1, extra: String = "") = write("${e}_Ga=t,f=32,s=4,v=2,i=$id$extra;$redPixels$e\\")

    // --- eda.nvim's path: transmit, place, hide, delete ---

    @Test
    fun `an image is transmitted then placed at the cursor with one axis derived from the aspect ratio`() {
        transmit(extra = ",q=2")
        write("$e[3;4H")
        write("${e}_Ga=p,i=1,p=1,C=1,c=8,q=2$e\\")

        val placement = placements().single()
        assertThat(placement.row).isEqualTo(2)
        assertThat(placement.column).isEqualTo(3)
        assertThat(placement.columns).isEqualTo(8)
        // 8 cells * 10px wide = 80px; the image is 4x2 so that is 40px tall, which is 2 rows of 20px.
        assertThat(placement.rows).isEqualTo(2)
        assertThat(terminal.cursor.row).isEqualTo(2)
        assertThat(terminal.cursor.column).isEqualTo(3)
        assertThat(responses).isEmpty()
    }

    @Test
    fun `the rows only form derives the columns`() {
        transmit()
        write("${e}_Ga=p,i=1,C=1,r=1$e\\")
        assertThat(placements().single().columns).isEqualTo(4)
    }

    @Test
    fun `a crop changes the aspect ratio the other axis is derived from`() {
        transmit()
        write("${e}_Ga=p,i=1,C=1,c=2,x=0,y=0,w=1,h=2$e\\")
        val placement = placements().single()
        assertThat(placement.sourceWidth).isEqualTo(1)
        assertThat(placement.sourceHeight).isEqualTo(2)
        // 2 cells = 20px wide, source is 1x2 so 40px tall = 2 rows.
        assertThat(placement.rows).isEqualTo(2)
    }

    @Test
    fun `re-placing with the same placement id moves the image rather than duplicating it`() {
        transmit()
        write("${e}_Ga=p,i=1,p=7,C=1,c=2$e\\")
        write("$e[4;1H${e}_Ga=p,i=1,p=7,C=1,c=2$e\\")
        assertThat(placements().map { it.row }).containsExactly(3)
    }

    @Test
    fun `deleting by id removes the placement but keeps the pixels until the capital form`() {
        transmit()
        write("${e}_Ga=p,i=1,p=1,C=1,c=2,q=2$e\\")
        write("${e}_Ga=d,d=i,i=1,p=1,q=2$e\\")
        assertThat(placements()).isEmpty()
        assertThat(terminal.graphics.image(1)).isNotNull()
        write("${e}_Ga=d,d=I,i=1,q=2$e\\")
        assertThat(terminal.graphics.image(1)).isNull()
    }

    @Test
    fun `the capability query is answered exactly as eda expects`() {
        write("${e}_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA$e\\")
        assertThat(responses).containsExactly("${e}_Gi=31;OK$e\\")
        assertThat(terminal.graphics.all).isEmpty()
    }

    // --- transmit and display ---

    @Test
    fun `transmit and display places at the cursor and walks the cursor past the image`() {
        write("$e[2;3H")
        write("${e}_Ga=T,f=32,s=4,v=2,i=1,c=4,r=2;$redPixels$e\\")
        assertThat(placements().single().row).isEqualTo(1)
        assertThat(terminal.cursor.row).isEqualTo(2)
        assertThat(terminal.cursor.column).isEqualTo(6)
        assertThat(responses).containsExactly("${e}_Gi=1;OK$e\\")
    }

    @Test
    fun `a placement without C also walks the cursor and wraps at the right edge`() {
        transmit()
        write("$e[1;18H${e}_Ga=p,i=1,c=4,r=1$e\\")
        assertThat(terminal.cursor.row).isEqualTo(1)
        assertThat(terminal.cursor.column).isEqualTo(0)
    }

    @Test
    fun `an image taller than the space below the cursor scrolls the screen and moves with it`() {
        transmit()
        write("$e[5;1H${e}_Ga=p,i=1,c=2,r=3$e\\")
        assertThat(terminal.cursor.row).isEqualTo(4)
        assertThat(placements().single().row).isEqualTo(2)
        assertThat(terminal.buffer.totalLinesScrolled).isEqualTo(2)
    }

    @Test
    fun `chunks with ordinary output between them still make one image`() {
        write("${e}_Ga=t,f=32,s=1,v=2,i=9,m=1;/wAA/w==$e\\")
        write("redraw\r\n$e[2K")
        write("${e}_Gm=0;/wAA/w==$e\\")
        assertThat(terminal.graphics.image(9)).isNotNull()
        assertThat(responses).containsExactly("${e}_Gi=9;OK$e\\")
    }

    @Test
    fun `an oversized chunk aborts the transfer so the next chunk cannot splice onto it`() {
        write("${e}_Ga=t,f=32,s=1,v=2,i=9,m=1;/wAA/w==$e\\")
        write("${e}_G" + "x".repeat(20000) + "$e\\")
        write("${e}_Gm=0;/wAA/w==$e\\")
        assertThat(terminal.graphics.image(9)).isNull()
    }

    @Test
    fun `an action this terminal does not implement is refused rather than left unanswered`() {
        write("${e}_Ga=f,i=5,r=1;AAAA$e\\")
        assertThat(responses).containsExactly("${e}_Gi=5;EINVAL:unsupported action$e\\")
    }

    @Test
    fun `placing an unknown image is refused with ENOENT`() {
        write("${e}_Ga=p,i=42,c=1$e\\")
        assertThat(responses).containsExactly("${e}_Gi=42;ENOENT:no image with id 42$e\\")
        assertThat(placements()).isEmpty()
    }

    // --- following the screen ---

    @Test
    fun `a line feed at the bottom moves a placement up and into scrollback`() {
        transmit()
        write("$e[5;1H${e}_Ga=p,i=1,C=1,c=2,r=1$e\\")
        write("\n")
        assertThat(placements().single().row).isEqualTo(3)
        repeat(4) { write("\n") }
        assertThat(placements().single().row).isEqualTo(-1)
    }

    @Test
    fun `an insert line inside a scroll region moves only the placements in it`() {
        transmit()
        write("$e[2;1H${e}_Ga=p,i=1,p=1,C=1,c=2,r=1$e\\")
        write("$e[5;1H${e}_Ga=p,i=1,p=2,C=1,c=2,r=1$e\\")
        write("$e[1;4r$e[2;1H$e[L")
        assertThat(placements().map { it.placementId to it.row }).containsExactly(1 to 2, 2 to 4)
    }

    @Test
    fun `leaving the alternate screen takes its placements with it`() {
        transmit()
        write("$e[?1049h${e}_Ga=p,i=1,C=1,c=2,r=1$e\\")
        assertThat(placements()).hasSize(1)
        write("$e[?1049l")
        assertThat(placements()).isEmpty()
        assertThat(terminal.graphics.image(1)).isNotNull()
    }

    @Test
    fun `erasing the screen removes its placements and frees images nothing shows any more`() {
        transmit(id = 1)
        transmit(id = 2)
        write("$e[1;1H${e}_Ga=p,i=1,C=1,c=2,r=1$e\\")
        repeat(5) { write("\n") }
        write("${e}_Ga=p,i=2,C=1,c=2,r=1$e\\")
        assertThat(placements().map { it.imageId to it.row }).containsExactly(1 to -1, 2 to 4)

        write("$e[2J")
        assertThat(placements().map { it.imageId }).containsExactly(1)
        assertThat(terminal.graphics.image(2)).isNull()
        assertThat(terminal.graphics.image(1)).isNotNull()

        write("$e[3J")
        assertThat(placements()).isEmpty()
        assertThat(terminal.graphics.image(1)).isNull()
    }

    @Test
    fun `a hard reset drops scrollback placements and the image store`() {
        transmit()
        write("$e[1;1H${e}_Ga=p,i=1,C=1,c=2,r=1$e\\")
        repeat(5) { write("\n") }
        assertThat(placements().single().row).isEqualTo(-1)
        write("${e}c")
        assertThat(placements()).isEmpty()
        assertThat(terminal.graphics.all).isEmpty()
    }

    @Test
    fun `deleting at the cursor removes only what is under it`() {
        transmit()
        write("$e[1;1H${e}_Ga=p,i=1,p=1,C=1,c=2,r=1$e\\")
        write("$e[3;1H${e}_Ga=p,i=1,p=2,C=1,c=2,r=1$e\\")
        write("$e[1;2H${e}_Ga=d,d=c$e\\")
        assertThat(placements().map { it.placementId }).containsExactly(2)
    }

    @Test
    fun `a delete target this terminal does not implement is ignored`() {
        transmit()
        write("${e}_Ga=p,i=1,C=1,c=2,r=1$e\\")
        write("${e}_Ga=d,d=z,z=0$e\\")
        assertThat(placements()).hasSize(1)
    }

    @Test
    fun `erasing the screen leaves an image that was transmitted but never placed`() {
        transmit(id = 1)
        write("$e[2J")
        assertThat(terminal.graphics.image(1)).isNotNull()
        write("${e}_Ga=p,i=1,C=1,c=2,r=1$e\\")
        assertThat(placements()).hasSize(1)
    }

    @Test
    fun `a delete with an id is acknowledged so a waiting client moves on`() {
        transmit(extra = ",q=2")
        write("${e}_Ga=p,i=1,p=3,C=1,c=2,r=1,q=2$e\\")
        write("${e}_Ga=d,d=i,i=1,p=3$e\\")
        assertThat(responses).containsExactly("${e}_Gi=1,p=3;OK$e\\")
    }

    @Test
    fun `placements that never scroll or delete are capped rather than unbounded`() {
        transmit()
        repeat(TerminalBuffer.MAX_PLACEMENTS + 10) { write("${e}_Ga=p,i=1,C=1,c=1,r=1,q=2$e\\") }
        assertThat(placements()).hasSize(TerminalBuffer.MAX_PLACEMENTS)
    }

    @Test
    fun `an error reply never echoes what the remote sent`() {
        write("${e}_Ga=t,i=7,x=\$(evil);AA$e\\")
        assertThat(responses).hasSize(1)
        assertThat(responses.single()).doesNotContain("evil")
        assertThat(responses.single()).startsWith("${e}_Gi=7;EINVAL:")
    }

    @Test
    fun `a placement wide enough to wrap an Int leaves the cursor on the grid`() {
        transmit()
        write("$e[1;2H${e}_Ga=p,i=1,c=2147483647,r=2147483647$e\\")
        assertThat(terminal.cursor.column).isIn(0 until terminal.columns)
        assertThat(terminal.cursor.row).isIn(0 until terminal.rows)
        write("x$e[K")
        assertThat(terminal.screenText()).isNotNull()
    }

    // --- pixel size reports ---

    @Test
    fun `the window and cell pixel reports come from the measured cell size`() {
        write("$e[14t$e[16t")
        assertThat(responses).containsExactly("$e[4;100;200t", "$e[6;20;10t").inOrder()
    }
}
