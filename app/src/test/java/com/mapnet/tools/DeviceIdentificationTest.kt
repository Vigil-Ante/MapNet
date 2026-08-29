package com.mapnet.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentificationTest {
    @Test
    fun `web identification keeps the first useful value and combines evidence`() {
        val first = DeviceIdentification(
            friendlyName = "Living room TV",
            source = DiscoverySource.WEB_FINGERPRINT,
            detail = "HTTP title: Living room TV"
        )
        val second = DeviceIdentification(
            model = "MediaServer/2.0",
            source = DiscoverySource.WEB_FINGERPRINT,
            detail = "Server: MediaServer/2.0"
        )

        val merged = first.merge(second)

        assertEquals("Living room TV", merged.friendlyName)
        assertEquals("MediaServer/2.0", merged.model)
        assertEquals("HTTP title: Living room TV · Server: MediaServer/2.0", merged.detail)
    }
}
