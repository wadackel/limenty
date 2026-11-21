package dev.wadackel.limenty.core.terminal.graphics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import javax.imageio.ImageIO

class GraphicsStoreTest {
    private val placed = mutableSetOf<Int>()
    private val removed = mutableListOf<Int>()
    private var store = GraphicsStore(isPlaced = { it in placed }, onRemoved = { removed += it })

    private val e = "\u001B"

    private fun send(text: String): Transmission = store.receive(requireNotNull(GraphicsCommand.parse(text.toByteArray(Charsets.US_ASCII))))

    private fun complete(text: String): Transmission.Complete = send(text) as Transmission.Complete

    private fun responseOf(text: String): String? {
        val result = complete(text)
        return GraphicsStore.response(result.command, result.error)?.let { String(it, Charsets.US_ASCII) }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** A real PNG, so the header reader is exercised against what an encoder writes. */
    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val buffer = ByteArray(bytes.size + 64)
        val length = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(length)
    }

    // --- direct transmission ---

    @Test
    fun `a raw RGBA pixel is stored as it arrived and answered with OK`() {
        val result = complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        val image = requireNotNull(result.image)
        assertThat(image.width).isEqualTo(1)
        assertThat(image.height).isEqualTo(1)
        assertThat(image.data).isInstanceOf(ImageData.Rgba::class.java)
        assertThat(image.data.bytes).isEqualTo(bytes(0xFF, 0x00, 0x00, 0xFF))
        assertThat(GraphicsStore.response(result.command, result.error)?.let { String(it, Charsets.US_ASCII) })
            .isEqualTo("${e}_Gi=1;OK$e\\")
        assertThat(store.image(1)).isSameInstanceAs(image)
    }

    @Test
    fun `an RGB pixel gains an opaque alpha channel`() {
        val image = requireNotNull(complete("Ga=t,f=24,s=1,v=1,i=1;/wAA").image)
        assertThat(image.data.bytes).isEqualTo(bytes(0xFF, 0x00, 0x00, 0xFF))
    }

    @Test
    fun `a PNG is kept as PNG with its size read from the header`() {
        val image = requireNotNull(complete("Ga=t,f=100,i=2;${base64(png(3, 2))}").image)
        assertThat(image.width).isEqualTo(3)
        assertThat(image.height).isEqualTo(2)
        assertThat(image.data).isInstanceOf(ImageData.Png::class.java)
    }

    @Test
    fun `deflated pixels are inflated before they are checked against the declared size`() {
        val pixels = bytes(0xFF, 0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF)
        val image = requireNotNull(complete("Ga=t,f=32,s=2,v=1,o=z,i=3;${base64(deflate(pixels))}").image)
        assertThat(image.data.bytes).isEqualTo(pixels)
    }

    // --- chunking ---

    @Test
    fun `chunks are joined and the single reply comes with the last one`() {
        assertThat(send("Ga=t,f=32,s=2,v=1,i=5,m=1;/wAA/w==")).isInstanceOf(Transmission.Pending::class.java)
        assertThat(store.isTransferring).isTrue()
        val response = responseOf("Gm=0;AP8A/w==")
        assertThat(response).isEqualTo("${e}_Gi=5;OK$e\\")
        assertThat(store.image(5)?.data?.bytes).isEqualTo(bytes(0xFF, 0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF))
        assertThat(store.isTransferring).isFalse()
    }

    @Test
    fun `a later chunk can raise the quiet level`() {
        send("Ga=t,f=32,s=1,v=1,i=5,m=1;/wAA")
        assertThat(responseOf("Gm=0,q=2;/w==")).isNull()
        assertThat(store.image(5)).isNotNull()
    }

    @Test
    fun `an aborted transfer does not swallow the next chunk`() {
        send("Ga=t,f=32,s=1,v=1,i=5,m=1;/wAA")
        store.abortTransfer("truncated")
        val result = complete("Gm=0;/w==")
        assertThat(result.image).isNull()
        assertThat(store.image(5)).isNull()
        assertThat(store.isTransferring).isFalse()
    }

    // --- validation ---

