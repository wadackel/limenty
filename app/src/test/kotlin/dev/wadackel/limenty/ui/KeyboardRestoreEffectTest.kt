package dev.wadackel.limenty.ui

import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the window asks of the platform when the app comes back. Whether the keyboard then opens is
 * the platform's doing and is observed on a device; this only pins the request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardRestoreEffectTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var active by mutableStateOf(true)

    /** The bits the effect is responsible for, without the ones the platform sets on resume. */
    private fun requestedMode(): Int = rule.runOnUiThread<Int> {
        rule.activity.window.attributes.softInputMode and (SOFT_INPUT_MASK_STATE or SOFT_INPUT_MASK_ADJUST)
    }

    /** The test activity declares nothing, so it is first put where the app's manifest starts. */
    @Before
    fun startAsTheManifestDeclares() {
        rule.runOnUiThread { rule.activity.window.setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE) }
        rule.setContent { KeyboardRestoreEffect(active) }
        rule.waitForIdle()
    }

    @Test
    fun theKeyboardStateIsLeftAloneOnReturnWhileActive() {
        assertThat(requestedMode()).isEqualTo(SOFT_INPUT_ADJUST_RESIZE or SOFT_INPUT_STATE_UNCHANGED)
    }

    @Test
    fun goingInactivePutsTheWindowBackAsTheManifestDeclaresIt() {
        assertThat(requestedMode()).isEqualTo(SOFT_INPUT_ADJUST_RESIZE or SOFT_INPUT_STATE_UNCHANGED)

        active = false
        rule.waitForIdle()

        assertThat(requestedMode()).isEqualTo(SOFT_INPUT_ADJUST_RESIZE or SOFT_INPUT_STATE_UNSPECIFIED)
    }
}
