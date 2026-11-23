package dev.wadackel.limenty.ui.terminal

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wadackel.limenty.core.input.TerminalKey
import dev.wadackel.limenty.data.AuxiliaryKey
import dev.wadackel.limenty.ui.AtSize
import dev.wadackel.limenty.ui.theme.LimentyColors
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the bar at both display widths and writes the result out, so its appearance can be
 * inspected without driving the app to a connected terminal.
 */
class AuxiliaryKeyBarCaptureTest {
    @get:Rule
    val rule = createComposeRule()

    private fun capture(name: String, widthDp: Int) {
        rule.setContent {
            AtSize(widthDp.dp) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1B26)).testTag(BAR)) {
                    AuxiliaryKeyBar(
                        keys = AuxiliaryKey.DefaultScrolling,
                        pinnedKeys = AuxiliaryKey.DefaultPinned,
                        onKeyPressed = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        // The bar rather than the root, which AtSize makes as tall as the display.
        val image = rule.onNodeWithTag(BAR).captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "captures",
        ).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun coverWidth() = capture("aux-bar-cover", 460)

    @Test
    fun mainWidth() = capture("aux-bar-main", 930)

    /**
     * The fade has to cover whatever passes under it, all of it.
     *
     * Sizing it to a key rather than to the row left the passing key's border at full strength above
     * and below the fade, so the key read as sliced through — the very thing the fade exists to
     * avoid. The font scale is raised here because that is what pulled the two apart: the scrolling
     * keys took their height from the label's line box, so a larger scale grew them past both the
     * fade and the pinned keys beside them.
     */
    @Test
    fun theFadeCoversEveryKeyThatPassesUnderIt() {
        val map = renderAtFontScale(MAX_FONT_SCALE).toPixelMap()
        val pxPerDp = map.width.toFloat() / COVER_WIDTH_DP
        fun px(dp: Dp) = (dp.value * pxPerDp).toInt()

        val metrics = AuxiliaryKeyBarMetrics
        val pinnedRun = defaultPinnedRun()
        val scrollRight = map.width - px(metrics.edgePadding) - px(pinnedRun) - px(metrics.dividerBlock)

        fun columnIsBarOnly(x: Int) = (0 until map.height).all { y -> map[x, y] == LimentyColors.Surface }

        // Anchors the assertion below: if nothing were passing under the fade, that assertion would
        // hold for the wrong reason. Searched across the whole fade rather than read off one column,
        // which a gap between keys can land on whenever a key or a gap changes width.
        val fadeStart = scrollRight - px(metrics.fadeWidth)
        assert((fadeStart + 1 until scrollRight - 2).any { x -> !columnIsBarOnly(x) }) {
            "no key is under the fade at ${COVER_WIDTH_DP}dp, so this proves nothing"
        }
        assert(columnIsBarOnly(scrollRight - 2)) {
            "a key shows through the opaque end of the fade"
        }
    }

    /** Both kinds of key are one height, so a row built from them has a single edge to cover. */
    @Test
    fun scrollingAndPinnedKeysAreTheSameHeight() {
        val map = renderAtFontScale(MAX_FONT_SCALE).toPixelMap()
        val pxPerDp = map.width.toFloat() / COVER_WIDTH_DP
        fun px(dp: Dp) = (dp.value * pxPerDp).toInt()

        val metrics = AuxiliaryKeyBarMetrics
        val pinnedRun = defaultPinnedRun()

        fun heightAt(x: Int): Int {
            val rows = (0 until map.height).filter { y -> map[x, y] != LimentyColors.Surface }
            return if (rows.isEmpty()) 0 else rows.last() - rows.first() + 1
        }

        // Past the corner radius on each, so the rounded corners do not shorten the reading.
        val scrolling = heightAt(px(metrics.edgePadding + metrics.keyRadius + 4.dp))
        val firstPinned = map.width - px(metrics.edgePadding) - px(pinnedRun)
        val pinnedHeight = heightAt(firstPinned + px(metrics.keyHeight / 2))

        assert(kotlin.math.abs(scrolling - pinnedHeight) <= 1) {
            "scrolling key is ${scrolling}px tall, pinned key is ${pinnedHeight}px"
        }
        assert(kotlin.math.abs(scrolling - px(metrics.keyHeight)) <= 1) {
            "keys are ${scrolling}px tall, expected ${px(metrics.keyHeight)}px"
        }
    }

    /**
     * A key whose label is one character is drawn square.
     *
     * `|` and `~` in a face shaped for `PgDn` are mostly empty, and four of them together left the
     * run looking gappy. Rendered alone so the whole of the bar's ink is the one key being measured.
     */
    @Test
    fun aOneCharacterKeyIsSquare() {
        val ink = inkOf(render(listOf(AuxiliaryKey("|", codePoint = '|'.code))))
        assert(kotlin.math.abs(ink.width - ink.height) <= 1) {
            "one-character key is ${ink.width}x${ink.height}px"
        }
    }

    /** A longer label still gets the width it needs, so squaring is not a cap on everything. */
    @Test
    fun aLongerLabelIsWiderThanItIsTall() {
        val ink = inkOf(render(listOf(AuxiliaryKey("PgDn", key = TerminalKey.PageDown))))
        assert(ink.width > ink.height) { "PgDn is ${ink.width}x${ink.height}px, expected wider" }
    }

    /**
     * The bar costs the terminal its own height every session, so it is the touch target and no
     * more: the key is drawn smaller inside it rather than the band growing to fit a bigger key.
     */
    @Test
    fun theBarIsExactlyTheTouchTargetItNeeds() {
        val image = render(listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape)))
        val pxPerDp = image.width.toFloat() / COVER_WIDTH_DP
        val expected = (AuxiliaryKeyBarMetrics.rowHeight.value * pxPerDp).toInt()
        assert(kotlin.math.abs(image.height - expected) <= 1) {
            "bar is ${image.height}px tall, expected ${'$'}expected"
        }
        val face = inkOf(image).height
        val faceExpected = (AuxiliaryKeyBarMetrics.keyHeight.value * pxPerDp).toInt()
        assert(kotlin.math.abs(face - faceExpected) <= 1) {
            "key face is ${'$'}{face}px tall, expected ${'$'}faceExpected"
        }
    }

    /**
     * A pinned key is drawn from its label like any other, one character square and the rest wide.
     *
     * The two assertions are the whole of what replacing the fixed square changed, so they are
     * measured off the pinned face itself. [inkOf] cannot do it: the divider beside the run is
     * drawn whenever anything is pinned and lands in the same bounding box, which would add its
     * width to a one-character key and make a wide one look wide for the wrong reason.
     */
    @Test
    fun `a pinned key with a one character label is square`() {
        val face = pinnedFaceOf(render(keys = emptyList(), pinned = listOf(AuxiliaryKey("←", key = TerminalKey.ArrowLeft))))
        assert(kotlin.math.abs(face.width - face.height) <= 1) {
            "pinned face is ${face.width}x${face.height}px, expected square"
        }
    }

    @Test
    fun `a pinned key with a longer label is wider than it is tall`() {
        val face = pinnedFaceOf(render(keys = emptyList(), pinned = listOf(AuxiliaryKey("PgDn", key = TerminalKey.PageDown))))
        assert(face.width > face.height + 4) {
            "pinned face is ${face.width}x${face.height}px, expected wider than tall"
        }
    }

    /** Rendered with no keys, so everything the bar draws is the marks. */
    @Test
    fun eachSetIsMarkedByADotAsWideAsItIsTall() {
        val map = render(keys = emptyList(), switcher = twoSets()).toPixelMap()
        val pxPerDp = map.width.toFloat() / COVER_WIDTH_DP
        fun inked(x: Int) = (0 until map.height).any { y -> map[x, y] != LimentyColors.Surface }

        val rows = (0 until map.height).filter { y -> (0 until map.width).any { x -> map[x, y] != LimentyColors.Surface } }
        check(rows.isNotEmpty()) { "the bar drew no marks" }
        val height = rows.last() - rows.first() + 1
        val firstColumn = (0 until map.width).first { inked(it) }
        val width = (firstColumn until map.width).takeWhile { inked(it) }.size

        // Rounded, as layout rounds a size to pixels; the one pixel either way is the dot's soft edge.
        val expected = kotlin.math.round(AuxiliaryKeyBarMetrics.setMarkSize.value * pxPerDp).toInt()
        assert(kotlin.math.abs(height - expected) <= 1) { "a mark is ${height}px tall, expected ${expected}px" }
        assert(kotlin.math.abs(width - height) <= 1) { "a mark is ${width}x${height}px, expected a dot" }
    }

    /** Read down the rows: bar only, the marks, bar only again, then the keys. */
    @Test
    fun theMarksSitMidwayBetweenTheTopEdgeAndTheKeys() {
        val map = render(AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned, switcher = twoSets()).toPixelMap()
        val pxPerDp = map.width.toFloat() / COVER_WIDTH_DP

        val rows = (0 until map.height).filter { y -> (0 until map.width).any { x -> map[x, y] != LimentyColors.Surface } }
        val marksEnd = rows.zipWithNext().first { (a, b) -> b != a + 1 }.first
        val keysStart = rows.first { it > marksEnd }
        val above = rows.first()
        val below = keysStart - marksEnd - 1

        // A dp rather than a pixel: the band, the padding and the dot are each rounded to whole
        // pixels, and the dot's soft edge adds one more, so exact centring is not reachable.
        assert(kotlin.math.abs(above - below) <= pxPerDp) { "${above}px above the marks, ${below}px below" }
    }

    @Test
    fun withMoreThanOneSetTheBarGrowsByTheMarkBand() {
        val image = render(listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape)), switcher = twoSets())
        val pxPerDp = image.width.toFloat() / COVER_WIDTH_DP
        val metrics = AuxiliaryKeyBarMetrics
        val expected = ((metrics.rowHeight + metrics.setMarkBand).value * pxPerDp).toInt()
        assert(kotlin.math.abs(image.height - expected) <= 1) { "bar is ${image.height}px tall, expected ${expected}px" }
    }

    /** One set has no marks, so it keeps the bar it had before there were sets. */
    @Test
    fun withOneSetTheBarKeepsItsHeight() {
        val image = render(
            listOf(AuxiliaryKey("Esc", key = TerminalKey.Escape)),
            switcher = KeySetSwitcher(index = 0, count = 1, name = "Default", onSwitch = {}),
        )
        val pxPerDp = image.width.toFloat() / COVER_WIDTH_DP
        val expected = (AuxiliaryKeyBarMetrics.rowHeight.value * pxPerDp).toInt()
        assert(kotlin.math.abs(image.height - expected) <= 1) { "bar is ${image.height}px tall, expected ${expected}px" }
    }

    private fun twoSets() = KeySetSwitcher(index = 0, count = 2, name = "Vim", onSwitch = {})

    /** The bounding box of the pinned key's own face, which is the only thing painted that colour. */
    private fun pinnedFaceOf(image: ImageBitmap): Ink {
        val map = image.toPixelMap()
        val marked = (0 until map.width).flatMap { x ->
            (0 until map.height).mapNotNull { y -> if (map[x, y] == LimentyColors.Surface2) x to y else null }
        }
        check(marked.isNotEmpty()) { "no pinned key face was drawn" }
        return Ink(
            width = marked.maxOf { it.first } - marked.minOf { it.first } + 1,
            height = marked.maxOf { it.second } - marked.minOf { it.second } + 1,
        )
    }

    /** The default pinned keys are four one-character labels, so each is a [keyHeight] square. */
    private fun defaultPinnedRun(): Dp {
        val metrics = AuxiliaryKeyBarMetrics
        val keys = AuxiliaryKey.DefaultPinned
        keys.forEach { check(it.label.length == 1) { "`${it.label}` is not one character, so this run is not squares" } }
        return metrics.pinnedRun(keys.map { metrics.keyHeight })
    }

    private data class Ink(val width: Int, val height: Int)

    /** The bounding box of everything the bar drew that is not its own background. */
    private fun inkOf(image: ImageBitmap): Ink {
        val map = image.toPixelMap()
        val marked = (0 until map.width).flatMap { x ->
            (0 until map.height).mapNotNull { y -> if (map[x, y] != LimentyColors.Surface) x to y else null }
        }
        check(marked.isNotEmpty()) { "the bar drew nothing" }
        return Ink(
            width = marked.maxOf { it.first } - marked.minOf { it.first } + 1,
            height = marked.maxOf { it.second } - marked.minOf { it.second } + 1,
        )
    }

    private fun renderAtFontScale(fontScale: Float): ImageBitmap = render(AuxiliaryKey.DefaultScrolling, AuxiliaryKey.DefaultPinned, fontScale)

    private fun render(
        keys: List<AuxiliaryKey>,
        pinned: List<AuxiliaryKey> = emptyList(),
        fontScale: Float = 1f,
        switcher: KeySetSwitcher? = null,
    ): ImageBitmap {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
            ) {
                Box(
                    modifier = Modifier
                        .width(COVER_WIDTH_DP.dp)
                        .background(Color(0xFF1A1B26)),
                ) {
                    AuxiliaryKeyBar(keys = keys, pinnedKeys = pinned, onKeyPressed = {}, switcher = switcher)
                }
            }
        }
        rule.waitForIdle()
        return rule.onRoot().captureToImage()
    }
}

private const val COVER_WIDTH_DP = 460

private const val BAR = "bar"

/** The largest the platform's accessibility settings go, where any height drift is widest. */
private const val MAX_FONT_SCALE = 2.0f
