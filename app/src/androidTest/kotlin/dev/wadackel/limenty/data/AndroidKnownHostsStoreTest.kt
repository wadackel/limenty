package dev.wadackel.limenty.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.wadackel.limenty.core.transport.HostKeyInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A host key accepted once is trusted next time, and a key that later disagrees has to
 * be distinguishable from it. That decision outlives the process, so it is stored and reread.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKnownHostsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newStore() = AndroidKnownHostsStore(context)

    private fun keyInfo(fingerprint: String, algorithm: String = "ssh-ed25519", blob: Int = 1) = HostKeyInfo(algorithm, fingerprint, ByteArray(32) { blob.toByte() })

    @Before
    fun setUp() {
        clear()
    }

    @After
    fun tearDown() {
        allowWrites()
        clear()
    }

    private fun clear(): Unit = runBlocking {
        val store = newStore()
        store.forget("example.test", 22)
        store.forget("example.test", 2222)
        store.forget("other.test", 22)
    }

    @Test
    fun aKeyAcceptedOnceIsFoundAgainAfterARestart(): Unit = runBlocking {
        newStore().remember("example.test", 22, keyInfo("SHA256:aaa"))

        val found = newStore().lookup("example.test", 22)

        assertThat(found.map { it.fingerprint }).containsExactly("SHA256:aaa")
        assertThat(found.single().algorithm).isEqualTo("ssh-ed25519")
    }

    @Test
    fun anUnknownHostHasNothingStored(): Unit = runBlocking {
        assertThat(newStore().lookup("never.seen", 22)).isEmpty()
    }

    /** The raw blob is what makes a later comparison exact rather than fingerprint-only. */
    @Test
    fun theKeyMaterialSurvivesTheRoundTrip(): Unit = runBlocking {
        val original = keyInfo("SHA256:bbb", blob = 7)
        newStore().remember("example.test", 22, original)

        assertThat(newStore().lookup("example.test", 22).single().key).isEqualTo(original.key)
    }

    @Test
    fun thesameHostOnAnotherPortIsAnotherHost(): Unit = runBlocking {
        newStore().remember("example.test", 22, keyInfo("SHA256:aaa"))

        assertThat(newStore().lookup("example.test", 2222)).isEmpty()
    }

    @Test
    fun aHostCanHoldOneKeyPerAlgorithm(): Unit = runBlocking {
        val store = newStore()
        store.remember("example.test", 22, keyInfo("SHA256:ed", algorithm = "ssh-ed25519"))
        store.remember("example.test", 22, keyInfo("SHA256:rsa", algorithm = "ssh-rsa"))

        assertThat(newStore().lookup("example.test", 22).map { it.algorithm })
            .containsExactly("ssh-ed25519", "ssh-rsa")
    }

    /** Re-accepting after a change must replace, not leave both keys trusted. */
    @Test
    fun rememberingAgainForOneAlgorithmReplacesTheKey(): Unit = runBlocking {
        val store = newStore()
        store.remember("example.test", 22, keyInfo("SHA256:old"))
        store.remember("example.test", 22, keyInfo("SHA256:new", blob = 2))

        val found = newStore().lookup("example.test", 22)
        assertThat(found).hasSize(1)
        assertThat(found.single().fingerprint).isEqualTo("SHA256:new")
    }

    @Test
    fun forgettingOneHostLeavesTheOthersTrusted(): Unit = runBlocking {
        val store = newStore()
        store.remember("example.test", 22, keyInfo("SHA256:aaa"))
        store.remember("other.test", 22, keyInfo("SHA256:bbb"))

        store.forget("example.test", 22)

        assertThat(newStore().lookup("example.test", 22)).isEmpty()
        assertThat(newStore().lookup("other.test", 22)).hasSize(1)
    }

    /**
     * The name the atomic write renames onto is taken by a directory, so the rename fails every
     * time rather than once — the temporary file the write cleans up is a different name.
     */
    private fun blockWrites() = File(context.filesDir, "known_hosts.json").let {
        it.delete()
        it.mkdirs()
    }

    private fun allowWrites() = File(context.filesDir, "known_hosts.json").delete()

    @Test
    fun aWriteThatCannotReachTheDiskIsReportedRatherThanLoggedAndForgotten(): Unit = runBlocking {
        val store = newStore()
        assertThat(blockWrites()).isTrue()

        val remembered = store.remember("other.test", 22, keyInfo("SHA256:bbb"))
        val forgotten = store.forget("example.test", 22)

        assertThat(remembered.isFailure).isTrue()
        assertThat(forgotten.isFailure).isTrue()
    }
}
