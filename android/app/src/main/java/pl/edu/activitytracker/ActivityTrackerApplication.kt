package pl.edu.activitytracker

import android.app.Application
import org.osmdroid.config.Configuration
import pl.edu.activitytracker.app.AppContainer

class ActivityTrackerApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        appContainer = AppContainer(this)
    }
}
