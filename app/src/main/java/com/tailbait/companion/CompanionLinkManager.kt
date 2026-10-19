package com.tailbait.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the BLE GATT link to the tailbait-companion ESP32 device.
 *
 * Deliberately uses the framework BluetoothGatt API rather than the Nordic
 * Kotlin client: this exact code path is battle-tested over multi-day bench
 * runs (see tools/e2e harness), and the classic-ESP32 windowed link requires
 * the rescan+direct-connect-per-window strategy which is straightforward
 * here. On the S3 persistent-link firmware the connection simply stays up.
 *
 * Lifecycle: [start] when tracking begins (and a paired, enabled companion
 * exists), [stop] on tracking stop. Records are emitted on [records];
 * connection state on [state].
 */
@Singleton
class CompanionLinkManager
    internal constructor(
        private val context: Context,
        private val companionRepository: com.tailbait.data.repository.CompanionDeviceRepository,
        private val scope: CoroutineScope,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            companionRepository: com.tailbait.data.repository.CompanionDeviceRepository,
        ) : this(context, companionRepository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        sealed class State {
            data object Idle : State()

            data class Scanning(val seconds: Int = 0) : State()

            data class Connecting(val address: String) : State()

            data class Connected(
                val address: String,
                val firmware: String? = null,
                val mode: Int? = null,
            ) : State()

            data class Reconnecting(val attempt: Int) : State()

            data class Error(val message: String) : State()
        }

        private var worker: Job? = null

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        private val _records = MutableSharedFlow<CompanionProtocol.Record>(extraBufferCapacity = 4096)
        val records: SharedFlow<CompanionProtocol.Record> = _records.asSharedFlow()

        private val adapter: BluetoothAdapter? by lazy {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }

        @Volatile
        private var gatt: BluetoothGatt? = null

        @Volatile
        private var targetAddress: String? = null

        private enum class Initialization { CONNECTING, DISCOVERY, MTU, DATA, STAT, READY }

        private var initialization: Initialization? = null
        private var pendingService: BluetoothGattService? = null
        private var initializationTimeout: Job? = null
        private var resyncScan: Pair<BluetoothLeScanner, ScanCallback>? = null
        private var discoveryScan: Pair<BluetoothLeScanner, ScanCallback>? = null
        private var discoveryTimeout: Job? = null

        @Volatile
        private var wantConnected = false

        /** Periodic REQ_STATUS while connected (keeps mode/fw fresh). */
        private var statusTicker: Job? = null

        @Synchronized
        fun start(address: String) {
            if (targetAddress != address) {
                stop()
                failures = 0
            }
            targetAddress = address
            wantConnected = true
            if (worker?.isActive == true) return
            worker = scope.launch(start = CoroutineStart.LAZY) { runLoop() }
            worker?.start()
        }

        @Synchronized
        fun stop() {
            wantConnected = false
            worker?.cancel()
            worker = null
            val scan = resyncScan
            resyncScan = null
            scan?.let(::stopRadioScan)
            stopScanningForCompanions()
            _state.value = State.Idle
            closeGatt()
        }

        fun setMode(mode: Int) {
            writeControl(CompanionProtocol.setModePayload(mode))
        }

        fun setDwell(ms: Int) {
            writeControl(CompanionProtocol.setDwellPayload(ms))
        }

        // ------------------------------------------------------------------
        @Volatile
        private var failures = 0

        private suspend fun runLoop() {
            val owner = currentCoroutineContext().job
            while (owner.isActive && wantConnected) {
                val bluetoothEnabled =
                    synchronized(this) {
                        if (worker !== owner || !wantConnected || targetAddress == null) return
                        (adapter?.isEnabled == true).also { enabled ->
                            if (!enabled) {
                                _state.value = State.Error("Bluetooth off")
                                closeGatt()
                            }
                        }
                    }
                if (!bluetoothEnabled) {
                    delay(5000)
                    continue
                }
                if (gatt == null) {
                    // Resync only every 3rd failure (failures 4, 7, 10, ...).
                    // The resync runs a FILTERED scan; Android suspends the
                    // app's unfiltered main BLE scan while a filtered scan is
                    // active (verified on-device: 60s main scans were 100%
                    // suspended). Rare + brief (3s) keeps that impact bounded.
                    if (failures >= 4 && (failures - 1) % 3 == 0) {
                        // The companion's BLE address is not guaranteed stable
                        // (regenerated on reflash / NVS layout change). After
                        // repeated GATT failures re-resolve it by scanning for
                        // the companion service UUID and persist the new one.
                        resyncAddress(owner)
                    }
                    currentCoroutineContext().ensureActive()
                    targetAddress?.let { connectDirect(it, owner) }
                    // GATT 133 storms: back off exponentially (concurrent BLE
                    // scanning on this radio makes immediate retries fail)
                    delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
                } else {
                    delay(1000)
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Synchronized
        private fun connectDirect(
            address: String,
            owner: Job,
        ) {
            if (worker !== owner || !owner.isActive || !wantConnected || gatt != null) return
            _state.value = State.Connecting(address)
            initialization = Initialization.CONNECTING
            try {
                val device = adapter?.getRemoteDevice(address)
                val connection = device?.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                if (connection == null) {
                    failGatt("Could not connect to companion $address")
                    return
                }
                gatt = connection
                initializationTimeout =
                    scope.launch(owner) {
                        delay(INITIALIZATION_TIMEOUT_MS)
                        synchronized(this@CompanionLinkManager) {
                            if (gatt === connection && initialization != Initialization.READY) {
                                failGatt("Companion initialization timed out")
                            }
                        }
                    }
            } catch (_: SecurityException) {
                failGatt("No Bluetooth permission")
            } catch (_: IllegalArgumentException) {
                failGatt("Unknown companion $address")
            }
        }

        /**
         * Re-resolve the companion address by scanning for the service UUID.
         * Called from the link loop after repeated connect failures; updates
         * [targetAddress] and persists the new address to the registry.
         */
        @SuppressLint("MissingPermission")
        private suspend fun resyncAddress(owner: Job) {
            val scanner = adapter?.bluetoothLeScanner ?: return
            val found = CompletableDeferred<String>()
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        found.complete(result.device.address)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        found.completeExceptionally(IllegalStateException("Companion resync scan failed: $errorCode"))
                    }
                }
            val scan = scanner to callback
            try {
                currentCoroutineContext().ensureActive()
                synchronized(this) {
                    if (worker !== owner || !owner.isActive || !wantConnected) return
                    resyncScan = scan
                    scanner.startScan(
                        listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_ID)).build()),
                        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                        callback,
                    )
                }
                val newAddress = withTimeoutOrNull(3_000) { found.await() }
                if (newAddress == null) {
                    Timber.w("companion resync scan found nothing")
                    return
                }
                currentCoroutineContext().ensureActive()
                val oldAddress =
                    synchronized(this) {
                        if (worker !== owner || !owner.isActive || !wantConnected) return
                        targetAddress.also { targetAddress = newAddress }
                    }
                if (oldAddress != newAddress) {
                    Timber.i("companion address changed: $oldAddress -> $newAddress (re-pairing)")
                    companionRepository.pair(newAddress, "TailBait-Companion")
                    currentCoroutineContext().ensureActive()
                    if (oldAddress != null) companionRepository.forget(oldAddress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "companion address resync failed")
            } finally {
                synchronized(this) {
                    if (resyncScan === scan) {
                        resyncScan = null
                        stopRadioScan(scan)
                    }
                }
            }
        }

        /** Scan for companions advertising our service — used by pairing UI. */
        @SuppressLint("MissingPermission")
        @Synchronized
        fun scanForCompanions(
            timeoutSeconds: Int = 15,
            onFound: (ScanResult) -> Unit,
            onDone: () -> Unit,
        ) {
            stopScanningForCompanions()
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                onDone()
                return
            }
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        synchronized(this@CompanionLinkManager) {
                            if (discoveryScan?.second === this) onFound(result)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        synchronized(this@CompanionLinkManager) {
                            if (discoveryScan?.second !== this) return
                            Timber.w("companion scan failed: $errorCode")
                            stopScanningForCompanions()
                            onDone()
                        }
                    }
                }
            val scan = scanner to callback
            discoveryScan = scan
            try {
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_ID)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
            } catch (_: SecurityException) {
                stopScanningForCompanions()
                onDone()
                return
            }
            discoveryTimeout =
                scope.launch {
                    delay(timeoutSeconds * 1000L)
                    synchronized(this@CompanionLinkManager) {
                        if (discoveryScan === scan) {
                            stopScanningForCompanions()
                            onDone()
                        }
                    }
                }
        }

        @Synchronized
        fun stopScanningForCompanions() {
            discoveryTimeout?.cancel()
            discoveryTimeout = null
            val scan = discoveryScan
            discoveryScan = null
            scan?.let(::stopRadioScan)
        }

        @SuppressLint("MissingPermission")
        private fun stopRadioScan(scan: Pair<BluetoothLeScanner, ScanCallback>) {
            try {
                scan.first.stopScan(scan.second)
            } catch (e: Exception) {
                Timber.w(e, "companion scan stop failed")
            }
        }

        // ------------------------------------------------------------------

        private val gattCb =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    synchronized(this@CompanionLinkManager) {
                        if (!isCurrent(g)) return
                        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                            Timber.i("companion disconnected ($status)")
                            failures++
                            _state.value = State.Reconnecting(failures)
                            closeGatt()
                        } else if (newState == BluetoothProfile.STATE_CONNECTED && initialization == Initialization.CONNECTING) {
                            initialization = Initialization.DISCOVERY
                            try {
                                if (!g.discoverServices()) failGatt("Companion discovery rejected")
                            } catch (_: SecurityException) {
                                failGatt("No Bluetooth permission")
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    synchronized(this@CompanionLinkManager) {
                        if (!isCurrent(g) || initialization != Initialization.DISCOVERY) return
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            failGatt("Companion discovery failed ($status)")
                            return
                        }
                        val service = g.getService(SERVICE_ID)
                        if (service == null ||
                            service.getCharacteristic(DATA_ID) == null ||
                            service.getCharacteristic(STAT_ID) == null ||
                            service.getCharacteristic(CTRL_ID) == null
                        ) {
                            failGatt("Companion service or required characteristic missing")
                            return
                        }
                        pendingService = service
                        initialization = Initialization.MTU
                        try {
                            if (!g.requestMtu(247)) failGatt("Companion MTU request rejected")
                        } catch (_: SecurityException) {
                            failGatt("No Bluetooth permission")
                        }
                    }
                }

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    mtu: Int,
                    status: Int,
                ) {
                    synchronized(this@CompanionLinkManager) {
                        if (!isCurrent(g) || initialization != Initialization.MTU) return
                        if (status != BluetoothGatt.GATT_SUCCESS || mtu < 23) {
                            failGatt("Companion MTU negotiation failed ($status, $mtu)")
                            return
                        }
                        Timber.i("companion MTU=$mtu")
                        subscribe(g, DATA_ID, Initialization.DATA)
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    synchronized(this@CompanionLinkManager) {
                        if (!isCurrent(g) || descriptor.uuid != CCCD_ID) return
                        val expected =
                            when (initialization) {
                                Initialization.DATA -> DATA_ID
                                Initialization.STAT -> STAT_ID
                                else -> return
                            }
                        if (descriptor.characteristic.uuid != expected) return
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            failGatt("Companion subscription failed ($status)")
                            return
                        }
                        if (initialization == Initialization.DATA) {
                            subscribe(g, STAT_ID, Initialization.STAT)
                        } else {
                            initialization = Initialization.READY
                            initializationTimeout?.cancel()
                            initializationTimeout = null
                            failures = 0
                            val address = g.device.address
                            _state.value = State.Connected(address)
                            updateRegistry { companionRepository.markConnected(address) }
                            writeControl(CompanionProtocol.setTimePayload(System.currentTimeMillis()))
                            startStatusTicker()
                        }
                    }
                }

                // API 33+ delivers the value as a parameter; older platforms
                // (e.g. Android 9 test phone) call the legacy variant below.
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    onValueChanged(g, characteristic, value)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    onValueChanged(g, characteristic, characteristic.value ?: return)
                }

                private fun onValueChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    synchronized(this@CompanionLinkManager) {
                        if (!isCurrent(g) || initialization != Initialization.READY) return
                        if (characteristic.uuid == STAT_ID) {
                            val address = g.device.address
                            CompanionProtocol.parseRecords(value).forEach { record ->
                                if (record is CompanionProtocol.Record.Status) {
                                    _state.value = State.Connected(address, record.firmware, record.mode)
                                    updateRegistry {
                                        companionRepository.updateLinkStats(
                                            address,
                                            records = record.bleCount + record.wifiCount,
                                            firmware = record.firmware,
                                            mode = if (record.mode == CompanionProtocol.MODE_CARRY) "CARRY" else "SENTINEL",
                                            stats = null,
                                        )
                                    }
                                }
                                _records.tryEmit(record)
                            }
                        } else if (characteristic.uuid == DATA_ID) {
                            CompanionProtocol.parseRecords(value).forEach { _records.tryEmit(it) }
                        }
                    }
                }
            }

        private fun isCurrent(connection: BluetoothGatt): Boolean = wantConnected && gatt === connection

        @SuppressLint("MissingPermission")
        private fun subscribe(
            connection: BluetoothGatt,
            characteristicId: UUID,
            stage: Initialization,
        ) {
            val characteristic = pendingService?.getCharacteristic(characteristicId)
            val descriptor = characteristic?.getDescriptor(CCCD_ID)
            if (characteristic == null || descriptor == null) {
                failGatt("Companion notification characteristic or CCCD missing")
                return
            }
            initialization = stage
            try {
                if (!connection.setCharacteristicNotification(characteristic, true)) {
                    failGatt("Companion notifications rejected")
                    return
                }
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!connection.writeDescriptor(descriptor)) failGatt("Companion subscription write rejected")
            } catch (_: SecurityException) {
                failGatt("No Bluetooth permission")
            }
        }

        private fun updateRegistry(update: suspend () -> Unit) {
            scope.launch(worker ?: return) {
                try {
                    update()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "companion registry update failed")
                }
            }
        }

        private fun startStatusTicker() {
            statusTicker?.cancel()
            statusTicker =
                scope.launch(worker ?: return) {
                    while (isActive && wantConnected) {
                        delay(30_000)
                        writeControl(CompanionProtocol.reqStatusPayload())
                    }
                }
        }

        @SuppressLint("MissingPermission")
        @Synchronized
        private fun writeControl(payload: ByteArray) {
            if (initialization != Initialization.READY) return
            val g = gatt ?: return
            val svc = g.getService(SERVICE_ID) ?: return
            val ctrl = svc.getCharacteristic(CTRL_ID) ?: return
            try {
                ctrl.value = payload
                ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!g.writeCharacteristic(ctrl)) {
                    Timber.w("companion ctrl write rejected")
                }
            } catch (e: SecurityException) {
                Timber.w(e, "companion ctrl write: no permission")
            }
        }

        private fun failGatt(message: String) {
            Timber.w(message)
            failures++
            _state.value = State.Error(message)
            closeGatt()
        }

        private fun closeGatt() {
            initializationTimeout?.cancel()
            initializationTimeout = null
            statusTicker?.cancel()
            statusTicker = null
            initialization = null
            pendingService = null
            val connection = gatt
            gatt = null
            try {
                connection?.disconnect()
            } catch (e: SecurityException) {
                Timber.w(e, "companion disconnect: no permission")
            } finally {
                try {
                    connection?.close()
                } catch (e: SecurityException) {
                    Timber.w(e, "companion close: no permission")
                }
            }
        }

        private companion object {
            const val INITIALIZATION_TIMEOUT_MS = 20_000L
            val SERVICE_ID: UUID = UUID.fromString(CompanionProtocol.SERVICE_UUID)
            val DATA_ID: UUID = UUID.fromString(CompanionProtocol.DATA_UUID)
            val STAT_ID: UUID = UUID.fromString(CompanionProtocol.STAT_UUID)
            val CTRL_ID: UUID = UUID.fromString(CompanionProtocol.CTRL_UUID)
            val CCCD_ID: UUID = UUID.fromString(CompanionProtocol.CCCD_UUID)
        }
    }
