package pl.edu.activitytracker.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.label
import pl.edu.activitytracker.ui.formatTimestamp

@Composable
fun DebugScreen(
    paddingValues: PaddingValues,
    state: TrackerState,
    onRequestStatus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Debug", style = MaterialTheme.typography.headlineSmall)
                Text(state.connectionState.label(), style = MaterialTheme.typography.bodyMedium)
                Text("Last update: ${formatTimestamp(state.lastUpdateMillis)}")
            }
            Button(onClick = onRequestStatus) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                Text("Status")
            }
        }

        HorizontalDivider()

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.rawEvents) { event ->
                Column {
                    Text(event.source, style = MaterialTheme.typography.labelLarge)
                    Text(event.payload, style = MaterialTheme.typography.bodyMedium)
                    Text(formatTimestamp(event.timestampMillis), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
