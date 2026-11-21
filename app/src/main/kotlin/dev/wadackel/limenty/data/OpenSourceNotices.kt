package dev.wadackel.limenty.data

import android.content.Context
import dev.wadackel.limenty.R
import org.json.JSONObject

/** A licence as the About screen shows it: a short label for the list, the full text for reading. */
internal data class NoticeLicense(val label: String, val name: String, val text: String)

/**
 * One line of the About screen's open-source list: a bundled font, or the artifacts of one Maven
 * group taken together.
 *
 * A group rather than an artifact because the build pulls in 93 artifacts, thirteen of them from
 * androidx.lifecycle alone, and a list of every one reads as noise; the artifacts are still named
 * when the line is opened.
 */
internal data class OpenSourceNotice(
    val name: String,
    val detail: String,
    val parts: List<String>,
    val licenses: List<NoticeLicense>,
    val isFont: Boolean = false,
)

private val ListItem = Regex("""^(\s|[-*#]|\d+[.)]|\([a-z0-9]+\))""")

private fun isHeading(line: String): Boolean {
    val rule = line.all { it == '-' || it == '=' || it.isWhitespace() }
    val capitals = line.length <= 40 && !line.endsWith(".") && line.any(Char::isLetter) && line == line.uppercase()
    return line.startsWith("#") || rule || capitals
}

/**
 * Joins the lines of each paragraph so the text wraps to the screen rather than at the column the
 * file was written for. Licence files break near 80 columns; on the cover display a line holds about
 * sixty, so left alone every line wrapped once more and the text read as a ragged column. Headings,
 * rules and list items keep their own lines, which is what keeps the OFL's structure readable; a
 * heading is a short line in capitals, since the warranty paragraphs are capitals too.
 */
internal fun reflowLicense(text: String): String {
    val lines = text.replace("\r\n", "\n").lines()
    val out = StringBuilder()
    lines.forEachIndexed { index, line ->
        out.append(line.trimEnd())
        val next = lines.getOrNull(index + 1) ?: return@forEachIndexed
        val joins = line.isNotBlank() && next.isNotBlank() && !isHeading(line.trim()) &&
            !isHeading(next.trim()) && !ListItem.containsMatchIn(next)
        out.append(if (joins) ' ' else '\n')
    }
    return out.toString().trim()
}

/** A font in the APK's assets, with the licence file shipped beside it. */
internal data class BundledFont(val name: String, val license: String, val asset: String)

internal val BundledFonts = listOf(
    BundledFont("JetBrains Mono", "OFL-1.1", "licenses/JetBrainsMono-OFL.txt"),
    BundledFont("Fira Code", "OFL-1.1", "licenses/FiraCode-OFL.txt"),
    BundledFont("Hack", "MIT + Bitstream Vera", "licenses/Hack-LICENSE.md"),
    BundledFont("Symbols Nerd Font Mono", "MIT", "licenses/SymbolsNerdFont-LICENSE.txt"),
)

/**
 * Names for groups whose id says little to a reader. The rest are named by rule in [groupName];
 * a group added later and missing here falls back to its id, which is still correct, only plainer.
 */
private val GroupNames = mapOf(
    "com.google.guava" to "Guava",
    "com.hierynomus" to "sshj",
    "com.squareup.okio" to "Okio",
    "org.bouncycastle" to "Bouncy Castle",
    "org.jetbrains" to "JetBrains Annotations",
    "org.jetbrains.kotlin" to "Kotlin",
    "org.jetbrains.kotlinx" to "kotlinx",
    "org.jspecify" to "JSpecify",
    "org.slf4j" to "SLF4J",
)

private fun titled(segments: String): String = segments.split('.').joinToString(" ") { segment ->
    if (segment == "ui") "UI" else segment.replaceFirstChar(Char::uppercaseChar)
}

internal fun groupName(group: String, artifactNames: List<String>): String = GroupNames[group] ?: when {
    group.startsWith("androidx.compose.") -> "Compose " + titled(group.removePrefix("androidx.compose."))
    group.startsWith("androidx.") -> "AndroidX " + titled(group.removePrefix("androidx."))
    artifactNames.size == 1 -> artifactNames.single()
    else -> group
}

/**
 * Reads the library definitions the AboutLibraries Gradle plugin writes at build time.
 *
 * Parsed here rather than through the plugin's runtime library, which would add a dependency to
 * read a file of three plain fields per entry. Unknown keys are ignored because the plugin adds
 * fields between releases.
 */
internal fun parseLibraryDefinitions(json: String): List<OpenSourceNotice> {
    val root = JSONObject(json)
    val licenseObjects = root.getJSONObject("licenses")
    val licenses = licenseObjects.keys().asSequence().associateWith { key ->
        val license = licenseObjects.getJSONObject(key)
        val name = license.optString("name", key)
        NoticeLicense(
            label = license.optString("spdxId").ifEmpty { name },
            name = name,
            text = reflowLicense(license.optString("content")),
        )
    }
    val libraries = root.getJSONArray("libraries")
    data class Artifact(val group: String, val id: String, val version: String, val name: String, val licenses: List<String>)
    val artifacts = (0 until libraries.length()).map { index ->
        val library = libraries.getJSONObject(index)
        val (group, id) = library.getString("uniqueId").split(":", limit = 2)
        val keys = library.optJSONArray("licenses")
        Artifact(
            group = group,
            id = id,
            version = library.optString("artifactVersion"),
            name = library.optString("name", id),
            licenses = keys?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty(),
        )
    }
    return artifacts.groupBy { it.group }.map { (group, members) ->
        OpenSourceNotice(
            name = groupName(group, members.map { it.name }),
            detail = group,
            parts = members.map { if (it.version.isEmpty()) it.id else "${it.id} ${it.version}" },
            licenses = members.flatMap { it.licenses }.distinct().mapNotNull(licenses::get),
        )
    }
}

/** Fonts first, then libraries by name: the order the About screen lists them in. */
internal fun openSourceNotices(
    libraryDefinitions: String,
    readAsset: (String) -> String,
): List<OpenSourceNotice> {
    val fonts = BundledFonts.map { font ->
        OpenSourceNotice(
            name = font.name,
            detail = "Font",
            parts = emptyList(),
            licenses = listOf(NoticeLicense(label = font.license, name = font.license, text = reflowLicense(readAsset(font.asset)))),
            isFont = true,
        )
    }
    return fonts + parseLibraryDefinitions(libraryDefinitions).sortedBy { it.name.lowercase() }
}

/** Everything the About screen reads from the APK, loaded together off the main thread. */
internal data class AboutContent(val ownLicense: NoticeLicense, val notices: List<OpenSourceNotice>)

/** Copied from the repository's LICENSE at build time, so the two cannot disagree. */
internal const val OWN_LICENSE_ASSET = "licenses/Limenty-LICENSE.txt"

internal fun loadAboutContent(context: Context): AboutContent {
    val readAsset = { path: String -> context.assets.open(path).bufferedReader().use { it.readText() } }
    val definitions = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
    return AboutContent(
        ownLicense = NoticeLicense(label = "MIT", name = "MIT License", text = reflowLicense(readAsset(OWN_LICENSE_ASSET))),
        notices = openSourceNotices(definitions, readAsset),
    )
}
