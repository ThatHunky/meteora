package dev.thathunky.meteora.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPolicyTest {
    @Test fun `private and tailnet hosts are allowed`() {
        listOf(
            "192.168.1.2", "10.0.0.5", "172.19.128.1", "172.31.255.255", "169.254.1.1", "127.0.0.1",
            "100.95.146.127", "100.64.0.1", "100.127.255.255", "desktop", "desktop.local",
            "pc.tail1234.ts.net", "localhost", "fe80::1", "[fd00::1]",
        ).forEach { assertTrue(it, HostPolicy.isAllowed(it)) }
    }

    @Test fun `public hosts are refused`() {
        listOf(
            "8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255", "example.com", "music.youtube.com",
            "2001:4860::8888", "", "256.1.1.1",
        ).forEach { assertFalse(it, HostPolicy.isAllowed(it)) }
    }
}
