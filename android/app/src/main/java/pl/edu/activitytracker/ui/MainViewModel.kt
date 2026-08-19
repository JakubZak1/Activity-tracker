package pl.edu.activitytracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.edu.activitytracker.data.ActivityTrackerRepository
import pl.edu.activitytracker.storage.SettingsStore
import pl.edu.activitytracker.storage.SettingsUiState
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BodySide
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.SensorPlacement

class MainViewModel(
    private val repository: ActivityTrackerRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val trackerState = repository.state

    val settings = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    fun connectDevice() = repository.connect()

    fun disconnectDevice() = repository.disconnect()

    fun startSession() = repository.startSession()

    fun stopSession() = repository.stopSession()

    fun resetSession() = repository.resetSession()

    fun requestStatus() = repository.requestStatus()

    fun startDataCollection(
        activityType: ActivityType,
        placement: SensorPlacement,
        bodySide: BodySide,
    ) = repository.startDataCollection(activityType, placement, bodySide)

    fun stopDataCollection() = repository.stopDataCollection()

    fun refreshDataLogs() = repository.refreshDataLogs()

    fun downloadLog(file: DeviceLogFile) = repository.downloadLog(file)

    fun deleteDeviceLog(file: DeviceLogFile) = repository.deleteDeviceLog(file)

    fun cancelFileTransfer() = repository.cancelFileTransfer()

    fun startLocationIfSessionRunning() = repository.startLocationIfSessionRunning()

    fun startLocationPreview() = repository.startLocationPreview()

    fun stopLocationPreviewIfNoSession() = repository.stopLocationPreviewIfNoSession()

    fun setWeightKg(weightKg: Double) {
        viewModelScope.launch {
            settingsStore.setWeightKg(weightKg)
        }
    }

    fun setDeviceName(deviceName: String) {
        viewModelScope.launch {
            settingsStore.setDeviceName(deviceName)
        }
    }

    fun setUseMockSource(useMockSource: Boolean) {
        viewModelScope.launch {
            settingsStore.setUseMockSource(useMockSource)
        }
    }

    fun setDataFolderUri(uri: String) = repository.setDataFolderUri(uri)

    class Factory(
        private val repository: ActivityTrackerRepository,
        private val settingsStore: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository, settingsStore) as T
        }
    }
}
