package dev.wadackel.limenty.input

import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The learning flag is what the keyboard reads as "this field is private": beyond not learning
 * from it, Gboard keeps anything copied there out of its clipboard history. It is therefore a
 * choice the user makes rather than a constant.
 */
class EditorConfigurationTest {
    private fun flagSet(options: Int) = options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    @Test
    fun `learning is suppressed by default`() {
        assertThat(flagSet(EditorConfiguration.Default.imeOptions)).isTrue()
        assertThat(flagSet(EditorConfiguration.Default.imeOptionsWith(personalizedLearning = false))).isTrue()
    }

    @Test
    fun `allowing it clears the flag the keyboard reads`() {
        assertThat(flagSet(EditorConfiguration.Default.imeOptionsWith(personalizedLearning = true))).isFalse()
    }

    @Test
    fun `the other options are untouched either way`() {
        for (allowed in listOf(true, false)) {
            val options = EditorConfiguration.Default.imeOptionsWith(allowed)
            assertThat(options and EditorInfo.IME_FLAG_NO_FULLSCREEN).isNotEqualTo(0)
            assertThat(options and EditorInfo.IME_FLAG_NO_EXTRACT_UI).isNotEqualTo(0)
        }
    }

    @Test
    fun `every configuration honours the choice`() {
        for (configuration in EditorConfiguration.All) {
            assertThat(flagSet(configuration.imeOptionsWith(personalizedLearning = true))).isFalse()
            assertThat(flagSet(configuration.imeOptionsWith(personalizedLearning = false))).isTrue()
        }
    }

    @Test
    fun `switching back and forth lands on the same options it started with`() {
        val start = EditorConfiguration.Default.imeOptions
        val roundTrip = EditorConfiguration.Default
            .imeOptionsWith(personalizedLearning = true)
            .let { EditorConfiguration.Default.imeOptionsWith(personalizedLearning = false) }

        assertThat(roundTrip).isEqualTo(start)
    }
}
