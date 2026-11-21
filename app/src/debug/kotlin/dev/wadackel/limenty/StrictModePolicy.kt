package dev.wadackel.limenty

import android.os.StrictMode
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import java.util.concurrent.Executors

/**
 * Reports work on the main thread that has no business being there.
 *
 * Only the three detectors that answer the question the stores were changed for. `detectAll` adds
 * untagged sockets, which SSHJ produces on every connection, and non-SDK API use, which the
 * framework produces on its own — both would bury a disk read behind noise nothing here can act
 * on.
 *
 * The violation goes to the app's own records rather than through `penaltyLog`, whose output
 * carries the framework's tag and is therefore invisible to `tools/app log`. `app` says whether
 * the stack reaches this codebase, because that is the part a change here can do anything about.
 *
 * A release build has the empty twin of this in `src/release`, so none of it ships.
 */
fun installStrictMode() {
    val log = TermLog.logger(LogTags.LIFECYCLE)
    val reporting = Executors.newSingleThreadExecutor()
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyListener(reporting) { violation ->
                val ours = violation.stackTrace.firstOrNull { it.className.startsWith(PACKAGE) }
                log.w(
                    "strictmode.thread",
                    "kind" to violation.javaClass.simpleName,
                    "app" to (ours != null),
                    "at" to (ours?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "framework"),
                )
            }
            .build(),
    )
}

private const val PACKAGE = "dev.wadackel.limenty"
