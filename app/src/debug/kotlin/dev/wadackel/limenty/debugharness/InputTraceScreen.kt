package dev.wadackel.limenty.debugharness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wadackel.limenty.core.common.log.LogFormatter
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.input.EditorConfiguration
import dev.wadackel.limenty.input.TerminalInputView
import dev.wadackel.limenty.log.AppLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TracedTags = setOf(LogTags.IME, LogTags.KEY, LogTags.INPUT)

@Composable
fun InputTraceScreen() {
    val scope = rememberCoroutineScope()
    val trace = remember { InputTraceState() }
    var composingText by remember { mutableStateOf("") }
    var composingActive by remember { mutableStateOf(false) }
    var composingCursor by remember { mutableIntStateOf(0) }
    var totalBytes by remember { mutableIntStateOf(0) }
    var leakedBytes by remember { mutableIntStateOf(0) }
    var hex by remember { mutableStateOf("") }
    var decoded by remember { mutableStateOf("") }
    var configuration by remember { mutableStateOf(EditorConfiguration.Default) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    var dumpPath by remember { mutableStateOf("") }

    fun refresh() {
        totalBytes = trace.backend.size()
        leakedBytes = trace.bytesWrittenWhileComposing
        hex = trace.hexDump()
        decoded = trace.decodedText()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Input Trace", color = Color(0xFFE6EDF3), fontSize = 18.sp)

        VerdictBanner(leakedBytes = leakedBytes, composingActive = composingActive)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorConfiguration.All.forEach { candidate ->
                ConfigChip(
                    label = candidate.name,
                    selected = candidate == configuration,
                    onClick = {
                        configuration = candidate
                        inputView?.editorConfiguration = candidate
                    },
                )
            }
        }

        // The input surface: tapping it focuses and raises the IME, exactly as the terminal will.
        AndroidView(
            factory = { context ->
                TerminalInputView(context).apply {
                    editorConfiguration = configuration
                    onComposingChanged = { state ->
                        composingText = state.text
                        composingActive = state.active
                        composingCursor = state.cursor
                        trace.composing = state
                    }
                    onInputEvents = { events ->
                        val active = buffer.composing.active
                        events.forEach { event ->
                            trace.onBytes(PipelineHolder.pipeline.encode(event), active)
                        }
                        refresh()
                    }
                    setOnClickListener { showSoftKeyboard() }
                    inputView = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .border(1.dp, Color(0xFF30363D)),
        )

        PreeditRow(text = composingText, cursor = composingCursor, active = composingActive)

        Text(
            "bytes total=$totalBytes  while-composing=$leakedBytes",
            color = Color(0xFF8B949E),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Text(
            "hex: $hex",
            color = Color(0xFF7EE787),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Text(
            "utf8: $decoded",
            color = Color(0xFF79C0FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                trace.clear()
                refresh()
            }) { Text("Clear bytes") }
            Button(onClick = { inputView?.showSoftKeyboard() }) { Text("Show IME") }
            Button(onClick = { inputView?.hideSoftKeyboard() }) { Text("Hide IME") }
            Button(onClick = { scope.launch { dumpPath = AppLogging.dumpToDisk("manual") ?: "dump failed" } }) {
                Text("Dump logs")
            }
            // Proves the crash path actually persists the ring buffer; without a way to trigger
            // it, the handler is untested until the first real crash loses the evidence.
            Button(onClick = { throw IllegalStateException("debug harness forced crash") }) {
                Text("Crash")
            }
        }

        if (dumpPath.isNotEmpty()) {
            Text(dumpPath, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }

        LogTrace(modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun VerdictBanner(leakedBytes: Int, composingActive: Boolean) {
    val passing = leakedBytes == 0
    Text(
        text = if (passing) {
            "PREEDIT CLEAN — 0 bytes sent while composing" +
                if (composingActive) " (composing now)" else ""
        } else {
            "PREEDIT LEAK — $leakedBytes byte(s) escaped during composition"
        },
        color = if (passing) Color(0xFF0B0F14) else Color(0xFFFFFFFF),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (passing) Color(0xFF3FB950) else Color(0xFFDA3633))
            .padding(8.dp),
        fontSize = 13.sp,
    )
}

@Composable
private fun ConfigChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color(0xFF0B0F14) else Color(0xFF8B949E),
        modifier = Modifier
            .background(if (selected) Color(0xFF58A6FF) else Color(0xFF161B22))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        fontSize = 12.sp,
    )
}

@Composable
private fun PreeditRow(text: String, cursor: Int, active: Boolean) {
    val rendered = if (!active || text.isEmpty()) {
        "(no composition)"
    } else {
        val safeCursor = cursor.coerceIn(0, text.length)
        text.substring(0, safeCursor) + "│" + text.substring(safeCursor)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("preedit ", color = Color(0xFF8B949E), fontSize = 12.sp)
        Text(
            rendered,
            color = if (active) Color(0xFFFFA657) else Color(0xFF484F58),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun LogTrace(modifier: Modifier = Modifier) {
    var records by remember { mutableStateOf(emptyList<String>()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            records = AppLogging.recent.snapshot()
                .filter { it.tag in TracedTags }
                .takeLast(300)
                .map { "${it.tag}: ${LogFormatter.formatMessage(it)}" }
            if (records.isNotEmpty()) listState.scrollToItem(records.lastIndex)
            delay(250)
        }
    }

    LazyColumn(modifier = modifier, state = listState) {
        items(records) { line ->
            Text(line, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}
