package pl.edu.activitytracker.ui.data

import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import pl.edu.activitytracker.data.TrackerState
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.CatalogState
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.TransferState
import pl.edu.activitytracker.domain.isBusy
import pl.edu.activitytracker.permissions.AppPermissions

@Composable
fun DataCollectionScreen(
    paddingValues: PaddingValues,
    state: TrackerState,
    useMockSource: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStart: (ActivityType) -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (DeviceLogFile) -> Unit,
    onDelete: (DeviceLogFile) -> Unit,
    onCancelTransfer: () -> Unit,
    onFolderSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val dataset = state.dataset
    val ready = dataset.connection is DatasetConnectionState.Ready
    val transferBusy = dataset.transfer.isBusy
    val physicalConnected = state.connectionState is ConnectionState.Connected
    val connectionInProgress = state.connectionState is ConnectionState.Scanning ||
        state.connectionState is ConnectionState.Connecting ||
        dataset.connection is DatasetConnectionState.Connecting ||
        dataset.connection is DatasetConnectionState.Handshaking ||
        dataset.connection is DatasetConnectionState.Synchronizing
    val collectionIdle = dataset.collection is CollectionState.Idle
    val fileActionsEnabled = ready && collectionIdle && !transferBusy
    val recording = dataset.collection as? CollectionState.Recording
    var displayedElapsedMillis by remember(recording?.fileName, recording?.elapsedMillis) {
        mutableLongStateOf(recording?.elapsedMillis ?: 0L)
    }
    LaunchedEffect(recording?.fileName, recording?.elapsedMillis) {
        val activeRecording = recording ?: return@LaunchedEffect
        val startedAtRealtime = SystemClock.elapsedRealtime()
        while (true) {
            displayedElapsedMillis = activeRecording.elapsedMillis +
                (SystemClock.elapsedRealtime() - startedAtRealtime).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    var selectedActivityName by rememberSaveable { mutableStateOf(ActivityType.Walking.wireName) }
    val selectedActivity = ActivityType.fromWire(selectedActivityName)
    var pendingDelete by remember { mutableStateOf<DeviceLogFile?>(null) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) onConnect()
    }
    fun connectWithPermission() {
        val missing = if (useMockSource) emptyArray() else AppPermissions.bluetoothPermissionsToRequest(context)
        if (missing.isEmpty()) onConnect() else bluetoothPermissionLauncher.launch(missing)
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                .onSuccess { onFolderSelected(uri.toString()) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Dataset collection", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (useMockSource) "Interactive protocol v3 simulator" else "Record and safely download board CSV logs",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    Text(dataset.connection.label())
                    dataset.operationMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                    if (physicalConnected) {
                        OutlinedButton(onClick = onDisconnect) {
                            Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                            Text("Disconnect")
                        }
                    } else {
                        Button(onClick = ::connectWithPermission, enabled = !connectionInProgress) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null)
                            Text(if (useMockSource) "Connect simulator" else "Scan & connect")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Storage folder", style = MaterialTheme.typography.titleMedium)
                    Text(if (dataset.dataFolderUri == null) "Required before recording" else "Folder permission stored")
                    OutlinedButton(onClick = { folderLauncher.launch(null) }, enabled = !transferBusy) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Text(if (dataset.dataFolderUri == null) "Choose folder" else "Change folder")
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Recording", style = MaterialTheme.typography.titleMedium)
                    CollectionDetails(dataset.collection, displayedElapsedMillis)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(COLLECTION_ACTIVITIES) { activity ->
                            FilterChip(
                                selected = selectedActivity == activity,
                                onClick = { selectedActivityName = activity.wireName },
                                enabled = dataset.collection is CollectionState.Idle && !transferBusy,
                                label = { Text(activity.displayName) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onStart(selectedActivity) },
                            enabled = ready && collectionIdle && !transferBusy && dataset.dataFolderUri != null,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Start")
                        }
                        Button(
                            onClick = onStop,
                            enabled = ready && dataset.collection is CollectionState.Recording && !transferBusy,
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("Stop")
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            enabled = ready && dataset.collection is CollectionState.Idle && !transferBusy &&
                                dataset.catalog !is CatalogState.Loading,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh logs")
                        }
                    }
                }
            }
        }

        item { TransferStatus(dataset.transfer, onCancelTransfer) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Device logs", style = MaterialTheme.typography.titleMedium)
                Text("${dataset.catalog.files.size}", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (dataset.catalog is CatalogState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (dataset.catalog.files.isEmpty()) {
            item { Text("No complete CSV logs reported by the device.") }
        } else {
            items(dataset.catalog.files, key = { it.name }) { file ->
                val identity = file.identity
                LogFileRow(
                    file = file,
                    isVerified = identity != null && identity in dataset.verifiedFiles,
                    actionsEnabled = fileActionsEnabled,
                    canResume = (dataset.transfer as? TransferState.Interrupted)?.let {
                        it.file?.name == file.name && it.canResume
                    } == true,
                    onDownload = { onDownload(file) },
                    onDelete = { pendingDelete = file },
                )
            }
        }
    }

    pendingDelete?.let { file ->
        val deleteStillAllowed = file.identity?.let { it in dataset.verifiedFiles } == true && fileActionsEnabled
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete device log?") },
            text = { Text("${file.name} passed local size and CRC32 verification. This removes only the device copy.") },
            confirmButton = {
                TextButton(
                    onClick = { pendingDelete = null; onDelete(file) },
                    enabled = deleteStillAllowed,
                    modifier = Modifier.testTag("confirm-delete-device-log"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CollectionDetails(state: CollectionState, displayedElapsedMillis: Long) {
    when (state) {
        CollectionState.Unknown -> Text("Device state unknown")
        is CollectionState.Idle -> {
            Text("Idle")
            Text("Last file: ${state.lastFile?.name ?: "--"}")
            Text("Free space: ${state.freeBytes?.let(::formatBytes) ?: "--"}")
        }
        is CollectionState.Starting -> Text("Starting ${state.label.displayName}...")
        is CollectionState.Recording -> {
            Text("Recording ${state.label.displayName}")
            Text("Current file: ${state.fileName}")
            Text("Elapsed: ${formatElapsed(displayedElapsedMillis)}")
            Text("Written: ${formatBytes(state.bytesWritten)}")
            Text("Free space: ${state.freeBytes?.let(::formatBytes) ?: "--"}")
        }
        is CollectionState.Stopping -> Text("Stopping ${state.fileName ?: "recording"}...")
        is CollectionState.Fault -> Text("Device fault: ${state.code}", color = MaterialTheme.colorScheme.error)
        is CollectionState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TransferStatus(state: TransferState, onCancel: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transfer", style = MaterialTheme.typography.titleMedium)
            when (state) {
                TransferState.Idle -> Text("Idle")
                is TransferState.WaitingForFolder -> Text("Choose a folder to download ${state.file.name}.")
                is TransferState.Preparing -> Text("Preparing ${state.file.name}...")
                is TransferState.AwaitingBegin -> {
                    Text("Starting ${state.file.name} at ${formatBytes(state.resumeOffset)}...")
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is TransferState.Receiving -> {
                    val progress = if (state.file.sizeBytes > 0L) {
                        (state.receivedBytes.toFloat() / state.file.sizeBytes).coerceIn(0f, 1f)
                    } else 1f
                    Text("${state.file.name}: ${formatBytes(state.receivedBytes)} / ${formatBytes(state.file.sizeBytes)}")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is TransferState.Finalizing -> Text("Checking size and CRC32 for ${state.file.name}...")
                is TransferState.Completed -> Text("Saved and CRC32 verified: ${state.file.name}")
                is TransferState.Interrupted -> Text("Interrupted: ${state.message}")
                is TransferState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LogFileRow(
    file: DeviceLogFile,
    isVerified: Boolean,
    actionsEnabled: Boolean,
    canResume: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatBytes(file.sizeBytes)} | ${file.crc32 ?: "no CRC"} | " +
                    if (!file.isComplete) "incomplete" else if (isVerified) "verified locally" else "on device",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = actionsEnabled && file.isComplete && file.crc32 != null && !isVerified,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(if (canResume) "Resume" else "Download")
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = actionsEnabled && isVerified,
                    modifier = Modifier.testTag("delete-device-log-${file.name}"),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("Delete")
                }
            }
        }
    }
}

private fun DatasetConnectionState.label(): String = when (this) {
    DatasetConnectionState.Offline -> "Disconnected"
    DatasetConnectionState.Connecting -> "Scanning or connecting..."
    DatasetConnectionState.Handshaking -> "Connected; checking protocol..."
    DatasetConnectionState.Synchronizing -> "Protocol v3; synchronizing..."
    is DatasetConnectionState.Ready -> "Ready (protocol $protocolVersion)"
    is DatasetConnectionState.Incompatible -> "Incompatible: $message"
    is DatasetConnectionState.Error -> "Error: $message"
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
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
    ActivityType.Cycling,
    ActivityType.Sitting,
    ActivityType.Lying,
)