    @Test
    fun `pixel data that does not match the declared size is refused`() {
        val result = complete("Ga=t,f=32,s=2,v=2,i=1;/wAA/w==")
        assertThat(result.image).isNull()
        assertThat(result.error).startsWith("ENODATA:")
        assertThat(store.image(1)).isNull()
    }

    @Test
    fun `more data than the declared size can hold is refused before it is buffered`() {
        val result = complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w8AAP8=")
        assertThat(result.error).startsWith("EFBIG:")
    }

    @Test
    fun `inflated data is bounded by the declared size`() {
        val bomb = deflate(ByteArray(4096))
        val result = complete("Ga=t,f=32,s=1,v=1,o=z,i=1;${base64(bomb)}")
        assertThat(result.error).startsWith("EFBIG:")
    }

    @Test
    fun `bytes that are not a PNG are refused`() {
        assertThat(complete("Ga=t,f=100,i=1;AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").error).startsWith("EINVAL:")
    }

    @Test
    fun `a command the parser rejected is answered with its error`() {
        assertThat(responseOf("Ga=t,f=8,i=7;AA")).isEqualTo("${e}_Gi=7;EINVAL:unsupported format 8$e\\")
    }

    // --- responses ---

    @Test
    fun `the capability query is answered with OK and stores nothing`() {
        assertThat(responseOf("Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA")).isEqualTo("${e}_Gi=31;OK$e\\")
        assertThat(store.all).isEmpty()
    }

    @Test
    fun `a command without an id gets no reply even on success`() {
        assertThat(responseOf("Ga=t,f=32,s=1,v=1;/wAA/w==")).isNull()
    }

    @Test
    fun `quiet one keeps OK back but still reports a failure`() {
        assertThat(responseOf("Ga=t,f=32,s=1,v=1,i=1,q=1;/wAA/w==")).isNull()
        assertThat(responseOf("Ga=t,f=32,s=2,v=2,i=1,q=1;/wAA/w==")).startsWith("${e}_Gi=1;ENODATA:")
    }

    @Test
    fun `quiet two keeps everything back`() {
        assertThat(responseOf("Ga=t,f=32,s=2,v=2,i=1,q=2;/wAA/w==")).isNull()
    }

    @Test
    fun `a placement id is echoed in the reply`() {
        val command = requireNotNull(GraphicsCommand.parse("Ga=p,i=4,p=9".toByteArray()))
        assertThat(GraphicsStore.response(command, null)?.let { String(it, Charsets.US_ASCII) }).isEqualTo("${e}_Gi=4,p=9;OK$e\\")
    }

    // --- lifetime ---

