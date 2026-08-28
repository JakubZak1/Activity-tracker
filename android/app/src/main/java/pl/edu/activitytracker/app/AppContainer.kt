package pl.edu.activitytracker.app

import android.content.Context
import pl.edu.activitytracker.data.ActivityTrackerRepository
import pl.edu.activitytracker.data.BleDeviceDataSource
import pl.edu.activitytracker.data.DatasetController
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.data.BleDeviceScanner
import pl.edu.activitytracker.data.MultiDeviceDatasetManager
import pl.edu.activitytracker.data.SelectableDeviceDataSource
import pl.edu.activitytracker.gps.AndroidLocationTracker
import pl.edu.activitytracker.session.SessionRecordingController
import pl.edu.activitytracker.session.DatasetTransferRuntime
import pl.edu.activitytracker.storage.SettingsStore
import pl.edu.activitytracker.storage.LogFileStore
import pl.edu.activitytracker.storage.SQLiteHomeSessionStore
import pl.edu.activitytracker.storage.SafHomeSessionExporter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

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
    private val homeSessionStore = SQLiteHomeSessionStore(appContext)
    private val homeSessionExporter = SafHomeSessionExporter(appContext)
    private val datasetTransferRuntime = DatasetTransferRuntime(appContext)
    private val datasetController = DatasetController(
        deviceDataSource = deviceDataSource,
        fileStore = logFileStore,
        workRuntime = datasetTransferRuntime,
    )

    private val multiBlueSource = SelectableDeviceDataSource(
        useMockSource = settingsStore.settings.map { it.useMockSource },
        bleSource = BleDeviceDataSource(appContext, settingsStore.settings.map { it.deviceName }),
        mockSource = MockDeviceDataSource(
            mockTransportIdentity = "MOCK-BLUE",
            mockHardwareIdentity = "01020304A1B2C3D4",
        ),
    )
    private val multiGreenSource = SelectableDeviceDataSource(
        useMockSource = settingsStore.settings.map { it.useMockSource },
        bleSource = BleDeviceDataSource(appContext, settingsStore.settings.map { it.deviceName }),
        mockSource = MockDeviceDataSource(
            mockTransportIdentity = "MOCK-GREEN",
            mockHardwareIdentity = "05060708E5F6A7B8",
        ),
    )
    private val multiTransferMutex = Mutex()
    val multiDatasetManager = MultiDeviceDatasetManager(
        scanner = BleDeviceScanner(appContext),
        blueSource = multiBlueSource,
        greenSource = multiGreenSource,
        blueController = DatasetController(multiBlueSource, logFileStore, sharedTransferMutex = multiTransferMutex),
        greenController = DatasetController(multiGreenSource, logFileStore, sharedTransferMutex = multiTransferMutex),
        settingsStore = settingsStore,
        workRuntime = datasetTransferRuntime,
    )

    val repository = ActivityTrackerRepository(
        deviceDataSource = deviceDataSource,
        datasetController = datasetController,
        locationTracker = locationTracker,
        sessionRecordingController = sessionRecordingController,
        settingsStore = settingsStore,
        homeSessionStore = homeSessionStore,
        homeSessionExporter = homeSessionExporter,
    )
}
