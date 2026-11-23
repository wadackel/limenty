package dev.wadackel.limenty.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.terminal.TerminalPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Everything the settings screen offers is remembered, and a stored value that
 * no longer makes sense cannot take the rest of the configuration down with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TerminalSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What a fresh process would see, once its store has finished reading. */
    private fun storedValues(): TerminalPreferences = runBlocking {
        TerminalSettings(context, scope).also { it.awaitLoaded() }.values.value!!
    }

    /**
     * Changes a setting and waits for the write, which the store performs without offering a
     * result — the read below is a different instance and has to see the disk, not the memory.
     */
    private fun writeSettings(transform: (TerminalPreferences) -> TerminalPreferences) = runBlocking {
        val settings = TerminalSettings(context, scope).also { it.awaitLoaded() }
        settings.update(transform)
        scope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() {
        clear()
        scope.cancel()
    }

    private fun clear() {
        context.getSharedPreferences("limenty-settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theDefaultsAreUsableWithoutAnythingStored() {
        val values = storedValues()

        assertThat(values.typography.fontSizeSp).isGreaterThan(0f)
        assertThat(values.palette).isEqualTo(TerminalPalette.Default)
        assertThat(values.keySet.scrolling).isNotEmpty()
    }

    @Test
    fun typographyIsRememberedAcrossRestarts() {
        writeSettings {
            it.copy(typography = it.typography.copy(fontId = "hack", fontSizeSp = 17f, lineHeight = 1.4f, letterSpacingSp = 0.5f))
        }

        val reloaded = storedValues().typography
        assertThat(reloaded.fontId).isEqualTo("hack")
        assertThat(reloaded.fontSizeSp).isEqualTo(17f)
        assertThat(reloaded.lineHeight).isEqualTo(1.4f)
        assertThat(reloaded.letterSpacingSp).isEqualTo(0.5f)
    }

    @Test
    fun theChosenThemeIsRemembered() {
        writeSettings { it.copy(themeId = TerminalPalette.Nord.name) }

        assertThat(storedValues().palette).isEqualTo(TerminalPalette.Nord)
    }

    @Test
    fun customThemesAndTheOneInUseAreRememberedAcrossRestarts() {
        val mine = CustomTheme("custom:test", TerminalPalette.Nord.copy(name = "Mine", background = 0xFF101010.toInt()))
        writeSettings { it.addTheme(mine).copy(themeId = mine.id) }

        val reloaded = storedValues()
        assertThat(reloaded.customThemes).containsExactly(mine)
        assertThat(reloaded.palette).isEqualTo(mine.palette)
    }

    @Test
    fun aThemeThatNoLongerExistsFallsBackRatherThanFailing() {
        writeSettings { it.copy(themeId = "a-theme-from-a-later-version") }

        assertThat(storedValues().palette).isEqualTo(TerminalPalette.Default)
    }

    @Test
    fun keepScreenAwakeIsRemembered() {
        writeSettings { it.copy(keepScreenAwake = false) }

        assertThat(storedValues().keepScreenAwake).isFalse()
    }

    @Test
    fun anEditedAuxiliaryBarIsRemembered() {
        val trimmed = AuxiliaryKey.DefaultScrolling.take(3)
        writeSettings { it.updateKeySet(0) { set -> set.copy(scrolling = trimmed) } }

        assertThat(storedValues().keySet.scrolling).isEqualTo(trimmed)
    }

    @Test
    fun keySetsAndTheOneInUseAreRememberedAcrossRestarts() {
        val agent = KeySet("Agent", AuxiliaryKey.DefaultScrolling.take(2), AuxiliaryKey.DefaultPinned.take(1))
        writeSettings { it.copy(keySets = it.keySets + agent, activeKeySet = 1) }

        val values = storedValues()
        assertThat(values.keySets).hasSize(2)
        assertThat(values.keySet).isEqualTo(agent)
    }

    /** The bar on the owner's phone was stored before there were sets, and has to survive the upgrade. */
    @Test
    fun aBarStoredBeforeKeySetsBecomesTheDefaultSet() {
        val scrolling = AuxiliaryKey.DefaultScrolling.take(4)
        val pinned = AuxiliaryKey.DefaultPinned.take(2)
        context.getSharedPreferences("limenty-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("auxKeys", AuxiliaryKey.encodeBar(scrolling))
            .putString("pinnedKeys", AuxiliaryKey.encodeBar(pinned))
            .commit()

        val values = storedValues()
        assertThat(values.keySets).containsExactly(KeySet(KeySet.DEFAULT_NAME, scrolling, pinned))
        assertThat(values.keySet).isEqualTo(values.keySets.single())
    }

    @Test
    fun unreadableKeySetsFallBackToTheBarStoredBeforeThem() {
        val scrolling = AuxiliaryKey.DefaultScrolling.take(4)
        context.getSharedPreferences("limenty-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("auxKeys", AuxiliaryKey.encodeBar(scrolling))
            .putString("keySets", "not json")
            .commit()

        assertThat(storedValues().keySet.scrolling).isEqualTo(scrolling)
    }

    @Test
    fun anEmptyListOfKeySetsFallsBackToTheShippedBar() {
        context.getSharedPreferences("limenty-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("keySets", "[]")
            .commit()

        assertThat(storedValues().keySets).containsExactly(KeySet.Default)
    }

    @Test
    fun gestureBindingsAreRemembered() {
        writeSettings { it.copy(gestures = it.gestures.with(Gesture.DoubleTap, GestureBehaviour.Copy)) }

        assertThat(storedValues().gestures[Gesture.DoubleTap]).isEqualTo(GestureBehaviour.Copy)
    }

    /** A binding written by a later version must not strand the settings that came with it. */
    @Test
    fun anUnknownGestureBindingLeavesTheOthersIntact() {
        writeSettings {
            it.copy(gestures = it.gestures.with(Gesture.DoubleTap, GestureBehaviour.Copy))
        }
        val preferences = context.getSharedPreferences("limenty-settings", Context.MODE_PRIVATE)
        val stored = preferences.getString("gestures", null)!!
        preferences.edit().putString("gestures", stored.replace("\"copy\"", "\"invented-later\"")).commit()

        val values = storedValues()
        assertThat(values.gestures[Gesture.DoubleTap]).isEqualTo(GestureBehaviour.None)
        assertThat(values.gestures[Gesture.LongPress]).isEqualTo(GestureBehaviour.SelectText)
    }

    @Test
    fun unreadableStoredSettingsDoNotPreventTheAppStarting() {
        context.getSharedPreferences("limenty-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("gestures", "not json")
            .putString("auxKeys", "not json either")
            .commit()

        val values = storedValues()
        assertThat(values.keySet.scrolling).isEqualTo(AuxiliaryKey.DefaultScrolling)
        assertThat(values.gestures[Gesture.LongPress]).isEqualTo(GestureBehaviour.SelectText)
    }

    @Test
    fun oneChangeDoesNotResetTheRest() {
        writeSettings { it.copy(keepScreenAwake = false) }
        writeSettings { it.copy(themeId = TerminalPalette.Nord.name) }

        val values = storedValues()
        assertThat(values.keepScreenAwake).isFalse()
        assertThat(values.palette).isEqualTo(TerminalPalette.Nord)
    }

    @Test
    fun theKeyboardSharingChoiceIsRemembered() {
        writeSettings { it.copy(allowPersonalizedLearning = true) }

        assertThat(storedValues().allowPersonalizedLearning).isTrue()
    }

    /** A terminal carries password prompts, so the private setting is the one you get by default. */
    @Test
    fun keyboardSharingIsOffUntilItIsAskedFor() {
        assertThat(storedValues().allowPersonalizedLearning).isFalse()
    }

    /** A keyboard closed on purpose should stay closed, so restoring is what you get by default. */
    @Test
    fun restoringTheKeyboardIsOnUnlessStoredOtherwise() {
        assertThat(storedValues().restoreKeyboardOnReturn).isTrue()
    }

    @Test
    fun turningKeyboardRestoreOffIsRemembered() {
        writeSettings { it.copy(restoreKeyboardOnReturn = false) }

        assertThat(storedValues().restoreKeyboardOnReturn).isFalse()
    }

    @Test
    fun theDoubleTapDragChoiceIsRemembered() {
        writeSettings { it.copy(doubleTapDragZoom = true) }

        assertThat(storedValues().doubleTapDragZoom).isTrue()
    }

    /** A tap followed by a scroll would otherwise change the font size, so it waits to be asked for. */
    @Test
    fun doubleTapDragIsOffUntilItIsAskedFor() {
        assertThat(storedValues().doubleTapDragZoom).isFalse()
    }
}
