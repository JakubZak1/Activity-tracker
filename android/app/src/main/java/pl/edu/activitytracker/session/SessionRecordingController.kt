package pl.edu.activitytracker.session

import android.content.Context
import pl.edu.activitytracker.permissions.AppPermissions

interface PhoneSessionController {
    fun startIfLocationAllowed()
    fun stop()
}

class SessionRecordingController(
    context: Context,
) : PhoneSessionController {
    private val appContext = context.applicationContext

    override fun startIfLocationAllowed() {
        if (AppPermissions.hasLocationPermission(appContext)) {
            SessionRecordingService.start(appContext)
        }
    }

    override fun stop() {
        SessionRecordingService.stop(appContext)
    }
}
