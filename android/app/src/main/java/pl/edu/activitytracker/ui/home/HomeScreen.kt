package pl.edu.activitytracker.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.label
import pl.edu.activitytracker.permissions.AppPermissions
import pl.edu.activitytracker.ui.formatDuration
import java.util.Locale

@Composable
fun HomeScreen(
    paddingValues: PaddingValues,
    state: TrackerState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onRequestStatus: () -> Unit,
) {
    val context = LocalContext.current
    val isConnected = state.connectionState is ConnectionState.Connected
    val battery = state.battery
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onStartSession()
    }

    fun startSessionWithLocationPrompt() {
        val missingPermissions = AppPermissions.sessionPermissionsToRequest(context)
        if (missingPermissions.isEmpty()) {
            onStartSession()
        } else {
            locationPermissionLauncher.launch(missingPermissions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Activity Tracker", style = MaterialTheme.typography.headlineMedium)
            Text("Device session", style = MaterialTheme.typography.bodyMedium)
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("BLE status", style = MaterialTheme.typography.labelLarge)
                        Text(state.connectionState.label(), style = MaterialTheme.typography.titleMedium)
                    }
                    if (isConnected) {
                        OutlinedButton(onClick = onDisconnect) {
                            Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                            Text("Disconnect")
                        }
                    } else {
                        Button(onClick = onConnect) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null)
                            Text("Connect")
                        }
                    }
                }

                OutlinedButton(onClick = onRequestStatus) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Request status")
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(
                listOf(
                    Metric("Current activity", state.currentActivity.type.displayName),
                    Metric("Confidence", "${state.currentActivity.confidencePercent}%"),
                    Metric("Activity time", formatDuration(state.currentActivity.durationSeconds)),
                    Metric("Battery", battery?.let { "${it.percent}%" } ?: "--"),
                    Metric("Voltage", battery?.let { "${it.voltageMv} mV" } ?: "--"),
                    Metric("Session", formatDuration(state.sessionDurationSeconds)),
                    Metric("Calories (est.)", String.format(Locale.US, "%.1f kcal", state.caloriesKcal)),
                    Metric("Steps", "${state.summary?.steps ?: 0}"),
                ),
            ) { metric ->
                MetricCard(metric)
            }
        }

        Button(
            onClick = if (state.isSessionRunning) onStopSession else ::startSessionWithLocationPrompt,
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSessionRunning) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Text("Stop session")
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("Start session")
            }
        }
    }
}

private data class Metric(
    val label: String,
    val value: String,
)

@Composable
private fun MetricCard(metric: Metric) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge)
            Text(
                metric.value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
