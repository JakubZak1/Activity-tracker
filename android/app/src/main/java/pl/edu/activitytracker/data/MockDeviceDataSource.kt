package pl.edu.activitytracker.data

import java.util.Locale
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.edu.activitytracker.ble.BlePayloadParser
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DATASET_PROTOCOL_VERSION
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.DeviceStatus
import pl.edu.activitytracker.domain.FileDataFrame
import pl.edu.activitytracker.domain.REQUIRED_DATASET_CAPABILITIES
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.RemoteFileIdentity
import pl.edu.activitytracker.domain.SummaryReading
import pl.edu.activitytracker.domain.Transport
import kotlin.math.max

class MockDeviceDataSource(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val connectDelayMillis: Long = 100L,
    private val frameDelayMillis: Long = 2L,
) : DeviceDataSource {
    private val simulatorLock = Any()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _connectionGeneration = MutableStateFlow(0L)
    override val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()
    private val _deviceIdentity = MutableStateFlow<String?>(null)
    override val deviceIdentity: StateFlow<String?> = _deviceIdentity.asStateFlow()

    private val _activity = MutableSharedFlow<pl.edu.activitytracker.domain.ActivityReading>(replay = 1, extraBufferCapacity = 16)
    override val activity: SharedFlow<pl.edu.activitytracker.domain.ActivityReading> = _activity.asSharedFlow()
    private val _battery = MutableSharedFlow<BatteryReading>(replay = 1, extraBufferCapacity = 4)
    override val battery: SharedFlow<BatteryReading> = _battery.asSharedFlow()
    private val _summary = MutableSharedFlow<SummaryReading>(replay = 1, extraBufferCapacity = 16)
    override val summary: SharedFlow<SummaryReading> = _summary.asSharedFlow()
    private val _rawEvents = MutableSharedFlow<RawDeviceEvent>(extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<RawDeviceEvent> = _rawEvents.asSharedFlow()
    private val protocolChannel = Channel<DeviceProtocolEvent>(64)
    override val protocolEvents = protocolChannel.receiveAsFlow()

    private val logs = linkedMapOf<String, ByteArray>()
    private var telemetryJob: Job? = null
    private var recordingJob: Job? = null
    private var transferJob: Job? = null
    private var recordingLabel: ActivityType? = null
    private var recordingName: String? = null
    private var recordingBuffer: StringBuilder? = null
    private var recordedSamples = 0L
    private var lastFile: RemoteFileIdentity? = null
    private var sessionIndex = 1
    private var steps = 0
    private var batteryPercent = 82
    private var connectEpoch = 0L
    private var timeoutForNextCommand: String? = null
    private var suppressedRequestId: Long? = null
    private var shouldCorruptNextDownload = false

    fun simulateTimeoutForNext(commandName: String) {
        require(commandName in SUPPORTED_COMMAND_NAMES) { "Unsupported mock command: $commandName" }
        synchronized(simulatorLock) {
            timeoutForNextCommand = commandName
        }
    }

    fun corruptNextDownload() {
        synchronized(simulatorLock) {
            shouldCorruptNextDownload = true
        }
    }

    override suspend fun scan() {
        _connectionState.value = ConnectionState.Scanning
        delay(150L)
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun connect(deviceId: String?) {
        val attempt = synchronized(simulatorLock) {
            if (_connectionState.value is ConnectionState.Connected ||
                _connectionState.value is ConnectionState.Connecting
            ) {
                return
            }
            connectEpoch += 1L
            connectEpoch
        }
        _connectionState.value = ConnectionState.Connecting
        delay(connectDelayMillis)
        val stillCurrent = synchronized(simulatorLock) {
            attempt == connectEpoch && _connectionState.value is ConnectionState.Connecting
        }
        if (!stillCurrent) return
        _connectionGeneration.value += 1L
        _deviceIdentity.value = MOCK_DEVICE_IDENTITY
        _connectionState.value = ConnectionState.Connected(Transport.Mock)
        startTelemetry()
    }

    override suspend fun disconnect() {
        synchronized(simulatorLock) {
            connectEpoch += 1L
        }
        transferJob?.cancel()
        transferJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        _connectionGeneration.value += 1L
        _deviceIdentity.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun sendCommand(command: DeviceCommand): Boolean {
        emitRaw("command", command.line)
        if (_connectionState.value !is ConnectionState.Connected) return false
        val commandName = command.commandName()
        val suppressResponse = synchronized(simulatorLock) {
            if (timeoutForNextCommand == commandName) {
                timeoutForNextCommand = null
                suppressedRequestId = command.requestId
                true
            } else {
                false
            }
        }
        if (suppressResponse) emitRaw("simulator_fault", "timeout,$commandName,${command.requestId}")
        when (command) {
            is DeviceCommand.Hello -> emitControl(
                DeviceControlResponse.Hello(command.requestId, DATASET_PROTOCOL_VERSION, REQUIRED_DATASET_CAPABILITIES),
            )
            is DeviceCommand.Status -> emitControl(DeviceControlResponse.Status(command.requestId, currentStatus()))
            is DeviceCommand.RecordStart -> startRecording(command)
            is DeviceCommand.RecordStop -> stopRecording(command.requestId)
            is DeviceCommand.ListLogs -> listLogs(command.requestId)
            is DeviceCommand.Download -> startDownload(command)
            is DeviceCommand.Cancel -> {
                transferJob?.cancel()
                transferJob = null
                emitControl(DeviceControlResponse.Cancelled(command.requestId))
            }
            is DeviceCommand.Delete -> deleteLog(command)
        }
        return true
    }

    private suspend fun startRecording(command: DeviceCommand.RecordStart) {
        var startSamples = false
        val response = synchronized(simulatorLock) {
            val activeLabel = recordingLabel
            val activeName = recordingName
            if (activeLabel != null && activeName != null) {
                if (activeLabel == command.activity) {
                    DeviceControlResponse.RecordingStarted(command.requestId, activeLabel, activeName, true)
                } else {
                    DeviceControlResponse.Error(command.requestId, "already_recording")
                }
            } else {
                val name = "${command.activity.wireName}_${sessionIndex.toString().padStart(4, '0')}.csv"
                sessionIndex += 1
                recordingLabel = command.activity
                recordingName = name
                recordingBuffer = StringBuilder(CSV_HEADER)
                recordedSamples = 0L
                startSamples = true
                DeviceControlResponse.RecordingStarted(command.requestId, command.activity, name, false)
            }
        }
        if (startSamples) startRecordingSamples()
        emitControl(response)
    }

    private suspend fun stopRecording(requestId: Long) {
        val response = synchronized(simulatorLock) {
            val name = recordingName
            if (name == null) {
                DeviceControlResponse.RecordingStopped(requestId, lastFile, true)
            } else {
                recordingJob?.cancel()
                recordingJob = null
                val bytes = recordingBuffer?.toString()?.toByteArray(Charsets.UTF_8) ?: CSV_HEADER.toByteArray()
                logs[name] = bytes
                val identity = RemoteFileIdentity(name, bytes.size.toLong(), crc32(bytes))
                lastFile = identity
                recordingLabel = null
                recordingName = null
                recordingBuffer = null
                DeviceControlResponse.RecordingStopped(requestId, identity, false)
            }
        }
        emitControl(response)
    }

    private fun startRecordingSamples() {
        val job = scope.launch {
            while (isActive) {
                delay(RECORDING_SAMPLE_INTERVAL_MS)
                val appended = synchronized(simulatorLock) {
                    val label = recordingLabel ?: return@synchronized false
                    val buffer = recordingBuffer ?: return@synchronized false
                    val timestamp = recordedSamples * RECORDING_SAMPLE_INTERVAL_MS
                    buffer.append(
                        "$timestamp,${label.wireName},0.0100,0.0200,1.0000,0.1000,0.2000,0.3000\n",
                    )
                    recordedSamples += 1L
                    true
                }
                if (!appended) break
            }
        }
        synchronized(simulatorLock) {
            recordingJob?.cancel()
            recordingJob = job
        }
    }

    private suspend fun listLogs(requestId: Long) {
        val snapshot = synchronized(simulatorLock) {
            if (recordingName != null) null else logs.mapValues { (_, bytes) -> bytes.copyOf() }
        } ?: return emitControl(DeviceControlResponse.Error(requestId, "busy_recording"))
        snapshot.forEach { (name, bytes) ->
            emitControl(
                DeviceControlResponse.FileEntry(
                    requestId,
                    DeviceLogFile(name, bytes.size.toLong(), crc32(bytes), isComplete = true),
                ),
            )
        }
        emitControl(DeviceControlResponse.ListEnd(requestId, snapshot.size))
    }

    private suspend fun startDownload(command: DeviceCommand.Download) {
        val bytes = synchronized(simulatorLock) {
            if (recordingName != null) null else logs[command.fileName]?.copyOf()
        }
        if (synchronized(simulatorLock) { recordingName != null }) {
            return emitControl(DeviceControlResponse.Error(command.requestId, "busy_recording"))
        }
        bytes
            ?: return emitControl(DeviceControlResponse.Error(command.requestId, "file_not_found"))
        if (command.offset < 0L || command.offset > bytes.size.toLong()) {
            emitControl(DeviceControlResponse.Error(command.requestId, "invalid_offset"))
            return
        }
        val corrupt = synchronized(simulatorLock) {
            shouldCorruptNextDownload.also { shouldCorruptNextDownload = false }
        }
        val transferredBytes = bytes.copyOf()
        if (corrupt && command.offset < transferredBytes.size) {
            val index = command.offset.toInt()
            transferredBytes[index] = (transferredBytes[index].toInt() xor 0x01).toByte()
            emitRaw("simulator_fault", "corrupt_download,${command.fileName},${command.requestId}")
        }
        transferJob?.cancel()
        transferJob = scope.launch {
            val identity = RemoteFileIdentity(command.fileName, bytes.size.toLong(), crc32(bytes))
            emitControl(DeviceControlResponse.DownloadBegin(command.requestId, identity, command.offset))
            var offset = command.offset.toInt()
            while (offset < transferredBytes.size && isActive) {
                val end = minOf(offset + MOCK_FRAME_DATA_BYTES, transferredBytes.size)
                if (!isResponseSuppressed(command.requestId)) {
                    protocolChannel.send(
                        DeviceProtocolEvent.FileData(
                            _connectionGeneration.value,
                            FileDataFrame(command.requestId, offset.toLong(), transferredBytes.copyOfRange(offset, end)),
                        ),
                    )
                }
                offset = end
                delay(frameDelayMillis)
            }
            if (isActive) emitControl(DeviceControlResponse.DownloadEnd(command.requestId, identity))
        }
    }

    private suspend fun deleteLog(command: DeviceCommand.Delete) {
        val bytes = synchronized(simulatorLock) {
            if (recordingName != null) null else logs[command.file.name]?.copyOf()
        }
        if (synchronized(simulatorLock) { recordingName != null }) {
            return emitControl(DeviceControlResponse.Error(command.requestId, "busy_recording"))
        }
        if (bytes == null) {
            emitControl(DeviceControlResponse.Deleted(command.requestId, command.file.name, true))
            return
        }
        val actual = RemoteFileIdentity(command.file.name, bytes.size.toLong(), crc32(bytes))
        if (actual != command.file) {
            emitControl(DeviceControlResponse.Error(command.requestId, "file_identity_mismatch"))
            return
        }
        synchronized(simulatorLock) {
            logs.remove(command.file.name)
            if (lastFile == command.file) lastFile = null
        }
        emitControl(DeviceControlResponse.Deleted(command.requestId, command.file.name, false))
    }

    private fun currentStatus(): DeviceStatus = synchronized(simulatorLock) {
        val label = recordingLabel
        val name = recordingName
        if (label != null && name != null) {
            DeviceStatus.Recording(
                label,
                name,
                recordedSamples * RECORDING_SAMPLE_INTERVAL_MS,
                recordingBuffer?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L,
                MOCK_FREE_BYTES,
            )
        } else {
            DeviceStatus.Idle(lastFile, MOCK_FREE_BYTES)
        }
    }

    private fun startTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            var tick = 0L
            var currentActivity = ActivityType.Walking
            var activityStartedTick = 0L
            emitBattery()
            while (isActive) {
                val nextActivity = activityForTick(tick)
                if (nextActivity != currentActivity) {
                    currentActivity = nextActivity
                    activityStartedTick = tick
                }
                val activityPayload = "${currentActivity.wireName},${confidenceFor(currentActivity, tick)},${tick - activityStartedTick}"
                emitRaw("current_activity", activityPayload)
                BlePayloadParser.parseActivity(activityPayload)?.let { _activity.emit(it) }

                if (currentActivity == ActivityType.Walking) steps += 2
                if (currentActivity == ActivityType.Running) steps += 3
                val summaryPayload = "$tick,${currentActivity.wireName},$steps"
                emitRaw("summary", summaryPayload)
                BlePayloadParser.parseSummary(summaryPayload)?.let { _summary.emit(it) }
                if (tick % 30L == 0L && tick != 0L) {
                    batteryPercent = max(15, batteryPercent - 1)
                    emitBattery()
                }
                tick += 1L
                delay(1_000L)
            }
        }
    }

    private suspend fun emitBattery() {
        val payload = "${3300 + batteryPercent * 8},$batteryPercent"
        emitRaw("battery", payload)
        BlePayloadParser.parseBattery(payload)?.let { _battery.emit(it) }
    }

    private suspend fun emitControl(response: DeviceControlResponse) {
        if (isResponseSuppressed(response.requestId)) return
        protocolChannel.send(DeviceProtocolEvent.Control(_connectionGeneration.value, response))
    }

    private fun isResponseSuppressed(requestId: Long): Boolean =
        synchronized(simulatorLock) { suppressedRequestId == requestId }

    private fun DeviceCommand.commandName(): String = when (this) {
        is DeviceCommand.Hello -> "hello"
        is DeviceCommand.Status -> "status"
        is DeviceCommand.RecordStart -> "record_start"
        is DeviceCommand.RecordStop -> "record_stop"
        is DeviceCommand.ListLogs -> "list"
        is DeviceCommand.Download -> "download"
        is DeviceCommand.Cancel -> "cancel"
        is DeviceCommand.Delete -> "delete"
    }

    private suspend fun emitRaw(source: String, payload: String) {
        _rawEvents.emit(RawDeviceEvent(source, payload, System.currentTimeMillis()))
    }

    private fun activityForTick(tick: Long): ActivityType = when ((tick / 18L) % 5L) {
        0L -> ActivityType.Walking
        1L -> ActivityType.Running
        2L -> ActivityType.Sitting
        3L -> ActivityType.Cycling
        else -> ActivityType.Lying
    }

    private fun confidenceFor(activityType: ActivityType, tick: Long): Int {
        val wobble = (tick % 8L).toInt()
        return when (activityType) {
            ActivityType.Walking -> 78 + wobble
            ActivityType.Running -> 84 + wobble
            ActivityType.Sitting -> 72 + wobble
            ActivityType.Lying -> 75 + wobble
            ActivityType.Cycling -> 81 + wobble
            ActivityType.Unknown -> 0
        }.coerceIn(0, 99)
    }

    private fun crc32(bytes: ByteArray): String {
        val crc = CRC32().apply { update(bytes) }
        return String.format(Locale.US, "%08X", crc.value)
    }

    companion object {
        private const val MOCK_DEVICE_IDENTITY = "mock:activity-tracker-v3"
        private const val MOCK_FREE_BYTES = 1_800_000L
        private const val MOCK_FRAME_DATA_BYTES = 12
        private const val RECORDING_SAMPLE_INTERVAL_MS = 20L
        private val SUPPORTED_COMMAND_NAMES = setOf(
            "hello",
            "status",
            "record_start",
            "record_stop",
            "list",
            "download",
            "cancel",
            "delete",
        )
        private const val CSV_HEADER =
            "timestamp_ms,label,acc_x_g,acc_y_g,acc_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps\n"
    }
}
