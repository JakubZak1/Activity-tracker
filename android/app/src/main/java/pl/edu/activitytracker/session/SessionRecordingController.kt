package pl.edu.activitytracker.session

import android.content.Context

interface PhoneSessionController {
    fun start()
    fun stop()
}

class SessionRecordingController(
    context: Context,
) : PhoneSessionController {
    private val appContext = context.applicationContext

    override fun start() = SessionRecordingService.start(appContext)

    override fun stop() {
        SessionRecordingService.stop(appContext)
    }
}
