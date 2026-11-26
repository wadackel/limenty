package dev.wadackel.limenty.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.wadackel.limenty.core.terminal.TerminalFont
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for org.json, which the plain JVM test classpath only has as stubs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OpenSourceNoticesTest {
    private val definitions = """
        {
          "libraries": [
            {"uniqueId": "androidx.lifecycle:lifecycle-runtime", "artifactVersion": "2.9.4", "name": "Lifecycle Runtime", "licenses": ["Apache-2.0"]},
            {"uniqueId": "androidx.lifecycle:lifecycle-common", "artifactVersion": "2.9.4", "name": "Lifecycle-Common", "licenses": ["Apache-2.0"]},
            {"uniqueId": "org.bouncycastle:bcprov-jdk18on", "artifactVersion": "1.83", "name": "Bouncy Castle Provider", "licenses": ["bc"]},
            {"uniqueId": "androidx.compose.ui:ui", "artifactVersion": "1.10.0", "name": "Compose UI", "licenses": ["Apache-2.0"]},
            {"uniqueId": "com.example:widget", "artifactVersion": "1.0", "name": "Widget", "licenses": ["MIT"], "targets": ["jvm"]}
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "spdxId": "Apache-2.0", "content": "apache text"},
            "MIT": {"name": "MIT License", "spdxId": "MIT", "content": "mit text"},
            "bc": {"name": "Bouncy Castle Licence", "content": "bc text"}
          }
        }
    """.trimIndent()

    @Test
    fun `the artifacts of one group are listed as one entry that still names each of them`() {
        val lifecycle = parseLibraryDefinitions(definitions).single { it.detail == "androidx.lifecycle" }

        assertThat(lifecycle.name).isEqualTo("AndroidX Lifecycle")
        assertThat(lifecycle.parts).containsExactly("lifecycle-runtime 2.9.4", "lifecycle-common 2.9.4")
        assertThat(lifecycle.licenses.map { it.label }).containsExactly("Apache-2.0")
    }

    @Test
    fun `groups are named by rule where no name is kept for them`() {
        val names = parseLibraryDefinitions(definitions).associate { it.detail to it.name }

        assertThat(names["androidx.compose.ui"]).isEqualTo("Compose UI")
        assertThat(names["org.bouncycastle"]).isEqualTo("Bouncy Castle")
        assertThat(names["com.example"]).isEqualTo("Widget")
    }

    @Test
    fun `a licence without an SPDX id is labelled by its name`() {
        val bouncyCastle = parseLibraryDefinitions(definitions).single { it.detail == "org.bouncycastle" }

        assertThat(bouncyCastle.licenses.single().label).isEqualTo("Bouncy Castle Licence")
        assertThat(bouncyCastle.licenses.single().text).isEqualTo("bc text")
    }

    @Test
    fun `fonts come first, then the icons, and libraries follow by name`() {
        val notices = openSourceNotices(definitions) { "font licence" }

        assertThat(notices.take(BundledFonts.size).map { it.name }).isEqualTo(BundledFonts.map { it.name })
        assertThat(notices[BundledFonts.size].name).isEqualTo("Lucide")
        assertThat(notices[BundledFonts.size].detail).isEqualTo("Icons")
        assertThat(notices.drop(BundledFonts.size + 1).map { it.name })
            .containsExactly("AndroidX Lifecycle", "Bouncy Castle", "Compose UI", "Widget")
            .inOrder()
    }

    @Test
    fun `a licence wraps to the screen while its headings and list items keep their lines`() {
        val text = """
            PREAMBLE
            The goals of the Open Font License (OFL) are to stimulate worldwide
            development of collaborative font projects.

            1) Neither the Font Software nor any of its individual components,
            in Original or Modified Versions, may be sold by itself.
            2) Original or Modified Versions of the Font Software may be bundled.
        """.trimIndent()

        assertThat(reflowLicense(text)).isEqualTo(
            "PREAMBLE\n" +
                "The goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects.\n" +
                "\n" +
                "1) Neither the Font Software nor any of its individual components, in Original or Modified Versions, may be sold by itself.\n" +
                "2) Original or Modified Versions of the Font Software may be bundled.",
        )
    }

    @Test
    fun `a paragraph written in capitals is joined like any other`() {
        val text = """
            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY IN THE
            SOFTWARE.
        """.trimIndent()

        assertThat(reflowLicense(text)).isEqualTo(
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
                "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY IN THE SOFTWARE.",
        )
    }

    @Test
    fun `every font the terminal offers has its licence on the About screen`() {
        val listed = BundledFonts.map { it.name }

        TerminalFont.All.forEach { font ->
            assertWithMessage(font.displayName).that(listed).contains(font.displayName)
        }
    }

    /**
     * The plugin drops a licence's text without a word when it cannot find one, so a dependency
     * added under a licence nobody supplied would ship as an entry with nothing to read.
     */
    @Test
    fun `every licence the app ships carries its text`() {
        val content = loadAboutContent(ApplicationProvider.getApplicationContext<Context>())

        assertThat(content.notices.size).isGreaterThan(BundledFonts.size)
        assertThat(content.ownLicense.text).contains("MIT License")
        content.notices.forEach { notice ->
            assertWithMessage("${notice.name} has a licence").that(notice.licenses).isNotEmpty()
            notice.licenses.forEach { license ->
                assertWithMessage("${notice.name}: ${license.name}").that(license.text.trim()).isNotEmpty()
            }
        }
    }
}
