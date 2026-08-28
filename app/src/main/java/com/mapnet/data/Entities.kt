package com.mapnet.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mapnet.security.WifiSecurityType

@Entity(tableName = "access_points")
data class AccessPointEntity(
    @PrimaryKey val bssid: String,
    val ssid: String,
    val lastSeenEpochMs: Long,
    val signalDbm: Int,
    val frequencyMhz: Int,
    val channel: Int?,
    val securityType: WifiSecurityType,
    val requiresPassword: Boolean,
    val isEncrypted: Boolean,
    val securityCapabilities: String,
    val latitude: Double?,
    val longitude: Double?,
    val observationCount: Int
)
@Entity(
    tableName = "observations",
    foreignKeys = [ForeignKey(
        entity = AccessPointEntity::class,
        parentColumns = ["bssid"],
        childColumns = ["bssid"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bssid")]
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bssid: String,
    val ssid: String,
    val observedAtEpochMs: Long,
    val signalDbm: Int,
    val frequencyMhz: Int,
    val channel: Int?,
    val securityType: WifiSecurityType,
    val requiresPassword: Boolean,
    val isEncrypted: Boolean,
    val securityCapabilities: String,
    val latitude: Double?,
    val longitude: Double?,
    /** Radius in metres at the location provider's reported confidence level. */
    val locationAccuracyMeters: Float?,
    /** Android location provider used for this survey coordinate, when known. */
    val locationProvider: String?,
    /** Timestamp of the coordinate itself; separate from the Wi-Fi scan timestamp. */
    val locationTimestampEpochMs: Long?
)

/** A user-created grouping for saved Wi-Fi networks. */
@Entity(tableName = "network_lists")
data class NetworkListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long
)

/**
 * A list membership refers to a visible network (SSID), rather than one BSSID,
 * so a network remains grouped even when the radio used to represent it changes.
 */
@Entity(
    tableName = "network_list_members",
    primaryKeys = ["listId", "networkKey"],
    foreignKeys = [ForeignKey(
        entity = NetworkListEntity::class,
        parentColumns = ["id"],
        childColumns = ["listId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("listId"), Index("networkKey")]
)
data class NetworkListMemberEntity(
    val listId: String,
    val networkKey: String
)

/** Stable key for one row in the user-facing, SSID-grouped survey list. */
fun AccessPointEntity.networkListKey(): String {
    val normalizedName = ssid.trim()
    return if (normalizedName.equals("<Hidden SSID>", ignoreCase = true) || normalizedName.isBlank()) {
        "hidden:$bssid"
    } else {
        "ssid:${normalizedName.lowercase()}"
    }
}
