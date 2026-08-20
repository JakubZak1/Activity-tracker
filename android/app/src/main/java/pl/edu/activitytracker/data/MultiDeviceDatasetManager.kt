package pl.edu.activitytracker.data

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import pl.edu.activitytracker.domain.ActivityType
import pl.edu.activitytracker.domain.BodySide
import pl.edu.activitytracker.domain.CollectionState
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DatasetState
import pl.edu.activitytracker.domain.DeviceLedColor
import pl.edu.activitytracker.domain.DeviceLogFile
import pl.edu.activitytracker.domain.SensorPlacement
import pl.edu.activitytracker.domain.isBusy
import pl.edu.activitytracker.storage.DatasetDeviceSettings

enum class DatasetDeviceSlot(val wireName: String, val color: DeviceLedColor, val displayName: String) {
    Blue("blue", DeviceLedColor.Blue, "Blue XIAO"),
    Green("green", DeviceLedColor.Green, "Green XIAO"),
}

data class DatasetSlotState(
    val slot: DatasetDeviceSlot,
    val transportConnection: ConnectionState = ConnectionState.Disconnected,
    val dataset: DatasetState = DatasetState(),
    val configuredAddress: String? = null,
)

data class MultiDeviceDatasetState(
    val scan: DatasetScanState = DatasetScanState(),
    val blue: DatasetSlotState = DatasetSlotState(DatasetDeviceSlot.Blue),
    val green: DatasetSlotState = DatasetSlotState(DatasetDeviceSlot.Green),
    val pairedOperationMessage: String? = null,
) {
    fun slot(value: DatasetDeviceSlot): DatasetSlotState = if (value == DatasetDeviceSlot.Blue) blue else green
}

