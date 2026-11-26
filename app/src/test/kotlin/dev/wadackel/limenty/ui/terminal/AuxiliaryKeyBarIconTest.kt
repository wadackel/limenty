package dev.wadackel.limenty.ui.terminal

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.input.Modifiers
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.data.KeyBarSlot
import dev.wadackel.limenty.data.KeyGroup
import dev.wadackel.limenty.data.KeyIcon
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Keys and groups with icons, on the bar at the cover display's width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w475dp-h751dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuxiliaryKeyBarIconTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun tmuxKey(name: String, icon: KeyIcon?, c: Char) = AuxiliaryKey(name, listOf(KeyStep.Press(null, 'b'.code, Modifiers.Ctrl), KeyStep.Press(null, c.code, Modifiers.None)), icon)

    /** A tmux set: Esc, ⌃c, and nine keys with names of their own, the kind that run long as text. */
    private fun tmux(withIcons: Boolean): List<KeyBarSlot> = listOf(
        AuxiliaryKey("Esc", key = TerminalKey.Escape),
        AuxiliaryKey("⌃c", codePoint = 'c'.code, modifiers = Modifiers.Ctrl),
    ) + listOf(
        Triple("new win", KeyIcon.APP_WINDOW, 'c'),
        Triple("split v", KeyIcon.COLUMNS_2, '%'),
        Triple("split h", KeyIcon.ROWS_2, '"'),
        Triple("zoom", KeyIcon.MAXIMIZE_2, 'z'),
        Triple("prev", KeyIcon.CHEVRONS_LEFT, 'p'),
        Triple("next", KeyIcon.CHEVRONS_RIGHT, 'n'),
        Triple("kill", KeyIcon.X, 'x'),
        Triple("copy", KeyIcon.COPY, '['),
        Triple("detach", KeyIcon.LOG_OUT, 'd'),
    ).map { (name, icon, c) -> tmuxKey(name, icon.takeIf { withIcons }, c) }

    private var shown by mutableStateOf<List<KeyBarSlot>?>(null)

    private fun show(scrolling: List<KeyBarSlot>) {
        if (shown == null) {
            rule.setContent {
                shown?.let { AuxiliaryKeyBar(keys = it, pinnedKeys = AuxiliaryKey.DefaultPinned, onKeyPressed = {}, interactive = false) }
            }
        }
        shown = scrolling
        rule.waitForIdle()
    }

    private fun widthOf(description: String): Dp = with(rule.density) {
        rule.onNode(hasContentDescription(description)).fetchSemanticsNode().boundsInRoot.width.toDp()
    }

    /** Keys whose face is not cut by the end of the scrolling run. */
    private fun keysInFull(): Int {
        val pinnedEdge = rule.onNode(hasContentDescription("Left")).fetchSemanticsNode().boundsInRoot.left
        val keys = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes()
            .filter { it.positionInRoot.x < pinnedEdge }
        // Bounds in the root are cut at the edge of the run, which is how far the run is seen.
        val runEnd = keys.maxOf { it.boundsInRoot.right }
        val halfGap = with(rule.density) { (AuxiliaryKeyBarMetrics.keyGap / 2).toPx() }
        return keys.count { it.positionInRoot.x + it.size.width - halfGap <= runEnd }
    }

    @Test
    fun aKeyWithAnIconIsASquareThatSaysItsName() {
        show(tmux(withIcons = true))

        // The slot is the face and the gap split either side of it, as for a one-character key.
        assertEquals((AuxiliaryKeyBarMetrics.keyHeight + AuxiliaryKeyBarMetrics.keyGap).value, widthOf("new win").value, 0.5f)
        rule.onNode(hasText("new win")).assertDoesNotExist()
    }

    @Test
    fun theBarFitsMoreKeysBeforeItScrollsWithIconsThanWithLabels() {
        show(tmux(withIcons = false))
        val asText = keysInFull()
        show(tmux(withIcons = true))
        val asIcons = keysInFull()

        assertEquals(3, asText)
        assertEquals(5, asIcons)
    }

    @Test
    fun aGroupWithAnIconShowsItAndANamelessGroupShowsItsKeysIcons() {
        val newWin = tmuxKey("New window", KeyIcon.APP_WINDOW, 'c')
        show(
            listOf(
                KeyGroup("", listOf(newWin), KeyIcon.ZAP),
                KeyGroup("", listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape), newWin)),
            ),
        )

        assertEquals((AuxiliaryKeyBarMetrics.keyHeight + AuxiliaryKeyBarMetrics.keyGap).value, widthOf("Bolt, 1 key").value, 0.5f)
        // The nameless group's face is its keys side by side, the icon in place of the label.
        rule.onNode(hasText("Esc")).assertExists()
        rule.onNode(hasText("New window")).assertDoesNotExist()
        rule.onNode(hasContentDescription("Escape New window, 2 keys")).assertExists()
    }

    @Test
    fun anIconGrowsWithTheFontScaleUntilItWouldNoLongerFitTheFace() {
        val sizes = mutableMapOf<Float, Dp>()
        rule.setContent {
            listOf(1f, 1.3f, 2f).forEach { scale ->
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    sizes[scale] = AuxiliaryKeyBarMetrics.iconSize()
                }
            }
        }
        rule.waitForIdle()

        assertEquals(15f, sizes.getValue(1f).value, 0.01f)
        assertEquals(19.5f, sizes.getValue(1.3f).value, 0.01f)
        assertEquals(AuxiliaryKeyBarMetrics.iconMaxSize, sizes.getValue(2f))
    }
}
