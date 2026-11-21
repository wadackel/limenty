package dev.wadackel.limenty.ui.components

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Japanese keyboard converts letters into kana as they are typed unless the field asks for a
 * password, and a converted password fails to authenticate with nothing on screen to say why.
 */
@RunWith(AndroidJUnit4::class)
class PasswordFieldKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theKeyboardIsAskedForAPasswordSoWhatIsTypedIsNotConverted() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            PasswordField(value = "", revealed = false, onValueChange = {}, onToggleReveal = {})
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()

        val editor = EditorInfo()
        var connection: InputConnection? = null
        compose.runOnUiThread { connection = view.onCreateInputConnection(editor) }

        assertWithMessage("input connection").that(connection).isNotNull()
        assertWithMessage("password variation")
            .that(editor.inputType and InputType.TYPE_MASK_VARIATION)
            .isEqualTo(InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }
}
