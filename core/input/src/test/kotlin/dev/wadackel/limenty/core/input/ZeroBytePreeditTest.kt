package dev.wadackel.limenty.core.input

import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.terminal.RecordingTerminalBackend
import org.junit.Test

/**
 * The input layer's central guarantee: while an IME composition is in flight the backend must
 * receive exactly zero bytes, and the confirmed text must arrive exactly once when it is
 * committed.
 */
class ZeroBytePreeditTest {
    private val backend = RecordingTerminalBackend()
    private val buffer = ImeEditBuffer()
    private val arbiter = InputArbiter(buffer)
    private val pipeline = TerminalInputPipeline(sink = backend::write)

    private fun ime(block: ImeEditBuffer.() -> List<TerminalInputEvent>) {
        pipeline.send(buffer.block())
    }

    private fun key(key: TerminalKey?, codePoint: Int = 0, modifiers: Modifiers = Modifiers.None) {
        pipeline.send(arbiter.onKey(key, codePoint, modifiers).events)
    }

    @Test
    fun `converting nihongo to kanji writes nothing until the commit`() {
        ime { setComposingText("n", 1) }
        ime { setComposingText("に", 1) }
        ime { setComposingText("にほ", 1) }
        ime { setComposingText("にほん", 1) }
        ime { setComposingText("にほんご", 1) }
        ime { setComposingText("日本語", 1) }

        assertThat(backend.size()).isEqualTo(0)

        ime { commitText("日本語", 1) }

        assertThat(backend.bytes()).isEqualTo("日本語".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `bunsetsu style repeated candidate cycling writes nothing`() {
        ime { setComposingText("きょうはいいてんきですね", 1) }
        repeat(20) { index ->
            ime { setComposingText("今日はいい天気ですね$index", 1) }
        }
        assertThat(backend.size()).isEqualTo(0)
    }

    @Test
    fun `aborting a conversion with esc writes nothing at all`() {
        ime { setComposingText("にほんご", 1) }
        key(TerminalKey.Escape)

        assertThat(backend.size()).isEqualTo(0)
        assertThat(buffer.composing.active).isFalse()
    }

    @Test
    fun `enter during conversion writes the confirmed text and no newline`() {
        ime { setComposingText("日本語", 1) }
        key(TerminalKey.Enter)

        assertThat(backend.bytes()).isEqualTo("日本語".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `ctrl c during conversion writes only the interrupt`() {
        ime { setComposingText("にほんご", 1) }
        key(null, 'c'.code, Modifiers.Ctrl)

        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x03))
    }

    @Test
    fun `backspace during conversion writes nothing`() {
        ime { setComposingText("にほんご", 1) }
        repeat(4) { key(TerminalKey.Backspace) }

        assertThat(backend.size()).isEqualTo(0)
    }

    @Test
    fun `text committed once is not written twice by a trailing finish`() {
        ime { setComposingText("日本語", 1) }
        ime { commitText("日本語", 1) }
        ime { endComposing() }

        assertThat(backend.text()).isEqualTo("日本語")
    }

    /**
     * Typing 「特定の」「パターンで」 quickly, as the device traced it. Between the two words Gboard
     * parks the unresolved consonant as its own composition. It belongs to the syllable the next
     * key completes, so it has to survive: withholding it turned 「の」 into 「お」 on the device.
     */
    @Test
    fun `the leftover consonant between two conversions is not swallowed`() {
        ime { setComposingText("とくていの", 1) }
        ime { commitText("特定の", 1) }
        ime { setComposingText("ｎ", 1) }
        ime { endComposing() }
        ime { setComposingText("ぱたーんで", 1) }
        ime { commitText("パターンで", 1) }
        ime { endComposing() }

        assertThat(backend.text()).isEqualTo("特定のｎパターンで")
    }

    @Test
    fun `an english sentence typed through the ime arrives verbatim`() {
        "ls -la /tmp".forEach { ch -> ime { commitText(ch.toString(), 1) } }
        assertThat(backend.text()).isEqualTo("ls -la /tmp")
    }

    @Test
    fun `hardware keys still reach the backend while no composition exists`() {
        key(null, 'l'.code, Modifiers.Ctrl)
        key(TerminalKey.Enter)

        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x0C, 0x0D))
    }

    @Test
    fun `a soft keyboard return reaches the backend as CR not LF`() {
        ime { commitText("ls", 1) }
        ime { commitText("\n", 1) }

        assertThat(backend.bytes()).isEqualTo(byteArrayOf(0x6C, 0x73, 0x0D))
    }

    @Test
    fun `interleaving hardware keys and conversions keeps the streams separate`() {
        key(null, 'v'.code)
        key(null, 'i'.code)
        ime { setComposingText("にほんご", 1) }
        assertThat(backend.text()).isEqualTo("vi")

        ime { commitText("日本語", 1) }
        key(TerminalKey.Enter)

        assertThat(backend.text()).isEqualTo("vi日本語\r")
    }
}
