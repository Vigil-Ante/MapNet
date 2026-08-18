package com.mapnet.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NoUpdate : UpdateUiState
    data object NotConfigured : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val progressPercent: Int?) : UpdateUiState
    data class ReadyToInstall(val apk: File) : UpdateUiState
    data class NeedsInstallPermission(val apk: File) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppUpdateRepository(application.applicationContext)
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state = _state.asStateFlow()

    fun checkForUpdate() {
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            _state.value = when (val result = repository.checkForUpdate()) {
                is UpdateCheckResult.Available -> UpdateUiState.Available(result.manifest)
                UpdateCheckResult.NoUpdate -> UpdateUiState.NoUpdate
                UpdateCheckResult.NotConfigured -> UpdateUiState.NotConfigured
                is UpdateCheckResult.Failed -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun download(manifest: UpdateManifest) {
        _state.value = UpdateUiState.Downloading(null)
        viewModelScope.launch {
            _state.value = when (val result = repository.downloadUpdate(manifest) { percent ->
                _state.value = UpdateUiState.Downloading(percent)
            }) {
                is UpdateDownloadResult.Ready -> UpdateUiState.ReadyToInstall(result.apk)
                is UpdateDownloadResult.Failed -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun requestInstall(apk: File) {
        _state.value = when (repository.requestInstall(apk)) {
            InstallRequestResult.STARTED -> UpdateUiState.Idle
            InstallRequestResult.NEEDS_PERMISSION -> UpdateUiState.NeedsInstallPermission(apk)
            InstallRequestResult.FAILED -> UpdateUiState.Error("Android could not open the package installer.")
        }
    }

    fun dismissDialog() {
        _state.value = UpdateUiState.Idle
    }

    fun clearTransientStatus() {
        if (_state.value is UpdateUiState.NoUpdate ||
            _state.value is UpdateUiState.NotConfigured ||
            _state.value is UpdateUiState.Error
        ) {
            _state.value = UpdateUiState.Idle
        }
    }
}
