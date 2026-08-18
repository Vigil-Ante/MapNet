package com.mapnet.connection

import com.mapnet.security.WifiSecurityType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiConnectionPolicyTest {
    @Test
    fun directRequestsCoverOpenAndPersonalNetworks() {
        assertTrue(WifiSecurityType.OPEN.canConnectWithMapNet())
        assertTrue(WifiSecurityType.WPA2.canConnectWithMapNet())
        assertTrue(WifiSecurityType.WPA3.canConnectWithMapNet())
        assertTrue(WifiSecurityType.WPA2_WPA3_TRANSITION.canConnectWithMapNet())
    }

    @Test
    fun advancedAndLegacyNetworksUseAndroidSettings() {
        assertFalse(WifiSecurityType.WEP.canConnectWithMapNet())
        assertFalse(WifiSecurityType.OWE.canConnectWithMapNet())
        assertFalse(WifiSecurityType.ENTERPRISE.canConnectWithMapNet())
        assertFalse(WifiSecurityType.UNKNOWN.canConnectWithMapNet())
    }

    @Test
    fun onlyOpenNetworksSkipPasswordPrompt() {
        assertFalse(WifiSecurityType.OPEN.needsPassphrase())
        assertTrue(WifiSecurityType.WPA2.needsPassphrase())
    }
}
