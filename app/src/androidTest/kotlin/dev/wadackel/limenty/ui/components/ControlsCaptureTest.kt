package dev.wadackel.limenty.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.ui.theme.LimentyColors
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the sliders and switches on their own, since the settings screen scrolls past them. */
class ControlsCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun controls() {
        rule.setContent {
            LimentyTheme {
                Column(
                    modifier = Modifier
                        .width(460.dp)
                        .background(LimentyColors.Bg)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    LimentySlider("Font size", 13f, 6f..32f, {}, unit = "sp")
                    LimentySlider("Line height", 1.15f, 0.9f..2.0f, {}, format = { "%.2f".format(it) })
                    SwitchRow("Keep screen awake", "Only while the terminal is visible.", true, {})
                    SwitchRow("Share with the keyboard", "Lets the keyboard keep copies.", false, {})
                }
            }
        }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "captures",
        ).apply { mkdirs() }
        File(dir, "controls.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
