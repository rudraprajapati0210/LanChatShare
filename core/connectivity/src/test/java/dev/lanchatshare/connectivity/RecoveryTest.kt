package dev.lanchatshare.connectivity

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    @Test fun reconnectDelayIsBoundedAndFreshLinksStartImmediately() {
        val retry = ReconnectDelay()
        retry.failed(100)
        assertEquals(1100L,retry.next)
        repeat(50) { retry.failed(100) }
        assertEquals(32100L,retry.next)
        retry.connected()
        assertEquals(0L,retry.next)
    }
    @Test fun scopeChecksHandlePartialPrefixAndAddressFamily() {
        val link = LanLink(InetAddress.getByName("192.168.1.130"),25,"test",null,null)
        assertTrue(link.contains(InetAddress.getByName("192.168.1.254")))
        assertFalse(link.contains(InetAddress.getByName("192.168.1.127")))
        assertFalse(link.contains(InetAddress.getByName("::1")))
    }
}
