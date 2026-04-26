package pl.edu.activitytracker.domain

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timestampMillis: Long,
)

sealed interface LocationStatus {
    data object Idle : LocationStatus
    data object Running : LocationStatus
    data object PermissionMissing : LocationStatus
    data class Failed(val message: String) : LocationStatus
}

fun LocationStatus.label(): String {
    return when (this) {
        LocationStatus.Idle -> "GPS idle"
        LocationStatus.Running -> "GPS running"
        LocationStatus.PermissionMissing -> "Location permission needed"
        is LocationStatus.Failed -> "GPS error: $message"
    }
}
