package pl.edu.activitytracker.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.SummaryReading

interface DeviceDataSource {
    val connectionState: StateFlow<ConnectionState>
    val activity: Flow<ActivityReading>
    val battery: Flow<BatteryReading>
    val summary: Flow<SummaryReading>
    val rawEvents: Flow<RawDeviceEvent>

    suspend fun scan()
    suspend fun connect(deviceId: String?)
    suspend fun disconnect()
    suspend fun sendCommand(command: DeviceCommand)
}
