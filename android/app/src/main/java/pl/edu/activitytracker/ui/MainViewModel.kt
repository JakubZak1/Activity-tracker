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
