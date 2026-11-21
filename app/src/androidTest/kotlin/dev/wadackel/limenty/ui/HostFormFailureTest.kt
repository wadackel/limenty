package dev.wadackel.limenty.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.wadackel.limenty.MainActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * A save that cannot reach the disk, as the person doing it sees it.
 *
 * The repository's own tests already show the outcome it returns. What none of them show is that
 * the outcome arrives on screen — which is the part that decides whether a failed save is a
 * problem the user knows about or a host that quietly is not there next time.
 */
@RunWith(AndroidJUnit4::class)
class HostFormFailureTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    private val hostsFile: File
        get() = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "hosts.json")

    /**
     * The name the atomic write renames onto, taken by a directory so the rename fails every time.
     *
     * Scoped to the one file rather than the app's whole data directory: a run that dies before
     * its cleanup would otherwise leave every later test and every manual launch unable to save.
     */
    private fun blockWrites(): Boolean = hostsFile.let {
        it.delete()
        it.mkdirs()
    }

    @After
    fun allowWrites() {
        hostsFile.delete()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aSaveThatCannotReachTheDiskSaysSoOnTheForm() {
        compose.onNodeWithText("Add host").performClick()
        awaitText("Hostname")
        // By position among the editable nodes: the labels are drawn beside the fields rather
        // than inside them, so matching on a label finds the caption and not the input.
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("macbook.example.test")
        fields[1].performTextInput("someone")
        // Save is only enabled once the form is valid, so a click that reached a disabled button
        // would time out below with nothing saying why.
        // The keyboard opened by the two fields above covers the button, and a click aimed at a
        // covered node lands on the keyboard instead.
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Save").assertIsEnabled()

        check(blockWrites()) { "could not take the name $hostsFile" }
        compose.onNodeWithText("Save").performClick()

        // Below the buttons and off the bottom of a form this long, so it has to be scrolled to
        // before it can be said to have reached the user.
        awaitText("Could not save this host")
        compose.onNodeWithText("Could not save this host", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
