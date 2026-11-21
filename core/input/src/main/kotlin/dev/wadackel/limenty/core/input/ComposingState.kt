package dev.wadackel.limenty.core.input

/**
 * The IME preedit as the terminal should draw it: at the cursor cell, never sent to the remote.
 *
 * [revision] increases on every change so a renderer can drop a frame it computed from a stale
 * snapshot instead of drawing preedit text that has already been committed.
 */
data class ComposingState(
    val text: String = "",
    val cursor: Int = 0,
    val active: Boolean = false,
    val revision: Long = 0,
) {
    companion object {
        val Inactive = ComposingState()
    }
}
