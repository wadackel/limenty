package dev.wadackel.limenty.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeystrokeDescriptionTest {
    private fun describe(character: Char): String = KeystrokeDescription.codePoint(character.code)

    @Test
    fun `a password is indistinguishable from any other text of the same shape`() {
        val password = "hunter2!".map(::describe)
        val innocuous = "abcdef9?".map(::describe)

        // Same shapes, different characters: the record cannot be used to recover either one.
        assertThat(password).isEqualTo(innocuous)
        assertThat(password.joinToString()).doesNotContain("U+")
    }

    @Test
    fun `the shape is still distinguishable enough to reconstruct a sequence`() {
        assertThat(describe('a')).isEqualTo("alnum")
        assertThat(describe('7')).isEqualTo("alnum")
        assertThat(describe(' ')).isEqualTo("space")
        assertThat(describe('!')).isEqualTo("other")
    }

    @Test
    fun `a key with no character is reported as such`() {
        assertThat(KeystrokeDescription.codePoint(0)).isEqualTo("-")
    }

    @Test
    fun `a key that cannot spell a secret keeps its code`() {
        val escape = KeyEvent.KEYCODE_ESCAPE
        assertThat(KeystrokeDescription.carriesText(escape, 0)).isFalse()
        assertThat(KeystrokeDescription.keyCode(escape, hasCharacter = false)).isEqualTo(escape.toString())
    }

    @Test
    fun `a text-carrying key withholds which key it was`() {
        assertThat(KeystrokeDescription.carriesText(KeyEvent.KEYCODE_A, 'a'.code)).isTrue()
        assertThat(KeystrokeDescription.keyCode(KeyEvent.KEYCODE_A, hasCharacter = true)).isEqualTo("text")
    }
}
