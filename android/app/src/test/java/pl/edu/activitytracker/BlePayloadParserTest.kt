package pl.edu.activitytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.edu.activitytracker.ble.BlePayloadParser
import pl.edu.activitytracker.domain.ActivityType

class BlePayloadParserTest {
    @Test
    fun parsesActivityPayload() {
        val reading = BlePayloadParser.parseActivity("walking,82,14", timestampMillis = 1000L)

        requireNotNull(reading)
        assertEquals(ActivityType.Walking, reading.type)
        assertEquals(82, reading.confidencePercent)
        assertEquals(14L, reading.durationSeconds)
        assertEquals(1000L, reading.timestampMillis)
    }

    @Test
    fun mapsUnknownActivityValuesToUnknown() {
        val reading = BlePayloadParser.parseActivity("jumping,50,3", timestampMillis = 1000L)

        requireNotNull(reading)
        assertEquals(ActivityType.Unknown, reading.type)
    }

    @Test
    fun rejectsMalformedActivityPayload() {
        assertNull(BlePayloadParser.parseActivity("walking,82"))
    }

    @Test
    fun parsesBatteryPayload() {
        val reading = BlePayloadParser.parseBattery("3910,76", timestampMillis = 2000L)

        requireNotNull(reading)
        assertEquals(3910, reading.voltageMv)
        assertEquals(76, reading.percent)
        assertEquals(2000L, reading.timestampMillis)
    }

    @Test
    fun parsesSummaryPayload() {
        val reading = BlePayloadParser.parseSummary("320,walking,410", timestampMillis = 3000L)

        requireNotNull(reading)
        assertEquals(320L, reading.sessionDurationSeconds)
        assertEquals(ActivityType.Walking, reading.currentActivity)
        assertEquals(410, reading.steps)
        assertEquals(3000L, reading.timestampMillis)
    }
}
