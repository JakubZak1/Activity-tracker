package pl.edu.activitytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.edu.activitytracker.domain.ActivityDurationBreakdown
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.CalorieCalculator
import pl.edu.activitytracker.domain.HomeSessionAccumulator
import pl.edu.activitytracker.domain.HomeSessionMetrics

class HomeSessionDomainTest {
    @Test
    fun everyActivityTransitionHasExactTotal() {
        var accumulator = HomeSessionAccumulator(lastTickElapsedMillis = 1_000L)
        ActivityType.entries.forEachIndexed { index, activity ->
            accumulator = accumulator.tick(1_000L + (index + 1L) * 137L, activity)
        }
        assertEquals(822L, accumulator.durations.totalMillis)
        assertEquals(137L, accumulator.durations.walkingMillis)
        assertEquals(137L, accumulator.durations.runningMillis)
        assertEquals(137L, accumulator.durations.cyclingMillis)
        assertEquals(137L, accumulator.durations.sittingMillis)
        assertEquals(137L, accumulator.durations.lyingMillis)
        assertEquals(137L, accumulator.durations.unknownMillis)
    }

    @Test
    fun negativeOrRepeatedClockDoesNotIncreaseDuration() {
        val initial = HomeSessionAccumulator(lastTickElapsedMillis = 5_000L)
        val result = initial.tick(4_000L, ActivityType.Running).tick(4_000L, ActivityType.Running)
        assertEquals(0L, result.durations.totalMillis)
    }

    @Test
    fun unknownNeverAddsCalories() {
        val durations = ActivityDurationBreakdown(unknownMillis = 3_600_000L)
        assertEquals(0.0, HomeSessionMetrics.caloriesFor(durations, 70.0), 0.0)
        assertNull(durations.dominantRecognizedActivity())
    }

    @Test
    fun caloriesAreDeterministicFromFrozenWeightAndBuckets() {
        val durations = ActivityDurationBreakdown(walkingMillis = 600_000L, runningMillis = 300_000L)
        val expected = CalorieCalculator.caloriesFor(ActivityType.Walking, 80.0, 10.0) +
            CalorieCalculator.caloriesFor(ActivityType.Running, 80.0, 5.0)
        assertEquals(expected, HomeSessionMetrics.caloriesFor(durations, 80.0), 0.0000001)
        assertEquals(expected, HomeSessionMetrics.caloriesFor(durations, 80.0), 0.0000001)
    }

    @Test
    fun dominantActivityIgnoresUnknown() {
        val durations = ActivityDurationBreakdown(walkingMillis = 10L, sittingMillis = 20L, unknownMillis = 1_000L)
        assertEquals(ActivityType.Sitting, durations.dominantRecognizedActivity())
    }
}
