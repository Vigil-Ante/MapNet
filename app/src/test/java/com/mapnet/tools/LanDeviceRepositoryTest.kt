package com.mapnet.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mapnet.data.MapNetDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanDeviceRepositoryTest {
    private lateinit var database: MapNetDatabase
    private lateinit var repository: LanDeviceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MapNetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LanDeviceRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `completed scans preserve edits and mark only absent devices offline`() = runBlocking {
        val connection = ConnectedWifiDetails(
            ssid = "Test Wi-Fi",
            bssid = "AA:BB:CC:DD:EE:01",
            ipv4Addresses = listOf("192.168.50.10"),
            subnet = "192.168.50.0/24",
            gateway = "192.168.50.1",
            dnsServers = listOf("192.168.50.1")
        )
        val firstScan = LocalNetworkMap(
            subnet = "192.168.50.0/24",
            scannedAddressCount = 253,
            devices = listOf(
                discovered("192.168.50.1", "00:11:22:33:44:55", gateway = true),
                discovered("192.168.50.20", "10:20:30:40:50:60", name = "Living room TV")
            )
        )
        val networkId = repository.persistSuccessfulScan(connection, firstScan, nowEpochMs = 1_000)
        val devicesAfterFirstScan = repository.observeDevices(networkId).first()
        val tv = devicesAfterFirstScan.single { it.ipAddress == "192.168.50.20" }
        repository.editDevice(tv.id, "Movie room", LanDeviceType.TV_STREAMER, "Do not unplug", 1_500)

        val secondScan = firstScan.copy(devices = listOf(firstScan.devices.first()))
        repository.persistSuccessfulScan(connection, secondScan, nowEpochMs = 2_000)
        val devicesAfterSecondScan = repository.observeDevices(networkId).first()
        val rememberedTv = devicesAfterSecondScan.single { it.id == tv.id }

        assertEquals(LanDeviceStatus.OFFLINE, rememberedTv.status)
        assertEquals("Movie room", rememberedTv.customName)
        assertEquals(LanDeviceType.TV_STREAMER, rememberedTv.customType)
        assertEquals("Do not unplug", rememberedTv.note)
        assertEquals(LanDeviceStatus.ONLINE, devicesAfterSecondScan.single { it.isGateway }.status)
        assertTrue(repository.observeEvents(tv.id).first().any { it.type == LanDeviceEventType.OFFLINE })
    }

    @Test
    fun `MAC identity survives an IP address change and records the event`() = runBlocking {
        val connection = ConnectedWifiDetails(
            ssid = "Test Wi-Fi",
            bssid = "AA:BB:CC:DD:EE:01",
            ipv4Addresses = listOf("192.168.50.10"),
            subnet = "192.168.50.0/24",
            gateway = "192.168.50.1",
            dnsServers = emptyList()
        )
        val mac = "10:20:30:40:50:60"
        val networkId = repository.persistSuccessfulScan(
            connection,
            LocalNetworkMap("192.168.50.0/24", 253, listOf(discovered("192.168.50.20", mac))),
            nowEpochMs = 1_000
        )
        val firstDevice = repository.observeDevices(networkId).first().single()
        repository.persistSuccessfulScan(
            connection,
            LocalNetworkMap("192.168.50.0/24", 253, listOf(discovered("192.168.50.44", mac))),
            nowEpochMs = 2_000
        )
        val movedDevice = repository.observeDevices(networkId).first().single()

        assertEquals(firstDevice.id, movedDevice.id)
        assertEquals("192.168.50.44", movedDevice.ipAddress)
        assertNotNull(repository.findKnownNetwork(connection))
        assertTrue(repository.observeEvents(movedDevice.id).first().any { it.type == LanDeviceEventType.IP_CHANGED })
    }

    private fun discovered(
        ip: String,
        mac: String,
        gateway: Boolean = false,
        name: String? = null
    ) = DiscoveredNetworkDevice(
        ipv4Address = ip,
        macAddress = mac,
        advertisedName = name,
        inferredType = if (gateway) LanDeviceType.ROUTER else LanDeviceType.UNKNOWN,
        isGateway = gateway,
        sources = setOf(DiscoverySource.ARP)
    )
}
