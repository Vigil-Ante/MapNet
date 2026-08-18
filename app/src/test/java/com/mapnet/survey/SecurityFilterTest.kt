package com.mapnet.survey

import com.mapnet.data.AccessPointEntity
import com.mapnet.security.WifiSecurityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityFilterTest {
    private fun ap(bssid: String, ssid: String, type: WifiSecurityType) = AccessPointEntity(
        bssid = bssid, ssid = ssid, lastSeenEpochMs = 1, signalDbm = -55, frequencyMhz = 5180,
        channel = 36, securityType = type, requiresPassword = type != WifiSecurityType.OPEN && type != WifiSecurityType.OWE,
        isEncrypted = type != WifiSecurityType.OPEN, securityCapabilities = "[ESS]", latitude = null,
        longitude = null, observationCount = 1
    )

    @Test fun `open filter returns only traditionally open BSSIDs`() {
        val openA = ap("00:11:22:33:44:55", "Guest", WifiSecurityType.OPEN)
        val openB = ap("00:11:22:33:44:56", "Guest", WifiSecurityType.OPEN)
        val owe = ap("00:11:22:33:44:57", "Airport", WifiSecurityType.OWE)
        assertEquals(listOf(openA, openB), listOf(openA, openB, owe).filter(SecurityFilter.OPEN::includes))
    }

    @Test fun `secured filter excludes open but keeps OWE`() {
        val open = ap("00:11:22:33:44:55", "", WifiSecurityType.OPEN)
        val owe = ap("00:11:22:33:44:56", "", WifiSecurityType.OWE)
        assertFalse(SecurityFilter.SECURED.includes(open))
        assertTrue(SecurityFilter.SECURED.includes(owe))
    }

    @Test fun `hidden SSID can be filtered using its capabilities-derived type`() {
        val hiddenOpen = ap("00:11:22:33:44:55", "<Hidden SSID>", WifiSecurityType.OPEN)
        val hiddenSecured = ap("00:11:22:33:44:56", "<Hidden SSID>", WifiSecurityType.WPA2)
        assertTrue(SecurityFilter.OPEN.includes(hiddenOpen))
        assertFalse(SecurityFilter.OPEN.includes(hiddenSecured))
    }
}

