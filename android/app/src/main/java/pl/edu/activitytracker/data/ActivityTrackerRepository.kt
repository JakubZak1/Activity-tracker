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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BodySide
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
import pl.edu.activitytracker.domain.HomeSession
import pl.edu.activitytracker.domain.HomeSessionAccumulator
import pl.edu.activitytracker.domain.HomeSessionMetrics
import pl.edu.activitytracker.domain.HomeSessionStatus
import pl.edu.activitytracker.domain.HomeSessionSummary
import pl.edu.activitytracker.domain.SessionExportState
import pl.edu.activitytracker.gps.LocationTracker
import pl.edu.activitytracker.session.PhoneSessionController
import pl.edu.activitytracker.storage.HomeSessionDataSource
import pl.edu.activitytracker.storage.HomeSessionExportDataSource
import pl.edu.activitytracker.storage.HomeSessionExportResult
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
    val activityDurations: ActivityDurationBreakdown = ActivityDurationBreakdown(),
    val sessionWeightKg: Double? = null,
    val route: List<RoutePoint> = emptyList(),
    val currentLocation: LocationSample? = null,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    val rawEvents: List<RawDeviceEvent> = emptyList(),
    val lastUpdateMillis: Long? = null,
    val dataset: DatasetState = DatasetState(),
    val history: List<HomeSessionSummary> = emptyList(),
    val selectedSession: HomeSession? = null,
    val lastSavedSessionId: String? = null,
    val sessionSaveError: String? = null,
    val homeConfigurationMessage: String? = null,
)

