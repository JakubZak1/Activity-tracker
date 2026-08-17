package pl.edu.activitytracker.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.SummaryReading

@OptIn(ExperimentalCoroutinesApi::class)
class SelectableDeviceDataSource(
    useMockSource: Flow<Boolean>,
    private val bleSource: DeviceDataSource,
    private val mockSource: DeviceDataSource,
) : DeviceDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeSource = MutableStateFlow(bleSource)

    override val connectionState: StateFlow<ConnectionState> = activeSource
        .flatMapLatest { it.connectionState }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ConnectionState.Disconnected)

    override val activity: Flow<ActivityReading> = activeSource.flatMapLatest { it.activity }
    override val battery: Flow<BatteryReading> = activeSource.flatMapLatest { it.battery }
    override val summary: Flow<SummaryReading> = activeSource.flatMapLatest { it.summary }
    override val rawEvents: Flow<RawDeviceEvent> = activeSource.flatMapLatest { it.rawEvents }
    override val protocolEvents: Flow<DeviceProtocolEvent> = activeSource.flatMapLatest { it.protocolEvents }

    init {
        scope.launch {
            useMockSource.distinctUntilChanged().collect { useMock ->
                val nextSource = if (useMock) mockSource else bleSource
                if (activeSource.value !== nextSource) {
                    activeSource.value.disconnect()
                    activeSource.value = nextSource
                }
            }
        }
    }

    override suspend fun scan() = activeSource.value.scan()

    override suspend fun connect(deviceId: String?) = activeSource.value.connect(deviceId)

    override suspend fun disconnect() = activeSource.value.disconnect()

    override suspend fun sendCommand(command: DeviceCommand) = activeSource.value.sendCommand(command)
}
