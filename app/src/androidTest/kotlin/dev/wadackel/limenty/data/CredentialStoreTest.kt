package dev.wadackel.limenty.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A password may be kept for one-tap connect, but never in a form that is readable
 * without the device. Runs on a device because the key lives in the Android Keystore.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: CredentialStore

    private val hosts = listOf("host-a", "host-b", "host-c")

    private fun stored(hostId: String): String? = context
        .getSharedPreferences("limenty-credentials", Context.MODE_PRIVATE)
        .getString(hostId, null)

    @Before
    fun setUp() {
        store = CredentialStore(context)
        hosts.forEach { store.forget(it) }
    }

    @After
    fun tearDown() {
        hosts.forEach { store.forget(it) }
    }

    @Test
    fun aSavedPasswordComesBackIntact() {
        store.save("host-a", "correct horse battery staple")

        assertThat(store.load("host-a")).isEqualTo("correct horse battery staple")
    }

    @Test
    fun nothingIsReturnedForAHostThatWasNeverSaved() {
        assertThat(store.load("host-b")).isNull()
    }

    @Test
    fun forgettingRemovesIt() {
        store.save("host-a", "secret")
        store.forget("host-a")

        assertThat(store.load("host-a")).isNull()
    }

    @Test
    fun theStoredFormIsNotThePassword() {
        val password = "plaintext-would-show-here"
        store.save("host-a", password)

        val raw = stored("host-a")

        assertThat(raw).isNotNull()
        assertThat(raw).doesNotContain(password)
        // Base64 of the plaintext would be a leak too, so rule out the obvious encoding.
        assertThat(raw).doesNotContain(Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
    }

    /** A repeated GCM nonce would reveal that two hosts share a password. */
    @Test
    fun theSamePasswordStoredTwiceLooksDifferentEachTime() {
        store.save("host-a", "shared")
        store.save("host-b", "shared")

        assertThat(stored("host-a")).isNotEqualTo(stored("host-b"))
        assertThat(store.load("host-a")).isEqualTo("shared")
        assertThat(store.load("host-b")).isEqualTo("shared")
    }

    @Test
    fun eachHostKeepsItsOwnPassword() {
        store.save("host-a", "alpha")
        store.save("host-b", "beta")

        assertThat(store.load("host-a")).isEqualTo("alpha")
        assertThat(store.load("host-b")).isEqualTo("beta")
    }

    @Test
    fun aTamperedPayloadIsRefusedRatherThanReturnedWrong() {
        store.save("host-a", "secret")
        val payload = Base64.decode(stored("host-a"), Base64.NO_WRAP)
        payload[payload.size - 1] = (payload[payload.size - 1] + 1).toByte()
        context.getSharedPreferences("limenty-credentials", Context.MODE_PRIVATE)
            .edit()
            .putString("host-a", Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()

        // The GCM tag fails; a failed decrypt must not become a wrong password on the wire.
        assertThat(store.load("host-a")).isNull()
    }

    @Test
    fun aPasswordOfNonAsciiCharactersSurvives() {
        store.save("host-c", "パスワード🔐ß")

        assertThat(store.load("host-c")).isEqualTo("パスワード🔐ß")
    }

    @Test
    fun anEmptyPasswordIsDistinctFromNoPassword() {
        store.save("host-c", "")

        assertThat(store.load("host-c")).isEqualTo("")
    }
}
