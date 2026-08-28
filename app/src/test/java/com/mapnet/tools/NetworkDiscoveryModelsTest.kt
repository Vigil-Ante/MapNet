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

    @Test
    fun `custom TCP ranges are validated and bounded`() {
        assertEquals(listOf(80, 81, 82), customTcpPorts(80, 82))
        assertEquals("HTTP", serviceNameForTcpPort(80))
        assertEquals("https://192.168.1.20:8443", webUrlFor("192.168.1.20", 8443))
        assertTrue(runCatching { customTcpPorts(1, 1024) }.isSuccess)
        assertTrue(runCatching { customTcpPorts(1, 1025) }.isFailure)
        assertTrue(runCatching { customTcpPorts(100, 99) }.isFailure)
    }

    @Test
    fun `device display names filters and online first sorting are stable`() {
        val online = device(
            id = "online",
            ip = "192.168.1.30",
            status = LanDeviceStatus.ONLINE,
            customName = "Living room Roku",
            vendor = "Roku"
        )
        val offline = device(
            id = "offline",
            ip = "192.168.1.2",
            status = LanDeviceStatus.OFFLINE,
            hostname = "printer.local",
            vendor = "Brother"
        )
        val unnamed = device(
            id = "unnamed",
            ip = "192.168.1.5",
            status = LanDeviceStatus.ONLINE,
            vendor = "Google"
        )

        assertEquals("Living room Roku", online.displayName)
        assertEquals("printer.local", offline.displayName)
        assertEquals("Google device", unnamed.displayName)
        assertEquals(
            listOf("unnamed", "online", "offline"),
            listOf(offline, online, unnamed)
                .filterAndSortDevices("", DeviceFilter.ALL, DeviceSort.IP_ADDRESS)
                .map(KnownLanDevice::id)
        )
        assertEquals(
            listOf("offline"),
            listOf(offline, online, unnamed)
                .filterAndSortDevices("brother", DeviceFilter.ALL, DeviceSort.NAME)
                .map(KnownLanDevice::id)
        )
    }

    private fun device(
        id: String,
        ip: String,
        status: LanDeviceStatus,
        customName: String? = null,
        hostname: String? = null,
        vendor: String? = null
    ) = KnownLanDevice(
        id = id,
        networkId = "network",
        ipAddress = ip,
        macAddress = null,
        advertisedName = null,
        hostname = hostname,
        vendor = vendor,
        model = null,
        inferredType = LanDeviceType.UNKNOWN,
        customName = customName,
        customType = null,
        note = "",
        status = status,
        isGateway = false,
        isThisDevice = false,
        sources = emptySet(),
        firstSeenEpochMs = 1,
        lastSeenEpochMs = 1
    )
}
