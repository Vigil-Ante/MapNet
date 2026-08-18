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
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyViewModel(private val repository: WifiSurveyRepository) : ViewModel() {
    private val selectedFilter = MutableStateFlow(SecurityFilter.ALL)
    private val selectedBssid = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false)
    private val scanStatus = MutableStateFlow<String?>(null)

    val filter: StateFlow<SecurityFilter> = selectedFilter
    val isScanning: StateFlow<Boolean> = scanning
    val status: StateFlow<String?> = scanStatus
    private val rawAccessPoints = repository.observeAccessPoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accessPoints = rawAccessPoints.map { it.collapseByNetworkName() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val filteredAccessPoints = combine(accessPoints, selectedFilter) { accessPoints, filter ->
        accessPoints.filter(filter::includes)
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
    fun showDetails(bssid: String) { selectedBssid.value = bssid }
    fun dismissDetails() { selectedBssid.value = null }
    fun clearStatus() { scanStatus.value = null }

    fun performScan(locationProvider: suspend () -> Location?) = viewModelScope.launch {
        scanning.value = true
        scanStatus.value = null
        runCatching {
            // A fresh coordinate makes the survey map useful; the provider has a
            // short timeout and falls back to the last known coordinate.
            repository.scanAndPersist(locationProvider())
        }
            .onSuccess { count -> scanStatus.value = "$count network observations saved" }
            .onFailure { error ->
                scanStatus.value = "Could not run Wi-Fi survey: ${error.message ?: "permission or platform restriction"}"
            }
        scanning.value = false
    }

    class Factory(private val repository: WifiSurveyRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SurveyViewModel(repository) as T
    }
}
