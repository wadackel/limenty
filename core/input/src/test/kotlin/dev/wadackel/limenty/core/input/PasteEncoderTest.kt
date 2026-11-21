package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasteEncoderTest {
    private val e = "\u001B"

    private fun encoded(text: String, bracketed: Boolean) = String(PasteEncoder.encode(text, bracketed), Charsets.UTF_8)

    @Test
    fun `plain paste sends the text as is`() {
        assertThat(encoded("ls -la", bracketed = false)).isEqualTo("ls -la")
    }

    @Test
    fun `bracketed paste wraps the text in markers`() {
        assertThat(encoded("ls -la", bracketed = true)).isEqualTo("$e[200~ls -la$e[201~")
    }

    @Test
    fun `newlines inside a paste stay newlines`() {
        assertThat(encoded("one\ntwo", bracketed = false)).isEqualTo("one\ntwo")
    }

    @Test
    fun `an embedded end marker cannot close the bracket early`() {
        val hostile = "safe$e[201~rm -rf /"
        val result = encoded(hostile, bracketed = true)

        assertThat(result.indexOf("$e[201~")).isEqualTo(result.length - 6)
        assertThat(result).doesNotContain("~rm -rf /$e[201~")
    }

    @Test
    fun `an end marker nested inside another cannot reassemble into one`() {
        val hostile = "a$e[201$e[201~~; rm -rf x\n"
        val result = encoded(hostile, bracketed = true)

        assertThat(result.indexOf("$e[201~")).isEqualTo(result.length - 6)
    }

    @Test
    fun `deeply nested end markers are removed in one pass`() {
        val depth = 100_000
        val hostile = "$e[201".repeat(depth) + "~".repeat(depth) + "tail"
        val result = encoded(hostile, bracketed = true)

        assertThat(result).isEqualTo("$e[200~tail$e[201~")
    }

    @Test
    fun `an empty paste sends nothing`() {
        assertThat(PasteEncoder.encode("", bracketed = true)).isEmpty()
    }

    @Test
    fun `utf-8 is preserved`() {
        assertThat(encoded("日本語", bracketed = false).toByteArray(Charsets.UTF_8))
            .isEqualTo("日本語".toByteArray(Charsets.UTF_8))
    }
}
