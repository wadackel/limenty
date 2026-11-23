package dev.wadackel.limenty.ui.hosts

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.ui.AtSize
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the host form so it can be measured against the design. */
class HostFormCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, widthDp: Int, existing: HostProfile?) {
        rule.setContent {
            LimentyTheme {
                AtSize(widthDp.dp, 860.dp) {
                    HostFormScreen(
                        initial = existing,
                        initialPassword = if (existing == null) "" else "hunter2hunter",
                        onSave = { _, _ -> },
                        onCancel = {},
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
    fun filledForm() = capture(
        "hostform-cover",
        460,
        HostProfile(
            id = "a",
            name = "",
            hostname = "a-long-machine-name.tailnet-name.ts.net",
            username = "someone",
        ),
    )
}
