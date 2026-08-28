package pl.edu.activitytracker

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.CalorieCalculator

class CalorieCalculatorTest {
    @Test
    fun usesMetFormulaForWalking() {
        val calories = CalorieCalculator.caloriesFor(
            activityType = ActivityType.Walking,
            weightKg = 70.0,
            minutes = 10.0,
        )

        assertEquals(42.875, calories, 0.0001)
    }

    @Test
    fun unknownActivityDoesNotAddCalories() {
        val calories = CalorieCalculator.caloriesFor(
            activityType = ActivityType.Unknown,
            weightKg = 70.0,
            minutes = 10.0,
        )

        assertEquals(0.0, calories, 0.0001)
    }

    @Test
    fun metValuesProduceExpectedCaloriesForTenMinutesAtSeventyKilograms() {
        val expected = mapOf(
            ActivityType.Lying to 12.25,
            ActivityType.Sitting to 15.925,
            ActivityType.Walking to 42.875,
            ActivityType.Cycling to 83.3,
            ActivityType.Running to 98.0,
        )

        expected.forEach { (activity, expectedCalories) ->
            assertEquals(
                activity.wireName,
                expectedCalories,
                CalorieCalculator.caloriesFor(activity, weightKg = 70.0, minutes = 10.0),
                0.0001,
            )
        }
    }

    @Test
    fun invalidWeightOrDurationDoesNotAddCalories() {
        assertEquals(0.0, CalorieCalculator.caloriesFor(ActivityType.Running, 0.0, 10.0), 0.0)
        assertEquals(0.0, CalorieCalculator.caloriesFor(ActivityType.Running, 70.0, 0.0), 0.0)
        assertEquals(0.0, CalorieCalculator.caloriesFor(ActivityType.Running, -1.0, 10.0), 0.0)
        assertEquals(0.0, CalorieCalculator.caloriesFor(ActivityType.Running, 70.0, -1.0), 0.0)
    }
}
