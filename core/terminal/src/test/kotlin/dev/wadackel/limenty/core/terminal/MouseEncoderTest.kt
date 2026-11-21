package dev.wadackel.limenty.core.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MouseEncoderTest {
    private val e = "\u001B"

    private fun modes(
        tracking: MouseTracking = MouseTracking.Normal,
        encoding: MouseEncoding = MouseEncoding.Sgr,
    ) = TerminalModes(mouse = tracking, mouseEncoding = encoding)

    private fun encoded(
        modes: TerminalModes,
        button: MouseEncoder.Button = MouseEncoder.Button.Left,
        action: MouseEncoder.Action = MouseEncoder.Action.Press,
        column: Int = 0,
        row: Int = 0,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ): String? = MouseEncoder.encode(modes, button, action, column, row, shift, alt, ctrl)
        ?.let { String(it, Charsets.ISO_8859_1) }

    @Test
    fun `nothing is sent while tracking is off`() {
        assertThat(encoded(modes(tracking = MouseTracking.None))).isNull()
    }

    @Test
    fun `an SGR press carries one-based coordinates`() {
        assertThat(encoded(modes(), column = 9, row = 4)).isEqualTo("$e[<0;10;5M")
    }

    @Test
    fun `an SGR release uses the lowercase final`() {
        assertThat(encoded(modes(), action = MouseEncoder.Action.Release, column = 9, row = 4))
            .isEqualTo("$e[<0;10;5m")
    }

    @Test
    fun `the middle and right buttons have their own codes`() {
        assertThat(encoded(modes(), button = MouseEncoder.Button.Middle)).isEqualTo("$e[<1;1;1M")
        assertThat(encoded(modes(), button = MouseEncoder.Button.Right)).isEqualTo("$e[<2;1;1M")
    }

    @Test
    fun `the wheel reports as buttons 64 and 65`() {
        assertThat(encoded(modes(), button = MouseEncoder.Button.WheelUp)).isEqualTo("$e[<64;1;1M")
        assertThat(encoded(modes(), button = MouseEncoder.Button.WheelDown)).isEqualTo("$e[<65;1;1M")
    }

    @Test
    fun `modifiers add their flags`() {
        assertThat(encoded(modes(), shift = true)).isEqualTo("$e[<4;1;1M")
        assertThat(encoded(modes(), alt = true)).isEqualTo("$e[<8;1;1M")
        assertThat(encoded(modes(), ctrl = true)).isEqualTo("$e[<16;1;1M")
        assertThat(encoded(modes(), shift = true, ctrl = true)).isEqualTo("$e[<20;1;1M")
    }

    @Test
    fun `drag motion sets the motion flag`() {
        val modes = modes(tracking = MouseTracking.ButtonEvent)
        assertThat(encoded(modes, action = MouseEncoder.Action.Motion, column = 2, row = 3))
            .isEqualTo("$e[<32;3;4M")
    }

    @Test
    fun `X10 reports presses only`() {
        val modes = modes(tracking = MouseTracking.X10)
        assertThat(encoded(modes, action = MouseEncoder.Action.Press)).isNotNull()
        assertThat(encoded(modes, action = MouseEncoder.Action.Release)).isNull()
        assertThat(encoded(modes, action = MouseEncoder.Action.Motion)).isNull()
    }

    @Test
    fun `normal tracking reports press and release but not motion`() {
        val modes = modes(tracking = MouseTracking.Normal)
        assertThat(encoded(modes, action = MouseEncoder.Action.Release)).isNotNull()
        assertThat(
            encoded(modes, action = MouseEncoder.Action.Motion, button = MouseEncoder.Button.WheelUp),
        ).isNull()
    }

    @Test
    fun `a bare hover is not reported as a drag`() {
        // Regression: hover was sent as Left+Motion, which tmux and nvim read as a drag and turn
        // into a selection the moment the pointer moves.
        val motion = encoded(
            modes(tracking = MouseTracking.ButtonEvent),
            button = MouseEncoder.Button.None,
            action = MouseEncoder.Action.Motion,
        )
        assertThat(motion).isNull()
    }

    @Test
    fun `any-event tracking reports a bare hover as button three`() {
        val motion = encoded(
            modes(tracking = MouseTracking.AnyEvent),
            button = MouseEncoder.Button.None,
            action = MouseEncoder.Action.Motion,
            column = 4,
            row = 2,
        )
        assertThat(motion).isEqualTo("$e[<35;5;3M")
    }

    @Test
    fun `a held button still reports motion under button-event tracking`() {
        val motion = encoded(
            modes(tracking = MouseTracking.ButtonEvent),
            button = MouseEncoder.Button.Left,
            action = MouseEncoder.Action.Motion,
        )
        assertThat(motion).isEqualTo("$e[<32;1;1M")
    }

    @Test
    fun `the legacy encoding offsets every field by 32`() {
        val legacy = modes(encoding = MouseEncoding.Default)
        val bytes = MouseEncoder.encode(legacy, MouseEncoder.Button.Left, MouseEncoder.Action.Press, 0, 0)!!
        assertThat(bytes.toList()).containsExactly(
            0x1B.toByte(),
            '['.code.toByte(),
            'M'.code.toByte(),
            32.toByte(),
            33.toByte(),
            33.toByte(),
        ).inOrder()
    }

    @Test
    fun `the legacy encoding reports every release as button 3`() {
        val legacy = modes(encoding = MouseEncoding.Default)
        val bytes = MouseEncoder.encode(legacy, MouseEncoder.Button.Right, MouseEncoder.Action.Release, 0, 0)!!
        assertThat(bytes[3]).isEqualTo((3 + 32).toByte())
    }

    @Test
    fun `the legacy encoding refuses coordinates it cannot express`() {
        val legacy = modes(encoding = MouseEncoding.Default)
        assertThat(MouseEncoder.encode(legacy, MouseEncoder.Button.Left, MouseEncoder.Action.Press, 300, 0)).isNull()
    }

    @Test
    fun `SGR has no coordinate ceiling`() {
        assertThat(encoded(modes(), column = 499, row = 299)).isEqualTo("$e[<0;500;300M")
    }
}
