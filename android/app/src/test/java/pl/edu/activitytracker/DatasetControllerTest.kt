package pl.edu.activitytracker

import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.edu.activitytracker.data.ActivityTrackerRepository
import pl.edu.activitytracker.data.DatasetController
import pl.edu.activitytracker.data.DatasetWorkRuntime
import pl.edu.activitytracker.data.DeviceDataSource
import pl.edu.activitytracker.data.MockDeviceDataSource
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.BodySide
import pl.edu.activitytracker.domain.CalorieCalculator
import pl.edu.activitytracker.domain.CatalogState
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DATASET_PROTOCOL_VERSION
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.DatasetSessionMetadata
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.DeviceStatus
import pl.edu.activitytracker.domain.LocationSample
import pl.edu.activitytracker.domain.LocationStatus
import pl.edu.activitytracker.domain.REQUIRED_DATASET_CAPABILITIES
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.RemoteFileIdentity
import pl.edu.activitytracker.domain.SensorPlacement
import pl.edu.activitytracker.domain.SummaryReading
import pl.edu.activitytracker.domain.TransferState
import pl.edu.activitytracker.domain.Transport
import pl.edu.activitytracker.gps.LocationTracker
import pl.edu.activitytracker.session.PhoneSessionController
import pl.edu.activitytracker.storage.DatasetFileStore
import pl.edu.activitytracker.storage.SettingsDataSource
import pl.edu.activitytracker.storage.SettingsStore
import pl.edu.activitytracker.storage.SettingsUiState

@OptIn(ExperimentalCoroutinesApi::class)
class DatasetControllerTest {
    @Test
    fun protocolVersionMismatchStopsHandshakeBeforeStatus() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        device.handler = { command ->
            when (command) {
                is DeviceCommand.Hello -> respond(
                    DeviceControlResponse.Hello(
                        command.requestId,
                        DATASET_PROTOCOL_VERSION - 1,
                        TEST_DEVICE_ID,
                        TEST_SHORT_ID,
                        REQUIRED_DATASET_CAPABILITIES,
                    ),
                )
                else -> error("Handshake must stop after an incompatible hello: $command")
            }
        }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        runCurrent()

        device.connect(null)
        runCurrent()

