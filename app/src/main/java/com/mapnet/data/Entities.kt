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
    val longitude: Double?
)

