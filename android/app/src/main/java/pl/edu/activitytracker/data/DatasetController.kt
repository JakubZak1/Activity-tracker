package pl.edu.activitytracker.data

import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.CatalogState
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DATASET_PROTOCOL_VERSION
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.DatasetState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.DeviceStatus
import pl.edu.activitytracker.domain.FileFrameDecision
import pl.edu.activitytracker.domain.FileTransferValidator
import pl.edu.activitytracker.domain.MAX_REQUEST_ID
import pl.edu.activitytracker.domain.REQUIRED_DATASET_CAPABILITIES
import pl.edu.activitytracker.domain.RemoteFileIdentity
import pl.edu.activitytracker.domain.TransferState
import pl.edu.activitytracker.domain.isBusy
import pl.edu.activitytracker.storage.DatasetFileStore

class DatasetController(
    private val deviceDataSource: DeviceDataSource,
    private val fileStore: DatasetFileStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commandTimeoutMillis: Long = 5_000L,
    private val catalogInactivityMillis: Long = 10_000L,
    private val fileInactivityMillis: Long = 10_000L,
    private val workRuntime: DatasetWorkRuntime = DatasetWorkRuntime.NoOp,
    private val autoOffloadPollMillis: Long = 2_000L,
) {
    private data class PendingTransaction(
        val requestId: Long,
        val generation: Long,
        val events: Channel<DeviceProtocolEvent> = Channel(PENDING_EVENT_CAPACITY),
    )

    private class ProtocolException(message: String) : Exception(message)
    private class DeviceRejectedException(val response: DeviceControlResponse.Error) :
        Exception(listOfNotNull(response.code, response.details).joinToString(": "))
    private class UserCancelledTransfer : CancellationException("Transfer cancelled")

    private val _state = MutableStateFlow(DatasetState())
    val state: StateFlow<DatasetState> = _state.asStateFlow()
    private val operationMutex = Mutex()
    private val pendingLock = Any()
    private var pending: PendingTransaction? = null
    private val requestCounter = AtomicLong(0L)
    @Volatile
    private var activeGeneration = 0L
    @Volatile
    private var readyInfo: DatasetConnectionState.Ready? = null
    @Volatile
    private var readyGeneration: Long? = null
    @Volatile
    private var activeSessionIdentity: String? = null
    private var connectionHandshakeJob: Job? = null
    @Volatile
    private var activeTransferJob: Job? = null
    @Volatile
    private var continuousSessionActive = false
    private var autoOffloadJob: Job? = null

    init {
        scope.launch {
            deviceDataSource.protocolEvents.collect { routeProtocolEvent(it) }
        }
        scope.launch {
            deviceDataSource.connectionState.collect { connection -> handleConnectionState(connection) }
        }
        scope.launch {
            deviceDataSource.connectionGeneration.collect { generation ->
                if (deviceDataSource.connectionState.value is ConnectionState.Connected) {
                    deviceDataSource.deviceIdentity.value?.let { identity ->
                        beginConnectionHandshake(generation, identity)
                    }
                }
            }
        }
        scope.launch {
            deviceDataSource.deviceIdentity.collect { identity ->
                if (identity != null && deviceDataSource.connectionState.value is ConnectionState.Connected) {
                    beginConnectionHandshake(deviceDataSource.connectionGeneration.value, identity)
                }
            }
        }
    }

    fun setDataFolderUri(uri: String?) {
        _state.update { current ->
            if (current.dataFolderUri == uri) {
                current.copy(operationMessage = null)
            } else {
                current.copy(
                    dataFolderUri = uri,
                    verifiedFiles = emptySet(),
                    operationMessage = null,
                )
            }
        }
        val waiting = _state.value.transfer as? TransferState.WaitingForFolder
        if (uri != null && waiting != null) downloadLog(waiting.file)
        if (uri != null && (waiting != null || continuousSessionActive || hasRemoteCompletedFiles())) {
            ensureAutoOffloadLoop()
        }
    }

    fun requestStatus() {
        scope.launch {
            runExclusive {
                if (!isReady()) return@runExclusive
                try {
                    applyStatus(requestStatusLocked())
                    clearMessage()
                } catch (error: Exception) {
                    showOperationError("Status failed: ${error.userMessage()}")
                }
            }
        }
    }

    fun startRecording(activityType: ActivityType) {
        if (activityType == ActivityType.Unknown) return
        scope.launch {
            runExclusive {
                if (!isReady() || _state.value.collection !is CollectionState.Idle || _state.value.transfer.isBusy) {
                    return@runExclusive
                }
                val folder = _state.value.dataFolderUri
                if (folder == null) {
                    showOperationError("Choose a storage folder before starting a recording.")
                    return@runExclusive
                }
                val folderAvailable = withContext(ioDispatcher) { fileStore.isFolderAvailable(folder) }
                if (_state.value.dataFolderUri != folder || !isReady() || _state.value.collection !is CollectionState.Idle) {
                    return@runExclusive
                }
                if (!folderAvailable) {
                    _state.update {
                        it.copy(
                            verifiedFiles = emptySet(),
                            operationMessage = "The selected storage folder is unavailable. Choose it again before recording.",
                        )
                    }
                    return@runExclusive
                }
                val idleState = _state.value.collection as? CollectionState.Idle ?: return@runExclusive
                _state.update {
                    it.copy(collection = CollectionState.Starting(activityType), operationMessage = "Starting ${activityType.displayName} recording...")
                }
                continuousSessionActive = true
                ensureAutoOffloadLoop()
                try {
                    val response = awaitSingle(DeviceCommand.RecordStart(nextRequestId(), activityType))
                    when (response) {
                        is DeviceControlResponse.RecordingStarted -> {
                            _state.update {
                                it.copy(
                                    collection = CollectionState.Recording(
                                        label = response.label,
                                        fileName = response.fileName,
                                        freeBytes = idleState.freeBytes,
                                    ),
                                    operationMessage = if (response.alreadyRecording) {
                                        "Recording was already active; state synchronized."
                                    } else null,
                                )
                            }
                        }
                        is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                        else -> throw ProtocolException("Unexpected response to record_start")
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val status = reconcileAfterMutationFailure("Start failed: ${error.userMessage()}")
                    if (status !is DeviceStatus.Recording && status !is DeviceStatus.PausedForOffload) {
                        continuousSessionActive = false
                    }
                }
            }
        }
    }

    fun stopRecording() {
        scope.launch {
            runExclusive {
                if (!isReady()) return@runExclusive
                val active = _state.value.collection
                val activeFileName = when (active) {
                    is CollectionState.Recording -> active.fileName
                    is CollectionState.PausedForOffload -> active.file.name
                    else -> return@runExclusive
                }
                _state.update {
                    it.copy(collection = CollectionState.Stopping(activeFileName), operationMessage = "Stopping recording...")
                }
                var fileToDownload: DeviceLogFile? = null
                try {
                    val response = awaitSingle(DeviceCommand.RecordStop(nextRequestId()))
                    when (response) {
                        is DeviceControlResponse.RecordingStopped -> {
                            fileToDownload = response.file?.toDeviceLogFile()
                            _state.update {
                                it.copy(
                                    collection = CollectionState.Idle(response.file),
                                    catalog = addCatalogFile(it.catalog, fileToDownload),
                                    operationMessage = null,
                                )
                            }
                        }
                        is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                        else -> throw ProtocolException("Unexpected response to record_stop")
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val status = reconcileAfterMutationFailure("Stop response was lost: ${error.userMessage()}")
                    if (status is DeviceStatus.Idle) fileToDownload = status.lastFile?.toDeviceLogFile()
                }
                if (_state.value.collection is CollectionState.Idle) continuousSessionActive = false
                fileToDownload?.let { downloadAndDeleteLocked(it) }
                ensureAutoOffloadLoop()
            }
        }
    }

    fun refreshCatalog() {
        scope.launch {
            runExclusive {
                if (!isReady() || _state.value.transfer.isBusy) {
                    return@runExclusive
                }
                try {
                    refreshCatalogLocked()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(
                            catalog = CatalogState.Error(error.userMessage(), it.catalog.files),
                            operationMessage = "Catalog failed: ${error.userMessage()}",
                        )
                    }
                    reconcileStatusBestEffort()
                }
            }
        }
    }

    fun downloadLog(file: DeviceLogFile) {
        scope.launch {
            runExclusive {
                if (!isReady() || _state.value.transfer.isBusy) {
                    return@runExclusive
                }
                downloadAndDeleteLocked(file)
            }
        }
    }

    fun cancelTransfer() {
        val job = activeTransferJob ?: return
        job.cancel(UserCancelledTransfer())
        scope.launch {
            runCatching { job.join() }
            runExclusive { cancelRemoteBestEffort() }
        }
    }

    fun deleteLog(file: DeviceLogFile) {
        scope.launch {
            runExclusive {
                deleteVerifiedLocked(file, requireIdle = true)
            }
        }
    }

    private suspend fun handleConnectionState(connection: ConnectionState) {
        when (connection) {
            is ConnectionState.Connected -> {
                val generation = deviceDataSource.connectionGeneration.value
                val identity = deviceDataSource.deviceIdentity.value
                if (identity != null) beginConnectionHandshake(generation, identity)
            }
            ConnectionState.Disconnected -> handleDisconnect(null)
            is ConnectionState.Failed -> handleDisconnect(connection.message)
            ConnectionState.Connecting,
            ConnectionState.Scanning -> {
                connectionHandshakeJob?.cancel()
                connectionHandshakeJob = null
                readyInfo = null
                readyGeneration = null
                activeSessionIdentity = null
                closePending(ProtocolException("Connection is being established"))
                _state.update {
                    it.copy(
                        connection = DatasetConnectionState.Connecting,
                        collection = CollectionState.Unknown,
                        operationMessage = if (connection is ConnectionState.Scanning) {
                            "Scanning for the activity tracker..."
                        } else {
                            "Establishing the BLE connection..."
                        },
                    )
                }
            }
        }
    }

    private fun handleDisconnect(message: String?) {
        connectionHandshakeJob?.cancel()
        connectionHandshakeJob = null
        readyInfo = null
        readyGeneration = null
        activeSessionIdentity = null
        closePending(ProtocolException(message ?: "Device disconnected"))
        val transfer = _state.value.transfer
        val interrupted = when (transfer) {
            is TransferState.Preparing -> TransferState.Interrupted(transfer.file, message ?: "Disconnected", true)
            is TransferState.AwaitingBegin -> TransferState.Interrupted(transfer.file, message ?: "Disconnected", true)
            is TransferState.Receiving -> TransferState.Interrupted(transfer.file, message ?: "Disconnected", true)
            is TransferState.Finalizing -> TransferState.Interrupted(transfer.file, message ?: "Disconnected", false)
            else -> transfer
        }
        _state.update {
            it.copy(
                connection = if (message == null) DatasetConnectionState.Offline else DatasetConnectionState.Error(message),
                collection = CollectionState.Unknown,
                transfer = interrupted,
                operationMessage = message,
            )
        }
    }

    @Synchronized
    private fun beginConnectionHandshake(generation: Long, identity: String) {
        val sameSession = activeGeneration == generation && activeSessionIdentity == identity
        if (sameSession && (connectionHandshakeJob?.isActive == true || readyGeneration == generation)) return
        activeGeneration = generation
        activeSessionIdentity = identity
        readyGeneration = null
        readyInfo = null
        closePending(ProtocolException("Connection generation changed"))
        connectionHandshakeJob?.cancel()
        connectionHandshakeJob = scope.launch {
            runExclusive { handshakeLocked(generation, identity) }
        }
    }

    private suspend fun handshakeLocked(expectedGeneration: Long, expectedIdentity: String) {
        ensureConnectionSession(expectedGeneration, expectedIdentity)
        _state.update {
            it.copy(
                connection = DatasetConnectionState.Handshaking,
                collection = CollectionState.Unknown,
                operationMessage = "Checking BLE protocol version...",
            )
        }
        try {
            val hello = awaitSingle(DeviceCommand.Hello(nextRequestId()))
            ensureConnectionSession(expectedGeneration, expectedIdentity)
            if (hello !is DeviceControlResponse.Hello) {
                throw ProtocolException("Device did not return a v5 hello response")
            }
            val missing = REQUIRED_DATASET_CAPABILITIES - hello.capabilities
            if (hello.protocolVersion != DATASET_PROTOCOL_VERSION || missing.isNotEmpty()) {
                readyInfo = null
                readyGeneration = null
                _state.update {
                    it.copy(
                        connection = DatasetConnectionState.Incompatible(
                            "Expected protocol 5 with all dataset capabilities; got ${hello.protocolVersion}, missing ${missing.joinToString()}",
                        ),
                        operationMessage = "Install matching Android and firmware v5 builds.",
                    )
                }
                return
            }
            _state.update { it.copy(connection = DatasetConnectionState.Synchronizing, operationMessage = "Synchronizing device state...") }
            val status = requestStatusLocked()
            ensureConnectionSession(expectedGeneration, expectedIdentity)
            applyStatus(status)
            readyInfo = DatasetConnectionState.Ready(hello.protocolVersion, hello.capabilities, expectedIdentity)
            readyGeneration = expectedGeneration
            _state.update { it.copy(connection = requireNotNull(readyInfo), operationMessage = null) }
            if (status !is DeviceStatus.Recording) {
                try {
                    refreshCatalogLocked()
                    ensureConnectionSession(expectedGeneration, expectedIdentity)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(
                            catalog = CatalogState.Error(error.userMessage(), it.catalog.files),
                            operationMessage = "Connected, but catalog sync failed: ${error.userMessage()}",
                        )
                    }
                }
            }
            if (continuousSessionActive || hasRemoteCompletedFiles()) ensureAutoOffloadLoop()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!isConnectionSessionActive(expectedGeneration, expectedIdentity)) return
            readyInfo = null
            readyGeneration = null
            _state.update {
                it.copy(
                    connection = DatasetConnectionState.Error("Handshake failed: ${error.userMessage()}"),
                    collection = CollectionState.Unknown,
                    operationMessage = "Reconnect after installing matching v5 firmware.",
                )
            }
        }
    }

    private suspend fun requestStatusLocked(): DeviceStatus {
        return when (val response = awaitSingle(DeviceCommand.Status(nextRequestId()))) {
            is DeviceControlResponse.Status -> response.value
            is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
            else -> throw ProtocolException("Unexpected response to status")
        }
    }

    private fun applyStatus(status: DeviceStatus) {
        val collection = when (status) {
            is DeviceStatus.Idle -> CollectionState.Idle(status.lastFile, status.freeBytes)
            is DeviceStatus.Recording -> CollectionState.Recording(
                status.label,
                status.fileName,
                status.elapsedMillis,
                status.bytesWritten,
                status.freeBytes,
            )
            is DeviceStatus.PausedForOffload -> CollectionState.PausedForOffload(
                status.label,
                status.file,
                status.elapsedMillis,
                status.freeBytes,
            )
            is DeviceStatus.Fault -> CollectionState.Fault(status.code, status.activeFileName, status.freeBytes)
        }
        _state.update { it.copy(collection = collection) }
        when (status) {
            is DeviceStatus.Recording -> {
                continuousSessionActive = true
                ensureAutoOffloadLoop()
            }
            is DeviceStatus.PausedForOffload -> {
                continuousSessionActive = true
                ensureAutoOffloadLoop()
            }
            is DeviceStatus.Idle,
            is DeviceStatus.Fault -> continuousSessionActive = false
        }
    }

    private suspend fun refreshCatalogLocked() {
        val cached = _state.value.catalog.files
        _state.update { it.copy(catalog = CatalogState.Loading(cached), operationMessage = "Loading device logs...") }
        val requestId = nextRequestId()
        val files = linkedMapOf<String, DeviceLogFile>()
        withTransaction(DeviceCommand.ListLogs(requestId)) { transaction ->
            var timeout = commandTimeoutMillis
            while (true) {
                when (val event = receiveEvent(transaction, timeout)) {
                    is DeviceProtocolEvent.Control -> when (val response = event.response) {
                        is DeviceControlResponse.FileEntry -> {
                            if (files.put(response.value.name, response.value) != null) {
                                throw ProtocolException("Catalog contains duplicate file names")
                            }
                            timeout = catalogInactivityMillis
                        }
                        is DeviceControlResponse.ListEnd -> {
                            if (response.count != files.size) {
                                throw ProtocolException("Catalog count mismatch: ${response.count} != ${files.size}")
                            }
                            return@withTransaction
                        }
                        is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                        else -> throw ProtocolException("Unexpected catalog response")
                    }
                    is DeviceProtocolEvent.Fault -> throw ProtocolException(event.message)
                    is DeviceProtocolEvent.FileData -> throw ProtocolException("Unexpected file data during catalog listing")
                }
            }
        }
        val sorted = files.values.sortedBy(DeviceLogFile::name)
        val folder = _state.value.dataFolderUri
        val verified = if (folder == null) emptySet() else withContext(ioDispatcher) {
            sorted.mapNotNull { file ->
                file.identity?.takeIf { identity -> fileStore.isVerified(folder, identity) }
            }.toSet()
        }
        _state.update {
            it.copy(catalog = CatalogState.Loaded(sorted), verifiedFiles = verified, operationMessage = null)
        }
    }

    private suspend fun downloadLocked(file: DeviceLogFile) {
        val identity = file.identity
        if (!file.isComplete || identity == null) {
            _state.update { it.copy(transfer = TransferState.Error(file, "Incomplete files cannot be downloaded", false)) }
            return
        }
        val folder = _state.value.dataFolderUri
        if (folder == null) {
            _state.update { it.copy(transfer = TransferState.WaitingForFolder(file), operationMessage = "Choose a folder to continue.") }
            return
        }
        val deviceIdentity = readyInfo?.deviceIdentity ?: return
        _state.update { it.copy(transfer = TransferState.Preparing(file), operationMessage = "Preparing ${file.name}...") }
        when (val prepared = withContext(ioDispatcher) { fileStore.prepare(folder, deviceIdentity, identity) }) {
            DatasetFileStore.PrepareResult.AlreadyComplete -> {
                _state.update {
                    it.copy(
                        transfer = TransferState.Completed(identity),
                        verifiedFiles = it.verifiedFiles + identity,
                        operationMessage = null,
                    )
                }
            }
            is DatasetFileStore.PrepareResult.Failure -> {
                _state.update {
                    it.copy(transfer = TransferState.Error(file, prepared.message, false), operationMessage = prepared.message)
                }
            }
            is DatasetFileStore.PrepareResult.Ready -> receiveDownload(file, prepared.sink, prepared.offset)
        }
    }

    private suspend fun downloadAndDeleteLocked(file: DeviceLogFile): Boolean {
        val identity = file.identity ?: return false
        downloadLocked(file)
        val completed = (_state.value.transfer as? TransferState.Completed)?.file == identity
        return completed && deleteVerifiedLocked(file, requireIdle = false)
    }

    private suspend fun deleteVerifiedLocked(file: DeviceLogFile, requireIdle: Boolean): Boolean {
        val identity = file.identity ?: return false
        val folder = _state.value.dataFolderUri ?: return false
        val collectionAllowsDelete = !requireIdle || _state.value.collection is CollectionState.Idle
        if (!isReady() ||
            !collectionAllowsDelete ||
            identity !in _state.value.verifiedFiles ||
            !file.isComplete ||
            _state.value.transfer.isBusy
        ) {
            return false
        }
        _state.update { it.copy(operationMessage = "Rechecking local size and CRC32 for ${file.name}...") }
        val stillVerified = withContext(ioDispatcher) { fileStore.isVerified(folder, identity) }
        val collectionStillAllowsDelete = !requireIdle || _state.value.collection is CollectionState.Idle
        if (_state.value.dataFolderUri != folder || !isReady() || !collectionStillAllowsDelete || !stillVerified) {
            _state.update {
                it.copy(
                    verifiedFiles = it.verifiedFiles - identity,
                    operationMessage = if (stillVerified) {
                        "Delete cancelled because the connection or storage folder changed."
                    } else {
                        "Delete blocked: the local file is missing, inaccessible, or no longer matches CRC32."
                    },
                )
            }
            return false
        }
        _state.update { it.copy(operationMessage = "Deleting verified ${file.name} from the device...") }
        return try {
            when (val response = awaitSingle(DeviceCommand.Delete(nextRequestId(), identity))) {
                is DeviceControlResponse.Deleted -> {
                    if (response.fileName != file.name) throw ProtocolException("Delete response names a different file")
                    _state.update { current ->
                        current.copy(
                            catalog = removeCatalogFile(current.catalog, file.name),
                            verifiedFiles = current.verifiedFiles - identity,
                            operationMessage = null,
                        )
                    }
                    true
                }
                is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                else -> throw ProtocolException("Unexpected response to delete")
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            showOperationError("Delete failed: ${error.userMessage()}")
            reconcileAfterMutationFailure("Delete outcome is unknown; catalog will be reconciled before retrying.")
            false
        }
    }

    private fun ensureAutoOffloadLoop() {
        if (autoOffloadJob?.isActive == true) {
            workRuntime.setActive(true)
            return
        }
        workRuntime.setActive(true)
        autoOffloadJob = scope.launch {
            try {
                delay(autoOffloadPollMillis)
                while (true) {
                    val keepRunning = runExclusive { autoOffloadPassLocked() }
                    if (!keepRunning) break
                    delay(autoOffloadPollMillis)
                }
            } finally {
                autoOffloadJob = null
                if (!continuousSessionActive && !hasRemoteCompletedFiles()) {
                    workRuntime.setActive(false)
                }
            }
        }
    }

    private suspend fun autoOffloadPassLocked(): Boolean {
        if (!isReady()) return continuousSessionActive || hasRemoteCompletedFiles()
        val folder = _state.value.dataFolderUri
        if (folder == null || !withContext(ioDispatcher) { fileStore.isFolderAvailable(folder) }) {
            return continuousSessionActive || hasRemoteCompletedFiles()
        }
        if (_state.value.transfer.isBusy) return true
        try {
            applyStatus(requestStatusLocked())
            if (_state.value.collection is CollectionState.Recording) return true
            refreshCatalogLocked()
            val completedFiles = _state.value.catalog.files
                .filter { it.isComplete && it.identity != null }
                .sortedBy(DeviceLogFile::name)
            val nextFile = completedFiles.firstOrNull()
            if (nextFile != null) {
                if (downloadAndDeleteLocked(nextFile)) {
                    applyStatus(requestStatusLocked())
                }
                return true
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            showOperationError("Automatic offload will retry: ${error.userMessage()}")
            return continuousSessionActive || hasRemoteCompletedFiles()
        }
        return continuousSessionActive || hasRemoteCompletedFiles()
    }

    private fun hasRemoteCompletedFiles(): Boolean =
        _state.value.catalog.files.any { it.isComplete && it.identity != null }

    private suspend fun receiveDownload(
        file: DeviceLogFile,
        sink: DatasetFileStore.DownloadSink,
        resumeOffset: Long,
    ) {
        val identity = requireNotNull(file.identity)
        activeTransferJob = currentCoroutineContext()[Job]
        _state.update {
            it.copy(transfer = TransferState.AwaitingBegin(file, resumeOffset), operationMessage = "Requesting ${file.name}...")
        }
        var shouldCancelRemote = false
        try {
            val requestId = nextRequestId()
            withTransaction(DeviceCommand.Download(requestId, file.name, resumeOffset)) { transaction ->
                when (val first = receiveEvent(transaction, commandTimeoutMillis)) {
                    is DeviceProtocolEvent.Control -> when (val response = first.response) {
                        is DeviceControlResponse.DownloadBegin -> {
                            if (response.file != identity || response.offset != sink.position) {
                                throw ProtocolException("Download metadata does not match the requested file")
                            }
                        }
                        is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                        else -> throw ProtocolException("Expected download_begin")
                    }
                    is DeviceProtocolEvent.Fault -> throw ProtocolException(first.message)
                    is DeviceProtocolEvent.FileData -> throw ProtocolException("File data arrived before download_begin")
                }

                _state.update {
                    it.copy(transfer = TransferState.Receiving(file, sink.position), operationMessage = null)
                }
                val writeBatch = ByteArrayOutputStream(FILE_WRITE_BATCH_BYTES)
                var contiguousPosition = sink.position

                suspend fun flushWriteBatch() {
                    if (writeBatch.size() == 0) return
                    val bytes = writeBatch.toByteArray()
                    writeBatch.reset()
                    withContext(ioDispatcher) { sink.write(bytes) }
                    if (sink.position != contiguousPosition) {
                        throw ProtocolException("Local partial-file position does not match received bytes")
                    }
                    _state.update { it.copy(transfer = TransferState.Receiving(file, sink.position)) }
                }

                while (true) {
                    when (val event = receiveEvent(transaction, fileInactivityMillis)) {
                        is DeviceProtocolEvent.FileData -> when (
                            FileTransferValidator.evaluate(contiguousPosition, identity.sizeBytes, event.frame)
                        ) {
                            FileFrameDecision.Duplicate -> Unit
                            FileFrameDecision.Accept -> {
                                writeBatch.write(event.frame.data)
                                contiguousPosition += event.frame.data.size
                                if (writeBatch.size() >= FILE_WRITE_BATCH_BYTES) flushWriteBatch()
                            }
                            FileFrameDecision.Gap -> throw ProtocolException("A file frame is missing")
                            FileFrameDecision.Overlap -> throw ProtocolException("A file frame overlaps received data")
                            FileFrameDecision.Overflow -> throw ProtocolException("A file frame exceeds the declared size")
                            FileFrameDecision.Empty -> throw ProtocolException("Device sent an empty file frame")
                        }
                        is DeviceProtocolEvent.Control -> when (val response = event.response) {
                            is DeviceControlResponse.DownloadEnd -> {
                                if (response.file != identity || contiguousPosition != identity.sizeBytes) {
                                    throw ProtocolException("download_end does not match received bytes")
                                }
                                flushWriteBatch()
                                return@withTransaction
                            }
                            is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                            else -> throw ProtocolException("Unexpected response during download")
                        }
                        is DeviceProtocolEvent.Fault -> throw ProtocolException(event.message)
                    }
                }
            }
            _state.update { it.copy(transfer = TransferState.Finalizing(file), operationMessage = "Verifying CRC32...") }
            when (val completed = withContext(ioDispatcher) { fileStore.complete(sink) }) {
                DatasetFileStore.CompleteResult.Success -> {
                    _state.update {
                        it.copy(
                            transfer = TransferState.Completed(identity),
                            verifiedFiles = it.verifiedFiles + identity,
                            operationMessage = null,
                        )
                    }
                }
                is DatasetFileStore.CompleteResult.Failure -> {
                    _state.update {
                        it.copy(transfer = TransferState.Error(file, completed.message, false), operationMessage = completed.message)
                    }
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable + ioDispatcher) { sink.close() }
            val userCancelled = error is UserCancelledTransfer
            val disconnected = deviceDataSource.connectionState.value !is ConnectionState.Connected
            shouldCancelRemote = !disconnected
            _state.update {
                it.copy(
                    transfer = TransferState.Interrupted(
                        file,
                        if (userCancelled) "Transfer cancelled" else error.userMessage(),
                        canResume = true,
                    ),
                    operationMessage = if (userCancelled) null else error.userMessage(),
                )
            }
            if (error is CancellationException && !userCancelled) throw error
        } finally {
            activeTransferJob = null
        }
        if (shouldCancelRemote) cancelRemoteBestEffort()
    }

    private suspend fun cancelRemoteBestEffort() {
        if (!isReady() || deviceDataSource.connectionState.value !is ConnectionState.Connected) return
        runCatching {
            when (val response = awaitSingle(DeviceCommand.Cancel(nextRequestId()))) {
                is DeviceControlResponse.Cancelled -> Unit
                is DeviceControlResponse.Error -> throw DeviceRejectedException(response)
                else -> throw ProtocolException("Unexpected response to cancel")
            }
        }
    }

    private suspend fun reconcileAfterMutationFailure(message: String): DeviceStatus? {
        showOperationError(message)
        return reconcileStatusBestEffort()
    }

    private suspend fun reconcileStatusBestEffort(): DeviceStatus? = runCatching {
        val status = requestStatusLocked()
        applyStatus(status)
        status
    }.getOrElse {
        _state.update { current -> current.copy(collection = CollectionState.Unknown) }
        null
    }

    private suspend fun awaitSingle(command: DeviceCommand): DeviceControlResponse =
        withTransaction(command) { transaction ->
            when (val event = receiveEvent(transaction, commandTimeoutMillis)) {
                is DeviceProtocolEvent.Control -> event.response
                is DeviceProtocolEvent.Fault -> throw ProtocolException(event.message)
                is DeviceProtocolEvent.FileData -> throw ProtocolException("Unexpected file data")
            }
        }

    private suspend fun <T> withTransaction(
        command: DeviceCommand,
        block: suspend (PendingTransaction) -> T,
    ): T {
        val transaction = PendingTransaction(command.requestId, activeGeneration)
        synchronized(pendingLock) {
            check(pending == null) { "Only one device transaction may be active" }
            pending = transaction
        }
        return try {
            if (!deviceDataSource.sendCommand(command)) throw ProtocolException("Command could not be queued")
            block(transaction)
        } finally {
            synchronized(pendingLock) {
                if (pending === transaction) pending = null
            }
            transaction.events.close()
        }
    }

    private suspend fun receiveEvent(transaction: PendingTransaction, timeoutMillis: Long): DeviceProtocolEvent =
        try {
            withTimeout(timeoutMillis) { transaction.events.receive() }
        } catch (_: TimeoutCancellationException) {
            throw ProtocolException("Timed out after ${timeoutMillis} ms")
        } catch (error: ClosedReceiveChannelException) {
            throw ProtocolException("Connection was interrupted")
        }

    private suspend fun routeProtocolEvent(event: DeviceProtocolEvent) {
        if (event.generation != activeGeneration ||
            deviceDataSource.connectionState.value !is ConnectionState.Connected
        ) return
        val controlResponse = (event as? DeviceProtocolEvent.Control)?.response
        if (controlResponse is DeviceControlResponse.Error && controlResponse.requestId == ASYNC_REQUEST_ID) {
            handleAsyncDeviceFault(controlResponse)
            return
        }
        val target = synchronized(pendingLock) { pending }
        if (target == null || event.generation != target.generation) return
        val requestMatches = when (event) {
            is DeviceProtocolEvent.Control -> event.response.requestId == target.requestId
            is DeviceProtocolEvent.FileData -> event.frame.requestId == target.requestId
            is DeviceProtocolEvent.Fault -> true
        }
        if (requestMatches) {
            try {
                target.events.send(event)
            } catch (_: Exception) {
                // The transaction was completed or cancelled while this callback was being routed.
            }
        }
    }

    private fun closePending(error: Throwable) {
        val target = synchronized(pendingLock) {
            pending.also { pending = null }
        }
        target?.events?.close(error)
    }

    private suspend fun <T> runExclusive(block: suspend () -> T): T =
        operationMutex.withLock { block() }

    private fun nextRequestId(): Long = requestCounter.updateAndGet { current ->
        if (current >= MAX_REQUEST_ID) 1L else current + 1L
    }

    private fun isReady(): Boolean =
        _state.value.connection is DatasetConnectionState.Ready &&
            readyInfo != null &&
            readyGeneration == activeGeneration &&
            deviceDataSource.connectionGeneration.value == activeGeneration &&
            deviceDataSource.deviceIdentity.value == activeSessionIdentity &&
            deviceDataSource.connectionState.value is ConnectionState.Connected

    private fun isConnectionSessionActive(expectedGeneration: Long, expectedIdentity: String): Boolean =
        activeGeneration == expectedGeneration &&
            activeSessionIdentity == expectedIdentity &&
            deviceDataSource.connectionGeneration.value == expectedGeneration &&
            deviceDataSource.deviceIdentity.value == expectedIdentity &&
            deviceDataSource.connectionState.value is ConnectionState.Connected

    private fun ensureConnectionSession(expectedGeneration: Long, expectedIdentity: String) {
        if (!isConnectionSessionActive(expectedGeneration, expectedIdentity)) {
            throw CancellationException("Connection generation changed")
        }
    }

    private fun handleAsyncDeviceFault(error: DeviceControlResponse.Error) {
        val snapshot = _state.value.collection
        val activeFile = when (snapshot) {
            is CollectionState.Recording -> snapshot.fileName
            is CollectionState.PausedForOffload -> snapshot.file.name
            is CollectionState.Stopping -> snapshot.fileName
            is CollectionState.Fault -> snapshot.activeFileName
            else -> null
        }
        val freeBytes = when (snapshot) {
            is CollectionState.Idle -> snapshot.freeBytes
            is CollectionState.Recording -> snapshot.freeBytes
            is CollectionState.PausedForOffload -> snapshot.freeBytes
            is CollectionState.Fault -> snapshot.freeBytes
            else -> null
        }
        val message = listOfNotNull(error.code, error.details).joinToString(": ")
        _state.update {
            it.copy(
                collection = CollectionState.Fault(error.code, activeFile, freeBytes),
                operationMessage = "Device fault: $message",
            )
        }
        closePending(DeviceRejectedException(error))
    }

    private fun showOperationError(message: String) {
        _state.update { it.copy(operationMessage = message) }
    }

    private fun clearMessage() {
        _state.update { it.copy(operationMessage = null) }
    }

    private fun Throwable.userMessage(): String = message ?: this::class.simpleName ?: "Unknown error"

    private fun RemoteFileIdentity.toDeviceLogFile() = DeviceLogFile(name, sizeBytes, crc32, true)

    private fun addCatalogFile(catalog: CatalogState, file: DeviceLogFile?): CatalogState {
        if (file == null) return catalog
        val files = (catalog.files.filterNot { it.name == file.name } + file).sortedBy(DeviceLogFile::name)
        return CatalogState.Loaded(files)
    }

    private fun removeCatalogFile(catalog: CatalogState, name: String): CatalogState =
        CatalogState.Loaded(catalog.files.filterNot { it.name == name })

    companion object {
        private const val ASYNC_REQUEST_ID = 0L
        private const val PENDING_EVENT_CAPACITY = 32
        private const val FILE_WRITE_BATCH_BYTES = 8 * 1024
    }
}
