package pl.edu.activitytracker.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BodySide
import pl.edu.activitytracker.domain.CalorieCalculator
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DatasetState
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.SensorPlacement
import pl.edu.activitytracker.domain.LocationSample
import pl.edu.activitytracker.domain.LocationStatus
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.domain.RoutePointFilter
import pl.edu.activitytracker.domain.Transport
import pl.edu.activitytracker.gps.LocationTracker
import pl.edu.activitytracker.session.PhoneSessionController
import pl.edu.activitytracker.storage.SettingsDataSource
import pl.edu.activitytracker.storage.SettingsStore

data class TrackerState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isSessionRunning: Boolean = false,
    val sessionStartedAtMillis: Long? = null,
    val sessionDurationSeconds: Long = 0L,
    val sessionSteps: Int = 0,
    val currentActivity: ActivityReading = ActivityReading.unknown(),
    val battery: pl.edu.activitytracker.domain.BatteryReading? = null,
    val summary: pl.edu.activitytracker.domain.SummaryReading? = null,
    val caloriesKcal: Double = 0.0,
    val route: List<RoutePoint> = emptyList(),
    val currentLocation: LocationSample? = null,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    val rawEvents: List<RawDeviceEvent> = emptyList(),
    val lastUpdateMillis: Long? = null,
    val dataset: DatasetState = DatasetState(),
)

