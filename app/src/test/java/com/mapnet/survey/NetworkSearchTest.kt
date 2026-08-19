package com.mapnet.survey

import com.mapnet.data.AccessPointEntity
import com.mapnet.security.WifiSecurityType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSearchTest {
    private val accessPoint = AccessPointEntity(
        bssid = "00:11:22:33:44:55",
        ssid = "Coffee Shop Guest",
        lastSeenEpochMs = 1,
        signalDbm = -45,
        frequencyMhz = 2412,
        channel = 1,
        securityType = WifiSecurityType.OPEN,
        requiresPassword = false,
        isEncrypted = false,
        securityCapabilities = "",
        latitude = null,
        longitude = null,
        observationCount = 1
    )

    @Test
    fun searchMatchesWifiNameWithoutCaseSensitivity() {
        assertTrue(accessPoint.matchesSearch("coffee"))
        assertTrue(accessPoint.matchesSearch("GUEST"))
    }

    @Test
    fun searchMatchesBssidAndRejectsUnrelatedText() {
        assertTrue(accessPoint.matchesSearch("22:33"))
        assertFalse(accessPoint.matchesSearch("library"))
    }
}
