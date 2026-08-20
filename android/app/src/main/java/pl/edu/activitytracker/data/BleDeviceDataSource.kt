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
import java.util.concurrent.atomic.AtomicBoolean
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
import pl.edu.activitytracker.ble.ControlRecordAssembler
import pl.edu.activitytracker.domain.ActivityReading
import pl.edu.activitytracker.domain.BatteryReading
import pl.edu.activitytracker.domain.ConnectionState
import pl.edu.activitytracker.domain.DeviceCommand
import pl.edu.activitytracker.domain.DeviceControlResponse
import pl.edu.activitytracker.domain.DeviceProtocolEvent
import pl.edu.activitytracker.domain.RawDeviceEvent
import pl.edu.activitytracker.domain.SummaryReading
import pl.edu.activitytracker.domain.Transport

@SuppressLint("MissingPermission")
class BleDeviceDataSource(
    context: Context,
    deviceName: Flow<String>,
) : DeviceDataSource {
    private data class NotificationRegistration(
        val characteristic: BluetoothGattCharacteristic,
        val indication: Boolean,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _connectionGeneration = MutableStateFlow(0L)
    override val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()
    private val _deviceIdentity = MutableStateFlow<String?>(null)
    override val deviceIdentity: StateFlow<String?> = _deviceIdentity.asStateFlow()

    private val _activity = MutableSharedFlow<ActivityReading>(replay = 1, extraBufferCapacity = 16)
    override val activity = _activity.asSharedFlow()
    private val _battery = MutableSharedFlow<BatteryReading>(replay = 1, extraBufferCapacity = 4)
    override val battery = _battery.asSharedFlow()
    private val _summary = MutableSharedFlow<SummaryReading>(replay = 1, extraBufferCapacity = 16)
    override val summary = _summary.asSharedFlow()
    private val _rawEvents = MutableSharedFlow<RawDeviceEvent>(extraBufferCapacity = 64)
    override val rawEvents = _rawEvents.asSharedFlow()

    private val protocolEventChannel = Channel<DeviceProtocolEvent>(PROTOCOL_EVENT_CAPACITY)
    override val protocolEvents = protocolEventChannel.receiveAsFlow()
    private val protocolOverflowReported = AtomicBoolean(false)
    private val fileFramesSuppressed = AtomicBoolean(false)

    private val notificationQueue = ArrayDeque<NotificationRegistration>()
    private val commandQueue = ArrayDeque<ByteArray>()
    private val commandLock = Any()
    private val cacheRefreshLock = Any()
    private val cacheRefreshAttemptedDeviceIds = mutableSetOf<String>()
    private val controlAssembler = ControlRecordAssembler()

    @Volatile
    private var expectedDeviceName = DEFAULT_DEVICE_NAME
    private var scanJob: Job? = null
    private var mtuFallbackJob: Job? = null
    private var phaseTimeoutJob: Job? = null
    private var commandWriteTimeoutJob: Job? = null
    private var commandWriteRetryJob: Job? = null
    @Volatile
    private var activeScanCallback: ScanCallback? = null
    @Volatile
    private var scanGeneration = 0L
    @Volatile
    private var connectionDesired = false
    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var commandWriteInProgress = false
    private var commandWriteRetryCount = 0
    @Volatile
    private var requestedDeviceId: String? = null
    private var generationCounter = 0L
    @Volatile
    private var activeGeneration = 0L
    @Volatile
    private var serviceDiscoveryStarted = false
    @Volatile
    private var negotiatedMtu = DEFAULT_MTU

    init {
        scope.launch {
            deviceName.collect { configuredName ->
                expectedDeviceName = configuredName.trim().ifBlank { DEFAULT_DEVICE_NAME }
            }
        }
    }

    override suspend fun scan() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.Scanning
        ) return
        connectionDesired = true
        startScan(deviceId = null)
    }

    override suspend fun connect(deviceId: String?) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.Scanning
        ) return
        connectionDesired = true
        startScan(deviceId)
    }

    override suspend fun disconnect() {
        connectionDesired = false
        stopScan()
        cancelGattTimeouts()
        val oldGatt = bluetoothGatt
        invalidateGeneration()
        bluetoothGatt = null
        resetGattState(clearProtocolEvents = true)
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
        _deviceIdentity.value = null
        _connectionState.value = ConnectionState.Disconnected
        emitRaw("connection", "disconnected")
    }

    override suspend fun sendCommand(command: DeviceCommand): Boolean {
        emitRaw("command", command.line)
        if (_connectionState.value !is ConnectionState.Connected) {
            emitRaw("command_error", "not_connected,${command.line}")
            return false
        }

        val fragments = runCatching {
            BleDatasetProtocol.fragmentCommand(command, (negotiatedMtu - ATT_OVERHEAD).coerceAtLeast(1))
        }.getOrElse {
            emitProtocolFault(activeGeneration, it.message ?: "invalid_command")
            return false
        }
        synchronized(commandLock) {
            if (commandQueue.size + fragments.size > MAX_QUEUED_COMMAND_FRAGMENTS) return false
            commandQueue.addAll(fragments)
        }
        writeNextCommandFragment()
        return true
    }

    @Synchronized
    private fun startScan(deviceId: String?) {
        if (!hasBluetoothPermissions()) return fail("Bluetooth permission missing")
        val adapter = bluetoothManager?.adapter ?: return fail("Bluetooth is not supported")
        if (!adapter.isEnabled) return fail("Bluetooth is disabled")

        stopScan()
        closeActiveGatt()
        connectionDesired = true
        requestedDeviceId = deviceId
        _connectionState.value = ConnectionState.Scanning
        emitRaw("scan", "started,$expectedDeviceName")
        val scanner = adapter.bluetoothLeScanner ?: return fail("BLE scanner is unavailable")
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleContract.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val token = ++scanGeneration
        val callback = createScanCallback(token)
        activeScanCallback = callback
        try {
            scanner.startScan(filters, settings, callback)
            scanJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (isActiveScan(token)) {
                    stopScan()
                    fail("ActivityTracker not found")
                }
            }
        } catch (_: SecurityException) {
            stopScan()
            fail("Bluetooth permission missing")
        }
    }

    private fun createScanCallback(token: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isActiveScan(token)) return
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name.orEmpty()
            val matchesId = requestedDeviceId == null || device.address.equals(requestedDeviceId, true)
            val matchesName = advertisedName.isBlank() ||
                advertisedName.equals(expectedDeviceName, true) ||
                advertisedName.startsWith("$expectedDeviceName-", true)
            if (matchesId && matchesName && isActiveScan(token)) {
                stopScan()
                if (connectionDesired) connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!isActiveScan(token)) return
            stopScan()
            fail("BLE scan failed ($errorCode)")
        }
    }

    private fun isActiveScan(token: Long): Boolean =
        connectionDesired && token == scanGeneration && _connectionState.value is ConnectionState.Scanning

    private fun connectGatt(device: BluetoothDevice) {
        if (!connectionDesired) return
        val generation = nextGeneration()
        _connectionState.value = ConnectionState.Connecting
        _deviceIdentity.value = device.address
        emitRaw("connection", "connecting,${device.address},generation=$generation")
        try {
            val callback = createGattCallback(generation)
            val gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                _deviceIdentity.value = null
                fail("Could not create a BLE GATT connection")
                return
            }
            bluetoothGatt = gatt
            armPhaseTimeout(gatt, generation, "BLE connection", CONNECT_TIMEOUT_MS)
        } catch (_: SecurityException) {
            _deviceIdentity.value = null
            fail("Bluetooth connect permission missing")
        }
    }

    private fun createGattCallback(generation: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isActive(gatt, generation)) {
                runCatching { gatt.close() }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(gatt, generation, "BLE connection failed ($status)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    cancelPhaseTimeout()
                    _connectionState.value = ConnectionState.Connecting
                    emitRaw("connection", "requesting_mtu")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    serviceDiscoveryStarted = false
                    negotiatedMtu = DEFAULT_MTU
                    if (!gatt.requestMtu(REQUESTED_MTU)) {
                        discoverServices(gatt, generation)
                    } else {
                        mtuFallbackJob?.cancel()
                        mtuFallbackJob = scope.launch {
                            delay(MTU_REQUEST_TIMEOUT_MS)
                            if (isActive(gatt, generation) && !serviceDiscoveryStarted) {
                                discoverServices(gatt, generation)
                            }
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelGattTimeouts()
                    closeGatt(gatt, generation)
                    _deviceIdentity.value = null
                    _connectionState.value = ConnectionState.Disconnected
                    emitRaw("connection", "disconnected")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isActive(gatt, generation)) return
            mtuFallbackJob?.cancel()
            mtuFallbackJob = null
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceAtLeast(DEFAULT_MTU) else DEFAULT_MTU
            emitRaw("mtu", "$negotiatedMtu,$status")
            discoverServices(gatt, generation)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActive(gatt, generation)) return
            cancelPhaseTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(gatt, generation, "BLE service discovery failed ($status)")
                return
            }
            val service = gatt.getService(BleContract.SERVICE_UUID)
                ?: return failAndClose(gatt, generation, "Activity Tracker BLE service not found")
            commandCharacteristic = service.getCharacteristic(BleContract.COMMAND_UUID)
                ?: return failAndClose(gatt, generation, "Command characteristic not found")

            val requiredDefinitions = listOf(
                BleContract.CURRENT_ACTIVITY_UUID to false,
                BleContract.SUMMARY_UUID to false,
                BleContract.BATTERY_UUID to false,
                BleContract.CONTROL_RESPONSE_UUID to true,
                BleContract.FILE_DATA_UUID to false,
            )
            val required = requiredDefinitions.map { (uuid, indication) ->
                service.getCharacteristic(uuid)?.let { NotificationRegistration(it, indication) }
            }
            if (required.any { it == null }) {
                val missing = requiredDefinitions.mapIndexedNotNull { index, definition ->
                    definition.first.takeIf { required[index] == null }
                }
                recoverFromStaleGattCache(gatt, generation, missing)
                return
            }
            synchronized(cacheRefreshLock) {
                cacheRefreshAttemptedDeviceIds.remove(gatt.device.address)
            }
            notificationQueue.clear()
            required.filterNotNullTo(notificationQueue)
            enableNextNotification(gatt, generation)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isActive(gatt, generation)) return
            cancelPhaseTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(gatt, generation, "Could not enable BLE updates ($status)")
                return
            }
            enableNextNotification(gatt, generation)
        }

        @Deprecated("Deprecated in Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (isActive(gatt, generation)) handleCharacteristicValue(characteristic.uuid, characteristic.value, generation)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (isActive(gatt, generation)) handleCharacteristicValue(characteristic.uuid, value, generation)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isActive(gatt, generation) || characteristic.uuid != BleContract.COMMAND_UUID) return
            commandWriteTimeoutJob?.cancel()
            commandWriteTimeoutJob = null
            commandWriteRetryJob?.cancel()
            commandWriteRetryJob = null
            synchronized(commandLock) {
                if (commandQueue.isNotEmpty()) commandQueue.removeFirst()
                commandWriteInProgress = false
                commandWriteRetryCount = 0
                if (status != BluetoothGatt.GATT_SUCCESS) commandQueue.clear()
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitRaw("command_error", "write_failed,$status")
                emitProtocolFault(generation, "command_write_failed:$status")
            } else {
                writeNextCommandFragment()
            }
        }
    }

    private fun enableNextNotification(gatt: BluetoothGatt, generation: Long) {
        if (!isActive(gatt, generation)) return
        if (notificationQueue.isEmpty()) {
            cancelPhaseTimeout()
            _connectionState.value = ConnectionState.Connected(Transport.Ble)
            emitRaw("connection", "connected,v3_updates_enabled")
            return
        }
        val registration = notificationQueue.removeFirst()
        if (!gatt.setCharacteristicNotification(registration.characteristic, true)) {
            failAndClose(gatt, generation, "Could not enable local BLE update")
            return
        }
        val descriptor = registration.characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        val value = if (registration.indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (descriptor == null) {
            failAndClose(gatt, generation, "Could not configure BLE update")
        } else {
            armPhaseTimeout(gatt, generation, "BLE update configuration", GATT_OPERATION_TIMEOUT_MS)
            if (!writeDescriptor(gatt, descriptor, value)) {
                cancelPhaseTimeout()
                failAndClose(gatt, generation, "Could not configure BLE update")
            }
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray, generation: Long) {
        val timestamp = System.currentTimeMillis()
        when (uuid) {
            BleContract.CURRENT_ACTIVITY_UUID -> {
                val payload = textPayload(value)
                emitRaw("current_activity", payload, timestamp)
                BlePayloadParser.parseActivity(payload, timestamp)?.let(_activity::tryEmit)
            }
            BleContract.SUMMARY_UUID -> {
                val payload = textPayload(value)
                emitRaw("summary", payload, timestamp)
                BlePayloadParser.parseSummary(payload, timestamp)?.let(_summary::tryEmit)
            }
            BleContract.BATTERY_UUID -> {
                val payload = textPayload(value)
                emitRaw("battery", payload, timestamp)
                BlePayloadParser.parseBattery(payload, timestamp)?.let(_battery::tryEmit)
            }
            BleContract.CONTROL_RESPONSE_UUID -> {
                try {
                    controlAssembler.append(value).forEach { line ->
                        emitRaw("control_response", line, timestamp)
                        val response = BleDatasetProtocol.parseControlLine(line)
                        if (response == null) {
                            emitProtocolFault(generation, "malformed_control_response")
                        } else {
                            if (response is DeviceControlResponse.DownloadBegin ||
                                response is DeviceControlResponse.Cancelled
                            ) {
                                fileFramesSuppressed.set(false)
                            }
                            emitProtocolEvent(DeviceProtocolEvent.Control(generation, response))
                        }
                    }
                } catch (error: IllegalArgumentException) {
                    emitProtocolFault(generation, error.message ?: "control_record_too_long")
                }
            }
            BleContract.FILE_DATA_UUID -> {
                val frame = BleDatasetProtocol.parseFileFrame(value)
                if (frame == null) emitProtocolFault(generation, "malformed_file_frame")
                else emitProtocolEvent(DeviceProtocolEvent.FileData(generation, frame))
            }
        }
    }

    private fun emitProtocolEvent(event: DeviceProtocolEvent) {
        if (event is DeviceProtocolEvent.FileData && fileFramesSuppressed.get()) return
        if (protocolOverflowReported.get()) return
        if (protocolEventChannel.trySend(event).isFailure && protocolOverflowReported.compareAndSet(false, true)) {
            fileFramesSuppressed.set(true)
            scope.launch {
                try {
                    // Once an event is lost, the active transaction is invalid.
                    // Drop its queued tail so the fault and a later cancel/status
                    // response can make progress on the same connection.
                    while (protocolEventChannel.tryReceive().isSuccess) Unit
                    protocolEventChannel.send(DeviceProtocolEvent.Fault(event.generation, "protocol_buffer_overflow"))
                } finally {
                    protocolOverflowReported.set(false)
                }
            }
        }
    }

    private fun emitProtocolFault(generation: Long, message: String) {
        emitRaw("protocol_error", message)
        emitProtocolEvent(DeviceProtocolEvent.Fault(generation, message))
    }

    private fun textPayload(value: ByteArray): String = value.toString(Charsets.UTF_8).trim().trimEnd('\u0000')

    private fun discoverServices(gatt: BluetoothGatt, generation: Long) {
        if (!isActive(gatt, generation) || serviceDiscoveryStarted) return
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        serviceDiscoveryStarted = true
        emitRaw("connection", "discovering_services")
        armPhaseTimeout(gatt, generation, "BLE service discovery", SERVICE_DISCOVERY_TIMEOUT_MS)
        if (!gatt.discoverServices()) {
            cancelPhaseTimeout()
            failAndClose(gatt, generation, "Could not discover BLE services")
        }
    }

    private fun recoverFromStaleGattCache(
        gatt: BluetoothGatt,
        generation: Long,
        missingCharacteristics: List<UUID>,
    ) {
        if (!isActive(gatt, generation)) return
        val deviceId = gatt.device.address
        val firstAttempt = synchronized(cacheRefreshLock) {
            cacheRefreshAttemptedDeviceIds.add(deviceId)
        }
        val missing = missingCharacteristics.joinToString(separator = ";")
        emitRaw("gatt_cache", "missing=$missing,refresh_attempt=$firstAttempt")
        if (!firstAttempt || !refreshGattCache(gatt)) {
            failAndClose(gatt, generation, "Required BLE v3 characteristics not found: $missing")
            return
        }

        closeGatt(gatt, generation)
        _deviceIdentity.value = null
        _connectionState.value = ConnectionState.Connecting
        emitRaw("gatt_cache", "refreshed,reconnecting,$deviceId")
        scope.launch {
            delay(GATT_CACHE_REFRESH_DELAY_MS)
            if (connectionDesired) startScan(deviceId)
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean = runCatching {
        val refresh = BluetoothGatt::class.java.getMethod("refresh")
        refresh.invoke(gatt) as? Boolean ?: false
    }.getOrDefault(false)

    private fun writeNextCommandFragment() {
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        val payload = synchronized(commandLock) {
            if (commandWriteInProgress || commandQueue.isEmpty()) return
            commandWriteInProgress = true
            commandQueue.first()
        }
        val generation = activeGeneration
        val startStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS else LEGACY_WRITE_NOT_STARTED
        }
        if (startStatus == BluetoothStatusCodes.SUCCESS) {
            armCommandWriteTimeout(gatt, generation)
        } else {
            retryCommandWriteNotStarted(gatt, generation, startStatus)
        }
    }

    private fun retryCommandWriteNotStarted(gatt: BluetoothGatt, generation: Long, startStatus: Int) {
        val shouldRetry = synchronized(commandLock) {
            commandWriteInProgress = false
            if (commandQueue.isEmpty() || commandWriteRetryCount >= MAX_COMMAND_WRITE_START_RETRIES) {
                commandQueue.clear()
                commandWriteRetryCount = 0
                false
            } else {
                commandWriteRetryCount += 1
                true
            }
        }
        if (!shouldRetry) {
            emitRaw("command_error", "write_not_started,status=$startStatus")
            emitProtocolFault(generation, "command_write_not_started:$startStatus")
            return
        }

        emitRaw("command_retry", "write_not_started,status=$startStatus,attempt=$commandWriteRetryCount")
        commandWriteRetryJob?.cancel()
        commandWriteRetryJob = scope.launch {
            delay(COMMAND_WRITE_START_RETRY_DELAY_MS)
            if (isActive(gatt, generation)) writeNextCommandFragment()
        }
    }

    @Synchronized
    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        val callback = activeScanCallback
        activeScanCallback = null
        scanGeneration += 1L
        if (callback == null || !hasBluetoothPermissions()) return
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun nextGeneration(): Long {
        generationCounter += 1L
        activeGeneration = generationCounter
        _connectionGeneration.value = activeGeneration
        protocolOverflowReported.set(false)
        fileFramesSuppressed.set(false)
        return activeGeneration
    }

    private fun invalidateGeneration() {
        nextGeneration()
    }

    private fun isActive(gatt: BluetoothGatt, generation: Long): Boolean =
        generation == activeGeneration && (bluetoothGatt == null || bluetoothGatt === gatt)

    private fun failAndClose(gatt: BluetoothGatt, generation: Long, message: String) {
        if (!isActive(gatt, generation)) return
        closeGatt(gatt, generation)
        _deviceIdentity.value = null
        fail(message)
    }

    private fun fail(message: String) {
        _connectionState.value = ConnectionState.Failed(message)
        emitRaw("error", message)
    }

    private fun closeActiveGatt() {
        val gatt = bluetoothGatt ?: return
        invalidateGeneration()
        bluetoothGatt = null
        resetGattState(clearProtocolEvents = true)
        runCatching { gatt.close() }
        _deviceIdentity.value = null
    }

    private fun closeGatt(gatt: BluetoothGatt, generation: Long) {
        if (generation == activeGeneration && bluetoothGatt === gatt) {
            bluetoothGatt = null
            resetGattState(clearProtocolEvents = false)
            invalidateGeneration()
        }
        runCatching { gatt.close() }
    }

    private fun resetGattState(clearProtocolEvents: Boolean) {
        cancelGattTimeouts()
        commandCharacteristic = null
        notificationQueue.clear()
        controlAssembler.reset()
        serviceDiscoveryStarted = false
        negotiatedMtu = DEFAULT_MTU
        synchronized(commandLock) {
            commandQueue.clear()
            commandWriteInProgress = false
            commandWriteRetryCount = 0
        }
        protocolOverflowReported.set(false)
        fileFramesSuppressed.set(false)
        if (clearProtocolEvents) while (protocolEventChannel.tryReceive().isSuccess) Unit
    }

    private fun armPhaseTimeout(
        gatt: BluetoothGatt,
        generation: Long,
        phase: String,
        timeoutMillis: Long,
    ) {
        phaseTimeoutJob?.cancel()
        phaseTimeoutJob = scope.launch {
            delay(timeoutMillis)
            if (isActive(gatt, generation)) {
                failAndClose(gatt, generation, "$phase timed out")
            }
        }
    }

    private fun cancelPhaseTimeout() {
        phaseTimeoutJob?.cancel()
        phaseTimeoutJob = null
    }

    private fun armCommandWriteTimeout(gatt: BluetoothGatt, generation: Long) {
        commandWriteTimeoutJob?.cancel()
        commandWriteTimeoutJob = scope.launch {
            delay(GATT_OPERATION_TIMEOUT_MS)
            if (isActive(gatt, generation)) {
                synchronized(commandLock) {
                    commandQueue.clear()
                    commandWriteInProgress = false
                }
                failAndClose(gatt, generation, "BLE command write timed out")
            }
        }
    }

    private fun cancelGattTimeouts() {
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        cancelPhaseTimeout()
        commandWriteTimeoutJob?.cancel()
        commandWriteTimeoutJob = null
        commandWriteRetryJob?.cancel()
        commandWriteRetryJob = null
    }

    private fun emitRaw(source: String, payload: String, timestamp: Long = System.currentTimeMillis()) {
        _rawEvents.tryEmit(RawDeviceEvent(source, payload, timestamp))
    }

    companion object {
        private const val DEFAULT_DEVICE_NAME = "ActivityTracker"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val ATT_OVERHEAD = 3
        private const val MTU_REQUEST_TIMEOUT_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L
        private const val GATT_OPERATION_TIMEOUT_MS = 5_000L
        private const val GATT_CACHE_REFRESH_DELAY_MS = 1_000L
        private const val COMMAND_WRITE_START_RETRY_DELAY_MS = 75L
        private const val MAX_COMMAND_WRITE_START_RETRIES = 6
        private const val LEGACY_WRITE_NOT_STARTED = -1
        private const val PROTOCOL_EVENT_CAPACITY = 64
        private const val MAX_QUEUED_COMMAND_FRAGMENTS = 32
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
