package dev.wadackel.limenty.core.common.log

/**
 * Marks a value that must never reach a log sink verbatim.
 *
 * Wrapping at the call site rather than filtering inside the sink keeps the guarantee
 * local to where the secret is known, so a new sink cannot accidentally leak it.
 */
@JvmInline
value class Redacted(private val raw: String?) {
    override fun toString(): String = if (raw == null) "<null>" else "<redacted:${raw.length}>"
}

fun String?.redacted(): Redacted = Redacted(this)
