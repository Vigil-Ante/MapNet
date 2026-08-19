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

    @Query("DELETE FROM access_points WHERE bssid = :bssid")
    suspend fun deleteByBssid(bssid: String): Int

    @Query("DELETE FROM access_points WHERE LOWER(TRIM(ssid)) = LOWER(TRIM(:ssid))")
    suspend fun deleteByNetworkName(ssid: String): Int
}

@Database(entities = [AccessPointEntity::class, ObservationEntity::class], version = 1, exportSchema = false)
@TypeConverters(SecurityConverters::class)
abstract class MapNetDatabase : RoomDatabase() {
    abstract fun accessPointDao(): AccessPointDao
}
