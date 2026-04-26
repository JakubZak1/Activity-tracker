package pl.edu.activitytracker.session

import android.content.Context
import pl.edu.activitytracker.permissions.AppPermissions

class SessionRecordingController(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun startIfLocationAllowed() {
        if (AppPermissions.hasLocationPermission(appContext)) {
            SessionRecordingService.start(appContext)
        }
    }

    fun stop() {
        SessionRecordingService.stop(appContext)
    }
}
