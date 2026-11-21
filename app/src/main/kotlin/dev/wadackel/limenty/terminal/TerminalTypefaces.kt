package dev.wadackel.limenty.terminal

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.terminal.TerminalFont
import java.io.File
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily

/**
 * Builds the typeface chain the terminal draws with.
 *
 * One chain per font: the chosen text face first, then the shared Nerd Font symbols face, then
 * the system fallback for CJK. Android resolves each code point through that chain, so a prompt
 * mixing ASCII, Powerline glyphs and Japanese renders from three sources without the app having
 * to decide per character which one owns it.
 */
class TerminalTypefaces(private val context: Context) {
    private val log = TermLog.logger(LogTags.RENDER)
    private val cache = HashMap<String, Typeface>()

    fun regular(font: TerminalFont): Typeface = get(font.id + ":regular") { build(font.regularAsset) }

    fun bold(font: TerminalFont): Typeface = get(font.id + ":bold") { build(font.boldAsset) }

    /** The same face, for Compose text — the settings screen sets each sample in its own font. */
    fun family(font: TerminalFont): ComposeFontFamily = ComposeFontFamily(regular(font))

    private fun get(key: String, create: () -> Typeface): Typeface = synchronized(cache) { cache.getOrPut(key, create) }

    private fun build(assetPath: String): Typeface = runCatching {
        val text = FontFamily.Builder(Font.Builder(context.assets, assetPath).build()).build()
        val symbols = FontFamily.Builder(
            Font.Builder(context.assets, TerminalFont.SYMBOLS_ASSET).build(),
        ).build()
        Typeface.CustomFallbackBuilder(text)
            .addCustomFallback(symbols)
            // Japanese is not in either bundled face; the platform font supplies it, and
            // naming a fallback here is what keeps CJK from rendering as tofu.
            .setSystemFallback("sans-serif")
            .build()
    }.onFailure { error ->
        log.e(error, "typeface.buildFailed", "asset" to assetPath)
    }.getOrElse { Typeface.MONOSPACE }

    /** Frees cached faces, e.g. when the process is trimmed. */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
