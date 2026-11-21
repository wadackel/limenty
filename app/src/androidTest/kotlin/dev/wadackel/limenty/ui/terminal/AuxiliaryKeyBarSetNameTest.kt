package dev.wadackel.limenty.ui.terminal

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.data.AuxiliaryKey
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Where the name of the set just switched to is drawn, laid out the way the terminal screen lays
 * the bar out: a terminal above, then the bar.
 */
class AuxiliaryKeyBarSetNameTest {
    @get:Rule
    val rule = createComposeRule()

    private var index by mutableIntStateOf(0)

    /** The screen before a switch, and again once the slide has finished and the name is showing. */
    private fun captureAroundSwitch(): Pair<ImageBitmap, ImageBitmap> {
        rule.setContent {
            Column(modifier = Modifier.width(460.dp)) {
                Box(modifier = Modifier.fillMaxWidth().height(TERMINAL_HEIGHT.dp).background(TERMINAL))
                AuxiliaryKeyBar(
                    keys = AuxiliaryKey.DefaultScrolling,
                    pinnedKeys = AuxiliaryKey.DefaultPinned,
                    onKeyPressed = {},
                    switcher = KeySetSwitcher(index = index, count = 2, name = "Vim", onSwitch = {}),
                )
            }
        }
        rule.waitForIdle()
        val before = rule.onRoot().captureToImage()
        rule.mainClock.autoAdvance = false
        index = 1
        // Past the slide, well short of the name fading out.
        rule.mainClock.advanceTimeBy(300)
        val after = rule.onRoot().captureToImage()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "captures").apply { mkdirs() }
        File(dir, "aux-bar-set-name.png").outputStream().use { after.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        return before to after
    }

    @Test
    fun theSetNameIsDrawnAboveTheBarRatherThanOverItsKeys() {
        val (before, after) = captureAroundSwitch()
        val was = before.toPixelMap()
        val now = after.toPixelMap()
        val barTop = (TERMINAL_HEIGHT * rule.density.density).toInt()
        // Found by what the switch changed rather than by its colour, which it shares with the keys.
        // Both sets hold the same keys, so the name is what changed; the set marks change too, but
        // each is narrower than the run looked for here.
        val rows = (0 until now.height).filter { y ->
            (0 until now.width).map { x -> now[x, y] != was[x, y] }.windowed(PILL_RUN).any { it.all { changed -> changed } }
        }

        check(rows.isNotEmpty()) { "the set name was not drawn" }
        assert(rows.max() < barTop) { "the name reaches row ${rows.max()}, and the bar starts at row $barTop" }
    }
}

private const val TERMINAL_HEIGHT = 120
private const val PILL_RUN = 20
private val TERMINAL = Color(0xFF1A1B26)
