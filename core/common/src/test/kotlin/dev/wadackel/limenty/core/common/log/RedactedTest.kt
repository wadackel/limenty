package dev.wadackel.limenty.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactedTest {
    @Test
    fun `redacted value never exposes its content`() {
        val rendered = LogFormatter.formatValue("hunter2".redacted())
        assertThat(rendered).doesNotContain("hunter2")
        assertThat(rendered).isEqualTo("<redacted:7>")
    }

    @Test
    fun `null secret is distinguishable from an empty one`() {
        assertThat(LogFormatter.formatValue(null.redacted())).isEqualTo("<null>")
        assertThat(LogFormatter.formatValue("".redacted())).isEqualTo("<redacted:0>")
    }
}
