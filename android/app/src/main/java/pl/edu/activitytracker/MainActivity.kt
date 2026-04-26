package pl.edu.activitytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.edu.activitytracker.ui.ActivityTrackerApp
import pl.edu.activitytracker.ui.MainViewModel
import pl.edu.activitytracker.ui.theme.ActivityTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as ActivityTrackerApplication).appContainer

        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModel.Factory(
                    repository = appContainer.repository,
                    settingsStore = appContainer.settingsStore,
                ),
            )

            ActivityTrackerTheme {
                ActivityTrackerApp(viewModel = viewModel)
            }
        }
    }
}
