package com.mapnet.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiscoveryModelsTest {
    @Test
    fun `derives usable hosts for a private slash 24 network`() {
        val subnet = Ipv4Subnet.from("192.168.50.41".toIpv4AddressValueOrNull()!!, 24)

        assertEquals("192.168.50.0/24", subnet.cidr)
        assertEquals(254, subnet.usableHostCount)
        assertEquals(
            listOf("192.168.50.1", "192.168.50.2", "192.168.50.3"),
            subnet.hostAddresses().take(3).map(Long::toIpv4Address).toList()
        )
        assertTrue(subnet.contains("192.168.50.254".toIpv4AddressValueOrNull()!!))
        assertFalse(subnet.contains("192.168.51.1".toIpv4AddressValueOrNull()!!))
        assertTrue(subnet.hasPrivateAddress())
    }

    @Test
    fun `recognizes private IPv4 address ranges`() {
        assertTrue(Ipv4Subnet.from("10.42.0.1".toIpv4AddressValueOrNull()!!, 24).hasPrivateAddress())
        assertTrue(Ipv4Subnet.from("172.16.0.1".toIpv4AddressValueOrNull()!!, 24).hasPrivateAddress())
        assertTrue(Ipv4Subnet.from("192.168.1.1".toIpv4AddressValueOrNull()!!, 24).hasPrivateAddress())
        assertFalse(Ipv4Subnet.from("8.8.8.8".toIpv4AddressValueOrNull()!!, 24).hasPrivateAddress())
    }
}
