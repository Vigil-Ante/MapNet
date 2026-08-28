package com.mapnet.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class ToolsRoute {
    INVENTORY,
    DEVICE_DETAIL,
    MANUAL_DIAGNOSTICS
}

data class DiagnosticUiState(
    val title: String? = null,
    val isRunning: Boolean = false,
    val progressText: String? = null,
    val output: String? = null
)

data class NetworkToolsUiState(
    val route: ToolsRoute = ToolsRoute.INVENTORY,
    val connection: ConnectedWifiDetails? = null,
    val network: KnownLanNetwork? = null,
    val devices: List<KnownLanDevice> = emptyList(),
    val searchQuery: String = "",
    val filter: DeviceFilter = DeviceFilter.ALL,
    val sort: DeviceSort = DeviceSort.NAME,
    val isScanning: Boolean = false,
    val networkMapProgress: NetworkMapProgress? = null,
    val scanMessage: String? = null,
    val selectedDevice: KnownLanDevice? = null,
    val selectedEvents: List<LanDeviceEvent> = emptyList(),
    val selectedServices: List<LanService> = emptyList(),
    val diagnostic: DiagnosticUiState = DiagnosticUiState()
) {
    val visibleDevices: List<KnownLanDevice>
        get() = devices.filterAndSortDevices(searchQuery, filter, sort)

    val onlineDeviceCount: Int
        get() = devices.count { it.status == LanDeviceStatus.ONLINE }

    val isNestedRoute: Boolean
        get() = route != ToolsRoute.INVENTORY
}

