package com.mapnet.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSecurityClassifierTest {
    @Test fun `ordinary ESS network is open and unencrypted`() {
        val profile = WifiSecurityClassifier.classify("[ESS]")
        assertEquals(WifiSecurityType.OPEN, profile.type)
        assertFalse(profile.requiresPassword)
        assertFalse(profile.isEncrypted)
    }

    @Test fun `WEP is classified`() = assertType("[WEP][ESS]", WifiSecurityType.WEP)
    @Test fun `WPA is classified`() = assertType("[WPA-PSK-TKIP][ESS]", WifiSecurityType.WPA)
    @Test fun `WPA2 is classified`() = assertType("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]", WifiSecurityType.WPA2)
    @Test fun `WPA3 is classified`() = assertType("[RSN-SAE-CCMP][ESS]", WifiSecurityType.WPA3)

    @Test fun `WPA2 WPA3 transition is classified`() {
        assertType("[RSN-PSK+SAE-CCMP][ESS]", WifiSecurityType.WPA2_WPA3_TRANSITION)
    }

    @Test fun `enterprise is classified`() = assertType("[WPA2-EAP-CCMP][ESS]", WifiSecurityType.ENTERPRISE)

    @Test fun `OWE remains encrypted despite having no password`() {
        val profile = WifiSecurityClassifier.classify("[RSN-OWE-CCMP][ESS]")
        assertEquals(WifiSecurityType.OWE, profile.type)
        assertFalse(profile.requiresPassword)
        assertTrue(profile.isEncrypted)
    }

    @Test fun `unrecognized capabilities are unknown`() = assertType("[FUTURE-AKM][ESSLESS]", WifiSecurityType.UNKNOWN)

    private fun assertType(raw: String, expected: WifiSecurityType) {
        assertEquals(expected, WifiSecurityClassifier.classify(raw).type)
    }
}

