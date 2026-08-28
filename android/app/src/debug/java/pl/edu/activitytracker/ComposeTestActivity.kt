package pl.edu.activitytracker

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Debug-only host that makes instrumentation UI tests independent of the device lock screen. */
class ComposeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
    }
}
