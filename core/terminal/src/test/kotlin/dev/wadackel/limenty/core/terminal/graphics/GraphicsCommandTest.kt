package dev.wadackel.limenty.core.terminal.graphics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphicsCommandTest {
    private fun parse(text: String): GraphicsCommand? = GraphicsCommand.parse(text.toByteArray(Charsets.US_ASCII))

    private fun parsed(text: String): GraphicsCommand = requireNotNull(parse(text)) { "not a graphics command: $text" }

    // --- what eda.nvim sends ---

    @Test
    fun `a first transmit chunk carries format id quiet and more`() {
        val command = parsed("Ga=t,f=100,i=412345,q=2,m=1;iVBORw0KGgo=")
        assertThat(command.action).isEqualTo('t')
        assertThat(command.format).isEqualTo(100)
        assertThat(command.imageId).isEqualTo(412345)
        assertThat(command.quiet).isEqualTo(2)
        assertThat(command.more).isTrue()
        assertThat(String(command.payload, Charsets.US_ASCII)).isEqualTo("iVBORw0KGgo=")
        assertThat(command.error).isNull()
    }

    @Test
    fun `a continuation chunk has only the more key and defaults to a transmit`() {
        val command = parsed("Gm=0;AAAA")
        assertThat(command.action).isEqualTo('t')
        assertThat(command.more).isFalse()
        assertThat(command.hasImageId).isFalse()
        assertThat(command.error).isNull()
    }

    @Test
    fun `a placement names the image the placement and one axis`() {
        val command = parsed("Gp=412345,C=1,a=p,c=40,i=412345,q=2")
        assertThat(command.action).isEqualTo('p')
        assertThat(command.placementId).isEqualTo(412345)
        assertThat(command.keepCursor).isTrue()
        assertThat(command.columns).isEqualTo(40)
        assertThat(command.rows).isEqualTo(0)
        assertThat(command.error).isNull()
    }

    @Test
    fun `the same keys in another order parse to the same command`() {
        val a = parsed("Ga=p,i=7,p=7,C=1,c=40,q=2")
        val b = parsed("Gq=2,c=40,C=1,p=7,i=7,a=p")
        assertThat(b.action).isEqualTo(a.action)
        assertThat(b.imageId).isEqualTo(a.imageId)
        assertThat(b.placementId).isEqualTo(a.placementId)
        assertThat(b.columns).isEqualTo(a.columns)
        assertThat(b.keepCursor).isEqualTo(a.keepCursor)
        assertThat(b.quiet).isEqualTo(a.quiet)
    }

    @Test
    fun `a delete by id keeps the target letter case`() {
        assertThat(parsed("Ga=d,d=i,i=5,p=5,q=2").deleteTarget).isEqualTo('i')
        assertThat(parsed("Ga=d,d=I,i=5,q=2").deleteTarget).isEqualTo('I')
    }

    @Test
    fun `the capability query parses with its dummy pixel`() {
        val command = parsed("Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA")
        assertThat(command.action).isEqualTo('q')
        assertThat(command.imageId).isEqualTo(31)
        assertThat(command.width).isEqualTo(1)
        assertThat(command.height).isEqualTo(1)
        assertThat(command.format).isEqualTo(24)
        assertThat(command.error).isNull()
    }

    // --- other direct-mode clients ---

    @Test
    fun `a transmit and display with compression and a crop is accepted`() {
        val command = parsed("Ga=T,f=32,s=640,v=480,o=z,i=1,x=10,y=20,w=100,h=50,c=20,r=5,z=-1,C=0;eJw=")
        assertThat(command.compressed).isTrue()
        assertThat(command.sourceX).isEqualTo(10)
        assertThat(command.sourceHeight).isEqualTo(50)
        assertThat(command.keepCursor).isFalse()
        assertThat(command.error).isNull()
    }

    @Test
    fun `an id at the top of the unsigned range keeps its bits`() {
        assertThat(parsed("Ga=t,i=4294967295;AA").imageId.toUInt()).isEqualTo(4294967295u)
    }

    @Test
    fun `unknown keys are skipped rather than rejected`() {
        assertThat(parsed("Ga=t,i=1,N=1,X=3;AA").error).isNull()
    }

    // --- what is not a graphics command ---

    @Test
    fun `an APC that does not start with G is not a graphics command`() {
        assertThat(parse("some private thing")).isNull()
        assertThat(parse("")).isNull()
    }

    // --- rejections ---

    @Test
    fun `an image number is rejected because only ids are supported`() {
        assertThat(parsed("Ga=t,I=3;AA").error).startsWith("EINVAL:")
    }

    @Test
    fun `a unicode placeholder placement is rejected rather than drawn where it was not wanted`() {
        assertThat(parsed("Ga=p,i=1,U=1").error).startsWith("EINVAL:")
    }

    @Test
    fun `a file transmission is rejected`() {
        assertThat(parsed("Ga=t,t=f,i=1;L3RtcC94").error).startsWith("EINVAL:")
    }

    @Test
    fun `an unknown format is rejected`() {
        assertThat(parsed("Ga=t,f=8,i=1;AA").error).startsWith("EINVAL:")
    }

    @Test
    fun `a query without an id is rejected`() {
        assertThat(parsed("Ga=q,s=1,v=1,f=24;AAAA").error).startsWith("EINVAL:")
    }

    @Test
    fun `a value that is not a number is rejected but the id is still read`() {
        val command = parsed("Ga=t,i=9,s=wide;AA")
        assertThat(command.error).startsWith("EINVAL:")
        assertThat(command.imageId).isEqualTo(9)
    }

    @Test
    fun `an unsupported action is rejected`() {
        assertThat(parsed("Ga=f,i=1;AA").error).startsWith("EINVAL:")
    }
}
