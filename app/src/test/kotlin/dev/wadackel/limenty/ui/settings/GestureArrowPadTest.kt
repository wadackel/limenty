package dev.wadackel.limenty.ui.settings

import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.input.ArrowPadSettings
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
import dev.wadackel.limenty.core.input.SettingRange
import dev.wadackel.limenty.ui.theme.LimentyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The arrow pad's timing on the Gestures screen, and what it says while the pad cannot open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureArrowPadTest {
    @get:Rule
    val rule = createComposeRule()

    private var pad by mutableStateOf(ArrowPadSettings())

    /** Writes, as opposed to the values a drag passes through on its way. */
    private val written = mutableListOf<ArrowPadSettings>()
    private var settled = 0

    private fun show(bindings: GestureBindings = GestureBindings.Initial, initial: ArrowPadSettings = ArrowPadSettings()) {
        pad = initial
        rule.setContent {
            LimentyTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GestureSettings(
                        bindings = bindings,
                        onChange = {},
                        doubleTapDragZoom = false,
                        onDoubleTapDragZoomChange = {},
                        arrowPad = pad,
                        onArrowPadChange = {
                            pad = it
                            written += it
                        },
                        onArrowPadDrag = { pad = it },
                        onArrowPadSettled = { settled++ },
                    )
                }
            }
        }
    }

    private fun slider(range: SettingRange) = rule.onNode(
        SemanticsMatcher("slider over ${range.min}..${range.max}") { node ->
            node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == range.min.toFloat()..range.max.toFloat()
        },
    )

    private fun setSlider(range: SettingRange, to: Float) {
        slider(range).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(to) }
        rule.waitForIdle()
    }

    @Test
    fun pressTimeFollowsTheSystemAndSaysWhatTheSystemTimeIs() {
        show()

        rule.onNodeWithText("System (${ViewConfiguration.getLongPressTimeout()}ms)").performScrollTo()
        rule.onNodeWithText("Follow the system").performScrollTo().assertIsOn()
    }

    @Test
    fun movingThePressTimeLeavesTheSystemForTheNearestStep() {
        show()

        setSlider(ArrowPadSettings.PressTime, 637f)

        assertThat(pad.pressTimeMillis).isEqualTo(650)
        rule.onNodeWithText("Follow the system").performScrollTo().assertIsOff()
    }

    @Test
    fun turningFollowTheSystemBackOnForgetsTheTimeThatWasSet() {
        show(initial = ArrowPadSettings(pressTimeMillis = 900))

        rule.onNodeWithText("Follow the system").performScrollTo().performClick()
        rule.waitForIdle()

        assertThat(written.last().pressTimeMillis).isNull()
    }

    @Test
    fun turningFollowTheSystemOffStartsFromTheSystemTime() {
        show()

        rule.onNodeWithText("Follow the system").performScrollTo().performClick()
        rule.waitForIdle()

        assertThat(written.last().pressTimeMillis).isEqualTo(ArrowPadSettings.PressTime.snap(ViewConfiguration.getLongPressTimeout()))
    }

    @Test
    fun eachRepeatAndDeadZoneSliderChangesItsOwnValueOnItsSteps() {
        show()

        setSlider(ArrowPadSettings.RepeatDelay, 260f)
        setSlider(ArrowPadSettings.RepeatInterval, 94f)
        setSlider(ArrowPadSettings.DeadZone, 42f)

        assertThat(pad).isEqualTo(ArrowPadSettings(repeatDelayMillis = 250, repeatIntervalMillis = 90, deadZoneDp = 40))
    }

    @Test
    fun resetToDefaultsIsOffWhileEveryValueIsAlreadyTheDefault() {
        show()

        rule.onNodeWithText("Reset to defaults").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun resetToDefaultsPutsEveryValueBackAtOnceAndPressTimeBackOnTheSystem() {
        show(initial = ArrowPadSettings(pressTimeMillis = 800, repeatDelayMillis = 150, repeatIntervalMillis = 60, deadZoneDp = 15))

        rule.onNodeWithText("Reset to defaults").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()

        assertThat(written).containsExactly(ArrowPadSettings())
        rule.onNodeWithText("Reset to defaults").assertIsNotEnabled()
    }

    @Test
    fun withLongPressBoundElsewhereOnlyThePressTimeCanBeChanged() {
        show(bindings = GestureBindings.Initial.with(Gesture.LongPress, GestureBehaviour.Paste))

        slider(ArrowPadSettings.PressTime).performScrollTo().assertIsEnabled()
        slider(ArrowPadSettings.RepeatDelay).performScrollTo().assertIsNotEnabled()
        slider(ArrowPadSettings.RepeatInterval).performScrollTo().assertIsNotEnabled()
        slider(ArrowPadSettings.DeadZone).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("apply when Long press is Select text", substring = true).performScrollTo()
    }

    @Test
    fun withLongPressOnSelectTextEveryArrowPadSliderCanBeChanged() {
        show()

        slider(ArrowPadSettings.RepeatDelay).performScrollTo().assertIsEnabled()
        slider(ArrowPadSettings.RepeatInterval).performScrollTo().assertIsEnabled()
        slider(ArrowPadSettings.DeadZone).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aDraggedSliderWritesOnceWhenItLetsGo() {
        show()

        setSlider(ArrowPadSettings.RepeatDelay, 600f)

        assertThat(written).isEmpty()
        assertThat(settled).isEqualTo(1)
    }
}
