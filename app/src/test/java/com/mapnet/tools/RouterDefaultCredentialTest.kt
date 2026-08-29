package com.mapnet.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterDefaultCredentialTest {
    @Test
    fun `known gateway vendor returns its curated legacy reference`() {
        val credentials = router(vendor = "NETGEAR, Inc.").defaultRouterCredentials()

        assertEquals(1, credentials.size)
        assertEquals("admin", credentials.single().username)
        assertEquals("password", credentials.single().password)
    }

    @Test
    fun `unrecognized router and non-router do not receive a credential reference`() {
        assertTrue(router(vendor = "Example Networking").defaultRouterCredentials().isEmpty())
        assertTrue(router(vendor = "NETGEAR", isGateway = false, type = LanDeviceType.COMPUTER).defaultRouterCredentials().isEmpty())
    }

    private fun router(
        vendor: String,
        isGateway: Boolean = true,
        type: LanDeviceType = LanDeviceType.ROUTER
    ) = KnownLanDevice(
        id = "router",
        networkId = "network",
        ipAddress = "192.168.1.1",
        macAddress = null,
        advertisedName = null,
        hostname = null,
        vendor = vendor,
        model = null,
        inferredType = type,
        customName = null,
        customType = null,
        note = "",
        status = LanDeviceStatus.ONLINE,
        isGateway = isGateway,
        isThisDevice = false,
        sources = emptySet(),
        firstSeenEpochMs = 0,
        lastSeenEpochMs = 0
    )
}