class NetworkToolsViewModel(
    application: Application,
    private val repository: LanDeviceRepository,
    private val diagnostics: NetworkDiagnostics
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetworkToolsUiState())
    val state = _state.asStateFlow()

    private var inventoryJob: Job? = null
    private var detailJob: Job? = null
    private var scanJob: Job? = null
    private var diagnosticJob: Job? = null
    private var boundNetworkId: String? = null

    init {
        refreshConnection(allowSmartScan = false)
    }

    fun onToolsVisible() = refreshConnection(allowSmartScan = true)

    fun refreshConnection() = refreshConnection(allowSmartScan = false)

    fun refreshNow() {
        if (_state.value.isScanning) cancelScan() else startNetworkScan()
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(
            isScanning = false,
            networkMapProgress = null,
            scanMessage = "Scan canceled. Saved device statuses were not changed."
        )
    }

    fun setSearchQuery(value: String) {
        _state.value = _state.value.copy(searchQuery = value.take(120))
    }

    fun setFilter(filter: DeviceFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    fun setSort(sort: DeviceSort) {
        _state.value = _state.value.copy(sort = sort)
    }

    fun openDevice(deviceId: String) {
        diagnosticJob?.cancel()
        _state.value = _state.value.copy(
            route = ToolsRoute.DEVICE_DETAIL,
            diagnostic = DiagnosticUiState(),
            selectedEvents = emptyList(),
            selectedServices = emptyList()
        )
        bindDevice(deviceId)
    }

    fun openManualDiagnostics() {
        detailJob?.cancel()
        _state.value = _state.value.copy(
            route = ToolsRoute.MANUAL_DIAGNOSTICS,
            selectedDevice = null,
            selectedEvents = emptyList(),
            selectedServices = emptyList(),
            diagnostic = DiagnosticUiState()
        )
    }

    fun goBack() {
        diagnosticJob?.cancel()
        detailJob?.cancel()
        _state.value = _state.value.copy(
            route = ToolsRoute.INVENTORY,
            selectedDevice = null,
            selectedEvents = emptyList(),
            selectedServices = emptyList(),
            diagnostic = DiagnosticUiState()
        )
    }

    fun editSelectedDevice(name: String?, type: LanDeviceType?, note: String) {
        val deviceId = _state.value.selectedDevice?.id ?: return
        viewModelScope.launch { repository.editDevice(deviceId, name, type, note) }
    }

    fun forgetSelectedDevice() {
        val deviceId = _state.value.selectedDevice?.id ?: return
        viewModelScope.launch {
            if (repository.forgetDevice(deviceId)) goBack()
        }
    }

    fun pingSelectedDevice() {
        val target = _state.value.selectedDevice?.ipAddress ?: return
        runDiagnostic("Ping") { diagnostics.ping(target) }
    }

    fun tracerouteSelectedDevice() {
        val target = _state.value.selectedDevice?.ipAddress ?: return
        runDiagnostic("Traceroute") { diagnostics.traceroute(target) }
    }

    fun scanCommonPorts() = scanPorts(COMMON_TCP_PORTS)

    fun scanCustomPorts(start: Int, endInclusive: Int) {
        val ports = runCatching { customTcpPorts(start, endInclusive) }.getOrElse { error ->
            _state.value = _state.value.copy(
                diagnostic = DiagnosticUiState(title = "Find open ports", output = error.message)
            )
            return
        }
        scanPorts(ports)
    }

    fun runManualPing(target: String) = runDiagnostic("Ping") { diagnostics.ping(target) }

    fun runManualTraceroute(target: String) = runDiagnostic("Traceroute") { diagnostics.traceroute(target) }

    fun cancelDiagnostic() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        _state.value = _state.value.copy(
            diagnostic = DiagnosticUiState(title = _state.value.diagnostic.title, output = "Canceled.")
        )
    }

    fun clearTransientMessage() {
        _state.value = _state.value.copy(scanMessage = null)
    }

    private fun refreshConnection(allowSmartScan: Boolean) {
        viewModelScope.launch {
            val connection = diagnostics.currentWifiDetails()
            _state.value = _state.value.copy(connection = connection)
            if (connection == null) {
                bindInventory(null)
                _state.value = _state.value.copy(network = null, devices = emptyList())
                return@launch
            }
            val knownNetwork = repository.findKnownNetwork(connection)
            bindInventory(knownNetwork?.id)
            if (knownNetwork == null) {
                _state.value = _state.value.copy(network = null, devices = emptyList())
            }
            val stale = knownNetwork?.lastSuccessfulScanEpochMs?.let {
                System.currentTimeMillis() - it >= SMART_REFRESH_AGE_MS
            } ?: true
            if (allowSmartScan && stale && scanJob?.isActive != true) startNetworkScan(connection)
        }
    }

    private fun startNetworkScan(connectionOverride: ConnectedWifiDetails? = null) {
        if (scanJob?.isActive == true) return
        val connection = connectionOverride ?: diagnostics.currentWifiDetails()
        if (connection == null) {
            _state.value = _state.value.copy(
                connection = null,
                scanMessage = "Connect to Wi-Fi before mapping local devices."
            )
            return
        }
        _state.value = _state.value.copy(
            connection = connection,
            isScanning = true,
            networkMapProgress = null,
            scanMessage = null
        )
        scanJob = viewModelScope.launch {
            try {
                val map = diagnostics.discoverLocalDevices { progress ->
                    _state.value = _state.value.copy(networkMapProgress = progress)
                }
                val currentConnection = diagnostics.currentWifiDetails()
                    ?: error("The Wi-Fi connection ended during the scan. Saved statuses were not changed.")
                require(currentConnection.subnet == connection.subnet && currentConnection.ssid == connection.ssid) {
                    "The Wi-Fi network changed during the scan. Saved statuses were not changed."
                }
                val networkId = repository.persistSuccessfulScan(currentConnection, map)
                bindInventory(networkId)
                _state.value = _state.value.copy(
                    connection = currentConnection,
                    isScanning = false,
                    networkMapProgress = null,
                    scanMessage = "Device scan complete."
                )
            } catch (canceled: CancellationException) {
                _state.value = _state.value.copy(isScanning = false, networkMapProgress = null)
                throw canceled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    connection = diagnostics.currentWifiDetails(),
                    isScanning = false,
                    networkMapProgress = null,
                    scanMessage = error.message ?: "Unable to map local devices."
                )
            } finally {
                scanJob = null
            }
        }
    }

    private fun bindInventory(networkId: String?) {
        if (networkId == boundNetworkId && inventoryJob?.isActive == true) return
        boundNetworkId = networkId
        inventoryJob?.cancel()
        if (networkId == null) {
            _state.value = _state.value.copy(network = null, devices = emptyList())
            return
        }
        inventoryJob = viewModelScope.launch {
            combine(
                repository.observeNetwork(networkId),
                repository.observeDevices(networkId)
            ) { network, devices -> network to devices }.collect { (network, devices) ->
                _state.value = _state.value.copy(network = network, devices = devices)
            }
        }
    }

    private fun bindDevice(deviceId: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            combine(
                repository.observeDevice(deviceId),
                repository.observeEvents(deviceId),
                repository.observeServices(deviceId)
            ) { device, events, services -> Triple(device, events, services) }
                .collect { (device, events, services) ->
                    if (device == null && _state.value.route == ToolsRoute.DEVICE_DETAIL) {
                        goBack()
                    } else {
                        _state.value = _state.value.copy(
                            selectedDevice = device,
                            selectedEvents = events,
                            selectedServices = services
                        )
                    }
                }
        }
    }

    private fun scanPorts(ports: List<Int>) {
        val device = _state.value.selectedDevice ?: return
        if (_state.value.isScanning) return
        diagnosticJob?.cancel()
        _state.value = _state.value.copy(
            diagnostic = DiagnosticUiState(title = "Find open ports", isRunning = true)
        )
        diagnosticJob = viewModelScope.launch {
            try {
                val result = diagnostics.scanTcpPorts(device.ipAddress, ports) { progress ->
                    _state.value = _state.value.copy(
                        diagnostic = _state.value.diagnostic.copy(
                            progressText = "Checking ${progress.completedPortCount} of ${progress.totalPortCount} TCP ports"
                        )
                    )
                }
                repository.savePortScan(device.id, result.openPorts)
                val output = if (result.openPorts.isEmpty()) {
                    "No open TCP ports were found in this scan."
                } else {
                    result.openPorts.joinToString(separator = "\n") { port ->
                        "$port  ${serviceNameForTcpPort(port)}"
                    }
                }
                _state.value = _state.value.copy(
                    diagnostic = DiagnosticUiState(title = "Open TCP ports", output = output)
                )
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    diagnostic = DiagnosticUiState(title = "Find open ports", output = error.message)
                )
            } finally {
                diagnosticJob = null
            }
        }
    }

    private fun runDiagnostic(title: String, action: suspend () -> String) {
        if (_state.value.isScanning) return
        diagnosticJob?.cancel()
        _state.value = _state.value.copy(
            diagnostic = DiagnosticUiState(title = title, isRunning = true, progressText = "Running ${title.lowercase()}…")
        )
        diagnosticJob = viewModelScope.launch {
            try {
                val output = action()
                _state.value = _state.value.copy(
                    diagnostic = DiagnosticUiState(title = title, output = output)
                )
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    diagnostic = DiagnosticUiState(title = title, output = error.message ?: "$title failed.")
                )
            } finally {
                diagnosticJob = null
            }
        }
    }

    class Factory(
        private val application: Application,
        private val repository: LanDeviceRepository,
        private val diagnostics: NetworkDiagnostics
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NetworkToolsViewModel(application, repository, diagnostics) as T
    }

    private companion object {
        const val SMART_REFRESH_AGE_MS = 5 * 60 * 1_000L
    }
}
