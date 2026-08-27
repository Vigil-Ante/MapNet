package com.mapnet.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsSetupTest {
    @Test
    fun `recognizes configured and placeholder Google Maps keys`() {
        assertTrue("AIzaExampleKey".isConfiguredGoogleMapsKey())
        assertFalse((null as String?).isConfiguredGoogleMapsKey())
        assertFalse("".isConfiguredGoogleMapsKey())
        assertFalse("MAPS_API_KEY_NOT_CONFIGURED".isConfiguredGoogleMapsKey())
        assertFalse("YOUR_GOOGLE_MAPS_ANDROID_API_KEY".isConfiguredGoogleMapsKey())
    }
}