    @Test
    fun `re-sending an id replaces the image and drops what pointed at the old one`() {
        val first = requireNotNull(complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==").image)
        val second = requireNotNull(complete("Ga=t,f=32,s=1,v=1,i=1;AP8A/w==").image)
        assertThat(second.serial).isNotEqualTo(first.serial)
        assertThat(removed).containsExactly(1)
        assertThat(store.image(1)).isSameInstanceAs(second)
    }

    @Test
    fun `over the quota the oldest unplaced image goes first and placed ones stay`() {
        store = GraphicsStore(isPlaced = { it in placed }, onRemoved = { removed += it }, quotaBytes = 8)
        complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        complete("Ga=t,f=32,s=1,v=1,i=2;/wAA/w==")
        placed += 1

        assertThat(complete("Ga=t,f=32,s=1,v=1,i=3;/wAA/w==").image).isNotNull()
        assertThat(store.image(2)).isNull()
        assertThat(store.image(1)).isNotNull()
        assertThat(removed).containsExactly(2)
    }

    @Test
    fun `when nothing can be evicted a new image is refused as its bytes arrive`() {
        store = GraphicsStore(isPlaced = { it in placed }, onRemoved = { removed += it }, quotaBytes = 8)
        complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        complete("Ga=t,f=32,s=1,v=1,i=2;/wAA/w==")
        placed += listOf(1, 2)

        val result = complete("Ga=t,f=32,s=1,v=1,i=3;/wAA/w==")
        assertThat(result.error).startsWith("ENOSPC:")
        assertThat(store.image(3)).isNull()
        assertThat(store.image(1)).isNotNull()
        assertThat(store.image(2)).isNotNull()
    }

    @Test
    fun `deleting by id frees the image and releasing unplaced ones keeps those on screen`() {
        complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        complete("Ga=t,f=32,s=1,v=1,i=2;/wAA/w==")
        complete("Ga=t,f=32,s=1,v=1,i=3;/wAA/w==")
        placed += 2

        assertThat(store.delete(1)).isTrue()
        assertThat(store.delete(1)).isFalse()
        store.release(setOf(2, 3))
        assertThat(store.all.map { it.id }).containsExactly(2)
    }

    @Test
    fun `releasing only touches the images it was asked about`() {
        complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        complete("Ga=t,f=32,s=1,v=1,i=2;/wAA/w==")
        store.release(setOf(2))
        assertThat(store.all.map { it.id }).containsExactly(1)
    }

    // --- hostile input ---

    @Test
    fun `a declared size beyond the limit is refused before anything is buffered`() {
        val result = complete("Ga=t,f=32,s=60000,v=60000,i=1,m=1;/wAA/w==")
        assertThat(result.error).startsWith("EFBIG:")
        assertThat(store.isTransferring).isFalse()
    }

    @Test
    fun `a compressed transfer that declares the largest legal image is still bounded and checked`() {
        val result = complete("Ga=t,f=32,s=4096,v=4096,o=z,i=1;${base64(deflate(ByteArray(1024)))}")
        assertThat(result.error).startsWith("ENODATA:")
        assertThat(store.image(1)).isNull()
    }

    @Test
    fun `bytes behind a tiny PNG header still count against the quota`() {
        store = GraphicsStore(isPlaced = { it in placed }, onRemoved = {}, quotaBytes = 2048)
        val padded = png(1, 1) + ByteArray(1024)
        val first = requireNotNull(complete("Ga=t,f=100,i=1;${base64(padded)}").image)
        assertThat(first.retainedBytes).isEqualTo(padded.size.toLong())
        placed += 1

        // A one-pixel header does not make the second one cheap: what is left of the quota beside
        // the placed image is less than the bytes it would have to hold.
        val result = complete("Ga=t,f=100,i=2;${base64(padded)}")
        assertThat(result.error).startsWith("ENOSPC:")
        assertThat(store.image(2)).isNull()
    }

    @Test
    fun `a transfer cannot buffer more than the quota has left beside placed images`() {
        store = GraphicsStore(isPlaced = { it in placed }, onRemoved = { removed += it }, quotaBytes = 8)
        complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        placed += 1
        // 8 bytes arriving, 4 left beside the placed image: refused as the chunk lands.
        val result = complete("Ga=t,f=32,s=2,v=1,i=2,m=1;/wAA/wD/AP8=")
        assertThat(result.error).startsWith("ENOSPC:")
        assertThat(store.isTransferring).isFalse()
    }

    @Test
    fun `a transfer left open takes room from unplaced images rather than from beside the quota`() {
        store = GraphicsStore(isPlaced = { it in placed }, onRemoved = { removed += it }, quotaBytes = 8)
        complete("Ga=t,f=32,s=1,v=1,i=1;/wAA/w==")
        complete("Ga=t,f=32,s=1,v=1,i=2;/wAA/w==")
        assertThat(send("Ga=t,f=32,s=3,v=1,i=3,m=1;/wAA/w==")).isInstanceOf(Transmission.Pending::class.java)
        assertThat(removed).containsExactly(1)
        assertThat(send("Gm=1;/wAA/w==")).isInstanceOf(Transmission.Pending::class.java)
        assertThat(removed).containsExactly(1, 2)
        val result = complete("Gm=1;/wAA/w==")
        assertThat(result.error).startsWith("ENOSPC:")
    }

    @Test
    fun `a compressed raw transfer is bounded by the declared size plus deflate framing`() {
        val result = complete("Ga=t,f=32,s=1,v=1,o=z,i=1;${base64(ByteArray(4096))}")
        assertThat(result.error).startsWith("EFBIG:")
    }

    @Test
    fun `the image count is bounded even when each image is tiny`() {
        repeat(300) { complete("Ga=t,f=32,s=1,v=1,i=${it + 1};/wAA/w==") }
        assertThat(store.all.size).isAtMost(256)
    }
}
