package pl.edu.activitytracker.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.activityTrackerDataStore by preferencesDataStore(name = "activity_tracker_settings")

data class SettingsUiState(
    val weightKg: Double = SettingsStore.DEFAULT_WEIGHT_KG,
    val deviceName: String = SettingsStore.DEFAULT_DEVICE_NAME,
    val useMockSource: Boolean = false,
    val dataFolderUri: String? = null,
    val blueDeviceAddress: String? = null,
    val greenDeviceAddress: String? = null,
)

interface SettingsDataSource {
    val settings: Flow<SettingsUiState>
    suspend fun setDataFolderUri(uri: String)
}

interface DatasetDeviceSettings : SettingsDataSource {
    suspend fun setDatasetDeviceAddress(slot: String, address: String?)
}

class SettingsStore(private val context: Context) : DatasetDeviceSettings {
    override val settings: Flow<SettingsUiState> = context.activityTrackerDataStore.data.map { preferences ->
        SettingsUiState(
            weightKg = preferences[Keys.WEIGHT_KG] ?: DEFAULT_WEIGHT_KG,
            deviceName = preferences[Keys.DEVICE_NAME] ?: DEFAULT_DEVICE_NAME,
            useMockSource = preferences[Keys.USE_MOCK_SOURCE] ?: false,
            dataFolderUri = preferences[Keys.DATA_FOLDER_URI],
            blueDeviceAddress = preferences[Keys.BLUE_DEVICE_ADDRESS],
            greenDeviceAddress = preferences[Keys.GREEN_DEVICE_ADDRESS],
        )
    }

    suspend fun setWeightKg(weightKg: Double) {
        if (weightKg <= 0.0) {
            return
        }
        context.activityTrackerDataStore.edit { preferences ->
            preferences[Keys.WEIGHT_KG] = weightKg
        }
    }

    suspend fun setDeviceName(deviceName: String) {
        context.activityTrackerDataStore.edit { preferences ->
            preferences[Keys.DEVICE_NAME] = deviceName.trim()
        }
    }

    suspend fun setUseMockSource(useMockSource: Boolean) {
        context.activityTrackerDataStore.edit { preferences ->
            preferences[Keys.USE_MOCK_SOURCE] = useMockSource
        }
    }

    override suspend fun setDataFolderUri(uri: String) {
        context.activityTrackerDataStore.edit { preferences ->
            preferences[Keys.DATA_FOLDER_URI] = uri
        }
    }

    override suspend fun setDatasetDeviceAddress(slot: String, address: String?) {
        context.activityTrackerDataStore.edit { preferences ->
            val key = if (slot == "blue") Keys.BLUE_DEVICE_ADDRESS else Keys.GREEN_DEVICE_ADDRESS
            if (address == null) preferences.remove(key) else preferences[key] = address
        }
    }

    private object Keys {
        val WEIGHT_KG = doublePreferencesKey("weight_kg")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val USE_MOCK_SOURCE = booleanPreferencesKey("use_mock_source")
        val DATA_FOLDER_URI = stringPreferencesKey("data_folder_uri")
        val BLUE_DEVICE_ADDRESS = stringPreferencesKey("blue_device_address")
        val GREEN_DEVICE_ADDRESS = stringPreferencesKey("green_device_address")
    }

    companion object {
        const val DEFAULT_WEIGHT_KG = 70.0
        const val DEFAULT_DEVICE_NAME = "ActivityTracker"
    }
}