class ActivityTrackerRepository(
    private val deviceDataSource: DeviceDataSource,
    private val datasetController: DatasetController,
    private val locationTracker: LocationTracker,
    private val sessionRecordingController: PhoneSessionController,
    private val settingsStore: SettingsDataSource,
    private val homeSessionStore: HomeSessionDataSource? = null,
    private val homeSessionExporter: HomeSessionExportDataSource? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val reconnectDelaysMillis: List<Long> = DEFAULT_RECONNECT_DELAYS_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }
            .getOrElse { System.nanoTime() / 1_000_000L }
    },
) {
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()
    private var weightKg = SettingsStore.DEFAULT_WEIGHT_KG
    private var accumulator: HomeSessionAccumulator? = null
    private var activeSessionId: String? = null
    private var activeSessionStartedAtEpochMillis: Long? = null
    private var activeDeviceShortId: String? = null
    private var frozenWeightKg: Double? = null
    private var lastCheckpointElapsedMillis: Long? = null
    private var persistedRouteCount = 0
    private var pendingSessionInMemory: HomeSession? = null
    private var lastActivityReceivedAtElapsedMillis: Long? = null
    private var dataFolderUri: String? = null
    private val persistenceMutex = Mutex()
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
                val folderChanged = dataFolderUri != settings.dataFolderUri
                dataFolderUri = settings.dataFolderUri
                datasetController.setDataFolderUri(settings.dataFolderUri)
                if (folderChanged && settings.dataFolderUri != null) retryPendingExports()
            }
        }
        scope.launch(Dispatchers.IO) {
            homeSessionStore?.markActiveSessionsInterrupted()
            homeSessionStore?.refresh()
            if (dataFolderUri != null) retryPendingExports()
        }
        homeSessionStore?.let { store ->
            scope.launch { store.summaries.collect { history -> _state.update { it.copy(history = history) } } }
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
                lastActivityReceivedAtElapsedMillis = elapsedRealtimeMillis()
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
                    activity = eligibleActivity(elapsedRealtimeMillis(), snapshot),
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
        scope.launch {
            val settings = settingsStore.settings.first()
            val targetDevice = if (settings.useMockSource) null else settings.greenDeviceAddress
            if (!settings.useMockSource && targetDevice.isNullOrBlank()) {
                _state.update {
                    it.copy(homeConfigurationMessage = "Assign Green on the Data screen before connecting Home.")
                }
                return@launch
            }
            _state.update { it.copy(homeConfigurationMessage = null) }
            val epoch = synchronized(reconnectLock) {
                reconnectEnabled = true
                reconnectEpoch += 1L
                reconnectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectEpoch
            }
            connectionActionMutex.withLock {
                val shouldConnect = synchronized(reconnectLock) {
                    reconnectEnabled && reconnectEpoch == epoch
                }
                if (shouldConnect) deviceDataSource.connect(deviceId = targetDevice)
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
        if (_state.value.isSessionRunning) return
        val now = System.currentTimeMillis()
        val elapsedNow = elapsedRealtimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val sessionWeight = weightKg
        accumulator = HomeSessionAccumulator(lastTickElapsedMillis = elapsedNow)
        activeSessionId = sessionId
        activeSessionStartedAtEpochMillis = now
        activeDeviceShortId = (_state.value.dataset.connection as? pl.edu.activitytracker.domain.DatasetConnectionState.Ready)
            ?.shortId ?: deviceDataSource.deviceIdentity.value?.takeLast(8)?.uppercase()
        frozenWeightKg = sessionWeight
        lastCheckpointElapsedMillis = elapsedNow
        persistedRouteCount = 0
        pendingSessionInMemory = null
        lastDeviceStepTotal = _state.value.summary?.steps
        _state.update {
            it.copy(
                isSessionRunning = true,
                sessionStartedAtMillis = now,
                sessionDurationSeconds = 0L,
                sessionSteps = 0,
                caloriesKcal = 0.0,
                activityDurations = ActivityDurationBreakdown(),
                sessionWeightKg = sessionWeight,
                route = emptyList(),
                lastSavedSessionId = null,
                sessionSaveError = null,
            )
        }
        checkpoint(HomeSessionStatus.Active, endedAtEpochMillis = null)
        sessionRecordingController.start()
        locationTracker.start()
    }

    fun stopSession() {
        if (!_state.value.isSessionRunning) return
        tickSession(forceCheckpoint = false)
        val endedAt = System.currentTimeMillis()
        val completed = buildSession(HomeSessionStatus.Completed, endedAt) ?: return
        _state.update { it.copy(isSessionRunning = false) }
        sessionRecordingController.stop()
        locationTracker.stop()
        scope.launch(Dispatchers.IO) { persistCompletedSession(completed) }
    }

    fun resetSession() {
        accumulator = null
        activeSessionId = null
        activeSessionStartedAtEpochMillis = null
        frozenWeightKg = null
        pendingSessionInMemory = null
        _state.update {
            it.copy(
                isSessionRunning = false,
                sessionStartedAtMillis = null,
                sessionDurationSeconds = 0L,
                sessionSteps = 0,
                caloriesKcal = 0.0,
                activityDurations = ActivityDurationBreakdown(),
                sessionWeightKg = null,
                route = emptyList(),
                rawEvents = emptyList(),
                sessionSaveError = null,
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

    fun loadSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            val session = homeSessionStore?.get(sessionId)
            _state.update { it.copy(selectedSession = session) }
        }
    }

    fun clearSelectedSession() = _state.update { it.copy(selectedSession = null) }

    fun retrySessionSave() {
        val session = pendingSessionInMemory ?: return
        scope.launch(Dispatchers.IO) { persistCompletedSession(session) }
    }

    fun retrySessionExport(sessionId: String) {
        scope.launch(Dispatchers.IO) { homeSessionStore?.get(sessionId)?.let(::exportSession) }
    }

    fun deleteSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            homeSessionStore?.delete(sessionId)
            _state.update { state ->
                state.copy(selectedSession = state.selectedSession?.takeUnless { it.id == sessionId })
            }
        }
    }

    fun startLocationIfSessionRunning() {
        if (_state.value.isSessionRunning) locationTracker.start()
    }

    fun startLocationPreview() = locationTracker.start()

    fun stopLocationPreviewIfNoSession() {
        if (!_state.value.isSessionRunning) locationTracker.stop()
    }

    fun stopLocation() = locationTracker.stop()

    private fun tickSession(forceCheckpoint: Boolean = true) {
        val snapshot = _state.value
        if (!snapshot.isSessionRunning) return
        val now = elapsedRealtimeMillis()
        val current = accumulator ?: HomeSessionAccumulator(lastTickElapsedMillis = now)
        val updated = current.tick(now, eligibleActivity(now, snapshot))
        accumulator = updated
        val sessionWeight = frozenWeightKg ?: weightKg
        _state.update {
            it.copy(
                sessionDurationSeconds = updated.durations.totalMillis / 1_000L,
                activityDurations = updated.durations,
                caloriesKcal = HomeSessionMetrics.caloriesFor(updated.durations, sessionWeight),
            )
        }
        val checkpointDue = now - (lastCheckpointElapsedMillis ?: now) >= CHECKPOINT_INTERVAL_MILLIS
        if (forceCheckpoint && checkpointDue) {
            lastCheckpointElapsedMillis = now
            checkpoint(HomeSessionStatus.Active, endedAtEpochMillis = null)
        }
    }

    private fun eligibleActivity(nowElapsedMillis: Long, snapshot: TrackerState = _state.value): ActivityType {
        val age = lastActivityReceivedAtElapsedMillis?.let { nowElapsedMillis - it }
        return if (snapshot.connectionState is ConnectionState.Connected && age != null && age in 0..TELEMETRY_FRESH_MILLIS) {
            snapshot.currentActivity.type
        } else ActivityType.Unknown
    }

    private fun buildSession(status: HomeSessionStatus, endedAtEpochMillis: Long?): HomeSession? {
        val snapshot = _state.value
        return HomeSession(
            id = activeSessionId ?: return null,
            status = status,
            deviceShortId = activeDeviceShortId,
            startedAtEpochMillis = activeSessionStartedAtEpochMillis ?: return null,
            endedAtEpochMillis = endedAtEpochMillis,
            lastCheckpointEpochMillis = System.currentTimeMillis(),
            weightKg = frozenWeightKg ?: weightKg,
            durations = accumulator?.durations ?: snapshot.activityDurations,
            steps = snapshot.sessionSteps,
            caloriesKcal = HomeSessionMetrics.caloriesFor(
                accumulator?.durations ?: snapshot.activityDurations,
                frozenWeightKg ?: weightKg,
            ),
            route = snapshot.route,
            exportState = if (dataFolderUri == null) SessionExportState.PendingFolder else SessionExportState.Pending,
        )
    }

    private fun checkpoint(status: HomeSessionStatus, endedAtEpochMillis: Long?) {
        val session = buildSession(status, endedAtEpochMillis) ?: return
        val routeStart = persistedRouteCount
        scope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                try {
                    homeSessionStore?.save(session, routeStart)
                    persistedRouteCount = session.route.size
                    _state.update { it.copy(sessionSaveError = null) }
                } catch (error: Exception) {
                    _state.update { it.copy(sessionSaveError = error.message ?: "Session checkpoint failed") }
                }
            }
        }
    }

    private suspend fun persistCompletedSession(session: HomeSession) {
        persistenceMutex.withLock {
            try {
                homeSessionStore?.save(session, persistedRouteCount)
                persistedRouteCount = session.route.size
                pendingSessionInMemory = null
                _state.update {
                    it.copy(lastSavedSessionId = session.id, sessionSaveError = null, selectedSession = session)
                }
            } catch (error: Exception) {
                pendingSessionInMemory = session
                _state.update { it.copy(sessionSaveError = error.message ?: "Session save failed") }
                return
            }
        }
        exportSession(session)
        accumulator = null
        activeSessionId = null
        activeSessionStartedAtEpochMillis = null
        frozenWeightKg = null
    }

    private fun exportSession(session: HomeSession) {
        val store = homeSessionStore ?: return
        val exporter = homeSessionExporter ?: return
        if (dataFolderUri == null) {
            store.updateExportState(session.id, SessionExportState.PendingFolder)
            return
        }
        store.updateExportState(session.id, SessionExportState.Exporting)
        when (val result = exporter.export(session, dataFolderUri)) {
            is HomeSessionExportResult.Success -> store.updateExportState(session.id, result.state)
            is HomeSessionExportResult.Failure -> store.updateExportState(session.id, SessionExportState.Error(result.message))
        }
        if (_state.value.selectedSession?.id == session.id) {
            _state.update { it.copy(selectedSession = store.get(session.id)) }
        }
    }

    private fun retryPendingExports() {
        scope.launch(Dispatchers.IO) {
            homeSessionStore?.summaries?.value
                ?.filter { it.exportState !is SessionExportState.Exported && it.status != HomeSessionStatus.Active }
                ?.forEach { summary -> homeSessionStore.get(summary.id)?.let(::exportSession) }
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
        const val TELEMETRY_FRESH_MILLIS = 3_000L
        const val CHECKPOINT_INTERVAL_MILLIS = 5_000L
    }
}
