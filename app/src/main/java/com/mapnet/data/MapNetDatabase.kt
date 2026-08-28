package com.mapnet.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mapnet.security.WifiSecurityType
import kotlinx.coroutines.flow.Flow

class SecurityConverters {
    @TypeConverter fun toValue(type: WifiSecurityType): String = type.name
    @TypeConverter fun fromValue(value: String): WifiSecurityType =
        WifiSecurityType.entries.firstOrNull { it.name == value } ?: WifiSecurityType.UNKNOWN
}
@Dao
interface AccessPointDao {
    @Query("SELECT * FROM access_points ORDER BY lastSeenEpochMs DESC")
    fun observeAll(): Flow<List<AccessPointEntity>>

    @Query("SELECT * FROM access_points WHERE bssid = :bssid LIMIT 1")
    suspend fun findByBssid(bssid: String): AccessPointEntity?

    @Upsert
    suspend fun upsert(accessPoint: AccessPointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertObservation(observation: ObservationEntity)

    @Query("SELECT * FROM observations WHERE bssid = :bssid ORDER BY observedAtEpochMs DESC")
    fun observeHistory(bssid: String): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM observations WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY observedAtEpochMs DESC")
    fun observeLocatedObservations(): Flow<List<ObservationEntity>>

    @Query("DELETE FROM access_points WHERE bssid = :bssid")
    suspend fun deleteByBssid(bssid: String): Int

    @Query("DELETE FROM access_points WHERE LOWER(TRIM(ssid)) = LOWER(TRIM(:ssid))")
    suspend fun deleteByNetworkName(ssid: String): Int
}

@Database(
    entities = [
        AccessPointEntity::class,
        ObservationEntity::class,
        LanNetworkEntity::class,
        LanDeviceEntity::class,
        LanDeviceEventEntity::class,
        LanServiceEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(SecurityConverters::class)
abstract class MapNetDatabase : RoomDatabase() {
    abstract fun accessPointDao(): AccessPointDao
    abstract fun lanDeviceDao(): LanDeviceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE observations ADD COLUMN locationAccuracyMeters REAL")
                database.execSQL("ALTER TABLE observations ADD COLUMN locationProvider TEXT")
                database.execSQL("ALTER TABLE observations ADD COLUMN locationTimestampEpochMs INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lan_networks` (
                        `id` TEXT NOT NULL,
                        `ssid` TEXT NOT NULL,
                        `bssid` TEXT,
                        `subnet` TEXT NOT NULL,
                        `gatewayIp` TEXT,
                        `gatewayMac` TEXT,
                        `firstSeenEpochMs` INTEGER NOT NULL,
                        `lastSeenEpochMs` INTEGER NOT NULL,
                        `lastSuccessfulScanEpochMs` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_networks_gatewayMac` ON `lan_networks` (`gatewayMac`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_networks_bssid_subnet` ON `lan_networks` (`bssid`, `subnet`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_networks_ssid_subnet` ON `lan_networks` (`ssid`, `subnet`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lan_devices` (
                        `id` TEXT NOT NULL,
                        `networkId` TEXT NOT NULL,
                        `ipAddress` TEXT NOT NULL,
                        `macAddress` TEXT,
                        `advertisedName` TEXT,
                        `hostname` TEXT,
                        `vendor` TEXT,
                        `model` TEXT,
                        `inferredType` TEXT NOT NULL,
                        `customName` TEXT,
                        `customType` TEXT,
                        `note` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `isGateway` INTEGER NOT NULL,
                        `isThisDevice` INTEGER NOT NULL,
                        `discoverySources` TEXT NOT NULL,
                        `firstSeenEpochMs` INTEGER NOT NULL,
                        `lastSeenEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`networkId`) REFERENCES `lan_networks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_devices_networkId` ON `lan_devices` (`networkId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_devices_networkId_macAddress` ON `lan_devices` (`networkId`, `macAddress`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_devices_networkId_ipAddress` ON `lan_devices` (`networkId`, `ipAddress`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lan_device_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `occurredAtEpochMs` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `detail` TEXT NOT NULL,
                        FOREIGN KEY(`deviceId`) REFERENCES `lan_devices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_device_events_deviceId` ON `lan_device_events` (`deviceId`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lan_services` (
                        `deviceId` TEXT NOT NULL,
                        `protocol` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `serviceName` TEXT NOT NULL,
                        `url` TEXT,
                        `source` TEXT NOT NULL,
                        `lastSeenEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`, `protocol`, `port`),
                        FOREIGN KEY(`deviceId`) REFERENCES `lan_devices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_lan_services_deviceId` ON `lan_services` (`deviceId`)")
            }
        }
    }
}
