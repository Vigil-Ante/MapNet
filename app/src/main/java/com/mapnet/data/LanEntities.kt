package com.mapnet.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "lan_networks",
    indices = [
        Index("gatewayMac"),
        Index(value = ["bssid", "subnet"]),
        Index(value = ["ssid", "subnet"])
    ]
)
data class LanNetworkEntity(
    @PrimaryKey val id: String,
    val ssid: String,
    val bssid: String?,
    val subnet: String,
    val gatewayIp: String?,
    val gatewayMac: String?,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val lastSuccessfulScanEpochMs: Long?
)

@Entity(
    tableName = "lan_devices",
    foreignKeys = [ForeignKey(
        entity = LanNetworkEntity::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("networkId"),
        Index(value = ["networkId", "macAddress"]),
        Index(value = ["networkId", "ipAddress"])
    ]
)
data class LanDeviceEntity(
    @PrimaryKey val id: String,
    val networkId: String,
    val ipAddress: String,
    val macAddress: String?,
    val advertisedName: String?,
    val hostname: String?,
    val vendor: String?,
    val model: String?,
    val inferredType: String,
    val customName: String?,
    val customType: String?,
    val note: String,
    val status: String,
    val isGateway: Boolean,
    val isThisDevice: Boolean,
    val discoverySources: String,
    val identificationSource: String? = null,
    val identificationDetail: String? = null,
    val identifiedAtEpochMs: Long? = null,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long
)

@Entity(
    tableName = "lan_device_events",
    foreignKeys = [ForeignKey(
        entity = LanDeviceEntity::class,
        parentColumns = ["id"],
        childColumns = ["deviceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("deviceId")]
)
data class LanDeviceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val occurredAtEpochMs: Long,
    val type: String,
    val detail: String
)

@Entity(
    tableName = "lan_services",
    primaryKeys = ["deviceId", "protocol", "port"],
    foreignKeys = [ForeignKey(
        entity = LanDeviceEntity::class,
        parentColumns = ["id"],
        childColumns = ["deviceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("deviceId")]
)
data class LanServiceEntity(
    val deviceId: String,
    val protocol: String,
    val port: Int,
    val serviceName: String,
    val url: String?,
    val source: String,
    val lastSeenEpochMs: Long
)

@Dao
interface LanDeviceDao {
    @Query("SELECT * FROM lan_networks WHERE id = :id LIMIT 1")
    suspend fun getNetwork(id: String): LanNetworkEntity?

    @Query("SELECT * FROM lan_networks WHERE id = :id LIMIT 1")
    fun observeNetwork(id: String): Flow<LanNetworkEntity?>

    @Query("SELECT * FROM lan_networks WHERE gatewayMac = :gatewayMac AND subnet = :subnet ORDER BY lastSeenEpochMs DESC LIMIT 1")
    suspend fun findNetworkByGatewayMacAndSubnet(gatewayMac: String, subnet: String): LanNetworkEntity?

    @Query("SELECT * FROM lan_networks WHERE bssid = :bssid AND subnet = :subnet ORDER BY lastSeenEpochMs DESC LIMIT 1")
    suspend fun findNetworkByBssidAndSubnet(bssid: String, subnet: String): LanNetworkEntity?

    @Query("SELECT * FROM lan_networks WHERE ssid = :ssid AND subnet = :subnet ORDER BY lastSeenEpochMs DESC LIMIT 1")
    suspend fun findNetworkBySsidAndSubnet(ssid: String, subnet: String): LanNetworkEntity?

    @Upsert
    suspend fun upsertNetwork(network: LanNetworkEntity)

    @Query("SELECT * FROM lan_devices WHERE networkId = :networkId")
    fun observeDevices(networkId: String): Flow<List<LanDeviceEntity>>

    @Query("SELECT * FROM lan_devices WHERE networkId = :networkId")
    suspend fun getDevices(networkId: String): List<LanDeviceEntity>

    @Query("SELECT * FROM lan_devices WHERE id = :deviceId LIMIT 1")
    fun observeDevice(deviceId: String): Flow<LanDeviceEntity?>

    @Query("SELECT * FROM lan_devices WHERE id = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): LanDeviceEntity?

    @Query("SELECT * FROM lan_devices WHERE networkId = :networkId AND macAddress = :macAddress LIMIT 1")
    suspend fun findDeviceByMac(networkId: String, macAddress: String): LanDeviceEntity?

    @Query("SELECT * FROM lan_devices WHERE networkId = :networkId AND ipAddress = :ipAddress LIMIT 1")
    suspend fun findDeviceByIp(networkId: String, ipAddress: String): LanDeviceEntity?

    @Query("SELECT * FROM lan_devices WHERE networkId = :networkId AND isThisDevice = 1 LIMIT 1")
    suspend fun findThisDevice(networkId: String): LanDeviceEntity?

    @Upsert
    suspend fun upsertDevice(device: LanDeviceEntity)

    @Query("DELETE FROM lan_devices WHERE id = :deviceId")
    suspend fun deleteDevice(deviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: LanDeviceEventEntity)

    @Query("SELECT * FROM lan_device_events WHERE deviceId = :deviceId ORDER BY occurredAtEpochMs DESC, id DESC")
    fun observeEvents(deviceId: String): Flow<List<LanDeviceEventEntity>>

    @Query("UPDATE lan_device_events SET deviceId = :targetDeviceId WHERE deviceId = :sourceDeviceId")
    suspend fun reassignEvents(sourceDeviceId: String, targetDeviceId: String)

    @Query("SELECT * FROM lan_services WHERE deviceId = :deviceId ORDER BY port")
    fun observeServices(deviceId: String): Flow<List<LanServiceEntity>>

    @Query("SELECT * FROM lan_services WHERE deviceId = :deviceId ORDER BY port")
    suspend fun getServices(deviceId: String): List<LanServiceEntity>

    @Query("DELETE FROM lan_services WHERE deviceId = :deviceId AND source = :source")
    suspend fun deleteServicesBySource(deviceId: String, source: String)

    @Upsert
    suspend fun upsertServices(services: List<LanServiceEntity>)
}
