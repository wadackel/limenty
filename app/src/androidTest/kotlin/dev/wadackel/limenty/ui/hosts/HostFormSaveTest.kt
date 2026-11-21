package dev.wadackel.limenty.ui.hosts

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.data.HostProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostFormSaveTest {
    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<HostProfile>()

    private fun fillNewHost(hostname: String, username: String) {
        compose.setContent {
            HostFormScreen(initial = null, initialPassword = "", onSave = { host, _ -> saved += host }, onCancel = {})
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(hostname)
        fields[1].performTextInput(username)
        // The keyboard the fields opened covers Save, and a click aimed at a covered node lands
        // on the keyboard instead.
        Espresso.closeSoftKeyboard()
    }

    private fun save() = compose.onNodeWithText("Save").performScrollTo().performClick()

    @Test
    fun spacesAroundTheAddressAndUserAreNotSaved() {
        fillNewHost(" macbook.example.test ", "someone ")
        save()

        assertThat(saved.single().hostname).isEqualTo("macbook.example.test")
        assertThat(saved.single().username).isEqualTo("someone")
    }

    @Test
    fun savingANewHostAgainUpdatesItRatherThanAddingASecond() {
        fillNewHost("macbook.example.test", "someone")
        save()
        save()

        assertThat(saved).hasSize(2)
        assertThat(saved[1].id).isEqualTo(saved[0].id)
    }
}
