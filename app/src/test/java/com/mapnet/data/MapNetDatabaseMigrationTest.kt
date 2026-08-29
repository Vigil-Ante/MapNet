package com.mapnet.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapNetDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "mapnet-migration-test.db"
    private var openedDatabase: MapNetDatabase? = null

    @After
    fun cleanUp() {
        openedDatabase?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `migration 2 to 5 keeps survey data LAN inventory custom lists and recognition fields`() = runBlocking {
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { database ->
            database.execSQL(
                """
                CREATE TABLE `access_points` (
                    `bssid` TEXT NOT NULL,
                    `ssid` TEXT NOT NULL,
                    `lastSeenEpochMs` INTEGER NOT NULL,
                    `signalDbm` INTEGER NOT NULL,
                    `frequencyMhz` INTEGER NOT NULL,
                    `channel` INTEGER,
                    `securityType` TEXT NOT NULL,
                    `requiresPassword` INTEGER NOT NULL,
                    `isEncrypted` INTEGER NOT NULL,
                    `securityCapabilities` TEXT NOT NULL,
                    `latitude` REAL,
                    `longitude` REAL,
                    `observationCount` INTEGER NOT NULL,
                    PRIMARY KEY(`bssid`)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE `observations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `bssid` TEXT NOT NULL,
                    `ssid` TEXT NOT NULL,
                    `observedAtEpochMs` INTEGER NOT NULL,
                    `signalDbm` INTEGER NOT NULL,
                    `frequencyMhz` INTEGER NOT NULL,
                    `channel` INTEGER,
                    `securityType` TEXT NOT NULL,
                    `requiresPassword` INTEGER NOT NULL,
                    `isEncrypted` INTEGER NOT NULL,
                    `securityCapabilities` TEXT NOT NULL,
                    `latitude` REAL,
                    `longitude` REAL,
                    `locationAccuracyMeters` REAL,
                    `locationProvider` TEXT,
                    `locationTimestampEpochMs` INTEGER,
                    FOREIGN KEY(`bssid`) REFERENCES `access_points`(`bssid`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL("CREATE INDEX `index_observations_bssid` ON `observations` (`bssid`)")
            database.execSQL(
                """
                INSERT INTO `access_points` VALUES (
                    'AA:BB:CC:DD:EE:FF', 'Saved network', 1000, -55, 2412, 1,
                    'WPA2_PERSONAL', 1, 1, '[WPA2-PSK-CCMP][ESS]', NULL, NULL, 1
                )
                """.trimIndent()
            )
            database.version = 2
        }

        val room = Room.databaseBuilder(context, MapNetDatabase::class.java, databaseName)
            .addMigrations(
                MapNetDatabase.MIGRATION_2_3,
                MapNetDatabase.MIGRATION_3_4,
                MapNetDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()
        openedDatabase = room
        room.openHelper.writableDatabase

        assertNotNull(room.accessPointDao().findByBssid("AA:BB:CC:DD:EE:FF"))
        room.lanDeviceDao().upsertNetwork(
            LanNetworkEntity(
                id = "network",
                ssid = "Saved network",
                bssid = "AA:BB:CC:DD:EE:FF",
                subnet = "192.168.1.0/24",
                gatewayIp = "192.168.1.1",
                gatewayMac = null,
                firstSeenEpochMs = 2_000,
                lastSeenEpochMs = 2_000,
                lastSuccessfulScanEpochMs = 2_000
            )
        )
        assertEquals("Saved network", room.lanDeviceDao().getNetwork("network")?.ssid)
        room.networkListDao().insertList(NetworkListEntity("favorites", "Favorites", 3_000))
        room.networkListDao().insertMembers(
            listOf(NetworkListMemberEntity("favorites", "ssid:saved network"))
        )
        assertEquals("Favorites", room.networkListDao().findByName("favorites")?.name)
    }
}
