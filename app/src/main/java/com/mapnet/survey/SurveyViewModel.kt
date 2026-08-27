package com.mapnet.survey

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapnet.data.AccessPointEntity
import com.mapnet.data.ObservationEntity
import com.mapnet.data.WifiSurveyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyViewModel(private val repository: WifiSurveyRepository) : ViewModel() {
    private val selectedFilter = MutableStateFlow(SecurityFilter.ALL)
    private val search = MutableStateFlow("")
    private val selectedBssid = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false)
    private val continuousScanning = MutableStateFlow(false)
    private val scanStatus = MutableStateFlow<String?>(null)
    private var continuousScanJob: Job? = null

    val filter: StateFlow<SecurityFilter> = selectedFilter
    val searchQuery: StateFlow<String> = search
    val isScanning: StateFlow<Boolean> = scanning
    val isContinuousScanning: StateFlow<Boolean> = continuousScanning
    val status: StateFlow<String?> = scanStatus
    private val rawAccessPoints = repository.observeAccessPoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val rawLocatedObservations = repository.observeLocatedObservations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accessPoints = rawAccessPoints.map { it.collapseByNetworkName() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val filteredAccessPoints = combine(accessPoints, selectedFilter, search) { accessPoints, filter, query ->
        accessPoints.filter { accessPoint ->
            filter.includes(accessPoint) && accessPoint.matchesSearch(query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Historical observations used by the map. These are deliberately not
     * collapsed by SSID because a single map marker summarizes a survey event. */
    val mapObservations = combine(rawLocatedObservations, selectedFilter, search) { observations, filter, query ->
        observations.filter { observation ->
            filter.includes(observation.securityType) && observation.matchesSearch(query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val summary = accessPoints.map { it.securitySummary() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecuritySummary(0, 0))
    val selectedAccessPoint = combine(rawAccessPoints, selectedBssid) { list, bssid ->
        list.firstOrNull { it.bssid == bssid }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val selectedHistory = selectedBssid.flatMapLatest { bssid ->
        if (bssid == null) flowOf(emptyList()) else repository.observeHistory(bssid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ObservationEntity>())

    fun selectFilter(filter: SecurityFilter) { selectedFilter.value = filter }
    fun setSearchQuery(query: String) { search.value = query }
    fun showDetails(bssid: String) { selectedBssid.value = bssid }
    fun dismissDetails() { selectedBssid.value = null }
    fun clearStatus() { scanStatus.value = null }

    fun performScan(locationProvider: suspend () -> Location?) {
        if (scanning.value || continuousScanning.value) return
        viewModelScope.launch { scanOnce(locationProvider, showStatus = true) }
    }

    fun startContinuousScan(locationProvider: suspend () -> Location?) {
        if (continuousScanJob?.isActive == true) return
        continuousScanning.value = true
        scanStatus.value = null
        scanStatus.value = "Continuous scan enabled. Android may throttle scan requests."
        continuousScanJob = viewModelScope.launch {
            while (true) {
                val hasFreshResults = scanOnce(locationProvider, showStatus = false)
                // Android exposes no callback for its scan-throttle window. When it
                // rejects a request, retry shortly so the next scan begins as soon
                // as the platform accepts it again.
                delay(if (hasFreshResults == false) THROTTLE_RETRY_INTERVAL_MS else CONTINUOUS_SCAN_INTERVAL_MS)
            }
        }
    }

    fun stopContinuousScan() {
        continuousScanJob?.cancel()
        continuousScanJob = null
        continuousScanning.value = false
        scanStatus.value = "Continuous scan stopped."
    }

    fun deleteAccessPoint(accessPoint: AccessPointEntity) = viewModelScope.launch {
        runCatching { repository.deleteVisibleNetwork(accessPoint) }
            .onSuccess { removedCount ->
                dismissDetails()
                scanStatus.value = if (removedCount == 1) {
                    "Deleted ${accessPoint.ssid} and its local observation history. A later scan can add it again."
                } else {
                    "Deleted $removedCount saved access points named ${accessPoint.ssid}. A later scan can add them again."
                }
            }
            .onFailure { error ->
                scanStatus.value = "Could not delete ${accessPoint.ssid}: ${error.message ?: "database error"}"
            }
    }

    private suspend fun scanOnce(locationProvider: suspend () -> Location?, showStatus: Boolean): Boolean? {
        if (scanning.value) return null
        scanning.value = true
        try {
            // A fresh coordinate makes the survey map useful; the provider has a
            // short timeout and falls back to the last known coordinate.
            val result = repository.scanAndPersist(locationProvider())
            if (showStatus) {
                scanStatus.value = if (result.hasFreshResults) {
                    "${result.observationCount} network observations saved"
                } else {
                    "Android did not allow a fresh Wi-Fi scan yet. Try again shortly."
                }
            }
            return result.hasFreshResults
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (showStatus) {
                scanStatus.value = "Could not run Wi-Fi survey: ${error.message ?: "permission or platform restriction"}"
            }
            return null
        } finally {
            scanning.value = false
        }
    }

    private companion object {
        const val CONTINUOUS_SCAN_INTERVAL_MS = 30_000L
        const val THROTTLE_RETRY_INTERVAL_MS = 5_000L
    }

    class Factory(private val repository: WifiSurveyRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SurveyViewModel(repository) as T
    }
}
