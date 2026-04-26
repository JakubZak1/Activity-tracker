package pl.edu.activitytracker.ble

import java.util.UUID

object BleContract {
    val SERVICE_UUID: UUID = UUID.fromString("7b7d0000-8f7a-4f6a-9f4f-1d2c3b4a5000")
    val CURRENT_ACTIVITY_UUID: UUID = UUID.fromString("7b7d0001-8f7a-4f6a-9f4f-1d2c3b4a5000")
    val BATTERY_UUID: UUID = UUID.fromString("7b7d0002-8f7a-4f6a-9f4f-1d2c3b4a5000")
    val SUMMARY_UUID: UUID = UUID.fromString("7b7d0003-8f7a-4f6a-9f4f-1d2c3b4a5000")
    val COMMAND_UUID: UUID = UUID.fromString("7b7d0004-8f7a-4f6a-9f4f-1d2c3b4a5000")
}
