package dev.wadackel.limenty.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces the file's contents, or leaves the previous ones in place.
 *
 * The bytes go to a sibling and are forced to the device before the rename, so a process that
 * dies part way through a save leaves either the whole old file or the whole new one. Writing
 * over the file directly left nothing to fall back to: the previous hosts were gone as soon as
 * the first byte landed.
 *
 * Not [android.util.AtomicFile], whose `finishWrite` logs a failed sync, a failed close and a
 * failed rename and then renames regardless — a write that ran out of space is reported as a
 * success, with the truncated result in place of the last good file. [Files.move] throws where
 * `renameTo` would return false.
 */
internal fun File.writeTextAtomically(text: String) {
    val temporary = toPath().resolveSibling("$name.tmp")
    try {
        FileOutputStream(temporary.toFile()).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary, toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (error: Throwable) {
        Files.deleteIfExists(temporary)
        throw error
    }
}
