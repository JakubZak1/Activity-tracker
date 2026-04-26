package pl.edu.activitytracker.domain

enum class ActivityType(
    val wireName: String,
    val displayName: String,
) {
    Walking("walking", "Walking"),
    Running("running", "Running"),
    Sitting("sitting", "Sitting"),
    Lying("lying", "Lying"),
    Cycling("cycling", "Cycling"),
    Unknown("unknown", "Unknown");

    val isMoving: Boolean
        get() = this == Walking || this == Running || this == Cycling

    val isStationary: Boolean
        get() = this == Sitting || this == Lying

    companion object {
        fun fromWire(value: String): ActivityType {
            return entries.firstOrNull { it.wireName == value.trim().lowercase() } ?: Unknown
        }
    }
}
