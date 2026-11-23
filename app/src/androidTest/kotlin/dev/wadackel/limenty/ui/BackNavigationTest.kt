package dev.wadackel.limenty.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.wadackel.limenty.MainActivity
import org.junit.Rule
import android.util.Log
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Back is the way out of a screen. The screens reached from the host list only
 * had their own button, so the system gesture backgrounded the app from the middle of editing a
 * host instead of returning to the list.
 */
@RunWith(AndroidJUnit4::class)
class BackNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The permission has to be granted before the activity launches: its dialog keeps the activity
     * out of the resumed state, and the compose rule has nothing to attach to until it is there.
     */
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    private val rule get() = compose

    private fun onHostList() = rule.onNodeWithText("Add host")

    /**
     * Waits for the destination rather than asserting on the frame that happens to be current.
     * Screens cross-fade, so the node exists before it is on screen and `waitForIdle` alone races
     * the transition.
     */
    private fun awaitHostList() {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Add host").fetchSemanticsNodes().isNotEmpty()
        }
        onHostList().assertIsDisplayed()
    }

    /** Waits for [text] to be composed and then for it to be on screen, for the same reason. */
    private fun awaitText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    /**
     * The settings index. These tests run on a phone-sized emulator, so settings is laid out for
     * the folded display, where only the index lists the gestures as a row of their own.
     */
    private fun awaitSettingsIndex() = awaitText("Gestures")

    private fun openSettings() {
        rule.onNodeWithText("Settings").performClick()
        awaitSettingsIndex()
    }

    @Test
    fun backLeavesSettingsForTheHostList() {
        val t0 = System.nanoTime()
        fun mark(what: String) = Log.i("ExpTiming", "$what ${(System.nanoTime() - t0) / 1_000_000}ms")
        mark("start")
        rule.waitForIdle()
        mark("idle")
        rule.onNodeWithText("Settings").performClick()
        mark("clicked")
        awaitSettingsIndex()
        mark("index")
        Espresso.pressBack()
        mark("back")
        awaitHostList()
        mark("list")
    }

    @Test
    fun backLeavesTheHostFormForTheHostList() {
        rule.onNodeWithText("Add host").performClick()
        rule.onNode(hasText("Hostname")).assertIsDisplayed()

        Espresso.pressBack()

        awaitHostList()
    }

    @Test
    fun theBackArrowLeavesSettingsToo() {
        openSettings()
        rule.onNodeWithContentDescription("Back").performClick()

        awaitHostList()
    }

    /** Going in and out repeatedly must not leave a screen stacked behind the list. */
    @Test
    fun settingsCanBeOpenedAndDismissedRepeatedly() {
        repeat(3) {
            openSettings()
            Espresso.pressBack()
            awaitHostList()
        }
    }

    /** A section opened from the index is a step in, and Back is a step out to the index, not past it. */
    @Test
    fun backFromASectionReturnsToTheSettingsIndex() {
        openSettings()
        rule.onNodeWithText("Appearance").performClick()
        awaitText("Theme")

        Espresso.pressBack()

        awaitSettingsIndex()
    }

    /**
     * The key bar editor is reached from the keyboard section and returns there, not to the host
     * list nor to the index — it is the one destination a level below a section.
     */
    @Test
    fun backFromTheKeyBarReturnsToTheKeyboardSection() {
        openSettings()
        rule.onNodeWithText("Keyboard & key bar").performClick()
        awaitText("Key sets")
        rule.onNodeWithText("Key sets").performScrollTo().performClick()
        awaitText("Pinned right")

        Espresso.pressBack()

        awaitText("Restore keyboard on return")
    }

    /** About is a section like the others, and Back takes it to the index the same way. */
    @Test
    fun backFromAboutReturnsToTheSettingsIndex() {
        openSettings()
        rule.onNodeWithText("About Limenty").performScrollTo().performClick()
        awaitText("An SSH terminal for Android, built for touch.")

        Espresso.pressBack()

        awaitSettingsIndex()
    }
}
