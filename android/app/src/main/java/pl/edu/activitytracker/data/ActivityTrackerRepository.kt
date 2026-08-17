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
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.CalorieCalculator
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCollectionStatus
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.FileDataFrame
import pl.edu.activitytracker.domain.FileFrameDecision
import pl.edu.activitytracker.domain.FileTransferState
import pl.edu.activitytracker.domain.FileTransferValidator
import pl.edu.activitytracker.domain.LocationSample
import pl.edu.activitytracker.domain.LocationStatus
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.RoutePoint
import pl.edu.activitytracker.domain.RoutePointFilter
import pl.edu.activitytracker.domain.Transport
import pl.edu.activitytracker.domain.isBusy
import pl.edu.activitytracker.gps.LocationTracker
import pl.edu.activitytracker.session.SessionRecordingController
import pl.edu.activitytracker.storage.LogFileStore
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
    val collectionStatus: DeviceCollectionStatus? = null,
    val deviceLogs: List<DeviceLogFile> = emptyList(),
    val verifiedLogNames: Set<String> = emptySet(),
    val fileTransfer: FileTransferState = FileTransferState.Idle,
    val dataFolderUri: String? = null,
)

class ActivityTrackerRepository(
    private val deviceDataSource: DeviceDataSource,
    private val locationTracker: LocationTracker,
    private val sessionRecordingController: SessionRecordingController,
    private val settingsStore: SettingsStore,
    private val logFileStore: LogFileStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    private var weightKg = SettingsStore.DEFAULT_WEIGHT_KG
    private var lastCalorieTickMillis: Long? = null
    private var pendingStartLabel: String? = null
    private var listAfterStatus = false
    private val pendingLogList = mutableListOf<DeviceLogFile>()
    private var activeDownload: DeviceLogFile? = null
    private var activeSink: LogFileStore.DownloadSink? = null

    init {
        scope.launch {
            settingsStore.settings.collect { settings ->
                weightKg = settings.weightKg
                val previousUri = _state.value.dataFolderUri
                _state.update { it.copy(dataFolderUri = settings.dataFolderUri) }
                if (previousUri != settings.dataFolderUri) {
                    refreshLocalVerification()
                    val waiting = _state.value.fileTransfer as? FileTransferState.WaitingForFolder
                    if (waiting != null && settings.dataFolderUri != null) {
                        scope.launch(Dispatchers.IO) {
                            prepareAndRequestDownload(
                                DeviceLogFile(waiting.fileName, waiting.sizeBytes, isActive = false),
                            )
                        }
                    }
                }
            }
        }

        scope.launch {
            deviceDataSource.connectionState.collect { connectionState ->
                _state.update { it.copy(connectionState = connectionState) }
                when (connectionState) {
                    is ConnectionState.Connected -> if (connectionState.transport == Transport.Ble) {
                        requestDeviceState()
                    }
                    ConnectionState.Disconnected,
                    is ConnectionState.Failed -> closeInterruptedTransfer("BLE connection lost")
                    ConnectionState.Connecting,
                    ConnectionState.Scanning -> Unit
                }
            }
        }

        scope.launch {
            deviceDataSource.activity.collect { reading ->
                _state.update {
                    it.copy(currentActivity = reading, lastUpdateMillis = reading.timestampMillis)
                }
            }
        }

        scope.launch {
            deviceDataSource.battery.collect { reading ->
                _state.update { it.copy(battery = reading, lastUpdateMillis = reading.timestampMillis) }
            }
        }

        scope.launch {
            deviceDataSource.summary.collect { reading ->
                _state.update { it.copy(summary = reading, lastUpdateMillis = reading.timestampMillis) }
            }
        }

        scope.launch {
            deviceDataSource.rawEvents.collect { event ->
                _state.update { it.copy(rawEvents = (listOf(event) + it.rawEvents).take(30)) }
            }
        }

        scope.launch(Dispatchers.IO) {
            deviceDataSource.protocolEvents.collect(::handleProtocolEvent)
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
        scope.launch { deviceDataSource.connect(deviceId = null) }
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
        scope.launch { deviceDataSource.sendCommand(DeviceCommand.Status) }
    }

    fun startDataCollection(activityType: ActivityType) {
        val snapshot = _state.value
        if (
            activityType == ActivityType.Unknown ||
            snapshot.collectionStatus?.isLogging == true ||
            snapshot.fileTransfer.isBusy ||
            pendingStartLabel != null
        ) return

        pendingStartLabel = activityType.wireName
        scope.launch { deviceDataSource.sendCommand(DeviceCommand.SetLabel(activityType.wireName)) }
    }

    fun stopDataCollection() {
        if (_state.value.collectionStatus?.isLogging != true) return
        scope.launch { deviceDataSource.sendCommand(DeviceCommand.Stop) }
    }

    fun refreshDataLogs() {
        if (_state.value.fileTransfer.isBusy) return
        pendingLogList.clear()
        scope.launch { deviceDataSource.sendCommand(DeviceCommand.ListLogs) }
    }

    fun downloadLog(file: DeviceLogFile) {
        if (file.isActive || _state.value.fileTransfer.isBusy) return
        scope.launch(Dispatchers.IO) { prepareAndRequestDownload(file) }
    }

    fun deleteDeviceLog(file: DeviceLogFile) {
        val snapshot = _state.value
        if (
            file.isActive ||
            file.name !in snapshot.verifiedLogNames ||
            snapshot.fileTransfer.isBusy
        ) return
        scope.launch { deviceDataSource.sendCommand(DeviceCommand.Delete(file.name)) }
    }

    fun cancelFileTransfer() {
        closeActiveSink()
        _state.update {
            it.copy(
                fileTransfer = FileTransferState.Failed(
                    fileName = activeDownload?.name,
                    message = "Transfer cancelled",
                    canResume = activeDownload != null,
                ),
            )
        }
        activeDownload = null
        scope.launch { deviceDataSource.sendCommand(DeviceCommand.Cancel) }
    }

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

    private suspend fun requestDeviceState() {
        listAfterStatus = true
        deviceDataSource.sendCommand(DeviceCommand.Status)
    }

    private suspend fun handleProtocolEvent(event: DeviceProtocolEvent) {
        when (event) {
            is DeviceProtocolEvent.Control -> handleControlResponse(event.response)
            is DeviceProtocolEvent.FileData -> handleFileFrame(event.frame)
        }
    }

    private suspend fun handleControlResponse(response: DeviceControlResponse) {
        when (response) {
            is DeviceControlResponse.Status -> {
                _state.update { it.copy(collectionStatus = response.value) }
                if (listAfterStatus) {
                    listAfterStatus = false
                    pendingLogList.clear()
                    deviceDataSource.sendCommand(DeviceCommand.ListLogs)
                }
            }
            is DeviceControlResponse.FileEntry -> pendingLogList += response.value
            is DeviceControlResponse.ListEnd -> {
                _state.update { it.copy(deviceLogs = pendingLogList.sortedBy { file -> file.name }) }
                pendingLogList.clear()
                refreshLocalVerification()
            }
            is DeviceControlResponse.LabelSet -> {
                _state.update { current ->
                    current.copy(collectionStatus = current.collectionStatus?.copy(label = response.label))
                }
                if (pendingStartLabel == response.label) {
                    pendingStartLabel = null
                    deviceDataSource.sendCommand(DeviceCommand.Start)
                }
            }
            is DeviceControlResponse.LoggingStarted -> {
                pendingStartLabel = null
                _state.update { current ->
                    current.copy(
                        collectionStatus = current.collectionStatus?.copy(
                            isLogging = true,
                            currentFile = response.fileName,
                        ),
                    )
                }
            }
            is DeviceControlResponse.LoggingStopped -> handleLoggingStopped(response)
            is DeviceControlResponse.DownloadBegin -> handleDownloadBegin(response)
            is DeviceControlResponse.DownloadEnd -> handleDownloadEnd(response)
            is DeviceControlResponse.Deleted -> {
                _state.update { current ->
                    current.copy(
                        deviceLogs = current.deviceLogs.filterNot { it.name == response.fileName },
                        verifiedLogNames = current.verifiedLogNames - response.fileName,
                    )
                }
                requestDeviceState()
            }
            DeviceControlResponse.Cancelled -> Unit
            is DeviceControlResponse.Error -> handleProtocolError(response)
            is DeviceControlResponse.Unknown -> Unit
        }
    }

    private suspend fun handleLoggingStopped(response: DeviceControlResponse.LoggingStopped) {
        _state.update { current ->
            current.copy(
                collectionStatus = current.collectionStatus?.copy(isLogging = false, currentFile = null),
            )
        }
        val fileName = response.fileName
        val size = response.sizeBytes
        if (fileName != null && size != null) {
            prepareAndRequestDownload(DeviceLogFile(fileName, size, isActive = false))
        } else {
            requestDeviceState()
        }
    }

    private suspend fun prepareAndRequestDownload(file: DeviceLogFile) {
        if (activeSink != null || _state.value.collectionStatus?.isLogging == true) return
        val folderUri = _state.value.dataFolderUri
        if (folderUri == null) {
            _state.update {
                it.copy(fileTransfer = FileTransferState.WaitingForFolder(file.name, file.sizeBytes))
            }
            return
        }

        _state.update { it.copy(fileTransfer = FileTransferState.Preparing(file.name, file.sizeBytes)) }
        when (val prepared = logFileStore.prepare(folderUri, file.name, file.sizeBytes)) {
            LogFileStore.PrepareResult.AlreadyComplete -> {
                _state.update {
                    it.copy(
                        verifiedLogNames = it.verifiedLogNames + file.name,
                        fileTransfer = FileTransferState.Completed(file.name, file.sizeBytes),
                    )
                }
                requestDeviceState()
            }
            is LogFileStore.PrepareResult.Failure -> {
                _state.update {
                    it.copy(
                        fileTransfer = FileTransferState.Failed(
                            fileName = file.name,
                            message = prepared.message,
                            canResume = false,
                        ),
                    )
                }
            }
            is LogFileStore.PrepareResult.Ready -> {
                activeDownload = file
                activeSink = prepared.sink
                _state.update {
                    it.copy(
                        fileTransfer = FileTransferState.Downloading(
                            fileName = file.name,
                            sizeBytes = file.sizeBytes,
                            receivedBytes = prepared.offset,
                        ),
                    )
                }
                deviceDataSource.sendCommand(DeviceCommand.Download(file.name, prepared.offset))
            }
        }
    }

    private suspend fun handleDownloadBegin(response: DeviceControlResponse.DownloadBegin) {
        val file = activeDownload
        val sink = activeSink
        if (
            file == null || sink == null ||
            response.fileName != file.name ||
            response.sizeBytes != file.sizeBytes ||
            response.offset != sink.position
        ) {
            failActiveTransfer("Device transfer metadata does not match", canResume = true)
            deviceDataSource.sendCommand(DeviceCommand.Cancel)
            return
        }
        _state.update {
            it.copy(
                fileTransfer = FileTransferState.Downloading(
                    file.name,
                    file.sizeBytes,
                    sink.position,
                ),
            )
        }
    }

    private suspend fun handleFileFrame(frame: FileDataFrame) {
        val file = activeDownload ?: return
        val sink = activeSink ?: return
        when (FileTransferValidator.evaluate(sink.position, file.sizeBytes, frame)) {
            FileFrameDecision.Duplicate -> return
            FileFrameDecision.Gap -> {
                failActiveTransfer("A BLE data fragment is missing", canResume = true)
                deviceDataSource.sendCommand(DeviceCommand.Cancel)
            }
            FileFrameDecision.Overflow -> {
                failActiveTransfer("Device sent more data than expected", canResume = true)
                deviceDataSource.sendCommand(DeviceCommand.Cancel)
            }
            FileFrameDecision.Accept -> {
                try {
                    sink.write(frame.data)
                    _state.update {
                        it.copy(
                            fileTransfer = FileTransferState.Downloading(
                                file.name,
                                file.sizeBytes,
                                sink.position,
                            ),
                        )
                    }
                } catch (error: Exception) {
                    failActiveTransfer(error.message ?: "Could not write downloaded file", canResume = true)
                    deviceDataSource.sendCommand(DeviceCommand.Cancel)
                }
            }
        }
    }

    private suspend fun handleDownloadEnd(response: DeviceControlResponse.DownloadEnd) {
        val file = activeDownload
        val sink = activeSink
        if (
            file == null || sink == null ||
            response.fileName != file.name ||
            response.sizeBytes != file.sizeBytes ||
            sink.position != file.sizeBytes
        ) {
            failActiveTransfer("Download ended before the complete file was received", canResume = true)
            return
        }

        activeSink = null
        activeDownload = null
        when (val result = logFileStore.complete(sink)) {
            LogFileStore.CompleteResult.Success -> {
                _state.update {
                    it.copy(
                        verifiedLogNames = it.verifiedLogNames + file.name,
                        fileTransfer = FileTransferState.Completed(file.name, file.sizeBytes),
                    )
                }
                requestDeviceState()
            }
            is LogFileStore.CompleteResult.Failure -> {
                _state.update {
                    it.copy(
                        fileTransfer = FileTransferState.Failed(file.name, result.message, canResume = false),
                    )
                }
            }
        }
    }

    private fun handleProtocolError(response: DeviceControlResponse.Error) {
        pendingStartLabel = null
        val message = listOfNotNull(response.code, response.details).joinToString(": ")
        if (activeSink != null) {
            failActiveTransfer(message, canResume = true)
        } else {
            _state.update {
                it.copy(fileTransfer = FileTransferState.Failed(null, message, canResume = false))
            }
        }
    }

    private fun failActiveTransfer(message: String, canResume: Boolean) {
        val fileName = activeDownload?.name
        closeActiveSink()
        activeDownload = null
        _state.update {
            it.copy(fileTransfer = FileTransferState.Failed(fileName, message, canResume))
        }
    }

    private fun closeInterruptedTransfer(message: String) {
        if (activeSink == null) return
        failActiveTransfer(message, canResume = true)
    }

    private fun closeActiveSink() {
        activeSink?.close()
        activeSink = null
    }

    private fun refreshLocalVerification() {
        val snapshot = _state.value
        val folder = snapshot.dataFolderUri
        val verified = if (folder == null) {
            emptySet()
        } else {
            snapshot.deviceLogs
                .filter { logFileStore.isComplete(folder, it.name, it.sizeBytes) }
                .mapTo(mutableSetOf()) { it.name }
        }
        _state.update { it.copy(verifiedLogNames = verified) }
    }

    private fun tickSession() {
        val now = System.currentTimeMillis()
        val snapshot = _state.value
        if (!snapshot.isSessionRunning) return

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
