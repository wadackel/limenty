package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CharacterWidthTest {
    @Test
    fun `ascii is one column`() {
        assertThat(CharacterWidth.of('a'.code)).isEqualTo(1)
        assertThat(CharacterWidth.of(' '.code)).isEqualTo(1)
    }

    @Test
    fun `control characters occupy no columns`() {
        assertThat(CharacterWidth.of(0x00)).isEqualTo(0)
        assertThat(CharacterWidth.of(0x1B)).isEqualTo(0)
        assertThat(CharacterWidth.of(0x7F)).isEqualTo(0)
    }

    @Test
    fun `cjk is two columns`() {
        listOf(0x65E5, 0x672C, 0x8A9E, 0x3042, 0x30A2, 0xAC00).forEach {
            assertThat(CharacterWidth.of(it)).isEqualTo(2)
        }
    }

    @Test
    fun `fullwidth latin is two columns`() {
        assertThat(CharacterWidth.of(0xFF21)).isEqualTo(2)
    }

    @Test
    fun `emoji are two columns`() {
        assertThat(CharacterWidth.of(0x1F600)).isEqualTo(2)
        assertThat(CharacterWidth.of(0x1F680)).isEqualTo(2)
    }

    @Test
    fun `narrow pictographs inside the emoji planes stay one column`() {
        // These sub-ranges are East Asian Neutral despite sitting between wide neighbours;
        // rounding the whole plane up would misalign any line containing one.
        listOf(0x1F321, 0x1F336, 0x1F37D, 0x1F3CB, 0x1F441, 0x1F4FD, 0x1F53E).forEach {
            assertThat(CharacterWidth.of(it)).isEqualTo(1)
        }
    }

    @Test
    fun `combining marks occupy no columns`() {
        assertThat(CharacterWidth.of(0x0301)).isEqualTo(0)
        assertThat(CharacterWidth.of(0x3099)).isEqualTo(0)
    }

    @Test
    fun `nerd font private use glyphs are one column`() {
        // Powerline and devicon glyphs live in the private use area; treating them as wide would
        // shift every prompt built with starship by one cell.
        listOf(0xE0B0, 0xE0B2, 0xE5FF, 0xF07C, 0xE700).forEach {
            assertThat(CharacterWidth.of(it)).isEqualTo(1)
        }
    }

    @Test
    fun `box drawing characters are one column`() {
        listOf(0x2500, 0x2502, 0x250C, 0x2588).forEach {
            assertThat(CharacterWidth.of(it)).isEqualTo(1)
        }
    }
}
