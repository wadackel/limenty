package dev.wadackel.limenty.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores host passwords encrypted with a key held in the Android Keystore.
 *
 * Credentials are never on disk in plaintext. The key never leaves the keystore, so the
 * ciphertext in shared preferences is useless on its own — including in a backup, which is also
 * why the manifest disables backup entirely.
 */
class CredentialStore(context: Context) {
    private val log = TermLog.logger(LogTags.STORAGE)

    // Opened on first use, not here: `getSharedPreferences` reads the file rather than promising
    // to, and every caller below already runs on [Dispatchers.IO].
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    /**
     * Stores the password, or says why it could not.
     *
     * The failure is returned as well as logged: a Keystore that refuses is usually going to keep
     * refusing, and the caller has to decide what a host with no stored password means rather
     * than find out at the next connection attempt.
     */
    fun save(hostId: String, password: String): Result<Unit> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        // The IV is generated per encryption and stored beside the ciphertext; reusing one
        // with GCM would leak the plaintext relationship between two saves.
        val payload = cipher.iv + ciphertext
        // Committed rather than applied: `apply` writes on a background thread and reports
        // nothing, which is the same silence this call is here to end.
        val written = preferences.edit()
            .putString(hostId, android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
            .commit()
        check(written) { "the credential store rejected the write" }
        log.i("credential.saved", "hostId" to hostId)
    }.onFailure { log.e(it, "credential.saveFailed", "hostId" to hostId) }

    fun load(hostId: String): String? = runCatching {
        val encoded = preferences.getString(hostId, null) ?: return null
        val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        if (payload.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH),
        )
        String(cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH), Charsets.UTF_8)
    }.onFailure { log.w(it, "credential.loadFailed", "hostId" to hostId) }.getOrNull()

    fun forget(hostId: String) {
        preferences.edit().remove(hostId).apply()
        log.i("credential.forgotten", "hostId" to hostId)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring authentication: an app lock is out of scope for V1,
                // and a one-tap connect is the product's central promise.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "limenty-credentials"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "limenty-credential-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
