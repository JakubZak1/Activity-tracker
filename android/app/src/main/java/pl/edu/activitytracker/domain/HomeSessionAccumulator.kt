package pl.edu.activitytracker.domain

data class HomeSessionAccumulator(
    val durations: ActivityDurationBreakdown = ActivityDurationBreakdown(),
    val lastTickElapsedMillis: Long,
) {
    fun tick(nowElapsedMillis: Long, activity: ActivityType): HomeSessionAccumulator {
        val safeNow = maxOf(nowElapsedMillis, lastTickElapsedMillis)
        val delta = safeNow - lastTickElapsedMillis
        return copy(
            durations = durations.plus(activity, delta),
            lastTickElapsedMillis = safeNow,
        )
    }
}
