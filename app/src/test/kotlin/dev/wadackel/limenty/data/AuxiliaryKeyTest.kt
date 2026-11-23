package dev.wadackel.limenty.data

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalInputEvent
import dev.wadackel.limenty.core.input.TerminalKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bar is configurable and its layout outlives the process, so what a button sends
 * has to survive being written down and read back. Runs under Robolectric for `org.json`, whose
 * stubs throw on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AuxiliaryKeyTest {
    @Test
    fun theDefaultBarSurvivesARoundTrip() {
        val decoded = AuxiliaryKey.decodeBar(AuxiliaryKey.encodeBar(AuxiliaryKey.DefaultScrolling))

        assertThat(decoded).isEqualTo(AuxiliaryKey.DefaultScrolling)
    }

    @Test
    fun everyDefaultButtonActuallySendsSomething() {
        for (entry in AuxiliaryKey.DefaultScrolling) {
            assertThat(entry.toEvents()).isNotEmpty()
            assertThat(entry.label).isNotEmpty()
        }
    }

    @Test
    fun aNamedKeyBecomesAKeyEvent() {
        val escape = AuxiliaryKey("Esc", key = TerminalKey.Escape)

        assertThat(escape.toEvents()).containsExactly(TerminalInputEvent.Key(TerminalKey.Escape, Modifiers.None))
    }

    @Test
    fun aModifiedCharacterBecomesAChordRatherThanText() {
        val interrupt = AuxiliaryKey("C-c", codePoint = 'c'.code, modifiers = Modifiers.Ctrl)

        assertThat(interrupt.toEvents()).containsExactly(TerminalInputEvent.Chord('c'.code, Modifiers.Ctrl))
    }

    @Test
    fun aBareCharacterBecomesText() {
        assertThat(AuxiliaryKey("|", codePoint = '|'.code).toEvents())
            .containsExactly(TerminalInputEvent.Text("|"))
    }

    @Test
    fun aButtonWithNothingBoundSendsNothing() {
        assertThat(AuxiliaryKey("blank").toEvents()).isEmpty()
    }

    @Test
    fun aLabelMayContainAnythingIncludingJsonPunctuation() {
        val awkward = listOf(
            AuxiliaryKey("\"quoted\"", codePoint = 'q'.code),
            AuxiliaryKey("{}[],:\\", codePoint = 'x'.code),
            AuxiliaryKey("日本語 ⏎", key = TerminalKey.Enter),
        )

        assertThat(AuxiliaryKey.decodeBar(AuxiliaryKey.encodeBar(awkward))).isEqualTo(awkward)
    }

    @Test
    fun anEmptyBarIsAValidConfiguration() {
        assertThat(AuxiliaryKey.decodeBar(AuxiliaryKey.encodeBar(emptyList()))).isEmpty()
    }

    @Test
    fun unreadableStoredLayoutIsReportedRatherThanThrown() {
        assertThat(AuxiliaryKey.decodeBar("not json at all")).isNull()
        assertThat(AuxiliaryKey.decodeBar("")).isNull()
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneStillSends() {
        val emoji = AuxiliaryKey("🔥", codePoint = 0x1F525)

        assertThat(emoji.toEvents()).containsExactly(TerminalInputEvent.Text("🔥"))
        assertThat(AuxiliaryKey.decodeBar(AuxiliaryKey.encodeBar(listOf(emoji)))).containsExactly(emoji)
    }

    /** The reason a button sends a list at all: a tmux prefix, then the command after it. */
    @Test
    fun aSequenceSendsItsStepsInOrder() {
        val window = AuxiliaryKey(
            label = "win",
            steps = listOf(
                KeyStep.Press(codePoint = 's'.code, modifiers = Modifiers.Ctrl),
                KeyStep.Press(codePoint = 'w'.code),
            ),
        )

        assertThat(window.toEvents())
            .containsExactly(
                TerminalInputEvent.Chord('s'.code, Modifiers.Ctrl),
                TerminalInputEvent.Text("w"),
            )
            .inOrder()
    }

    @Test
    fun literalTextIsSentAsTyped() {
        val run = AuxiliaryKey(
            label = "status",
            steps = listOf(KeyStep.Literal("git status"), KeyStep.Press(key = TerminalKey.Enter)),
        )

        assertThat(run.toEvents())
            .containsExactly(
                TerminalInputEvent.Text("git status"),
                TerminalInputEvent.Key(TerminalKey.Enter, Modifiers.None),
            )
            .inOrder()
    }

    @Test
    fun aSequenceSurvivesARoundTrip() {
        val bar = listOf(
            AuxiliaryKey(
                label = "win",
                steps = listOf(
                    KeyStep.Press(codePoint = 's'.code, modifiers = Modifiers.Ctrl),
                    KeyStep.Literal("w"),
                ),
            ),
        )

        assertThat(AuxiliaryKey.decodeBar(AuxiliaryKey.encodeBar(bar))).isEqualTo(bar)
    }

    /**
     * A bar written by the version before steps existed still reads.
     *
     * This is the one regression that costs something real: an unreadable bar is discarded whole
     * and the caller falls back to the shipped defaults, so a format that could not read the old
     * one would silently replace a configured bar with a stock one on first launch.
     */
    @Test
    fun aBarStoredBeforeStepsExistedStillLoads() {
        val stored = """[
            {"label":"Esc","key":"Escape","codePoint":0,"modifiers":0},
            {"label":"C-s","key":null,"codePoint":115,"modifiers":4},
            {"label":"|","codePoint":124,"modifiers":0}
        ]"""

        assertThat(AuxiliaryKey.decodeBar(stored))
            .containsExactly(
                AuxiliaryKey("Esc", key = TerminalKey.Escape),
                AuxiliaryKey("C-s", codePoint = 's'.code, modifiers = Modifiers.Ctrl),
                AuxiliaryKey("|", codePoint = '|'.code),
            )
            .inOrder()
    }
}
