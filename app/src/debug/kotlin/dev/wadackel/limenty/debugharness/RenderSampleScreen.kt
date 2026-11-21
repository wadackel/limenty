package dev.wadackel.limenty.debugharness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wadackel.limenty.core.session.SessionId
import dev.wadackel.limenty.core.session.TerminalSession
import dev.wadackel.limenty.core.terminal.TerminalFont
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalTypography
import dev.wadackel.limenty.terminal.TerminalTypefaces
import dev.wadackel.limenty.terminal.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Draws a fixed sample through the real renderer.
 *
 * Cell alignment across ASCII, CJK and Nerd Font glyphs is the one part of glyph rendering that no
 * unit test can settle: it depends on the device's fonts and rasteriser. Having the
 * sample in the app means the check can be repeated on the Galaxy Fold itself.
 */
@Composable
fun RenderSampleScreen() {
    var font by remember { mutableStateOf(TerminalFont.Default) }
    var palette by remember { mutableStateOf(TerminalPalette.Default) }
    var view by remember { mutableStateOf<TerminalView?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TerminalFont.All.forEach { candidate ->
                FilterChip(
                    selected = candidate.id == font.id,
                    onClick = { font = candidate },
                    label = { Text(candidate.displayName, fontSize = 11.sp) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TerminalPalette.All.take(3).forEach { candidate ->
                FilterChip(
                    selected = candidate.name == palette.name,
                    onClick = { palette = candidate },
                    label = { Text(candidate.name, fontSize = 11.sp) },
                )
            }
        }

        AndroidView(
            factory = { context ->
                val typefaces = TerminalTypefaces(context)
                val session = offlineSession()
                TerminalView(context).apply {
                    this.session = session
                    this.palette = palette
                    setTypefaces(typefaces.regular(font), typefaces.bold(font))
                    refreshTypography(TerminalTypography(fontId = font.id, fontSizeSp = 13f))
                    post {
                        session.emulator.write(SAMPLE)
                        invalidate()
                    }
                    view = this
                }
            },
            update = { terminal ->
                val typefaces = TerminalTypefaces(terminal.context)
                terminal.palette = palette
                terminal.setTypefaces(typefaces.regular(font), typefaces.bold(font))
                terminal.session?.let { session ->
                    synchronized(session.lock) {
                        session.emulator.reset()
                        session.emulator.write(SAMPLE)
                    }
                }
                terminal.invalidate()
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

private fun offlineSession(): TerminalSession = TerminalSession(
    id = SessionId("render-sample"),
    hostId = "sample",
    hostName = "Render Sample",
    transport = OfflineTransport(),
    columns = 80,
    rows = 30,
    parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)

private val e = "\u001B"

/**
 * Deliberately mixes the four things that fight each other on a terminal grid: ASCII, wide CJK,
 * private-use Nerd Font glyphs and box drawing. Column markers make a misalignment visible rather
 * than merely ugly.
 */
private val SAMPLE = buildString {
    append("0123456789".repeat(8)).append("\r\n")
    append("ascii    | The quick brown fox jumps over the lazy dog\r\n")
    append("cjk      | 日本語のファイル名とターミナル表示\r\n")
    append("mixed    | src/日本語/main.kt -> ok\r\n")
    append("nfsymbol | \uE0B0 \uE0A0 \uF07C \uF15B \uF1D3 \uE7A8 \uE718\r\n")
    append("boxes    | \u250C\u2500\u252C\u2500\u2510 \u2502 \u2514\u2500\u2534\u2500\u2518 \u2588\u2593\u2592\u2591\r\n")
    append("combining| \u304B\u3099 \u306F\u309A e\u0301\r\n")
    append("\r\n")
    append("$e[1mbold$e[0m $e[3mitalic$e[0m $e[4munderline$e[0m $e[9mstrike$e[0m $e[2mdim$e[0m $e[7mreverse$e[0m $e[5mblink$e[0m\r\n")
    append("\r\n")
    for (index in 0 until 8) append("$e[4${index}m  $e[0m")
    append("\r\n")
    for (index in 0 until 8) append("$e[10${index}m  $e[0m")
    append("\r\n")
    for (step in 0 until 32) {
        val value = step * 8
        append("$e[48;2;$value;${255 - value};128m ")
    }
    append("$e[0m\r\n\r\n")
    append("$e[38;5;39m\uE0B0$e[0m$e[48;5;39m$e[38;5;235m ~/dev/limenty $e[0m")
    append("$e[38;5;39m$e[48;5;238m\uE0B0$e[0m$e[48;5;238m$e[38;5;250m \uE0A0 main $e[0m")
    append("$e[38;5;238m\uE0B0$e[0m\r\n")
    // A tmux status-right and a lualine mode segment: rounded separators have to meet their
    // segment edge to edge, which a glyph from the symbols face never did.
    append("$e[38;2;146;155;229m\uE0B6$e[48;2;146;155;229m$e[38;2;42;44;63m\uF017 ")
    append("$e[48;2;42;44;63m$e[38;2;84;92;140m 2026/09/19 08:36 $e[0m\r\n")
    append("$e[38;2;115;193;169m\uE0B6$e[48;2;115;193;169m$e[38;2;34;36;51m NORMAL $e[0m")
    append("$e[38;2;115;193;169m\uE0B4$e[0m \uE0B1 \uE0B3 \uE0B5 \uE0B7 \uE0B2\r\n")
}
