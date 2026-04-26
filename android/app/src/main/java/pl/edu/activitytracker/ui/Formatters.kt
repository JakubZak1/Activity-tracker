package pl.edu.activitytracker.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatTimestamp(timestampMillis: Long?): String {
    if (timestampMillis == null) {
        return "Never"
    }
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    return formatter.format(Date(timestampMillis))
}
