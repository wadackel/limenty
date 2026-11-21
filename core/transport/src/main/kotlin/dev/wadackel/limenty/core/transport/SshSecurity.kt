package dev.wadackel.limenty.core.transport

import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the full BouncyCastle provider before any SSH work happens.
 *
 * Android ships a stripped-down provider under the same name `BC`, missing the curve25519 and
 * ed25519 primitives that current OpenSSH negotiates by default. Leaving it in place limits the
 * client to `ssh-rsa` and `ssh-dss`, both of which OpenSSH 8.8 and later refuse — so a stock
 * macOS host would simply be unreachable.
 */
object SshSecurity {
    private val installed = AtomicBoolean(false)

    fun ensureInstalled() {
        if (!installed.compareAndSet(false, true)) return
        val log = TermLog.logger(LogTags.SSH)
        runCatching {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            SecurityUtils.setRegisterBouncyCastle(true)
            SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
        }.onSuccess {
            log.i("security.provider.installed", "provider" to BouncyCastleProvider.PROVIDER_NAME)
        }.onFailure { error ->
            installed.set(false)
            log.e(error, "security.provider.failed")
        }
    }
}
