package pl.edu.activitytracker.ble

import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.SummaryReading

object BlePayloadParser {
    fun parseActivity(
        payload: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): ActivityReading? {
        val parts = payload.trim().split(",")
        if (parts.size != 3) {
            return null
        }

        val confidence = parts[1].trim().toIntOrNull() ?: return null
        val durationSeconds = parts[2].trim().toLongOrNull() ?: return null

        return ActivityReading(
            type = ActivityType.fromWire(parts[0]),
            confidencePercent = confidence.coerceIn(0, 100),
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            timestampMillis = timestampMillis,
        )
    }

    fun parseBattery(
        payload: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): BatteryReading? {
        val parts = payload.trim().split(",")
        if (parts.size != 2) {
            return null
        }

        val voltageMv = parts[0].trim().toIntOrNull() ?: return null
        val percent = parts[1].trim().toIntOrNull() ?: return null

        return BatteryReading(
            voltageMv = voltageMv.coerceAtLeast(0),
            percent = percent.coerceIn(0, 100),
            timestampMillis = timestampMillis,
        )
    }

    fun parseSummary(
        payload: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): SummaryReading? {
        val parts = payload.trim().split(",")
        if (parts.size != 3) {
            return null
        }

        val durationSeconds = parts[0].trim().toLongOrNull() ?: return null
        val steps = parts[2].trim().toIntOrNull() ?: return null

        return SummaryReading(
            sessionDurationSeconds = durationSeconds.coerceAtLeast(0L),
            currentActivity = ActivityType.fromWire(parts[1]),
            steps = steps.coerceAtLeast(0),
            timestampMillis = timestampMillis,
        )
    }
}
