package pl.edu.activitytracker.ui.data

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.FileTransferState
import pl.edu.activitytracker.domain.Transport
import pl.edu.activitytracker.domain.isBusy
import java.util.Locale

@Composable
fun DataCollectionScreen(
    paddingValues: PaddingValues,
    state: TrackerState,
    useMockSource: Boolean,
    onStart: (ActivityType) -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (DeviceLogFile) -> Unit,
    onDelete: (DeviceLogFile) -> Unit,
    onCancelTransfer: () -> Unit,
    onFolderSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val status = state.collectionStatus
    val connectedWithBle = (state.connectionState as? ConnectionState.Connected)?.transport == Transport.Ble
    val transferBusy = state.fileTransfer.isBusy
    var selectedActivityName by rememberSaveable { mutableStateOf(ActivityType.Walking.wireName) }
    val selectedActivity = ActivityType.fromWire(selectedActivityName)
    var pendingDelete by remember { mutableStateOf<DeviceLogFile?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }.onSuccess {
                onFolderSelected(uri.toString())
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Dataset collection", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (useMockSource) "Unavailable for the mock source" else "Control and download board logs",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Storage folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.dataFolderUri == null) "No folder selected" else "Folder selected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        enabled = !transferBusy,
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Text(if (state.dataFolderUri == null) "Choose folder" else "Change folder")
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Recording", style = MaterialTheme.typography.titleMedium)
                    Text("Device label: ${status?.label ?: "--"}")
                    Text("Current file: ${status?.currentFile ?: "--"}")
                    Text("Free space: ${status?.freeBytes?.let(::formatBytes) ?: "--"}")

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(COLLECTION_ACTIVITIES) { activity ->
                            FilterChip(
                                selected = selectedActivity == activity,
                                onClick = { selectedActivityName = activity.wireName },
                                enabled = status?.isLogging != true && !transferBusy,
                                label = { Text(activity.displayName) },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onStart(selectedActivity) },
                            enabled = connectedWithBle &&
                                status != null &&
                                !status.isLogging &&
                                !transferBusy &&
                                state.dataFolderUri != null,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Start")
                        }
                        Button(
                            onClick = onStop,
                            enabled = connectedWithBle && status?.isLogging == true && !transferBusy,
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("Stop")
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            enabled = connectedWithBle && !transferBusy,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh logs")
                        }
                    }
                }
            }
        }

        item { TransferStatus(state.fileTransfer, onCancelTransfer) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Device logs", style = MaterialTheme.typography.titleMedium)
                Text("${state.deviceLogs.size}", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (state.deviceLogs.isEmpty()) {
            item { Text("No CSV logs reported by the device.") }
        } else {
            items(state.deviceLogs, key = { it.name }) { file ->
                LogFileRow(
                    file = file,
                    isVerified = file.name in state.verifiedLogNames,
                    transferBusy = transferBusy,
                    canResume = (state.fileTransfer as? FileTransferState.Failed)?.let {
                        it.fileName == file.name && it.canResume
                    } == true,
                    onDownload = { onDownload(file) },
                    onDelete = { pendingDelete = file },
                )
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete device log?") },
            text = { Text("${file.name} has been verified on this phone. This removes only the board copy.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(file)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TransferStatus(state: FileTransferState, onCancel: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Transfer", style = MaterialTheme.typography.titleMedium)
            when (state) {
                FileTransferState.Idle -> Text("Idle")
                is FileTransferState.WaitingForFolder -> Text("Choose a folder to download ${state.fileName}.")
                is FileTransferState.Preparing -> Text("Preparing ${state.fileName}...")
                is FileTransferState.Downloading -> {
                    val progress = if (state.sizeBytes > 0L) {
                        (state.receivedBytes.toFloat() / state.sizeBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Text("${state.fileName}: ${formatBytes(state.receivedBytes)} / ${formatBytes(state.sizeBytes)}")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is FileTransferState.Completed -> Text("Saved and verified: ${state.fileName}")
                is FileTransferState.Failed -> Text("Error: ${state.message}")
            }
        }
    }
}

@Composable
private fun LogFileRow(
    file: DeviceLogFile,
    isVerified: Boolean,
    transferBusy: Boolean,
    canResume: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatBytes(file.sizeBytes)} | ${if (file.isActive) "recording" else if (isVerified) "downloaded" else "on device"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = !file.isActive && !transferBusy && !isVerified,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(if (canResume) "Resume" else "Download")
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !file.isActive && !transferBusy && isVerified,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("Delete")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024L) return "$bytes B"
    if (bytes < 1_048_576L) return String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    return String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
}

private val COLLECTION_ACTIVITIES = listOf(
    ActivityType.Walking,
    ActivityType.Running,
    ActivityType.Sitting,
    ActivityType.Lying,
    ActivityType.Cycling,
)
