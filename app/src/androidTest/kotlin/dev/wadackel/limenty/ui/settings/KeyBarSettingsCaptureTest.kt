package dev.wadackel.limenty.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeySet
import dev.wadackel.limenty.data.TerminalPreferences
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the key bar settings so it can be measured against the design. */
class KeyBarSettingsCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun coverWidth() {
        rule.setContent {
            LimentyTheme {
                Box(modifier = Modifier.width(460.dp).height(900.dp)) {
                    KeyBarSettingsScreen(
                        preferences = TerminalPreferences(
                            keySets = listOf(
                                KeySet.Default,
                                KeySet("Agent", AuxiliaryKey.DefaultScrolling.take(6), AuxiliaryKey.DefaultPinned.take(2)),
                            ),
                        ),
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
        File(dir, "keybar-cover.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
