package pl.edu.activitytracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.edu.activitytracker.ui.debug.DebugScreen
import pl.edu.activitytracker.ui.home.HomeScreen
import pl.edu.activitytracker.ui.map.MapScreen
import pl.edu.activitytracker.ui.settings.SettingsScreen

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Default.Home),
    Map("map", "Map", Icons.Default.Map),
    Settings("settings", "Settings", Icons.Default.Settings),
    Debug("debug", "Debug", Icons.Default.BugReport),
}

@Composable
fun ActivityTrackerApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val state by viewModel.trackerState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destination.Home.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigate(destination.route) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    paddingValues = paddingValues,
                    state = state,
                    onConnect = viewModel::connectDevice,
                    onDisconnect = viewModel::disconnectDevice,
                    onStartSession = viewModel::startSession,
                    onStopSession = viewModel::stopSession,
                    onRequestStatus = viewModel::requestStatus,
                )
            }
            composable(Destination.Map.route) {
                MapScreen(
                    paddingValues = paddingValues,
                    state = state,
                    onLocationPermissionGranted = viewModel::startLocationPreview,
                    onMapVisible = viewModel::startLocationPreview,
                    onMapHidden = viewModel::stopLocationPreviewIfNoSession,
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    paddingValues = paddingValues,
                    settings = settings,
                    onWeightChanged = viewModel::setWeightKg,
                    onDeviceNameChanged = viewModel::setDeviceName,
                    onUseMockChanged = viewModel::setUseMockSource,
                    onResetSession = viewModel::resetSession,
                    onConnect = viewModel::connectDevice,
                )
            }
            composable(Destination.Debug.route) {
                DebugScreen(
                    paddingValues = paddingValues,
                    state = state,
                    onRequestStatus = viewModel::requestStatus,
                )
            }
        }
    }
}
