package pl.edu.activitytracker.ui.data

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.edu.activitytracker.data.DatasetDeviceSlot
import pl.edu.activitytracker.data.DatasetSlotState
import pl.edu.activitytracker.data.DiscoveredDatasetDevice
import pl.edu.activitytracker.data.MultiDeviceDatasetState
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BodySide
import pl.edu.activitytracker.domain.CatalogState
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DatasetConnectionState
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.SensorPlacement
import pl.edu.activitytracker.domain.TransferState
import pl.edu.activitytracker.domain.isBusy
import pl.edu.activitytracker.permissions.AppPermissions

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun MultiDataCollectionScreen(
    paddingValues: PaddingValues,
    state: MultiDeviceDatasetState,
    useMockSource: Boolean,
    onScan: () -> Unit,
    onConnect: (DatasetDeviceSlot, String?) -> Unit,
    onDisconnect: (DatasetDeviceSlot, Boolean) -> Unit,
    onIdentify: (DatasetDeviceSlot) -> Unit,
    onStartBoth: (ActivityType, SensorPlacement, BodySide, SensorPlacement, BodySide) -> Unit,
    onStopBoth: () -> Unit,
    onRefresh: (DatasetDeviceSlot) -> Unit,
    onDownload: (DatasetDeviceSlot, DeviceLogFile) -> Unit,
    onDelete: (DatasetDeviceSlot, DeviceLogFile) -> Unit,
    onCancel: (DatasetDeviceSlot) -> Unit,
    onFolderSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    var selectedActivity by rememberSaveable { mutableStateOf(ActivityType.Walking) }
    var bluePlacement by rememberSaveable { mutableStateOf(SensorPlacement.Wrist) }
    var blueSide by rememberSaveable { mutableStateOf(BodySide.Left) }
    var greenPlacement by rememberSaveable { mutableStateOf(SensorPlacement.Leg) }
    var greenSide by rememberSaveable { mutableStateOf(BodySide.Left) }
    var pendingDelete by remember { mutableStateOf<Pair<DatasetDeviceSlot, DeviceLogFile>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> if (grants.values.all { it }) onScan() }
    fun scanWithPermission() {
        val missing = if (useMockSource) emptyArray() else AppPermissions.bluetoothPermissionsToRequest(context)
        if (missing.isEmpty()) onScan() else permissionLauncher.launch(missing)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                .onSuccess { onFolderSelected(uri.toString()) }
        }
    }

    val bothReady = state.blue.dataset.connection is DatasetConnectionState.Ready &&
        state.green.dataset.connection is DatasetConnectionState.Ready
    val bothIdle = state.blue.dataset.collection is CollectionState.Idle &&
        state.green.dataset.collection is CollectionState.Idle
    val eitherRecording = state.blue.dataset.collection is CollectionState.Recording ||
        state.green.dataset.collection is CollectionState.Recording ||
        state.blue.dataset.collection is CollectionState.PausedForOffload ||
        state.green.dataset.collection is CollectionState.PausedForOffload
    val folderSelected = state.blue.dataset.dataFolderUri != null && state.green.dataset.dataFolderUri != null

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Two-device dataset collection", style = MaterialTheme.typography.headlineSmall)
            Text("One paired activity, independent wrist and leg recordings (BLE protocol v6).")
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    Text(if (folderSelected) "Folder permission stored; each XIAO uses its own subfolder." else "Choose a folder before recording.")
                    OutlinedButton(onClick = { folderLauncher.launch(null) }) { Text("Choose / change folder") }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Find XIAO boards", style = MaterialTheme.typography.titleMedium)
                    if (useMockSource) {
                        Button(onClick = {
                            onConnect(DatasetDeviceSlot.Blue, null)
                            onConnect(DatasetDeviceSlot.Green, null)
                        }) { Text("Connect two simulators") }
                    } else {
                        Button(onClick = ::scanWithPermission, enabled = !state.scan.scanning) {
                            Text(if (state.scan.scanning) "Scanning…" else "Scan for boards")
                        }
                    }
                    state.scan.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.scan.devices.forEach { device ->
                        ScanDeviceRow(device, state, onConnect)
                    }
                }
            }
        }
        item {
            SlotCard(
                state.blue, bluePlacement, blueSide,
                onPlacement = { bluePlacement = it }, onSide = { blueSide = it },
                onDisconnect = onDisconnect, onIdentify = onIdentify, onRefresh = onRefresh,
                onDownload = onDownload, onDelete = { slot, file -> pendingDelete = slot to file },
                onCancel = onCancel,
            )
        }
        item {
            SlotCard(
                state.green, greenPlacement, greenSide,
                onPlacement = { greenPlacement = it }, onSide = { greenSide = it },
                onDisconnect = onDisconnect, onIdentify = onIdentify, onRefresh = onRefresh,
                onDownload = onDownload, onDelete = { slot, file -> pendingDelete = slot to file },
                onCancel = onCancel,
            )
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paired recording", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DATASET_ACTIVITIES.forEach { activity ->
                            FilterChip(
                                selected = activity == selectedActivity,
                                onClick = { selectedActivity = activity },
                                enabled = bothIdle,
                                label = { Text(activity.displayName) },
                            )
                        }
                    }
                    state.pairedOperationMessage?.let { Text(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onStartBoth(selectedActivity, bluePlacement, blueSide, greenPlacement, greenSide) },
                            enabled = bothReady && bothIdle && folderSelected,
                        ) { Text("Start both") }
                        Button(onClick = onStopBoth, enabled = bothReady && eitherRecording) { Text("Stop both") }
                    }
                }
            }
        }
    }

    pendingDelete?.let { (slot, file) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete from ${slot.displayName}?") },
            text = { Text("${file.name} remains on the phone. The board copy is deleted only after local CRC verification.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(slot, file) }) { Text("Delete board copy") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScanDeviceRow(
    device: DiscoveredDatasetDevice,
    state: MultiDeviceDatasetState,
    onConnect: (DatasetDeviceSlot, String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${device.advertisedName}  (${device.rssi} dBm)")
        Text(device.address, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onConnect(DatasetDeviceSlot.Blue, device.address) },
                enabled = state.green.configuredAddress != device.address,
            ) { Text("Use as Blue") }
            OutlinedButton(
                onClick = { onConnect(DatasetDeviceSlot.Green, device.address) },
                enabled = state.blue.configuredAddress != device.address,
            ) { Text("Use as Green") }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SlotCard(
    slotState: DatasetSlotState,
    placement: SensorPlacement,
    side: BodySide,
    onPlacement: (SensorPlacement) -> Unit,
    onSide: (BodySide) -> Unit,
    onDisconnect: (DatasetDeviceSlot, Boolean) -> Unit,
    onIdentify: (DatasetDeviceSlot) -> Unit,
    onRefresh: (DatasetDeviceSlot) -> Unit,
    onDownload: (DatasetDeviceSlot, DeviceLogFile) -> Unit,
    onDelete: (DatasetDeviceSlot, DeviceLogFile) -> Unit,
    onCancel: (DatasetDeviceSlot) -> Unit,
) {
    val dataset = slotState.dataset
    val ready = dataset.connection is DatasetConnectionState.Ready
    val idle = dataset.collection is CollectionState.Idle
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(slotState.slot.displayName, style = MaterialTheme.typography.titleMedium)
            Text(connectionText(slotState))
            dataset.operationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onIdentify(slotState.slot) }, enabled = ready) { Text("Blink ${slotState.slot.color.displayName}") }
                OutlinedButton(
                    onClick = { onDisconnect(slotState.slot, false) },
                    enabled = slotState.transportConnection is ConnectionState.Connected,
                ) { Text("Disconnect") }
                TextButton(onClick = { onDisconnect(slotState.slot, true) }) { Text("Forget") }
            }
            Text(collectionText(dataset.collection))
            Text("Placement for next paired session")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SensorPlacement.Wrist, SensorPlacement.Leg).forEach {
                    FilterChip(selected = placement == it, onClick = { onPlacement(it) }, enabled = idle, label = { Text(it.displayName) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(BodySide.Left, BodySide.Right).forEach {
                    FilterChip(selected = side == it, onClick = { onSide(it) }, enabled = idle, label = { Text(it.displayName) })
                }
            }
            OutlinedButton(onClick = { onRefresh(slotState.slot) }, enabled = ready && idle && !dataset.transfer.isBusy) { Text("Refresh files") }
            transferText(dataset.transfer)?.let { Text(it) }
            if (dataset.transfer.isBusy) OutlinedButton(onClick = { onCancel(slotState.slot) }) { Text("Cancel transfer") }
            if (dataset.catalog is CatalogState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            dataset.catalog.files.forEach { file ->
                val verified = file.identity?.let { it in dataset.verifiedFiles } == true
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(file.name)
                    Text("${file.sizeBytes} B • ${if (verified) "CRC verified" else if (file.isComplete) "on board" else "incomplete"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onDownload(slotState.slot, file) },
                            enabled = ready && idle && !dataset.transfer.isBusy && file.isComplete && !verified,
                        ) { Text("Download") }
                        OutlinedButton(
                            onClick = { onDelete(slotState.slot, file) },
                            enabled = ready && idle && !dataset.transfer.isBusy && verified,
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private fun connectionText(state: DatasetSlotState): String = when (val connection = state.dataset.connection) {
    is DatasetConnectionState.Ready -> "Ready • ID ${connection.shortId} • ${state.configuredAddress ?: connection.transportIdentity}"
    DatasetConnectionState.Offline -> "Offline${state.configuredAddress?.let { " • remembered $it" } ?: ""}"
    DatasetConnectionState.Connecting -> "Connecting…"
    DatasetConnectionState.Handshaking -> "Checking protocol…"
    DatasetConnectionState.Synchronizing -> "Reading logger state…"
    is DatasetConnectionState.Incompatible -> "Incompatible: ${connection.message}"
    is DatasetConnectionState.Error -> "Error: ${connection.message}"
}

private fun collectionText(state: CollectionState): String = when (state) {
    CollectionState.Unknown -> "Recording state unknown"
    is CollectionState.Idle -> "Idle • free ${state.freeBytes ?: 0} B"
    is CollectionState.Starting -> "Starting ${state.label.displayName}…"
    is CollectionState.Recording -> "Recording ${state.label.displayName} • ${state.fileName} • ${state.elapsedMillis / 1000}s"
    is CollectionState.PausedForOffload -> "Paused for offload • ${state.file.name}"
    is CollectionState.Stopping -> "Stopping…"
    is CollectionState.Fault -> "Device fault: ${state.code}"
    is CollectionState.Error -> "Error: ${state.message}"
}

private fun transferText(state: TransferState): String? = when (state) {
    TransferState.Idle -> null
    is TransferState.WaitingForFolder -> "Waiting for folder: ${state.file.name}"
    is TransferState.Preparing -> "Preparing ${state.file.name}"
    is TransferState.AwaitingBegin -> "Starting transfer ${state.file.name}"
    is TransferState.Receiving -> "Receiving ${state.file.name}: ${state.receivedBytes}/${state.file.sizeBytes} B"
    is TransferState.Finalizing -> "Verifying ${state.file.name}"
    is TransferState.Completed -> "Saved and CRC verified: ${state.file.name}"
    is TransferState.Interrupted -> "Transfer interrupted: ${state.message}"
    is TransferState.Error -> "Transfer error: ${state.message}"
}

private val DATASET_ACTIVITIES = listOf(
    ActivityType.Walking,
    ActivityType.Running,
    ActivityType.Cycling,
    ActivityType.Sitting,
    ActivityType.Lying,
)
