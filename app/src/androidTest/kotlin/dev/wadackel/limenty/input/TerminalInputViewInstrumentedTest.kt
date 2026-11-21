package dev.wadackel.limenty.input

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.TerminalInputEvent
import dev.wadackel.limenty.core.input.TerminalInputPipeline
import dev.wadackel.limenty.core.terminal.RecordingTerminalBackend
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the input path through real framework types, which the JVM tests cannot construct:
 * `KeyEvent` unicode resolution and the `InputConnection` contract both live in the platform.
 */
@RunWith(AndroidJUnit4::class)
class TerminalInputViewInstrumentedTest {
    private lateinit var view: TerminalInputView
    private lateinit var backend: RecordingTerminalBackend
    private lateinit var pipeline: TerminalInputPipeline

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = TerminalInputView(ApplicationProvider.getApplicationContext())
        }
        backend = RecordingTerminalBackend()
        pipeline = TerminalInputPipeline(sink = backend::write)
        view.onInputEvents = { events -> pipeline.send(events) }
    }

    private fun press(keyCode: Int, metaState: Int = 0) {
        val event = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.onKeyDown(keyCode, event)
        }
    }

    @Test
    fun letterKeyProducesItsCharacter() {
        press(KeyEvent.KEYCODE_A)
        assertThat(backend.text()).isEqualTo("a")
    }

    @Test
    fun shiftedLetterProducesUppercase() {
        press(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        assertThat(backend.text()).isEqualTo("A")
    }

    @Test
    fun ctrlCProducesEtx() {
        press(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x03))
    }

    @Test
    fun altXIsPrefixedWithEscape() {
        press(KeyEvent.KEYCODE_X, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x1B, 0x78))
    }

    @Test
    fun namedKeysUseTheirTerminalSequences() {
        press(KeyEvent.KEYCODE_ESCAPE)
        press(KeyEvent.KEYCODE_TAB)
        press(KeyEvent.KEYCODE_ENTER)
        press(KeyEvent.KEYCODE_DEL)
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x1B, 0x09, 0x0D, 0x7F))
    }

    @Test
    fun arrowKeysUseCsiSequences() {
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
    }

    @Test
    fun modifierKeysAloneProduceNothing() {
        press(KeyEvent.KEYCODE_CTRL_LEFT)
        press(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertThat(backend.size()).isEqualTo(0)
    }

    @Test
    fun volumeKeysAreLeftToTheSystem() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        assertThat(backend.size()).isEqualTo(0)
    }

    @Test
    fun composingThroughTheInputConnectionSendsNothingUntilCommit() {
        val connection = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())

        connection.setComposingText("にほんご", 1)
        connection.setComposingText("日本語", 1)
        assertThat(backend.size()).isEqualTo(0)

        connection.commitText("日本語", 1)
        assertThat(backend.bytes()).isEqualTo("日本語".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun aKeyPressDuringCompositionCannotLeakThePreedit() {
        val connection = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        connection.setComposingText("にほんご", 1)

        press(KeyEvent.KEYCODE_ESCAPE)

        assertThat(backend.size()).isEqualTo(0)
    }

    @Test
    fun eventsAreEncodedForTheTerminalNotAsPlainText() {
        view.onInputEvents = { events ->
            assertThat(events).isNotEmpty()
            assertThat(events.first()).isInstanceOf(TerminalInputEvent.Key::class.java)
        }
        press(KeyEvent.KEYCODE_ENTER)
    }
}
