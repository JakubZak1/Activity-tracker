package pl.edu.activitytracker.app

import android.content.Context
import pl.edu.activitytracker.data.ActivityTrackerRepository
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.gps.AndroidLocationTracker
import pl.edu.activitytracker.session.SessionRecordingController
import pl.edu.activitytracker.storage.SettingsStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    private val deviceDataSource = MockDeviceDataSource()
    private val locationTracker = AndroidLocationTracker(appContext)
    private val sessionRecordingController = SessionRecordingController(appContext)

    val repository = ActivityTrackerRepository(
        deviceDataSource = deviceDataSource,
        locationTracker = locationTracker,
        sessionRecordingController = sessionRecordingController,
        settingsStore = settingsStore,
    )
}
