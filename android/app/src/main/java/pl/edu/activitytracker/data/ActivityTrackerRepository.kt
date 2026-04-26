package pl.edu.activitytracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.CalorieCalculator
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.LocationSample
import pl.edu.activitytracker.domain.LocationStatus
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.RoutePointFilter
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.gps.LocationTracker
import pl.edu.activitytracker.session.SessionRecordingController
import pl.edu.activitytracker.storage.SettingsStore

data class TrackerState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isSessionRunning: Boolean = false,
    val sessionStartedAtMillis: Long? = null,
    val sessionDurationSeconds: Long = 0L,
    val currentActivity: ActivityReading = ActivityReading.unknown(),
    val battery: pl.edu.activitytracker.domain.BatteryReading? = null,
    val summary: pl.edu.activitytracker.domain.SummaryReading? = null,
    val caloriesKcal: Double = 0.0,
    val route: List<RoutePoint> = emptyList(),
    val currentLocation: LocationSample? = null,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    val rawEvents: List<RawDeviceEvent> = emptyList(),
    val lastUpdateMillis: Long? = null,
)

class ActivityTrackerRepository(
    private val deviceDataSource: DeviceDataSource,
    private val locationTracker: LocationTracker,
    private val sessionRecordingController: SessionRecordingController,
    settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    private var weightKg = SettingsStore.DEFAULT_WEIGHT_KG
    private var lastCalorieTickMillis: Long? = null

    init {
        scope.launch {
            settingsStore.settings.collect { settings ->
                weightKg = settings.weightKg
            }
        }

        scope.launch {
            deviceDataSource.connectionState.collect { connectionState ->
                _state.update { it.copy(connectionState = connectionState) }
            }
        }

        scope.launch {
            deviceDataSource.activity.collect { reading ->
                _state.update {
                    it.copy(
                        currentActivity = reading,
                        lastUpdateMillis = reading.timestampMillis,
                    )
                }
            }
        }

        scope.launch {
            deviceDataSource.battery.collect { reading ->
                _state.update {
                    it.copy(
                        battery = reading,
                        lastUpdateMillis = reading.timestampMillis,
                    )
                }
            }
        }

        scope.launch {
            deviceDataSource.summary.collect { reading ->
                _state.update {
                    it.copy(
                        summary = reading,
                        lastUpdateMillis = reading.timestampMillis,
                    )
                }
            }
        }

        scope.launch {
            deviceDataSource.rawEvents.collect { event ->
                _state.update {
                    it.copy(rawEvents = (listOf(event) + it.rawEvents).take(30))
                }
            }
        }

        scope.launch {
            locationTracker.status.collect { status ->
                _state.update { it.copy(locationStatus = status) }
            }
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
                _state.update { state ->
                    val nextState = state.copy(currentLocation = location)
                    if (
                        state.isSessionRunning &&
                        RoutePointFilter.shouldAppend(state.route, routePoint)
                    ) {
                        nextState.copy(route = state.route + routePoint)
                    } else {
                        nextState
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
        scope.launch {
            deviceDataSource.connect(deviceId = null)
        }
    }

    fun disconnect() {
        scope.launch {
            deviceDataSource.disconnect()
            sessionRecordingController.stop()
            locationTracker.stop()
        }
    }

    fun startSession() {
        val now = System.currentTimeMillis()
        lastCalorieTickMillis = now
        _state.update {
            it.copy(
                isSessionRunning = true,
                sessionStartedAtMillis = now,
                sessionDurationSeconds = 0L,
                caloriesKcal = 0.0,
                route = emptyList(),
            )
        }

        sessionRecordingController.startIfLocationAllowed()
        scope.launch {
            deviceDataSource.sendCommand(DeviceCommand.Start)
            locationTracker.start()
        }
    }

    fun stopSession() {
        lastCalorieTickMillis = null
        _state.update { it.copy(isSessionRunning = false) }

        scope.launch {
            deviceDataSource.sendCommand(DeviceCommand.Stop)
            sessionRecordingController.stop()
            locationTracker.stop()
        }
    }

    fun resetSession() {
        lastCalorieTickMillis = null
        _state.update {
            it.copy(
                isSessionRunning = false,
                sessionStartedAtMillis = null,
                sessionDurationSeconds = 0L,
                caloriesKcal = 0.0,
                route = emptyList(),
                rawEvents = emptyList(),
            )
        }

        scope.launch {
            deviceDataSource.sendCommand(DeviceCommand.ResetSession)
            sessionRecordingController.stop()
            locationTracker.stop()
        }
    }

    fun requestStatus() {
        scope.launch {
            deviceDataSource.sendCommand(DeviceCommand.Status)
        }
    }

    fun startLocationIfSessionRunning() {
        if (_state.value.isSessionRunning) {
            locationTracker.start()
        }
    }

    fun startLocationPreview() {
        locationTracker.start()
    }

    fun stopLocationPreviewIfNoSession() {
        if (!_state.value.isSessionRunning) {
            locationTracker.stop()
        }
    }

    fun stopLocation() {
        locationTracker.stop()
    }

    private fun tickSession() {
        val now = System.currentTimeMillis()
        val snapshot = _state.value
        if (!snapshot.isSessionRunning) {
            return
        }

        val previousTick = lastCalorieTickMillis ?: now
        val deltaMinutes = (now - previousTick).coerceAtLeast(0L) / 60_000.0
        val caloriesDelta = CalorieCalculator.caloriesFor(
            activityType = snapshot.currentActivity.type,
            weightKg = weightKg,
            minutes = deltaMinutes,
        )

        lastCalorieTickMillis = now
        val startedAt = snapshot.sessionStartedAtMillis ?: now
        _state.update {
            it.copy(
                sessionDurationSeconds = ((now - startedAt) / 1_000L).coerceAtLeast(0L),
                caloriesKcal = it.caloriesKcal + caloriesDelta,
            )
        }
    }
}