class ActivityTrackerRepository(
    private val deviceDataSource: DeviceDataSource,
    private val datasetController: DatasetController,
    private val locationTracker: LocationTracker,
    private val sessionRecordingController: PhoneSessionController,
    private val settingsStore: SettingsDataSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val reconnectDelaysMillis: List<Long> = DEFAULT_RECONNECT_DELAYS_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()
    private var weightKg = SettingsStore.DEFAULT_WEIGHT_KG
    private var lastCalorieTickMillis: Long? = null
    private var sessionStartedAtElapsedMillis: Long? = null
    private var lastDeviceStepTotal: Int? = null
    private val connectionActionMutex = Mutex()
    private val reconnectLock = Any()
    private var reconnectJob: Job? = null
    private var reconnectEnabled = false
    private var reconnectEpoch = 0L
    private var reconnectAttempt = 0
    private var hasEstablishedConnection = false
    private var lastKnownDeviceId: String? = null
    private var lastConnectedTransport: Transport? = null

    init {
        scope.launch {
            settingsStore.settings.collect { settings ->
                weightKg = settings.weightKg
                datasetController.setDataFolderUri(settings.dataFolderUri)
            }
        }
        scope.launch {
            datasetController.state.collect { dataset -> _state.update { it.copy(dataset = dataset) } }
        }
        scope.launch {
            deviceDataSource.connectionState.collect { connection ->
                _state.update { it.copy(connectionState = connection) }
                when (connection) {
                    is ConnectionState.Connected -> synchronized(reconnectLock) {
                        hasEstablishedConnection = true
                        lastConnectedTransport = connection.transport
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                    }
                    ConnectionState.Disconnected,
                    is ConnectionState.Failed -> scheduleReconnectAfterUnexpectedDisconnect()
                    ConnectionState.Connecting,
                    ConnectionState.Scanning -> Unit
                }
            }
        }
        scope.launch {
            deviceDataSource.deviceIdentity.collect { identity ->
                if (identity != null) synchronized(reconnectLock) { lastKnownDeviceId = identity }
            }
        }
        scope.launch {
            deviceDataSource.activity.collect { reading ->
                _state.update { it.copy(currentActivity = reading, lastUpdateMillis = reading.timestampMillis) }
            }
        }
        scope.launch {
            deviceDataSource.battery.collect { reading ->
                _state.update { it.copy(battery = reading, lastUpdateMillis = reading.timestampMillis) }
            }
        }
        scope.launch {
            deviceDataSource.summary.collect { reading ->
                val previousTotal = lastDeviceStepTotal
                lastDeviceStepTotal = reading.steps
                _state.update { current ->
                    val delta = if (current.isSessionRunning && previousTotal != null) {
                        if (reading.steps >= previousTotal) {
                            reading.steps - previousTotal
                        } else {
                            // Treat a lower total as a device reboot/counter reset.
                            reading.steps
                        }
                    } else {
                        0
                    }
                    current.copy(
                        summary = reading,
                        sessionSteps = current.sessionSteps + delta,
                        lastUpdateMillis = reading.timestampMillis,
                    )
                }
            }
        }
        scope.launch {
            deviceDataSource.rawEvents.collect { event ->
                _state.update { it.copy(rawEvents = (listOf(event) + it.rawEvents).take(30)) }
            }
        }
        scope.launch {
            locationTracker.status.collect { status -> _state.update { it.copy(locationStatus = status) } }
        }
        scope.launch {
            locationTracker.locations.collect { location ->
                val snapshot = _state.value
                val routePoint = RoutePoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracyMeters,
                    timestampMillis = location.timestampMillis,
                    activity = snapshot.currentActivity.type,
                )
                _state.update { current ->
                    val withLocation = current.copy(currentLocation = location)
                    if (current.isSessionRunning && RoutePointFilter.shouldAppend(current.route, routePoint)) {
                        withLocation.copy(route = current.route + routePoint)
                    } else {
                        withLocation
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000L)
                tickSession()
            }
        }
    }

    fun connect() {
        val epoch = synchronized(reconnectLock) {
            reconnectEnabled = true
            reconnectEpoch += 1L
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectEpoch
        }
        scope.launch {
            connectionActionMutex.withLock {
                val shouldConnect = synchronized(reconnectLock) {
                    reconnectEnabled && reconnectEpoch == epoch
                }
                if (shouldConnect) deviceDataSource.connect(deviceId = null)
            }
        }
    }

    fun disconnect() {
        synchronized(reconnectLock) {
            reconnectEnabled = false
            reconnectEpoch += 1L
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
        }
        scope.launch { connectionActionMutex.withLock { deviceDataSource.disconnect() } }
    }

    // Home sessions are phone-side GPS/calorie sessions. They never mutate the dataset logger.
    fun startSession() {
        val now = System.currentTimeMillis()
        val elapsedNow = elapsedRealtimeMillis()
        lastCalorieTickMillis = elapsedNow
        sessionStartedAtElapsedMillis = elapsedNow
        lastDeviceStepTotal = _state.value.summary?.steps
        _state.update {
            it.copy(
                isSessionRunning = true,
                sessionStartedAtMillis = now,
                sessionDurationSeconds = 0L,
                sessionSteps = 0,
                caloriesKcal = 0.0,
                route = emptyList(),
            )
        }
        sessionRecordingController.startIfLocationAllowed()
        locationTracker.start()
    }

    fun stopSession() {
        lastCalorieTickMillis = null
        _state.update { it.copy(isSessionRunning = false) }
        sessionRecordingController.stop()
        locationTracker.stop()
    }

    fun resetSession() {
        lastCalorieTickMillis = null
        sessionStartedAtElapsedMillis = null
        _state.update {
            it.copy(
                isSessionRunning = false,
                sessionStartedAtMillis = null,
                sessionDurationSeconds = 0L,
                sessionSteps = 0,
                caloriesKcal = 0.0,
                route = emptyList(),
                rawEvents = emptyList(),
            )
        }
        sessionRecordingController.stop()
        locationTracker.stop()
    }

    fun requestStatus() = datasetController.requestStatus()

    fun startDataCollection(
        activityType: ActivityType,
        placement: SensorPlacement,
        bodySide: BodySide,
    ) = datasetController.startRecording(activityType, placement, bodySide)

    fun stopDataCollection() = datasetController.stopRecording()

    fun refreshDataLogs() = datasetController.refreshCatalog()

    fun downloadLog(file: DeviceLogFile) = datasetController.downloadLog(file)

    fun deleteDeviceLog(file: DeviceLogFile) = datasetController.deleteLog(file)

    fun cancelFileTransfer() = datasetController.cancelTransfer()

    fun setDataFolderUri(uri: String) {
        scope.launch { settingsStore.setDataFolderUri(uri) }
    }

    fun startLocationIfSessionRunning() {
        if (_state.value.isSessionRunning) locationTracker.start()
    }

    fun startLocationPreview() = locationTracker.start()

    fun stopLocationPreviewIfNoSession() {
        if (!_state.value.isSessionRunning) locationTracker.stop()
    }

    fun stopLocation() = locationTracker.stop()

    private fun tickSession() {
        val snapshot = _state.value
        if (!snapshot.isSessionRunning) return
        val now = elapsedRealtimeMillis()
        val previousTick = lastCalorieTickMillis ?: now
        val deltaMinutes = (now - previousTick).coerceAtLeast(0L) / 60_000.0
        val caloriesDelta = CalorieCalculator.caloriesFor(
            activityType = snapshot.currentActivity.type,
            weightKg = weightKg,
            minutes = deltaMinutes,
        )
        lastCalorieTickMillis = now
        val startedAt = sessionStartedAtElapsedMillis ?: now
        _state.update {
            it.copy(
                sessionDurationSeconds = ((now - startedAt) / 1_000L).coerceAtLeast(0L),
                caloriesKcal = it.caloriesKcal + caloriesDelta,
            )
        }
    }

    private fun scheduleReconnectAfterUnexpectedDisconnect() {
        val scheduled = synchronized(reconnectLock) {
            if (!reconnectEnabled || !hasEstablishedConnection || reconnectJob?.isActive == true ||
                reconnectAttempt >= reconnectDelaysMillis.size
            ) {
                return
            }
            val delayMillis = reconnectDelaysMillis[reconnectAttempt++]
            val epoch = reconnectEpoch
            val deviceId = lastKnownDeviceId.takeIf { lastConnectedTransport == Transport.Ble }
            Triple(delayMillis, epoch, deviceId)
        }
        val job = scope.launch {
            delay(scheduled.first)
            connectionActionMutex.withLock {
                val shouldReconnect = synchronized(reconnectLock) {
                    reconnectEnabled && reconnectEpoch == scheduled.second
                }
                if (shouldReconnect && deviceDataSource.connectionState.value !is ConnectionState.Connected) {
                    deviceDataSource.connect(scheduled.third)
                }
            }
            val retryAgain = synchronized(reconnectLock) {
                if (reconnectEpoch == scheduled.second) reconnectJob = null
                reconnectEnabled && reconnectEpoch == scheduled.second
            }
            if (retryAgain && (deviceDataSource.connectionState.value == ConnectionState.Disconnected ||
                    deviceDataSource.connectionState.value is ConnectionState.Failed)
            ) scheduleReconnectAfterUnexpectedDisconnect()
        }
        synchronized(reconnectLock) {
            if (reconnectEnabled && reconnectEpoch == scheduled.second) reconnectJob = job else job.cancel()
        }
    }

    companion object {
        val DEFAULT_RECONNECT_DELAYS_MILLIS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    }
}
