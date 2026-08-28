package pl.edu.activitytracker.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.edu.activitytracker.ble.BleContract

data class DiscoveredDatasetDevice(
    val address: String,
    val advertisedName: String,
    val rssi: Int,
    val lastSeenEpochMillis: Long,
) {
    val advertisedShortId: String?
        get() = advertisedName.substringAfter("ActivityTracker-", "").takeIf { it.length == 8 }
}

data class DatasetScanState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredDatasetDevice> = emptyList(),
    val error: String? = null,
)

/** Discovery only. Connections are owned by the two independent slot transports. */
@SuppressLint("MissingPermission")
class BleDeviceScanner(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val found = ConcurrentHashMap<String, DiscoveredDatasetDevice>()
    private val _state = MutableStateFlow(DatasetScanState())
    val state: StateFlow<DatasetScanState> = _state.asStateFlow()
    private var callback: ScanCallback? = null
    private var timeoutJob: Job? = null

    fun start() {
        if (_state.value.scanning) return
        if (!hasPermissions()) {
            _state.value = _state.value.copy(error = "Bluetooth permission missing")
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(error = "Bluetooth is disabled")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(error = "BLE scanner is unavailable")
            return
        }
        stop()
        found.clear()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
            override fun onScanFailed(errorCode: Int) {
                stop()
                _state.value = _state.value.copy(error = "BLE scan failed ($errorCode)")
            }
        }
        callback = scanCallback
        _state.value = DatasetScanState(scanning = true)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleContract.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure {
                callback = null
                _state.value = DatasetScanState(error = "Could not start BLE scan")
            }
        timeoutJob = scope.launch {
            delay(12_000L)
            stop()
        }
    }

    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        val current = callback
        callback = null
        if (current != null && hasPermissions()) {
            runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(current) }
        }
        _state.value = _state.value.copy(scanning = false)
    }

    private fun accept(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: result.device.name ?: "ActivityTracker"
        if (name != "ActivityTracker" && !name.startsWith("ActivityTracker-", ignoreCase = true)) return
        found[result.device.address] = DiscoveredDatasetDevice(
            address = result.device.address,
            advertisedName = name,
            rssi = result.rssi,
            lastSeenEpochMillis = System.currentTimeMillis(),
        )
        _state.value = _state.value.copy(
            devices = found.values.sortedWith(compareByDescending<DiscoveredDatasetDevice> { it.rssi }.thenBy { it.address }),
            error = null,
        )
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
