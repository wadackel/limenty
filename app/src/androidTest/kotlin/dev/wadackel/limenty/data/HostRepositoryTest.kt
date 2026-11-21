package dev.wadackel.limenty.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Hosts are added, edited and removed, and survive a restart. The password rides along but
 * belongs to the keystore, so deleting a host must take it with them.
 */
@RunWith(AndroidJUnit4::class)
class HostRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.filesDir, "hosts.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newRepository() = HostRepository(context, CredentialStore(context), scope)

    /** What a fresh process would see, once its repository has finished reading the file. */
    private fun storedHosts() = runBlocking {
        newRepository().also { it.awaitLoaded() }.hosts.value.orEmpty()
    }

    private fun profile(name: String, id: String = name) = HostProfile(id = id, name = name, hostname = "$name.example", username = "someone")

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() {
        clear()
        scope.cancel()
    }

    private fun clear() {
        val credentials = CredentialStore(context)
        storedHosts().forEach { credentials.forget(it.id) }
        file.delete()
    }

    @Test
    fun aHostIsRememberedAcrossRestarts() {
        runBlocking {
            newRepository().upsert(profile("alpha"), password = null)

            // A fresh instance is what the next process launch sees.
            assertThat(storedHosts().map { it.name }).containsExactly("alpha")
        }
    }

    @Test
    fun editingReplacesRatherThanDuplicates() {
        runBlocking {
            val repository = newRepository()
            repository.upsert(profile("alpha"), password = null)
            repository.upsert(profile("alpha").copy(hostname = "moved.example", port = 2222), password = null)

            val hosts = storedHosts()
            assertThat(hosts).hasSize(1)
            assertThat(hosts.single().hostname).isEqualTo("moved.example")
            assertThat(hosts.single().port).isEqualTo(2222)
        }
    }

    @Test
    fun deletingRemovesTheHostAndItsPassword() {
        runBlocking {
            val repository = newRepository()
            repository.upsert(profile("alpha"), password = "secret")
            assertThat(repository.passwordFor("alpha")).isEqualTo("secret")

            repository.delete("alpha")

            assertThat(storedHosts()).isEmpty()
            assertThat(CredentialStore(context).load("alpha")).isNull()
        }
    }

    @Test
    fun theStoredFileNeverHoldsThePassword() {
        runBlocking {
            newRepository().upsert(profile("alpha"), password = "plaintext-secret")

            assertThat(file.readText()).doesNotContain("plaintext-secret")
        }
    }

    @Test
    fun aPasswordCanBeChangedWithoutTouchingTheHost() {
        runBlocking {
            val repository = newRepository()
            repository.upsert(profile("alpha"), password = "first")

            repository.updatePassword("alpha", "second")

            assertThat(repository.passwordFor("alpha")).isEqualTo("second")
            assertThat(repository.host("alpha")?.hostname).isEqualTo("alpha.example")
        }
    }

    @Test
    fun severalHostsKeepTheirOwnDetails() {
        runBlocking {
            val repository = newRepository()
            repository.upsert(profile("alpha"), password = "a")
            repository.upsert(profile("beta"), password = "b")

            val reloaded = newRepository().also { it.awaitLoaded() }
            assertThat(reloaded.hosts.value.orEmpty().map { it.name }).containsExactly("alpha", "beta")
            assertThat(reloaded.passwordFor("alpha")).isEqualTo("a")
            assertThat(reloaded.passwordFor("beta")).isEqualTo("b")
        }
    }

    @Test
    fun anUnreadableFileLeavesTheAppWithAnEmptyListRatherThanCrashing() {
        runBlocking {
            file.writeText("{ this is not the array it expects")

            assertThat(storedHosts()).isEmpty()
        }
    }

    @Test
    fun upsertingWithoutAPasswordKeepsTheOneAlreadyStored() {
        runBlocking {
            val repository = newRepository()
            repository.upsert(profile("alpha"), password = "keep me")

            repository.upsert(profile("alpha").copy(username = "renamed"), password = null)

            assertThat(repository.passwordFor("alpha")).isEqualTo("keep me")
        }
    }
}
