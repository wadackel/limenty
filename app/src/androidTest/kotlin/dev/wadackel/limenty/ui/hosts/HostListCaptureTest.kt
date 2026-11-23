package dev.wadackel.limenty.ui.hosts

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.data.HostProfile
import dev.wadackel.limenty.data.LanService
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.ui.AtSize
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the host list so it can be measured against the design. */
class HostListCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private val palette = TerminalPalette.Default

    private val connected = HostProfile(
        id = "a",
        name = "MacBook Air",
        hostname = "a-long-machine-name.tailnet-name.ts.net",
        username = "someone",
    )
    private val idle = HostProfile(
        id = "b",
        name = "Workstation",
        hostname = "workstation.tailnet-name.ts.net",
        username = "someone",
    )

    private val preview = SessionPreview(
        lines = listOf(
            "~/works/limenty",
            "drwxr-xr-x   8 someone  256 Aug 19 app/",
            "drwxr-xr-x  14 someone  448 Aug 19 core/",
            "-rw-r--r--   1 someone 1284 Aug 19 build.gradle.kts",
            "❯ ./gradlew :app:assembleDebug",
            "BUILD SUCCESSFUL in 42s",
            "❯ ",
        ),
        meta = "tmux · 14m",
    )

    private fun capture(name: String, widthDp: Int, heightDp: Int, select: String? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            LimentyTheme {
                AtSize(widthDp.dp, heightDp.dp) {
                    HostListScreen(
                        hosts = listOf(connected, idle),
                        statusOf = { if (it.id == "a") HostStatus.Connected else HostStatus.Disconnected },
                        previewOf = { host -> if (host.id == "a") preview else null },
                        palette = palette,
                        mono = TerminalTypefaces(context).family(TerminalFont.Default),
                        found = listOf(LanService("Studio iMac", "192.168.1.24", 22)),
                        absent = { false },
                        onConnect = {},
                        onAddFound = {},
                        onEdit = {},
                        onDelete = {},
                        onDisconnect = {},
                        onAdd = {},
                        onOpenSettings = {},
                        selectedHostId = selected,
                        onSelectHost = { selected = it },
                    )
                }
            }
        }
        select?.let { rule.onNodeWithText(it).performClick() }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "captures",
        ).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theCoverDisplayStacksTheHostsAsCards() = capture("hostlist-cover", 460, 751)

    @Test
    fun theInnerDisplayShowsTheConnectedHostBesideTheList() = capture("hostlist-main", 933, 704)

    @Test
    fun theInnerDisplayShowsAHostWithoutASessionWhenItIsChosen() = capture("hostlist-main-idle", 933, 704, select = "Workstation")
}
