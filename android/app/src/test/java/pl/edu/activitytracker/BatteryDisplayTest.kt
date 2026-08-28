package pl.edu.activitytracker

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.ui.data.batteryText

class BatteryDisplayTest {
    @Test
    fun formatsFreshReading() {
        assertEquals(
            "Battery: ~75% • 3900 mV • updated 12s ago",
            batteryText(BatteryReading(3900, 75, 1_000L), nowMillis = 13_000L),
        )
    }

    @Test
    fun marksOldReadingAsStale() {
        assertEquals(
            "Battery: ~20% • 3600 mV • stale",
            batteryText(BatteryReading(3600, 20, 1_000L), nowMillis = 92_000L),
        )
    }

    @Test
    fun waitsForFirstNotification() {
        assertEquals("Battery: waiting for reading…", batteryText(null, nowMillis = 0L))
    }
}
