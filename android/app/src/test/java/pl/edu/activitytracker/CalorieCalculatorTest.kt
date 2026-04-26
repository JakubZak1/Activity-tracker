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
}