        assertTrue(controller.state.value.connection is DatasetConnectionState.Incompatible)
        assertEquals(1, device.commands.size)
        assertTrue(device.commands.single() is DeviceCommand.Hello)
    }

    @Test
    fun missingFifoAcquisitionCapabilityRejectsOlderV5Firmware() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        device.handler = { command ->
            when (command) {
                is DeviceCommand.Hello -> respond(
                    DeviceControlResponse.Hello(
                        command.requestId,
                        DATASET_PROTOCOL_VERSION,
                        TEST_DEVICE_ID,
                        TEST_SHORT_ID,
                        REQUIRED_DATASET_CAPABILITIES - "imu_drdy104_mean2_52_deadline_guard",
                    ),
                )
                else -> error("Handshake must stop when guarded IMU acquisition is missing: $command")
            }
        }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        runCurrent()

        device.connect(null)
        runCurrent()

        assertTrue(controller.state.value.connection is DatasetConnectionState.Incompatible)
        assertEquals(1, device.commands.size)
        assertTrue(device.commands.single() is DeviceCommand.Hello)
    }

    @Test
    fun rapidStartTapsProduceOneAtomicRecordStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        var startCount = 0
        device.handler = { command ->
            when (command) {
                is DeviceCommand.Hello -> respond(
                    testHello(command.requestId),
                )
                is DeviceCommand.Status -> respond(DeviceControlResponse.Status(command.requestId, DeviceStatus.Idle(null, 1000)))
                is DeviceCommand.ListLogs -> respond(DeviceControlResponse.ListEnd(command.requestId, 0))
                is DeviceCommand.RecordStart -> {
                    startCount += 1
                    respond(
                        DeviceControlResponse.RecordingStarted(
                            command.requestId,
                            command.activity,
                            "walking_0001.csv",
                            false,
                        ),
                    )
                }
                else -> error("Unexpected $command")
            }
        }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        controller.setDataFolderUri("memory://logs")
        runCurrent()
        device.connect(null)
        runCurrent()

        controller.startRecording(ActivityType.Walking, SensorPlacement.Wrist, BodySide.Left)
        controller.startRecording(ActivityType.Walking, SensorPlacement.Wrist, BodySide.Left)
        controller.startRecording(ActivityType.Walking, SensorPlacement.Wrist, BodySide.Left)
        runCurrent()

        assertEquals(1, startCount)
        assertTrue(controller.state.value.collection is CollectionState.Recording)
    }

    @Test
    fun mutationTimeoutReconcilesStatusWithoutBlindRetryAndReconnects() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        var startCount = 0
        var boardStatus: DeviceStatus = DeviceStatus.Idle(null, 1000)
        device.handler = { command ->
            when (command) {
                is DeviceCommand.Hello -> respond(
                    testHello(command.requestId),
                )
                is DeviceCommand.Status -> respond(DeviceControlResponse.Status(command.requestId, boardStatus))
                is DeviceCommand.ListLogs -> respond(DeviceControlResponse.ListEnd(command.requestId, 0))
                is DeviceCommand.RecordStart -> {
                    startCount += 1
                    boardStatus = DeviceStatus.Recording(
                        command.activity,
                        "walking_0001.csv",
                        0,
                        80,
                        900,
                    )
                    // Simulate a lost mutation response. The controller must query status, not resend start.
                }
                else -> error("Unexpected $command")
            }
        }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher, timeout = 100)
        controller.setDataFolderUri("memory://logs")
        runCurrent()
        device.connect(null)
        runCurrent()
        controller.startRecording(ActivityType.Walking, SensorPlacement.Wrist, BodySide.Left)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertEquals(1, startCount)
        assertTrue(controller.state.value.collection is CollectionState.Recording)

        device.disconnect()
        runCurrent()
        assertEquals(CollectionState.Unknown, controller.state.value.collection)
        device.connect(null)
        runCurrent()
        assertTrue(controller.state.value.connection is DatasetConnectionState.Ready)
        assertTrue(controller.state.value.collection is CollectionState.Recording)
        assertEquals(1, startCount)
    }

    @Test
    fun startWithoutStorageFolderNeverSendsRecordStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource().apply { installIdleProtocolHandler() }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        runCurrent()
        device.connect(null)
        runCurrent()

        controller.startRecording(ActivityType.Walking, SensorPlacement.Wrist, BodySide.Left)
        runCurrent()

        assertTrue(device.commands.none { it is DeviceCommand.RecordStart })
        assertTrue(controller.state.value.operationMessage.orEmpty().contains("folder", ignoreCase = true))
        assertTrue(controller.state.value.collection is CollectionState.Idle)
    }

    @Test
    fun startWithRevokedStorageFolderNeverSendsRecordStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource().apply { installIdleProtocolHandler() }
        val store = MemoryFileStore().apply { folderAvailable = false }
        val controller = controller(device, store, backgroundScope, dispatcher)
        controller.setDataFolderUri("memory://revoked")
        runCurrent()
        device.connect(null)
        runCurrent()

        controller.startRecording(ActivityType.Walking)
        runCurrent()

        assertTrue(device.commands.none { it is DeviceCommand.RecordStart })
        assertTrue(controller.state.value.operationMessage.orEmpty().contains("unavailable", ignoreCase = true))
        assertTrue(controller.state.value.collection is CollectionState.Idle)
    }

    @Test
    fun startWithoutPlacementNeverSendsRecordStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource().apply { installIdleProtocolHandler() }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        controller.setDataFolderUri("memory://logs")
        runCurrent()
        device.connect(null)
        runCurrent()

        controller.startRecording(ActivityType.Walking)
        runCurrent()

        assertTrue(device.commands.none { it is DeviceCommand.RecordStart })
        assertTrue(controller.state.value.operationMessage.orEmpty().contains("placement", ignoreCase = true))
        assertTrue(controller.state.value.collection is CollectionState.Idle)
    }

    @Test
    fun transferResumesChecksCrcAndOnlyThenAllowsDelete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bytes = "timestamp_ms,label\n0,walking\n1,walking\n".toByteArray()
        val identity = RemoteFileIdentity("walking_0001.csv", bytes.size.toLong(), crc32(bytes))
        val file = DeviceLogFile(identity.name, identity.sizeBytes, identity.crc32, true)
        val store = MemoryFileStore().apply { partials[file.name] = bytes.copyOfRange(0, 7).toMutableList() }
        val device = FakeDeviceDataSource()
        var requestedOffset = -1L
        var deleteCount = 0
        device.handler = { command ->
            when (command) {
                is DeviceCommand.Hello -> respond(testHello(command.requestId))
                is DeviceCommand.Status -> respond(DeviceControlResponse.Status(command.requestId, DeviceStatus.Idle(identity, 1000)))
                is DeviceCommand.ListLogs -> {
                    respond(DeviceControlResponse.FileEntry(command.requestId, file))
                    respond(DeviceControlResponse.ListEnd(command.requestId, 1))
                }
                is DeviceCommand.Download -> {
                    requestedOffset = command.offset
                    respond(DeviceControlResponse.DownloadBegin(command.requestId, identity, command.offset))
                    emitFile(command.requestId, command.offset, bytes.copyOfRange(command.offset.toInt(), bytes.size))
                    respond(DeviceControlResponse.DownloadEnd(command.requestId, identity))
                }
                is DeviceCommand.Delete -> {
                    deleteCount += 1
                    assertEquals(identity, command.file)
                    respond(DeviceControlResponse.Deleted(command.requestId, identity.name, false))
                }
                else -> error("Unexpected $command")
            }
        }
        val controller = controller(device, store, backgroundScope, dispatcher)
        controller.setDataFolderUri("memory://logs")
        runCurrent()
        device.connect(null)
        runCurrent()
        controller.downloadLog(file)
        runCurrent()

        assertEquals(7L, requestedOffset)
        assertTrue(controller.state.value.transfer is TransferState.Completed)
        assertEquals(1, deleteCount)
        assertFalse(identity in controller.state.value.verifiedFiles)
        assertTrue(controller.state.value.catalog.files.isEmpty())
    }

    @Test
    fun crcFailureNeverMarksFileVerifiedOrSendsDelete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val expected = "expected bytes".toByteArray()
        val received = "corrupted data".toByteArray()
        assertEquals(expected.size, received.size)
        val identity = RemoteFileIdentity("walking_0002.csv", expected.size.toLong(), crc32(expected))
        val file = DeviceLogFile(identity.name, identity.sizeBytes, identity.crc32, true)
        val device = FakeDeviceDataSource()
        var deleteCount = 0
        device.handler = { command ->
            when (command) {
                is DeviceCommand.Hello -> respond(testHello(command.requestId))
                is DeviceCommand.Status -> respond(DeviceControlResponse.Status(command.requestId, DeviceStatus.Idle(identity, 1000)))
                is DeviceCommand.ListLogs -> {
                    respond(DeviceControlResponse.FileEntry(command.requestId, file))
                    respond(DeviceControlResponse.ListEnd(command.requestId, 1))
                }
                is DeviceCommand.Download -> {
                    respond(DeviceControlResponse.DownloadBegin(command.requestId, identity, 0))
                    emitFile(command.requestId, 0, received)
                    respond(DeviceControlResponse.DownloadEnd(command.requestId, identity))
                }
                is DeviceCommand.Delete -> deleteCount += 1
                else -> error("Unexpected $command")
            }
        }
        val controller = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        controller.setDataFolderUri("memory://logs")
        runCurrent()
        device.connect(null)
        runCurrent()
        controller.downloadLog(file)
        runCurrent()

        assertTrue(controller.state.value.transfer is TransferState.Error)
        assertFalse(identity in controller.state.value.verifiedFiles)
        controller.deleteLog(file)
        runCurrent()
        assertEquals(0, deleteCount)
    }

    @Test
    fun mockSourceRunsFullRecordDownloadAndDeleteWorkflow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mock = MockDeviceDataSource(backgroundScope, connectDelayMillis = 0, frameDelayMillis = 0)
        val store = MemoryFileStore()
        val controller = controller(mock, store, backgroundScope, dispatcher)
        controller.setDataFolderUri("memory://logs")
        runCurrent()
        mock.connect(null)
        runCurrent()
        assertTrue(controller.state.value.connection is DatasetConnectionState.Ready)

        controller.startRecording(ActivityType.Cycling, SensorPlacement.Leg, BodySide.Right)
        runCurrent()
        assertTrue(controller.state.value.collection is CollectionState.Recording)
        advanceTimeBy(1_001)
        runCurrent()
        controller.stopRecording()
        runCurrent()

        assertTrue(controller.state.value.transfer is TransferState.Completed)
        assertTrue(controller.state.value.catalog.files.isEmpty())
        val metadata = store.sessions.values.single()
        assertEquals(ActivityType.Cycling, metadata.activity)
        assertEquals(SensorPlacement.Leg, metadata.placement)
        assertEquals(BodySide.Right, metadata.bodySide)
    }

    @Test
    fun recordingRotatesOffloadsAndDeletesClosedSegmentsWhileItContinues() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mock = MockDeviceDataSource(
            backgroundScope,
            connectDelayMillis = 0,
            frameDelayMillis = 0,
            segmentMaxSamples = 10,
        )
        val store = MemoryFileStore()
        val runtime = FakeDatasetWorkRuntime()
        val controller = DatasetController(
            deviceDataSource = mock,
            fileStore = store,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            commandTimeoutMillis = 500,
            catalogInactivityMillis = 500,
            fileInactivityMillis = 500,
            workRuntime = runtime,
            autoOffloadPollMillis = 50,
        )
        controller.setDataFolderUri("memory://logs")
        mock.connect(null)
        runCurrent()

        controller.startRecording(ActivityType.Walking, SensorPlacement.Wrist, BodySide.Left)
        runCurrent()
        advanceTimeBy(401)
        runCurrent()

        assertTrue(controller.state.value.collection is CollectionState.Recording)
        assertTrue(store.partials.isNotEmpty())
        assertTrue(store.sessions.isNotEmpty())
        assertTrue(store.sessions.values.all { it.placement == SensorPlacement.Wrist && it.bodySide == BodySide.Left })
        assertEquals(1, store.sessions.values.map { it.sessionId }.distinct().size)
        assertTrue(mock.snapshotClosedLogNames().isEmpty())
        assertTrue(runtime.running)

        controller.stopRecording()
        runCurrent()
        advanceTimeBy(51)
        runCurrent()

        assertTrue(controller.state.value.collection is CollectionState.Idle)
        assertTrue(mock.snapshotClosedLogNames().isEmpty())
        assertFalse(runtime.running)
    }

    @Test
    fun phoneHomeSessionDoesNotSendAnyDatasetCommand() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        val dataset = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        val location = FakeLocationTracker()
        val phoneSession = FakePhoneSessionController()
        val repository = ActivityTrackerRepository(
            deviceDataSource = device,
            datasetController = dataset,
            locationTracker = location,
            sessionRecordingController = phoneSession,
            settingsStore = FakeSettingsStore(),
            scope = backgroundScope,
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
        runCurrent()

        repository.startSession()
        repository.stopSession()
        repository.resetSession()
        runCurrent()

        assertTrue(device.commands.isEmpty())
        assertEquals(1, phoneSession.starts)
        assertEquals(2, phoneSession.stops)
        assertEquals(1, location.starts)
        assertEquals(2, location.stops)
    }

    @Test
    fun phoneSessionUsesDeviceStepDeltasAndSurvivesCounterReset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        val dataset = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        val repository = ActivityTrackerRepository(
            deviceDataSource = device,
            datasetController = dataset,
            locationTracker = FakeLocationTracker(),
            sessionRecordingController = FakePhoneSessionController(),
            settingsStore = FakeSettingsStore(),
            scope = backgroundScope,
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
        runCurrent()

        device.emitSummary(100)
        runCurrent()
        repository.startSession()
        device.emitSummary(104)
        device.emitSummary(109)
        runCurrent()
        assertEquals(9, repository.state.value.sessionSteps)

        repository.stopSession()
        device.emitSummary(115)
        runCurrent()
        assertEquals(9, repository.state.value.sessionSteps)

        repository.startSession()
        device.emitSummary(118)
        device.emitSummary(2) // Device reboot: the new counter starts again at zero.
        runCurrent()
        assertEquals(5, repository.state.value.sessionSteps)
    }

    @Test
    fun phoneSessionUsesMonotonicDurationAndIntegratesCaloriesByActivity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource()
        val dataset = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        val repository = ActivityTrackerRepository(
            deviceDataSource = device,
            datasetController = dataset,
            locationTracker = FakeLocationTracker(),
            sessionRecordingController = FakePhoneSessionController(),
            settingsStore = FakeSettingsStore(),
            scope = backgroundScope,
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )
        runCurrent()

        device.emitActivity(ActivityType.Walking)
        runCurrent()
        repository.startSession()
        advanceTimeBy(10_000L)
        runCurrent()

        device.emitActivity(ActivityType.Running)
        runCurrent()
        advanceTimeBy(20_000L)
        runCurrent()

        val expected = CalorieCalculator.caloriesFor(ActivityType.Walking, 70.0, 10.0 / 60.0) +
            CalorieCalculator.caloriesFor(ActivityType.Running, 70.0, 20.0 / 60.0)
        assertEquals(30L, repository.state.value.sessionDurationSeconds)
        assertEquals(expected, repository.state.value.caloriesKcal, 0.000001)

        repository.stopSession()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(30L, repository.state.value.sessionDurationSeconds)
        assertEquals(expected, repository.state.value.caloriesKcal, 0.000001)
    }

    @Test
    fun unexpectedDisconnectReconnectsAfterBackoffButManualDisconnectDoesNot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = FakeDeviceDataSource().apply {
            transport = Transport.Ble
            installIdleProtocolHandler()
        }
        val dataset = controller(device, MemoryFileStore(), backgroundScope, dispatcher)
        val repository = ActivityTrackerRepository(
            deviceDataSource = device,
            datasetController = dataset,
            locationTracker = FakeLocationTracker(),
            sessionRecordingController = FakePhoneSessionController(),
            settingsStore = FakeSettingsStore(),
            scope = backgroundScope,
            reconnectDelaysMillis = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L),
        )
        runCurrent()

        repository.connect()
        runCurrent()
        assertEquals(1, device.connectCalls)

        device.dropUnexpectedly()
        runCurrent()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, device.connectCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, device.connectCalls)
        assertEquals("fake-device", device.connectIds.last())

        repository.disconnect()
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, device.connectCalls)
    }

    private fun controller(
        device: DeviceDataSource,
        store: DatasetFileStore,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        timeout: Long = 500,
    ) = DatasetController(
        deviceDataSource = device,
        fileStore = store,
        scope = scope,
        ioDispatcher = dispatcher,
        commandTimeoutMillis = timeout,
        catalogInactivityMillis = timeout,
        fileInactivityMillis = timeout,
    )

    private class FakeDeviceDataSource : DeviceDataSource {
        private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _connection.asStateFlow()
        private val _generation = MutableStateFlow(0L)
        override val connectionGeneration: StateFlow<Long> = _generation.asStateFlow()
        private val _identity = MutableStateFlow<String?>(null)
        override val deviceIdentity: StateFlow<String?> = _identity.asStateFlow()
        private val _activity = MutableStateFlow(ActivityReading.unknown(0L))
        override val activity: Flow<ActivityReading> = _activity
        override val battery: Flow<BatteryReading> = emptyFlow()
        private val _summary = MutableSharedFlow<SummaryReading>(replay = 1, extraBufferCapacity = 8)
        override val summary: Flow<SummaryReading> = _summary
        override val rawEvents: Flow<RawDeviceEvent> = emptyFlow()
        private val events = Channel<DeviceProtocolEvent>(64)
        override val protocolEvents = events.receiveAsFlow()
        val commands = mutableListOf<DeviceCommand>()
        val connectIds = mutableListOf<String?>()
        var connectCalls = 0
        var transport: Transport = Transport.Mock
        var handler: suspend FakeDeviceDataSource.(DeviceCommand) -> Unit = {}

        override suspend fun scan() = Unit

        override suspend fun connect(deviceId: String?) {
            connectCalls += 1
            connectIds += deviceId
            _generation.value += 1
            _identity.value = "fake-device"
            _connection.value = ConnectionState.Connected(transport)
        }

        override suspend fun disconnect() {
            _generation.value += 1
            _identity.value = null
            _connection.value = ConnectionState.Disconnected
        }

        override suspend fun sendCommand(command: DeviceCommand): Boolean {
            commands += command
            handler(command)
            return true
        }

        suspend fun respond(response: DeviceControlResponse) {
            events.send(DeviceProtocolEvent.Control(_generation.value, response))
        }

        suspend fun emitFile(requestId: Long, offset: Long, bytes: ByteArray) {
            events.send(
                DeviceProtocolEvent.FileData(
                    _generation.value,
                    pl.edu.activitytracker.domain.FileDataFrame(requestId, offset, bytes),
                ),
            )
        }

        fun dropUnexpectedly() {
            _generation.value += 1
            _identity.value = null
            _connection.value = ConnectionState.Disconnected
        }

        suspend fun emitSummary(steps: Int) {
            _summary.emit(
                SummaryReading(
                    sessionDurationSeconds = 0L,
                    currentActivity = ActivityType.Walking,
                    steps = steps,
                    timestampMillis = 0L,
                ),
            )
        }

        fun emitActivity(type: ActivityType) {
            _activity.value = ActivityReading(
                type = type,
                confidencePercent = 100,
                durationSeconds = 0L,
                timestampMillis = 0L,
            )
        }

        fun installIdleProtocolHandler() {
            handler = { command ->
                when (command) {
                    is DeviceCommand.Hello -> respond(
                        DeviceControlResponse.Hello(
                            command.requestId,
                            DATASET_PROTOCOL_VERSION,
                            TEST_DEVICE_ID,
                            TEST_SHORT_ID,
                            REQUIRED_DATASET_CAPABILITIES,
                        ),
                    )
                    is DeviceCommand.Status -> respond(
                        DeviceControlResponse.Status(command.requestId, DeviceStatus.Idle(null, 1_000L)),
                    )
                    is DeviceCommand.ListLogs -> respond(DeviceControlResponse.ListEnd(command.requestId, 0))
                    else -> error("Unexpected $command")
                }
            }
        }
    }

    private class MemoryFileStore : DatasetFileStore {
        val partials = mutableMapOf<String, MutableList<Byte>>()
        val sessions = mutableMapOf<String, DatasetSessionMetadata>()
        private val verified = mutableSetOf<RemoteFileIdentity>()
        var folderAvailable = true

        private inner class Sink(
            override val file: RemoteFileIdentity,
            private val bytes: MutableList<Byte>,
        ) : DatasetFileStore.DownloadSink {
            override val position: Long get() = bytes.size.toLong()
            override fun write(bytes: ByteArray) { this.bytes += bytes.toList() }
            override fun close() = Unit
        }

        override fun prepare(
            treeUri: String,
            deviceIdentity: String,
            file: RemoteFileIdentity,
            sessionMetadata: DatasetSessionMetadata?,
        ): DatasetFileStore.PrepareResult {
            if (file in verified) return DatasetFileStore.PrepareResult.AlreadyComplete
            sessionMetadata?.let { sessions[file.name] = it }
            val bytes = partials.getOrPut(file.name) { mutableListOf() }
            return DatasetFileStore.PrepareResult.Ready(Sink(file, bytes), bytes.size.toLong())
        }

        override fun complete(sink: DatasetFileStore.DownloadSink): DatasetFileStore.CompleteResult {
            val bytes = partials.getValue(sink.file.name).toByteArray()
            return if (bytes.size.toLong() == sink.file.sizeBytes && crc32(bytes) == sink.file.crc32) {
                verified += sink.file
                DatasetFileStore.CompleteResult.Success
            } else {
                DatasetFileStore.CompleteResult.Failure("CRC32 mismatch")
            }
        }

        override fun isVerified(
            treeUri: String,
            deviceIdentity: String,
            file: RemoteFileIdentity,
        ): Boolean = file in verified

        override fun isFolderAvailable(treeUri: String): Boolean = folderAvailable
    }

    private class FakeLocationTracker : LocationTracker {
        override val locations = MutableSharedFlow<LocationSample>()
        override val status = MutableStateFlow<LocationStatus>(LocationStatus.Idle)
        var starts = 0
        var stops = 0
        override fun start() { starts += 1 }
        override fun stop() { stops += 1 }
    }

    private class FakePhoneSessionController : PhoneSessionController {
        var starts = 0
        var stops = 0
        override fun startIfLocationAllowed() { starts += 1 }
        override fun stop() { stops += 1 }
    }

    private class FakeDatasetWorkRuntime : DatasetWorkRuntime {
        var running = false
        override fun setActive(active: Boolean) {
            running = active
        }
    }

    private class FakeSettingsStore : SettingsDataSource {
        override val settings = MutableStateFlow(SettingsUiState())
        override suspend fun setDataFolderUri(uri: String) {
            settings.value = settings.value.copy(dataFolderUri = uri)
        }
    }

    companion object {
        private const val TEST_DEVICE_ID = "11111111A1B2C3D4"
        private const val TEST_SHORT_ID = "A1B2C3D4"

        private fun testHello(requestId: Long) = DeviceControlResponse.Hello(
            requestId,
            DATASET_PROTOCOL_VERSION,
            TEST_DEVICE_ID,
            TEST_SHORT_ID,
            REQUIRED_DATASET_CAPABILITIES,
        )

        private fun crc32(bytes: ByteArray): String {
            val crc = CRC32().apply { update(bytes) }
            return String.format(Locale.US, "%08X", crc.value)
        }
    }
}
