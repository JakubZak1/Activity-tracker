package pl.edu.activitytracker.domain

object CalorieCalculator {
    fun metFor(activityType: ActivityType): Double {
        return when (activityType) {
            ActivityType.Lying -> 1.0
            ActivityType.Sitting -> 1.3
            ActivityType.Walking -> 3.5
            ActivityType.Cycling -> 6.8
            ActivityType.Running -> 8.0
            ActivityType.Unknown -> 0.0
        }
    }

    fun caloriesFor(
        activityType: ActivityType,
        weightKg: Double,
        minutes: Double,
    ): Double {
        if (weightKg <= 0.0 || minutes <= 0.0) {
            return 0.0
        }
        return metFor(activityType) * 3.5 * weightKg / 200.0 * minutes
    }
}