class MultiDeviceDatasetManager(
    private val scanner: BleDeviceScanner,
    private val blueSource: DeviceDataSource,
    private val greenSource: DeviceDataSource,
    private val blueController: DatasetController,
    private val greenController: DatasetController,
    private val settingsStore: DatasetDeviceSettings,
    private val workRuntime: DatasetWorkRuntime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class SlotRuntime(
        val source: DeviceDataSource,
        val controller: DatasetController,
        var desired: Boolean = false,
        var address: String? = null,
        var reconnectJob: Job? = null,
    )

    private val slots = mapOf(
        DatasetDeviceSlot.Blue to SlotRuntime(blueSource, blueController),
        DatasetDeviceSlot.Green to SlotRuntime(greenSource, greenController),
    )
    private val _state = MutableStateFlow(MultiDeviceDatasetState())
    val state: StateFlow<MultiDeviceDatasetState> = _state.asStateFlow()

    init {
        scope.launch { scanner.state.collect { scan -> _state.update { it.copy(scan = scan) } } }
        DatasetDeviceSlot.entries.forEach { slot ->
            val runtime = runtime(slot)
            scope.launch {
                runtime.source.connectionState.collect { connection ->
                    updateSlot(slot) { it.copy(transportConnection = connection) }
                    when (connection) {
                        is ConnectionState.Connected -> runtime.reconnectJob?.cancel()
                        ConnectionState.Disconnected, is ConnectionState.Failed -> scheduleReconnect(slot)
                        else -> Unit
                    }
                }
            }
            scope.launch {
                runtime.controller.state.collect { dataset ->
                    updateSlot(slot) { it.copy(dataset = dataset) }
                    updateForegroundRuntime()
                }
            }
        }
        scope.launch {
            settingsStore.settings.collect { settings ->
                runtime(DatasetDeviceSlot.Blue).address = settings.blueDeviceAddress
                runtime(DatasetDeviceSlot.Green).address = settings.greenDeviceAddress
                updateSlot(DatasetDeviceSlot.Blue) { it.copy(configuredAddress = settings.blueDeviceAddress) }
                updateSlot(DatasetDeviceSlot.Green) { it.copy(configuredAddress = settings.greenDeviceAddress) }
                blueController.setDataFolderUri(settings.dataFolderUri)
                greenController.setDataFolderUri(settings.dataFolderUri)
            }
        }
    }

    fun scan() = scanner.start()
    fun stopScan() = scanner.stop()

    fun connect(slot: DatasetDeviceSlot, address: String?) {
        val runtime = runtime(slot)
        runtime.desired = true
        runtime.address = address
        runtime.reconnectJob?.cancel()
        scope.launch {
            settingsStore.setDatasetDeviceAddress(slot.wireName, address)
            runtime.source.connect(address)
        }
    }

    fun disconnect(slot: DatasetDeviceSlot, forget: Boolean = false) {
        val runtime = runtime(slot)
        runtime.desired = false
        runtime.reconnectJob?.cancel()
        scope.launch {
            runtime.source.disconnect()
            if (forget) {
                runtime.address = null
                settingsStore.setDatasetDeviceAddress(slot.wireName, null)
            }
        }
    }

    fun restoreConnections() {
        scope.launch {
            val settings = settingsStore.settings.first()
            val remembered = mapOf(
                DatasetDeviceSlot.Blue to settings.blueDeviceAddress,
                DatasetDeviceSlot.Green to settings.greenDeviceAddress,
            )
            remembered.forEach { (slot, address) ->
                if (address != null) connect(slot, address)
            }
        }
    }

    fun identify(slot: DatasetDeviceSlot) = runtime(slot).controller.identify(slot.color)

    fun startBoth(
        activity: ActivityType,
        bluePlacement: SensorPlacement,
        blueSide: BodySide,
        greenPlacement: SensorPlacement,
        greenSide: BodySide,
    ) = scope.launch {
        val pairedId = UUID.randomUUID().toString()
        _state.update { it.copy(pairedOperationMessage = "Starting paired session…") }
        val jobs = listOf(
            blueController.startRecording(activity, bluePlacement, blueSide, pairedId),
            greenController.startRecording(activity, greenPlacement, greenSide, pairedId),
        )
        jobs.joinAll()
        val blueRecording = blueController.state.value.collection is CollectionState.Recording
        val greenRecording = greenController.state.value.collection is CollectionState.Recording
        if (blueRecording != greenRecording) {
            if (blueRecording) blueController.stopRecording().join()
            if (greenRecording) greenController.stopRecording().join()
            _state.update { it.copy(pairedOperationMessage = "Paired start failed; the started board was stopped safely.") }
        } else {
            _state.update {
                it.copy(pairedOperationMessage = if (blueRecording) "Paired session active" else "Neither board started")
            }
        }
    }

    fun stopBoth() = scope.launch {
        _state.update { it.copy(pairedOperationMessage = "Stopping both boards…") }
        listOf(blueController.stopRecording(), greenController.stopRecording()).joinAll()
        _state.update { it.copy(pairedOperationMessage = "Both stop operations completed") }
    }

    fun refresh(slot: DatasetDeviceSlot) = runtime(slot).controller.refreshCatalog()
    fun download(slot: DatasetDeviceSlot, file: DeviceLogFile) = runtime(slot).controller.downloadLog(file)
    fun delete(slot: DatasetDeviceSlot, file: DeviceLogFile) = runtime(slot).controller.deleteLog(file)
    fun cancel(slot: DatasetDeviceSlot) = runtime(slot).controller.cancelTransfer()

    private fun scheduleReconnect(slot: DatasetDeviceSlot) {
        val runtime = runtime(slot)
        if (!runtime.desired || runtime.reconnectJob?.isActive == true) return
        runtime.reconnectJob = scope.launch {
            for (waitMillis in RECONNECT_DELAYS) {
                delay(waitMillis)
                if (!runtime.desired || runtime.source.connectionState.value is ConnectionState.Connected) return@launch
                runtime.source.connect(runtime.address)
                delay(1_500L)
            }
        }
    }

    private fun updateForegroundRuntime() {
        val active = listOf(blueController.state.value, greenController.state.value).any { dataset ->
            dataset.collection is CollectionState.Recording ||
                dataset.collection is CollectionState.PausedForOffload || dataset.transfer.isBusy
        }
        workRuntime.setActive(active)
    }

    private fun runtime(slot: DatasetDeviceSlot): SlotRuntime = requireNotNull(slots[slot])

    private fun updateSlot(slot: DatasetDeviceSlot, block: (DatasetSlotState) -> DatasetSlotState) {
        _state.update { current ->
            if (slot == DatasetDeviceSlot.Blue) current.copy(blue = block(current.blue))
            else current.copy(green = block(current.green))
        }
    }

    companion object {
        private val RECONNECT_DELAYS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    }
}
