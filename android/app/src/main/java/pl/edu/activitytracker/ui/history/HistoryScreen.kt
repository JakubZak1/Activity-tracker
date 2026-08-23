package pl.edu.activitytracker.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.HomeSession
import pl.edu.activitytracker.domain.HomeSessionSummary
import pl.edu.activitytracker.domain.SessionExportState
import pl.edu.activitytracker.ui.formatDuration
import pl.edu.activitytracker.ui.map.HistoricalRouteMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    paddingValues: PaddingValues,
    sessions: List<HomeSessionSummary>,
    onOpen: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Session history", style = MaterialTheme.typography.headlineSmall)
        if (sessions.isEmpty()) {
            Text("No saved sessions yet. Completed and interrupted Home sessions will appear here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sessions, key = { it.id }) { session ->
                    ElevatedCard(onClick = { onOpen(session.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatDate(session.startedAtEpochMillis), style = MaterialTheme.typography.titleMedium)
                                Text(session.status.name)
                            }
                            Text("${session.dominantActivity?.displayName ?: "Unknown"} · ${formatDuration(session.durationMillis / 1_000L)}")
                            Text("${session.steps} steps · ${String.format(Locale.US, "%.1f kcal", session.caloriesKcal)}")
                            Text(exportLabel(session.exportState), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionDetailScreen(
    paddingValues: PaddingValues,
    session: HomeSession?,
    onBack: () -> Unit,
    onRetryExport: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && session != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("The internal history entry will be deleted. Previously exported files stay in the selected folder.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(session.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    Column(
        Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        if (session == null) {
            Text("Loading session…")
            return@Column
        }
        Text("Session details", style = MaterialTheme.typography.headlineSmall)
        Text("${formatDate(session.startedAtEpochMillis)} · ${session.status.name}")
        Text("Device: ${session.deviceShortId ?: "unknown"}")
        Text("Time: ${formatDuration(session.durationMillis / 1_000L)}")
        Text("Steps: ${session.steps}")
        Text("Calories: ${String.format(Locale.US, "%.2f kcal", session.caloriesKcal)} (${session.calorieMethod})")
        Text("Calculation weight: ${String.format(Locale.US, "%.1f kg", session.weightKg)}")

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Activity breakdown", style = MaterialTheme.typography.titleMedium)
                ActivityType.entries.forEach { activity ->
                    DurationBar(activity, session.durations.forActivity(activity), session.durationMillis)
                }
                if (session.durations.unknownMillis > 0L) {
                    Text("Unknown covers disconnected or stale-telemetry periods and adds no calories.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("Route", style = MaterialTheme.typography.titleMedium)
        if (session.route.isEmpty()) {
            Text("No GPS points were recorded for this session.")
        } else {
            HistoricalRouteMap(route = session.route, modifier = Modifier.fillMaxWidth().height(320.dp))
        }

        Text(exportLabel(session.exportState))
        if (session.exportState !is SessionExportState.Exported) {
            Button(onClick = { onRetryExport(session.id) }) { Text("Retry export") }
        }
        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete internal session") }
    }
}

@Composable
private fun DurationBar(activity: ActivityType, millis: Long, totalMillis: Long) {
    val fraction = if (totalMillis == 0L) 0f else millis.toFloat() / totalMillis
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(activity.displayName)
            Text(formatDuration(millis / 1_000L))
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

private fun exportLabel(state: SessionExportState): String = when (state) {
    SessionExportState.PendingFolder -> "Export: folder required"
    SessionExportState.Pending -> "Export: pending"
    SessionExportState.Exporting -> "Export: in progress"
    is SessionExportState.Exported -> "Exported: ${state.summaryFileName}"
    is SessionExportState.Error -> "Export error: ${state.message}"
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
