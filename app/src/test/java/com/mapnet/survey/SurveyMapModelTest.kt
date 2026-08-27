package com.mapnet.survey

import com.mapnet.data.ObservationEntity
import com.mapnet.security.WifiSecurityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyMapModelTest {
    @Test
    fun `groups radios from the same scan into one phone survey point`() {
        val clusters = listOf(
            observation(bssid = "00:00:00:00:00:01", ssid = "Guest", securityType = WifiSecurityType.OPEN),
            observation(bssid = "00:00:00:00:00:02", ssid = "Office", securityType = WifiSecurityType.WPA2)
        ).toSurveyLocationClusters()

        assertEquals(1, clusters.size)
        assertEquals(2, clusters.single().observationCount)
        assertEquals(2, clusters.single().bssidCount)
        assertEquals(2, clusters.single().ssidCount)
        assertEquals(1, clusters.single().openNetworkCount)
        assertEquals(12f, clusters.single().locationAccuracyMeters)
    }

    @Test
    fun `keeps separate scan events at the same coordinate`() {
        val clusters = listOf(
            observation(observedAt = 1_000L),
            observation(bssid = "00:00:00:00:00:02", observedAt = 2_000L, accuracy = null)
        ).toSurveyLocationClusters()

        assertEquals(2, clusters.size)
        assertEquals(2_000L, clusters.first().observedAtEpochMs)
        assertNull(clusters.first().locationAccuracyMeters)
    }

    private fun observation(
        bssid: String = "00:00:00:00:00:01",
        ssid: String = "Example",
        observedAt: Long = 1_000L,
        securityType: WifiSecurityType = WifiSecurityType.WPA2,
        accuracy: Float? = 12f
    ) = ObservationEntity(
        bssid = bssid,
        ssid = ssid,
        observedAtEpochMs = observedAt,
        signalDbm = -50,
        frequencyMhz = 5180,
        channel = 36,
        securityType = securityType,
        requiresPassword = securityType != WifiSecurityType.OPEN,
        isEncrypted = securityType != WifiSecurityType.OPEN,
        securityCapabilities = "[WPA2-PSK-CCMP][ESS]",
        latitude = 40.7128,
        longitude = -74.0060,
        locationAccuracyMeters = accuracy,
        locationProvider = "gps",
        locationTimestampEpochMs = observedAt
    )
}
