package dev.wadackel.limenty.core.input

@JvmInline
value class Modifiers(val bits: Int) {
    val shift: Boolean get() = bits and SHIFT != 0
    val alt: Boolean get() = bits and ALT != 0
    val ctrl: Boolean get() = bits and CTRL != 0
    val meta: Boolean get() = bits and META != 0

    val isEmpty: Boolean get() = bits == 0

    operator fun plus(other: Modifiers): Modifiers = Modifiers(bits or other.bits)

    /**
     * The xterm modifier parameter: 1 + a bitmask of shift/alt/ctrl/meta.
     * Returns null when no modifier is held, in which case the sequence omits the parameter.
     */
    fun xtermParameter(): Int? {
        if (bits == 0) return null
        var value = 1
        if (shift) value += 1
        if (alt) value += 2
        if (ctrl) value += 4
        if (meta) value += 8
        return value
    }

    override fun toString(): String = if (bits == 0) {
        "none"
    } else {
        buildList {
            if (ctrl) add("ctrl")
            if (alt) add("alt")
            if (shift) add("shift")
            if (meta) add("meta")
        }.joinToString("+")
    }

    companion object {
        const val SHIFT = 1 shl 0
        const val ALT = 1 shl 1
        const val CTRL = 1 shl 2
        const val META = 1 shl 3

        val None = Modifiers(0)
        val Shift = Modifiers(SHIFT)
        val Alt = Modifiers(ALT)
        val Ctrl = Modifiers(CTRL)
        val Meta = Modifiers(META)
    }
}
