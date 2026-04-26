package pl.edu.activitytracker.domain

data class ActivityReading(
    val type: ActivityType,
    val confidencePercent: Int,
    val durationSeconds: Long,
    val timestampMillis: Long,
) {
    companion object {
        fun unknown(timestampMillis: Long = System.currentTimeMillis()): ActivityReading {
            return ActivityReading(
                type = ActivityType.Unknown,
                confidencePercent = 0,
                durationSeconds = 0L,
                timestampMillis = timestampMillis,
            )
        }
    }
}

data class BatteryReading(
    val voltageMv: Int,
    val percent: Int,
    val timestampMillis: Long,
)

data class SummaryReading(
    val sessionDurationSeconds: Long,
    val currentActivity: ActivityType,
    val steps: Int,
    val timestampMillis: Long,
)

data class RawDeviceEvent(
    val source: String,
    val payload: String,
    val timestampMillis: Long,
)
