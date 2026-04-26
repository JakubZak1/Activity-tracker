package pl.edu.activitytracker.domain

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timestampMillis: Long,
    val activity: ActivityType,
)

data class RouteSegment(
    val start: RoutePoint,
    val end: RoutePoint,
    val activity: ActivityType,
)

object RoutePointFilter {
    private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
    private const val MIN_MOVING_DISTANCE_METERS = 4.0
    private const val MIN_STATIONARY_DISTANCE_METERS = 12.0
    private const val MAX_REASONABLE_SPEED_MPS = 18.0

    fun shouldAppend(points: List<RoutePoint>, candidate: RoutePoint): Boolean {
        if (!candidate.latitude.isFinite() || !candidate.longitude.isFinite()) {
            return false
        }

        val previous = points.lastOrNull() ?: return true

        val accuracy = candidate.accuracyMeters
        if (accuracy != null && accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            return false
        }

        val distanceMeters = distanceMeters(previous, candidate)
        val elapsedSeconds = ((candidate.timestampMillis - previous.timestampMillis) / 1_000.0)
            .coerceAtLeast(0.1)
        val speedMetersPerSecond = distanceMeters / elapsedSeconds
        if (speedMetersPerSecond > MAX_REASONABLE_SPEED_MPS) {
            return false
        }

        if (candidate.activity == previous.activity) {
            val minimumDistance = if (candidate.activity.isStationary) {
                MIN_STATIONARY_DISTANCE_METERS
            } else {
                MIN_MOVING_DISTANCE_METERS
            }

            if (distanceMeters < minimumDistance) {
                return false
            }
        }

        return true
    }

    fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = Math.toRadians(b.latitude - a.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)

        val h = kotlin.math.sin(deltaLat / 2.0) * kotlin.math.sin(deltaLat / 2.0) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(deltaLon / 2.0) * kotlin.math.sin(deltaLon / 2.0)
        return earthRadiusMeters * 2.0 * kotlin.math.atan2(
            kotlin.math.sqrt(h),
            kotlin.math.sqrt(1.0 - h),
        )
    }
}

object RouteSegmenter {
    fun movingSegments(points: List<RoutePoint>): List<RouteSegment> {
        return points.zipWithNext()
            .map { (start, end) -> RouteSegment(start = start, end = end, activity = end.activity) }
            .filter { it.activity.isMoving }
    }

    fun stationaryPoints(points: List<RoutePoint>): List<RoutePoint> {
        return points.filter { it.activity.isStationary }
    }
}
