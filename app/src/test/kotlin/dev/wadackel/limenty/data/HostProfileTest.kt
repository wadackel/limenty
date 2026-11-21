package dev.wadackel.limenty.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HostProfileTest {
    private fun unnamed(hostname: String) = HostProfile(name = "", hostname = hostname, username = "someone")

    @Test
    fun `an unnamed tailnet host is called by its machine name`() {
        assertThat(unnamed("macbook.tail1234.ts.net").displayName).isEqualTo("macbook")
    }

    @Test
    fun `an unnamed IPv4 host is called by its whole address`() {
        assertThat(unnamed("10.0.2.2").displayName).isEqualTo("10.0.2.2")
    }

    @Test
    fun `an unnamed IPv6 host is called by its whole address`() {
        assertThat(unnamed("fd7a:115c::1.2").displayName).isEqualTo("fd7a:115c::1.2")
    }

    @Test
    fun `an unnamed host found on the network is called by its whole service name`() {
        val host = HostProfile(name = "", hostname = "alice.smith’s MacBook Pro", username = "someone", lan = true)

        assertThat(host.displayName).isEqualTo("alice.smith’s MacBook Pro")
    }

    @Test
    fun `a name the user chose wins over the address`() {
        assertThat(HostProfile(name = "Box", hostname = "10.0.2.2", username = "someone").displayName).isEqualTo("Box")
    }
}
