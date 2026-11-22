package dev.wadackel.limenty.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test

/**
 * The Version, Build and Commit lines under the mark copy their value when pressed, so each is a
 * target and is held to the height everything pressed has. Version and Build were the height of
 * their text and sat against each other, so a press near the line between them could copy the
 * other value; the screenshot does not show that, the bounds do.
 */
class BuildRowHeightTest {
    @get:Rule
    val rule = createComposeRule()

    private fun copyRow(label: String) = rule.onNode(
        SemanticsMatcher("copies $label") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == "Copy $label"
        },
    )

    @Test
    fun eachLineThatCopiesItsValueIsTallEnoughToPress() {
        rule.setContent {
            LimentyTheme {
                Column(modifier = Modifier.width(460.dp)) {
                    AboutSection(
                        build = BuildInfo(version = "0.1.0", type = "debug", commit = "07a5ff5", dirty = false),
                        ownLicense = null,
                        notices = emptyList(),
                        fontFamilyOf = { null },
                    )
                }
            }
        }
        listOf("Version", "Build", "Commit").forEach { label ->
            copyRow(label).assertHeightIsAtLeast(TOUCH_TARGET)
        }
    }
}

/** What the rest of the app calls a target worth hitting. */
private val TOUCH_TARGET = 44.dp
