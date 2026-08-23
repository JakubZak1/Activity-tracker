package pl.edu.activitytracker.domain

data class ActivityDurationBreakdown(
    val walkingMillis: Long = 0L,
    val runningMillis: Long = 0L,
    val cyclingMillis: Long = 0L,
    val sittingMillis: Long = 0L,
    val lyingMillis: Long = 0L,
    val unknownMillis: Long = 0L,
) {
    val totalMillis: Long
        get() = walkingMillis + runningMillis + cyclingMillis + sittingMillis + lyingMillis + unknownMillis

    fun forActivity(activity: ActivityType): Long = when (activity) {
        ActivityType.Walking -> walkingMillis
        ActivityType.Running -> runningMillis
        ActivityType.Cycling -> cyclingMillis
        ActivityType.Sitting -> sittingMillis
        ActivityType.Lying -> lyingMillis
        ActivityType.Unknown -> unknownMillis
    }

    fun plus(activity: ActivityType, millis: Long): ActivityDurationBreakdown {
        val safeMillis = millis.coerceAtLeast(0L)
        return when (activity) {
            ActivityType.Walking -> copy(walkingMillis = walkingMillis + safeMillis)
            ActivityType.Running -> copy(runningMillis = runningMillis + safeMillis)
            ActivityType.Cycling -> copy(cyclingMillis = cyclingMillis + safeMillis)
            ActivityType.Sitting -> copy(sittingMillis = sittingMillis + safeMillis)
            ActivityType.Lying -> copy(lyingMillis = lyingMillis + safeMillis)
            ActivityType.Unknown -> copy(unknownMillis = unknownMillis + safeMillis)
        }
    }

    fun dominantRecognizedActivity(): ActivityType? = RECOGNIZED_ACTIVITIES
        .maxByOrNull(::forActivity)
        ?.takeIf { forActivity(it) > 0L }

    companion object {
        val RECOGNIZED_ACTIVITIES = listOf(
            ActivityType.Walking,
            ActivityType.Running,
            ActivityType.Cycling,
            ActivityType.Sitting,
            ActivityType.Lying,
        )
    }
}

enum class HomeSessionStatus {
    Active,
    Completed,
    Interrupted,
}

sealed interface SessionExportState {
    data object PendingFolder : SessionExportState
    data object Pending : SessionExportState
    data object Exporting : SessionExportState
    data class Exported(
        val summaryFileName: String,
        val routeFileName: String,
    ) : SessionExportState
    data class Error(val message: String) : SessionExportState
}

data class HomeSession(
    val id: String,
    val status: HomeSessionStatus,
    val deviceShortId: String?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val lastCheckpointEpochMillis: Long,
    val weightKg: Double,
    val durations: ActivityDurationBreakdown,
    val steps: Int,
    val caloriesKcal: Double,
    val route: List<RoutePoint>,
    val exportState: SessionExportState,
    val calorieMethod: String = CALORIE_METHOD,
) {
    val durationMillis: Long get() = durations.totalMillis
    val dominantActivity: ActivityType? get() = durations.dominantRecognizedActivity()

    companion object {
        const val CALORIE_METHOD = "MET_v1"
    }
}

data class HomeSessionSummary(
    val id: String,
    val status: HomeSessionStatus,
    val deviceShortId: String?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val durationMillis: Long,
    val steps: Int,
    val caloriesKcal: Double,
    val dominantActivity: ActivityType?,
    val routePointCount: Int,
    val exportState: SessionExportState,
)

fun HomeSession.toSummary() = HomeSessionSummary(
    id = id,
    status = status,
    deviceShortId = deviceShortId,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    durationMillis = durationMillis,
    steps = steps,
    caloriesKcal = caloriesKcal,
    dominantActivity = dominantActivity,
    routePointCount = route.size,
    exportState = exportState,
)

object HomeSessionMetrics {
    fun caloriesFor(durations: ActivityDurationBreakdown, weightKg: Double): Double =
        ActivityDurationBreakdown.RECOGNIZED_ACTIVITIES.sumOf { activity ->
            CalorieCalculator.caloriesFor(
                activityType = activity,
                weightKg = weightKg,
                minutes = durations.forActivity(activity) / 60_000.0,
            )
        }
}
