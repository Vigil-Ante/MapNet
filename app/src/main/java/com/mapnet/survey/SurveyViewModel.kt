package com.mapnet.survey

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapnet.data.AccessPointEntity
import com.mapnet.data.NetworkListEntity
import com.mapnet.data.ObservationEntity
import com.mapnet.data.WifiSurveyRepository
import com.mapnet.data.networkListKey
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
    private val selectedNetworkList = MutableStateFlow<String?>(null)
    private val selectedNetworkKeySet = MutableStateFlow<Set<String>>(emptySet())
    private val selectedBssid = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false)
    private val continuousScanning = MutableStateFlow(false)
    private val scanStatus = MutableStateFlow<String?>(null)
    private var continuousScanJob: Job? = null

    val filter: StateFlow<SecurityFilter> = selectedFilter
    val searchQuery: StateFlow<String> = search
    val activeNetworkListId: StateFlow<String?> = selectedNetworkList
    val selectedNetworkKeys: StateFlow<Set<String>> = selectedNetworkKeySet
    val isScanning: StateFlow<Boolean> = scanning
    val isContinuousScanning: StateFlow<Boolean> = continuousScanning
    val status: StateFlow<String?> = scanStatus
    private val rawAccessPoints = repository.observeAccessPoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val rawLocatedObservations = repository.observeLocatedObservations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val customNetworkLists: StateFlow<List<NetworkListEntity>> = repository.observeNetworkLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val networkListMembers = repository.observeNetworkListMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val accessPoints = rawAccessPoints.map { it.collapseByNetworkName() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val securityAndSearchAccessPoints = combine(accessPoints, selectedFilter, search) { accessPoints, filter, query ->
        accessPoints.filter { accessPoint ->
            filter.includes(accessPoint) && accessPoint.matchesSearch(query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val filteredAccessPoints = combine(
        securityAndSearchAccessPoints,
        selectedNetworkList,
        networkListMembers
    ) { accessPoints, listId, members ->
        val keys = listId?.let(members::get)
        if (keys == null) accessPoints else accessPoints.filter { it.networkListKey() in keys }
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
    fun selectNetworkList(listId: String?) {
        selectedNetworkList.value = listId
        clearNetworkSelection()
    }
    fun showDetails(bssid: String) { selectedBssid.value = bssid }
    fun dismissDetails() { selectedBssid.value = null }
    fun clearStatus() { scanStatus.value = null }

    fun toggleNetworkSelection(accessPoint: AccessPointEntity) {
        val key = accessPoint.networkListKey()
        selectedNetworkKeySet.value = selectedNetworkKeySet.value.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
    }

    fun selectAllNetworks(accessPoints: List<AccessPointEntity>) {
        selectedNetworkKeySet.value = accessPoints.map(AccessPointEntity::networkListKey).toSet()
    }

    fun clearNetworkSelection() { selectedNetworkKeySet.value = emptySet() }

    fun createNetworkList(name: String) = viewModelScope.launch {
        val created = repository.createNetworkList(name)
        if (created == null) {
            scanStatus.value = "Enter a unique name for the new list."
        } else {
            selectedNetworkList.value = created.id
            scanStatus.value = "Created the ${created.name} list."
        }
    }

    fun createListAndOrganizeSelected(name: String) = viewModelScope.launch {
        val created = repository.createNetworkList(name)
        if (created == null) {
            scanStatus.value = "Enter a unique name for the new list."
        } else {
            repository.addNetworksToList(created.id, selectedNetworkKeySet.value)
            selectedNetworkList.value = created.id
            clearNetworkSelection()
            scanStatus.value = "Created ${created.name} and added the selected networks."
        }
    }

    fun addSelectedNetworksToList(listId: String) = viewModelScope.launch {
        val count = selectedNetworkKeySet.value.size
        repository.addNetworksToList(listId, selectedNetworkKeySet.value)
        clearNetworkSelection()
        scanStatus.value = "Added $count selected network${if (count == 1) "" else "s"} to the list."
    }

    fun removeSelectedNetworksFromActiveList() {
        val listId = selectedNetworkList.value ?: return
        viewModelScope.launch {
            val count = selectedNetworkKeySet.value.size
            repository.removeNetworksFromList(listId, selectedNetworkKeySet.value)
            clearNetworkSelection()
            scanStatus.value = "Removed $count network${if (count == 1) "" else "s"} from this list."
        }
    }

    fun deleteActiveNetworkList() {
        val listId = selectedNetworkList.value ?: return
        viewModelScope.launch {
            repository.deleteNetworkList(listId)
            selectedNetworkList.value = null
            clearNetworkSelection()
            scanStatus.value = "Deleted the custom list. Saved networks were not deleted."
        }
    }

    fun deleteSelectedNetworks() = viewModelScope.launch {
        val keys = selectedNetworkKeySet.value
        val toDelete = accessPoints.value.filter { it.networkListKey() in keys }
        if (toDelete.isEmpty()) return@launch
        runCatching { repository.deleteVisibleNetworks(toDelete) }
            .onSuccess { removedCount ->
                selectedBssid.value?.let { bssid ->
                    if (toDelete.any { it.bssid == bssid }) dismissDetails()
                }
                clearNetworkSelection()
                scanStatus.value = "Deleted $removedCount saved access point${if (removedCount == 1) "" else "s"} and their local observation history."
            }
            .onFailure { error ->
                scanStatus.value = "Could not delete selected networks: ${error.message ?: "database error"}"
            }
    }

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
