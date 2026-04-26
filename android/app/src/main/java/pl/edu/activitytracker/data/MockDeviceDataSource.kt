package pl.edu.activitytracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.edu.activitytracker.ble.BlePayloadParser
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.SummaryReading
import pl.edu.activitytracker.domain.Transport
import kotlin.math.max

class MockDeviceDataSource : DeviceDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activity = MutableSharedFlow<pl.edu.activitytracker.domain.ActivityReading>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    override val activity: SharedFlow<pl.edu.activitytracker.domain.ActivityReading> = _activity.asSharedFlow()

    private val _battery = MutableSharedFlow<BatteryReading>(
        replay = 1,
        extraBufferCapacity = 4,
    )
    override val battery: SharedFlow<BatteryReading> = _battery.asSharedFlow()

    private val _summary = MutableSharedFlow<SummaryReading>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    override val summary: SharedFlow<SummaryReading> = _summary.asSharedFlow()

    private val _rawEvents = MutableSharedFlow<RawDeviceEvent>(extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<RawDeviceEvent> = _rawEvents.asSharedFlow()

    private var telemetryJob: Job? = null
    private var sessionRunning = false
    private var sessionStartedAtMillis: Long? = null
    private var steps = 0
    private var batteryPercent = 82

    override suspend fun scan() {
        _connectionState.value = ConnectionState.Scanning
        delay(500L)
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun connect(deviceId: String?) {
        if (_connectionState.value is ConnectionState.Connected) {
            return
        }

        _connectionState.value = ConnectionState.Connecting
        delay(400L)
        _connectionState.value = ConnectionState.Connected(Transport.Mock)
        startTelemetry()
    }

    override suspend fun disconnect() {
        telemetryJob?.cancel()
        telemetryJob = null
        sessionRunning = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        emitRaw("command", command.payload)
        when (command) {
            DeviceCommand.Start -> {
                sessionRunning = true
                sessionStartedAtMillis = System.currentTimeMillis()
                steps = 0
            }
            DeviceCommand.Stop -> {
                sessionRunning = false
            }
            DeviceCommand.ResetSession -> {
                sessionRunning = false
                sessionStartedAtMillis = null
                steps = 0
            }
            DeviceCommand.Status,
            DeviceCommand.ModeDataset,
            DeviceCommand.ModeInference -> Unit
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

                val confidence = confidenceFor(currentActivity, tick)
                val activityDuration = tick - activityStartedTick
                val activityPayload = "${currentActivity.wireName},$confidence,$activityDuration"
                emitRaw("current_activity", activityPayload)
                BlePayloadParser.parseActivity(activityPayload)?.let { _activity.emit(it) }

                if (sessionRunning && currentActivity == ActivityType.Walking) {
                    steps += 2
                } else if (sessionRunning && currentActivity == ActivityType.Running) {
                    steps += 3
                }

                val sessionDuration = sessionStartedAtMillis
                    ?.let { ((System.currentTimeMillis() - it) / 1_000L).coerceAtLeast(0L) }
                    ?: 0L
                val summaryPayload = "$sessionDuration,${currentActivity.wireName},$steps"
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
        val voltage = 3300 + batteryPercent * 8
        val payload = "$voltage,$batteryPercent"
        emitRaw("battery", payload)
        BlePayloadParser.parseBattery(payload)?.let { _battery.emit(it) }
    }

    private suspend fun emitRaw(source: String, payload: String) {
        _rawEvents.emit(
            RawDeviceEvent(
                source = source,
                payload = payload,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun activityForTick(tick: Long): ActivityType {
        return when ((tick / 18L) % 5L) {
            0L -> ActivityType.Walking
            1L -> ActivityType.Running
            2L -> ActivityType.Sitting
            3L -> ActivityType.Cycling
            else -> ActivityType.Lying
        }
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
}
