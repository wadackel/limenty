package dev.wadackel.limenty.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the settings screen so its appearance can be measured against the design. */
class SettingsCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, widthDp: Int, section: SettingsSection? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(widthDp.dp)) {
                    SettingsScreen(
                        preferences = TerminalPreferences(),
                        typefaces = TerminalTypefaces(context),
                        section = section,
                        onSelectSection = {},
                        onChange = {},
                        onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "captures",
        ).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theIndexIsCapturedAtTheCoverWidth() = capture("settings-cover", 460)

    @Test
    fun appearanceIsCapturedAtTheCoverWidth() = capture("settings-cover-appearance", 460, SettingsSection.Appearance)

    @Test
    fun gesturesAreCapturedAtTheCoverWidth() = capture("settings-cover-gestures", 460, SettingsSection.Gestures)

    @Test
    fun theTwoPanesAreCapturedAtTheInnerWidth() = capture("settings-main", 930)

    @Test
    fun gesturesAreCapturedInThePaneAtTheInnerWidth() = capture("settings-main-gestures", 930, SettingsSection.Gestures)
}
