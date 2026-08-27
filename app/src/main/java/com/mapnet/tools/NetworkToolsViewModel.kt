package com.mapnet.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetworkToolsUiState(
    val connection: ConnectedWifiDetails? = null,
    val isRunning: Boolean = false,
    val outputTitle: String? = null,
    val output: String? = null,
    val networkMap: LocalNetworkMap? = null,
    val networkMapProgress: NetworkMapProgress? = null
)

class NetworkToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val diagnostics = NetworkDiagnostics(application.applicationContext)
    private val _state = MutableStateFlow(NetworkToolsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(connection = diagnostics.currentWifiDetails())
    }

    fun ping(destination: String) = runDiagnostic("Ping") { diagnostics.ping(destination) }

    fun traceroute(destination: String) = runDiagnostic("Traceroute") { diagnostics.traceroute(destination) }

    fun mapLocalNetwork() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(
            isRunning = true,
            outputTitle = "Network map",
            output = null,
            networkMap = null,
            networkMapProgress = null
        )
        viewModelScope.launch {
            val result = runCatching {
                diagnostics.discoverLocalDevices { progress ->
                    _state.value = _state.value.copy(networkMapProgress = progress)
                }
            }
            _state.value = _state.value.copy(
                connection = diagnostics.currentWifiDetails(),
                isRunning = false,
                outputTitle = result.exceptionOrNull()?.let { "Network map" },
                output = result.exceptionOrNull()?.message,
                networkMap = result.getOrNull(),
                networkMapProgress = null
            )
        }
    }

    private fun runDiagnostic(title: String, action: suspend () -> String) {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(
            isRunning = true,
            outputTitle = title,
            output = null,
            networkMapProgress = null
        )
        viewModelScope.launch {
            val output = runCatching { action() }
                .getOrElse { error -> error.message ?: "$title failed." }
            _state.value = _state.value.copy(
                connection = diagnostics.currentWifiDetails(),
                isRunning = false,
                outputTitle = title,
                output = output
            )
        }
    }
}
