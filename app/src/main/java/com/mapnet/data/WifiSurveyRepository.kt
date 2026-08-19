package com.mapnet.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.room.withTransaction
import com.mapnet.security.WifiSecurityClassifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class WifiScanOutcome(
    val observationCount: Int,
    val hasFreshResults: Boolean
)

class WifiSurveyRepository(
    private val context: Context,
    private val database: MapNetDatabase
) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    fun observeAccessPoints(): Flow<List<AccessPointEntity>> = database.accessPointDao().observeAll()

    fun observeHistory(bssid: String): Flow<List<ObservationEntity>> =
        database.accessPointDao().observeHistory(bssid)

    /** Removes the visible network and all of its locally stored observations. */
    suspend fun deleteVisibleNetwork(accessPoint: AccessPointEntity): Int = database.withTransaction {
        val dao = database.accessPointDao()
        if (accessPoint.ssid.isHiddenNetworkName()) {
            dao.deleteByBssid(accessPoint.bssid)
        } else {
            dao.deleteByNetworkName(accessPoint.ssid)
        }
    }

    /** Starts a platform scan and saves every returned BSSID as an individual AP. */
    @SuppressLint("MissingPermission")
    suspend fun scanAndPersist(location: Location?): WifiScanOutcome {
        val scan = awaitScanResults()
        if (!scan.hasFreshResults) return WifiScanOutcome(observationCount = 0, hasFreshResults = false)

        persist(scan.results, location)
        return WifiScanOutcome(observationCount = scan.results.size, hasFreshResults = true)
    }

    suspend fun persist(results: List<ScanResult>, location: Location?) = database.withTransaction {
        val dao = database.accessPointDao()
        val seenAt = System.currentTimeMillis()
        results.filter { it.BSSID.isNotBlank() }.forEach { result ->
            val profile = WifiSecurityClassifier.classify(result.capabilities)
            val old = dao.findByBssid(result.BSSID)
            val ssid = result.SSID.takeUnless { it.isNullOrBlank() } ?: "<Hidden SSID>"
            val channel = result.frequency.toWifiChannel()
            dao.upsert(
                AccessPointEntity(
                    bssid = result.BSSID,
                    ssid = ssid,
                    lastSeenEpochMs = seenAt,
                    signalDbm = result.level,
                    frequencyMhz = result.frequency,
                    channel = channel,
                    securityType = profile.type,
                    requiresPassword = profile.requiresPassword,
                    isEncrypted = profile.isEncrypted,
                    securityCapabilities = result.capabilities.orEmpty(),
                    latitude = location?.latitude ?: old?.latitude,
                    longitude = location?.longitude ?: old?.longitude,
                    observationCount = (old?.observationCount ?: 0) + 1
                )
            )
            dao.insertObservation(
                ObservationEntity(
                    bssid = result.BSSID,
                    ssid = ssid,
                    observedAtEpochMs = seenAt,
                    signalDbm = result.level,
                    frequencyMhz = result.frequency,
                    channel = channel,
                    securityType = profile.type,
                    requiresPassword = profile.requiresPassword,
                    isEncrypted = profile.isEncrypted,
                    securityCapabilities = result.capabilities.orEmpty(),
                    latitude = location?.latitude,
                    longitude = location?.longitude
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitScanResults(): ScanResults = withTimeoutOrNull(SCAN_RESULT_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var registered = false
            lateinit var receiver: BroadcastReceiver
            fun unregister() {
                if (registered) {
                    runCatching { context.unregisterReceiver(receiver) }
                    registered = false
                }
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && continuation.isActive) {
                        val hasFreshResults = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        unregister()
                        continuation.resume(ScanResults(wifiManager.scanResults, hasFreshResults))
                    }
                }
            }
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            registered = true
            continuation.invokeOnCancellation { unregister() }
            if (!wifiManager.startScan() && continuation.isActive) {
                unregister()
                continuation.resume(ScanResults(emptyList(), hasFreshResults = false))
            }
        }
    } ?: ScanResults(emptyList(), hasFreshResults = false)

    private data class ScanResults(val results: List<ScanResult>, val hasFreshResults: Boolean)

    private companion object {
        const val SCAN_RESULT_TIMEOUT_MS = 15_000L
    }
}

private fun Int.toWifiChannel(): Int? = when {
    this in 2412..2472 -> (this - 2407) / 5
    this == 2484 -> 14
    this in 5170..5895 -> (this - 5000) / 5
    this in 5955..7115 -> (this - 5950) / 5
    else -> null
}

private fun String.isHiddenNetworkName(): Boolean =
    trim().equals("<Hidden SSID>", ignoreCase = true) || isBlank()
