package com.mapnet.survey

import com.mapnet.data.AccessPointEntity
import com.mapnet.data.networkListKey
import com.mapnet.security.WifiSecurityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkNameCollapseTest {

    @Test
    fun `network list key is case insensitive for visible SSIDs`() {
        assertEquals(
            "ssid:home wifi",
            accessPoint("00:00:00:00:00:01", " Home WiFi ", 100, -50).networkListKey()
        )
    }

    @Test
    fun visibleNetworkNamesCollapseToTheirMostRecentObservation() {
        val older = accessPoint(bssid = "00:00:00:00:00:01", ssid = "Coffee Shop", seenAt = 100, signal = -40)
        val newer = accessPoint(bssid = "00:00:00:00:00:02", ssid = "Coffee Shop", seenAt = 200, signal = -70)
        val other = accessPoint(bssid = "00:00:00:00:00:03", ssid = "Library", seenAt = 150, signal = -50)

        val displayed = listOf(older, newer, other).collapseByNetworkName()

        assertEquals(2, displayed.size)
        assertTrue(displayed.any { it.bssid == newer.bssid })
        assertTrue(displayed.any { it.bssid == other.bssid })
    }

    @Test
    fun hiddenNetworksRemainSeparateBecauseTheirNamesAreUnavailable() {
        val first = accessPoint("00:00:00:00:00:01", "<Hidden SSID>", 100, -50)
        val second = accessPoint("00:00:00:00:00:02", "<Hidden SSID>", 200, -50)

        assertEquals(2, listOf(first, second).collapseByNetworkName().size)
    }

    private fun accessPoint(bssid: String, ssid: String, seenAt: Long, signal: Int) = AccessPointEntity(
        bssid = bssid,
        ssid = ssid,
        lastSeenEpochMs = seenAt,
        signalDbm = signal,
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
}
