package pl.edu.activitytracker.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import pl.edu.activitytracker.ble.BleContract
import pl.edu.activitytracker.ble.BleDatasetProtocol
import pl.edu.activitytracker.ble.BlePayloadParser
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.SummaryReading
import pl.edu.activitytracker.domain.Transport

@SuppressLint("MissingPermission")
class BleDeviceDataSource(
    context: Context,
    deviceName: Flow<String>,
) : DeviceDataSource {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activity = MutableSharedFlow<ActivityReading>(replay = 1, extraBufferCapacity = 16)
    override val activity = _activity.asSharedFlow()

    private val _battery = MutableSharedFlow<BatteryReading>(replay = 1, extraBufferCapacity = 4)
    override val battery = _battery.asSharedFlow()

    private val _summary = MutableSharedFlow<SummaryReading>(replay = 1, extraBufferCapacity = 16)
    override val summary = _summary.asSharedFlow()

    private val _rawEvents = MutableSharedFlow<RawDeviceEvent>(extraBufferCapacity = 64)
    override val rawEvents = _rawEvents.asSharedFlow()

    private val protocolEventChannel = Channel<DeviceProtocolEvent>(Channel.UNLIMITED)
    override val protocolEvents = protocolEventChannel.receiveAsFlow()

    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val commandQueue = ArrayDeque<ByteArray>()
    private val commandLock = Any()

    private var expectedDeviceName = DEFAULT_DEVICE_NAME
    private var scanJob: Job? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var commandWriteInProgress = false
    private var requestedDeviceId: String? = null
    private var serviceDiscoveryStarted = false
    private val controlLineBuffer = StringBuilder()

    init {
        scope.launch {
            deviceName.collect { configuredName ->
                expectedDeviceName = configuredName.trim().ifBlank { DEFAULT_DEVICE_NAME }
            }
        }
    }

    override suspend fun scan() {
        startScan(deviceId = null)
    }

    override suspend fun connect(deviceId: String?) {
        if (_connectionState.value is ConnectionState.Connected) {
            return
        }

        startScan(deviceId)
    }

    override suspend fun disconnect() {
        stopScan()
        synchronized(commandLock) {
            commandQueue.clear()
            commandWriteInProgress = false
        }
        commandCharacteristic = null
        notificationQueue.clear()
        controlLineBuffer.clear()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.Disconnected
        emitRaw("connection", "disconnected")
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        emitRaw("command", command.payload)
        if (_connectionState.value !is ConnectionState.Connected) {
            emitRaw("command_error", "not_connected,${command.payload}")
            return
        }

        queueCommand(command.payload)
    }

    private fun startScan(deviceId: String?) {
        if (!hasBluetoothPermissions()) {
            fail("Bluetooth permission missing")
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            fail("Bluetooth is not supported")
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is disabled")
            return
        }

        stopScan()
        closeGatt()
        requestedDeviceId = deviceId
        _connectionState.value = ConnectionState.Scanning
        emitRaw("scan", "started,$expectedDeviceName")

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            fail("BLE scanner is unavailable")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleContract.SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            scanJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (_connectionState.value is ConnectionState.Scanning) {
                    stopScan()
                    fail("ActivityTracker not found")
                }
            }
        } catch (error: SecurityException) {
            fail("Bluetooth permission missing")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name.orEmpty()
            val requestedId = requestedDeviceId
            val matchesId = requestedId == null || device.address.equals(requestedId, ignoreCase = true)
            val matchesName = advertisedName.isBlank() ||
                advertisedName.equals(expectedDeviceName, ignoreCase = true)

            if (matchesId && matchesName) {
                stopScan()
                connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            fail("BLE scan failed ($errorCode)")
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        emitRaw("connection", "connecting,${device.address}")
        try {
            bluetoothGatt = device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        } catch (error: SecurityException) {
            fail("Bluetooth connect permission missing")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt(gatt)
                fail("BLE connection failed ($status)")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.Connecting
                    emitRaw("connection", "requesting_mtu")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    serviceDiscoveryStarted = false
                    if (!gatt.requestMtu(REQUESTED_MTU)) {
                        discoverServices(gatt)
                    } else {
                        scope.launch {
                            delay(MTU_REQUEST_TIMEOUT_MS)
                            if (bluetoothGatt === gatt && !serviceDiscoveryStarted) {
                                discoverServices(gatt)
                            }
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt(gatt)
                    _connectionState.value = ConnectionState.Disconnected
                    emitRaw("connection", "disconnected")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (bluetoothGatt !== gatt) return
            emitRaw("mtu", "$mtu,$status")
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(gatt, "BLE service discovery failed ($status)")
                return
            }

            val service = gatt.getService(BleContract.SERVICE_UUID)
            if (service == null) {
                failAndClose(gatt, "Activity Tracker BLE service not found")
                return
            }

            commandCharacteristic = service.getCharacteristic(BleContract.COMMAND_UUID)
            if (commandCharacteristic == null) {
                failAndClose(gatt, "Command characteristic not found")
                return
            }

            notificationQueue.clear()
            val notificationCharacteristics = listOf(
                BleContract.CURRENT_ACTIVITY_UUID,
                BleContract.BATTERY_UUID,
                BleContract.SUMMARY_UUID,
                BleContract.CONTROL_RESPONSE_UUID,
                BleContract.FILE_DATA_UUID,
            ).mapNotNull(service::getCharacteristic)

            if (notificationCharacteristics.size != 5) {
                failAndClose(gatt, "Required BLE characteristics not found")
                return
            }

            notificationQueue.addAll(notificationCharacteristics)
            enableNextNotification(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (bluetoothGatt !== gatt) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(gatt, "Could not enable BLE notifications ($status)")
                return
            }
            enableNextNotification(gatt)
        }

        @Deprecated("Deprecated in Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicValue(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicValue(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (bluetoothGatt !== gatt) {
                return
            }
            synchronized(commandLock) {
                if (commandQueue.isNotEmpty()) {
                    commandQueue.removeFirst()
                }
                commandWriteInProgress = false
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitRaw("command_error", "write_failed,$status")
            }
            writeNextCommand()
        }
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        if (notificationQueue.isEmpty()) {
            _connectionState.value = ConnectionState.Connected(Transport.Ble)
            emitRaw("connection", "connected,notifications_enabled")
            return
        }

        val characteristic = notificationQueue.removeFirst()
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            failAndClose(gatt, "Could not enable local BLE notification")
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null || !writeDescriptor(gatt, descriptor)) {
            failAndClose(gatt, "Could not configure BLE notification")
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray) {
        val timestamp = System.currentTimeMillis()
        when (uuid) {
            BleContract.CURRENT_ACTIVITY_UUID -> {
                val payload = textPayload(value)
                emitRaw("current_activity", payload, timestamp)
                BlePayloadParser.parseActivity(payload, timestamp)?.let(_activity::tryEmit)
            }

            BleContract.BATTERY_UUID -> {
                val payload = textPayload(value)
                emitRaw("battery", payload, timestamp)
                BlePayloadParser.parseBattery(payload, timestamp)?.let(_battery::tryEmit)
            }

            BleContract.SUMMARY_UUID -> {
                val payload = textPayload(value)
                emitRaw("summary", payload, timestamp)
                BlePayloadParser.parseSummary(payload, timestamp)?.let(_summary::tryEmit)
            }

            BleContract.CONTROL_RESPONSE_UUID -> handleControlBytes(value, timestamp)

            BleContract.FILE_DATA_UUID -> {
                BleDatasetProtocol.parseFileFrame(value)?.let { frame ->
                    protocolEventChannel.trySend(DeviceProtocolEvent.FileData(frame))
                }
            }
        }
    }

    private fun textPayload(value: ByteArray): String =
        value.toString(Charsets.UTF_8).trim().trimEnd('\u0000')

    private fun handleControlBytes(value: ByteArray, timestamp: Long) {
        controlLineBuffer.append(value.toString(Charsets.UTF_8).trimEnd('\u0000'))
        if (controlLineBuffer.length > MAX_CONTROL_BUFFER_LENGTH) {
            controlLineBuffer.clear()
            emitRaw("control_error", "response_too_long", timestamp)
            return
        }

        while (true) {
            val newlineIndex = controlLineBuffer.indexOf("\n")
            if (newlineIndex < 0) return
            val line = controlLineBuffer.substring(0, newlineIndex).trimEnd('\r')
            controlLineBuffer.delete(0, newlineIndex + 1)
            if (line.isBlank()) continue

            emitRaw("control_response", line, timestamp)
            val response = BleDatasetProtocol.parseControlLine(line) ?: continue
            protocolEventChannel.trySend(DeviceProtocolEvent.Control(response))
        }
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        if (bluetoothGatt !== gatt || serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        emitRaw("connection", "discovering_services")
        if (!gatt.discoverServices()) {
            failAndClose(gatt, "Could not discover BLE services")
        }
    }

    private fun queueCommand(payload: String) {
        synchronized(commandLock) {
            commandQueue.addLast(payload.toByteArray(Charsets.UTF_8))
        }
        writeNextCommand()
    }

    private fun writeNextCommand() {
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        val payload = synchronized(commandLock) {
            if (commandWriteInProgress || commandQueue.isEmpty()) {
                return
            }
            commandWriteInProgress = true
            commandQueue.first()
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            synchronized(commandLock) {
                if (commandQueue.isNotEmpty()) {
                    commandQueue.removeFirst()
                }
                commandWriteInProgress = false
            }
            emitRaw("command_error", "write_not_started")
            writeNextCommand()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (!hasBluetoothPermissions()) {
            return
        }
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // The OS can revoke Bluetooth permission while a scan is active.
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun failAndClose(gatt: BluetoothGatt, message: String) {
        closeGatt(gatt)
        fail(message)
    }

    private fun fail(message: String) {
        _connectionState.value = ConnectionState.Failed(message)
        emitRaw("error", message)
    }

    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        resetGattState()
    }

    private fun resetGattState() {
        commandCharacteristic = null
        notificationQueue.clear()
        controlLineBuffer.clear()
        serviceDiscoveryStarted = false
        synchronized(commandLock) {
            commandQueue.clear()
            commandWriteInProgress = false
        }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
            resetGattState()
        }
        gatt.close()
    }

    private fun emitRaw(source: String, payload: String, timestamp: Long = System.currentTimeMillis()) {
        _rawEvents.tryEmit(
            RawDeviceEvent(
                source = source,
                payload = payload,
                timestampMillis = timestamp,
            ),
        )
    }

    companion object {
        private const val DEFAULT_DEVICE_NAME = "ActivityTracker"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val REQUESTED_MTU = 247
        private const val MTU_REQUEST_TIMEOUT_MS = 2_000L
        private const val MAX_CONTROL_BUFFER_LENGTH = 2_048
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
