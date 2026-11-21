package dev.wadackel.limenty.core.session

@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value
}
