package com.mapnet.survey

import com.mapnet.data.ObservationEntity
import com.mapnet.security.WifiSecurityType

/**
 * One marker represents one Wi-Fi survey at the phone's reported coordinate.
 * It intentionally is not an AP position: a scan can only establish that a
 * radio was heard from this point, not where its transmitter is installed.
 */
data class SurveyLocationCluster(
    val key: String,
    val latitude: Double,
    val longitude: Double,
    val observedAtEpochMs: Long,
    val observationCount: Int,
    val bssidCount: Int,
    val ssidCount: Int,
    val openNetworkCount: Int,
    val averageSignalDbm: Int,
    val locationAccuracyMeters: Float?,
    val locationProvider: String?
)

fun List<ObservationEntity>.toSurveyLocationClusters(): List<SurveyLocationCluster> =
    asSequence()
        .filter { observation ->
            observation.latitude != null && observation.longitude != null &&
                observation.latitude in -90.0..90.0 && observation.longitude in -180.0..180.0
        }
        .groupBy { observation ->
            SurveyLocationKey(
                observedAtEpochMs = observation.observedAtEpochMs,
                latitude = observation.latitude!!,
                longitude = observation.longitude!!
            )
        }
        .map { (key, observations) ->
            SurveyLocationCluster(
                key = "${key.observedAtEpochMs}:${key.latitude}:${key.longitude}",
                latitude = key.latitude,
                longitude = key.longitude,
                observedAtEpochMs = key.observedAtEpochMs,
                observationCount = observations.size,
                bssidCount = observations.map { it.bssid }.distinct().size,
                ssidCount = observations
                    .map { it.ssid.trim() }
                    .filter { it.isNotBlank() && !it.equals("<Hidden SSID>", ignoreCase = true) }
                    .distinct()
                    .size,
                openNetworkCount = observations.count { it.securityType == WifiSecurityType.OPEN },
                averageSignalDbm = observations.map { it.signalDbm }.average().toInt(),
                // Use the least precise result if data from a batch disagrees.
                locationAccuracyMeters = observations.mapNotNull { it.locationAccuracyMeters }.maxOrNull(),
                locationProvider = observations.mapNotNull { it.locationProvider }.firstOrNull()
            )
        }
        .sortedByDescending { it.observedAtEpochMs }
        .toList()

private data class SurveyLocationKey(
    val observedAtEpochMs: Long,
    val latitude: Double,
    val longitude: Double
)
