package dev.wadackel.limenty.debugharness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.ui.theme.LimentyTheme
import dev.wadackel.limenty.ui.theme.enableLimentyEdgeToEdge

/**
 * Debug-only harness. It lives in the `debug` source set so it cannot reach a release build, which
 * keeps debug UI from bleeding into production code.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableLimentyEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LimentyTheme { DebugHarness() } }
    }
}

private enum class Harness(val label: String) {
    InputTrace("Input Trace"),
    RenderSample("Render Sample"),
}

@Composable
private fun DebugHarness() {
    var selected by remember { mutableStateOf(Harness.InputTrace) }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Harness.entries.forEach { harness ->
                FilterChip(
                    selected = harness == selected,
                    onClick = { selected = harness },
                    label = { Text(harness.label) },
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            when (selected) {
                Harness.InputTrace -> InputTraceScreen()
                Harness.RenderSample -> RenderSampleScreen()
            }
        }
    }
}
