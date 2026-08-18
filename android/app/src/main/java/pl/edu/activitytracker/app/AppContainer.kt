package pl.edu.activitytracker.app

import android.content.Context
import pl.edu.activitytracker.data.ActivityTrackerRepository
import pl.edu.activitytracker.data.BleDeviceDataSource
import pl.edu.activitytracker.data.DatasetController
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.data.SelectableDeviceDataSource
import pl.edu.activitytracker.gps.AndroidLocationTracker
import pl.edu.activitytracker.session.SessionRecordingController
import pl.edu.activitytracker.session.DatasetTransferRuntime
import pl.edu.activitytracker.storage.SettingsStore
import pl.edu.activitytracker.storage.LogFileStore
import kotlinx.coroutines.flow.map

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    private val bleDeviceDataSource = BleDeviceDataSource(
        context = appContext,
        deviceName = settingsStore.settings.map { it.deviceName },
    )
    private val mockDeviceDataSource = MockDeviceDataSource()
    private val deviceDataSource = SelectableDeviceDataSource(
        useMockSource = settingsStore.settings.map { it.useMockSource },
        bleSource = bleDeviceDataSource,
        mockSource = mockDeviceDataSource,
    )
    private val locationTracker = AndroidLocationTracker(appContext)
    private val sessionRecordingController = SessionRecordingController(appContext)
    private val logFileStore = LogFileStore(appContext)
    private val datasetTransferRuntime = DatasetTransferRuntime(appContext)
    private val datasetController = DatasetController(
        deviceDataSource = deviceDataSource,
        fileStore = logFileStore,
        workRuntime = datasetTransferRuntime,
    )

    val repository = ActivityTrackerRepository(
        deviceDataSource = deviceDataSource,
        datasetController = datasetController,
        locationTracker = locationTracker,
        sessionRecordingController = sessionRecordingController,
        settingsStore = settingsStore,
    )
}
