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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val companionRepository: com.tailbait.data.repository.CompanionDeviceRepository,
    ) {
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

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var worker: Job? = null

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        private val _records = MutableSharedFlow<CompanionProtocol.Record>(extraBufferCapacity = 4096)
        val records: SharedFlow<CompanionProtocol.Record> = _records.asSharedFlow()

        private var adapter: BluetoothAdapter? = null
        private var gatt: BluetoothGatt? = null
        private var targetAddress: String? = null

        @Volatile
        private var wantConnected = false

        /** Periodic REQ_STATUS while connected (keeps mode/fw fresh). */
        private var statusTicker: Job? = null

        fun start(address: String) {
            targetAddress = address
            wantConnected = true
            if (worker?.isActive == true) return
            worker = scope.launch { runLoop() }
        }

        fun stop() {
            wantConnected = false
            statusTicker?.cancel()
            worker?.cancel()
            worker = null
            closeGatt()
            _state.value = State.Idle
        }

        fun setMode(mode: Int) {
            writeControl(CompanionProtocol.setModePayload(mode))
        }

        fun setDwell(ms: Int) {
            writeControl(CompanionProtocol.setDwellPayload(ms))
        }

        // ------------------------------------------------------------------
        private var failures = 0

        private suspend fun runLoop() {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            adapter = bm?.adapter
            while (scope.isActive && wantConnected) {
                val addr = targetAddress ?: return
                if (adapter == null || adapter?.isEnabled != true) {
                    _state.value = State.Error("Bluetooth off")
                    delay(5000)
                    continue
                }
                if (gatt == null) {
                    if (failures >= 2) {
                        // The companion's BLE address is not guaranteed stable
                        // (regenerated on reflash / NVS layout change). After
                        // repeated GATT failures re-resolve it by scanning for
                        // the companion service UUID and persist the new one.
                        // NOTE: failures is counted in the disconnect callback —
                        // connectGatt returns non-null while pending, so the
                        // loop must not treat a pending gatt as success.
                        resyncAddress()
                    }
                    connectDirect(addr)
                    // GATT 133 storms: back off exponentially (concurrent BLE
                    // scanning on this radio makes immediate retries fail)
                    delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
                } else {
                    delay(1000)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun connectDirect(address: String) {
            val dev: BluetoothDevice? =
                try {
                    adapter?.getRemoteDevice(address)
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (dev == null) {
                _state.value = State.Error("Unknown companion $address")
                return
            }
            _state.value = State.Connecting(address)
            try {
                gatt = dev.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                _state.value = State.Error("No Bluetooth permission")
            }
        }

        private val resyncGate = Any()

        /**
         * Re-resolve the companion address by scanning for the service UUID.
         * Called from the link loop after repeated connect failures; updates
         * [targetAddress] and persists the new address to the registry.
         */
        @SuppressLint("MissingPermission")
        private fun resyncAddress() {
            synchronized(resyncGate) {
                val scanner = adapter?.bluetoothLeScanner ?: return
                val svc = UUID.fromString(CompanionProtocol.SERVICE_UUID)
                // Written from the binder/main callback thread, polled here on IO
                val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val cb =
                    object : ScanCallback() {
                        override fun onScanResult(
                            cbType: Int,
                            result: ScanResult,
                        ) {
                            found.set(result.device.address)
                        }
                    }
                try {
                    scanner.startScan(
                        listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(svc)).build()),
                        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                        cb,
                    )
                } catch (_: SecurityException) {
                    return
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (found.get() == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                try {
                    scanner.stopScan(cb)
                } catch (_: Exception) {
                }
                if (found.get() == null) Timber.w("companion resync scan found nothing")
                found.get()?.let { newAddr ->
                    if (newAddr != targetAddress) {
                        Timber.i("companion address changed: $targetAddress -> $newAddr (re-pairing)")
                        val old = targetAddress
                        targetAddress = newAddr
                        scope.launch {
                            try {
                                if (old != null) companionRepository.forget(old)
                            } catch (_: Exception) {
                            }
                            try {
                                companionRepository.pair(newAddr, "TailBait-Companion")
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }

        /** Scan for companions advertising our service — used by pairing UI. */
        @SuppressLint("MissingPermission")
        fun scanForCompanions(
            timeoutSeconds: Int = 15,
            onFound: (ScanResult) -> Unit,
            onDone: () -> Unit,
        ) {
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                onDone()
                return
            }
            val svc = UUID.fromString(CompanionProtocol.SERVICE_UUID)
            val filters =
                listOf(
                    ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(svc)).build(),
                )
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val cb =
                object : ScanCallback() {
                    override fun onScanResult(
                        cbType: Int,
                        result: ScanResult,
                    ) = onFound(result)

                    override fun onScanFailed(code: Int) {
                        Timber.w("companion scan failed: $code")
                        onDone()
                    }
                }
            try {
                scanner.startScan(filters, settings, cb)
            } catch (_: SecurityException) {
                onDone()
                return
            }
            scope.launch {
                delay(timeoutSeconds * 1000L)
                try {
                    scanner.stopScan(cb)
                } catch (_: Exception) {
                }
                onDone()
            }
        }

        // ------------------------------------------------------------------
        private val cccdGate = Any()
        private var statSubscribed = false

        private val gattCb =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Timber.i("companion connected ($status)")
                        failures = 0
                        _state.value = State.Connected(g.device.address)
                        scope.launch {
                            try {
                                companionRepository.markConnected(g.device.address)
                            } catch (_: Exception) {
                            }
                        }
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Timber.i("companion disconnected ($status)")
                        statSubscribed = false
                        failures++
                        closeGatt()
                        if (wantConnected) _state.value = State.Reconnecting(failures)
                    }
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    val svc: BluetoothGattService =
                        g.getService(UUID.fromString(CompanionProtocol.SERVICE_UUID))
                            ?: run {
                                Timber.w("companion service missing ($status)")
                                g.disconnect()
                                return
                            }
                    g.requestMtu(247)
                    pendingSvc = svc
                }

                private var pendingSvc: BluetoothGattService? = null

                override fun onMtuChanged(
                    g: BluetoothGatt,
                    mtu: Int,
                    status: Int,
                ) {
                    Timber.i("companion MTU=$mtu")
                    val svc = pendingSvc ?: return
                    val data = svc.getCharacteristic(UUID.fromString(CompanionProtocol.DATA_UUID)) ?: return
                    try {
                        g.setCharacteristicNotification(data, true)
                        val d = data.getDescriptor(UUID.fromString(CompanionProtocol.CCCD_UUID))
                        if (d != null) {
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(d)
                        }
                    } catch (_: SecurityException) {
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    d: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    val chr = d.characteristic
                    if (chr.uuid.toString() == CompanionProtocol.DATA_UUID && !statSubscribed) {
                        // chain: subscribe STAT next (one GATT op at a time)
                        val svc = pendingSvc ?: return
                        val stat = svc.getCharacteristic(UUID.fromString(CompanionProtocol.STAT_UUID)) ?: return
                        statSubscribed = true
                        try {
                            g.setCharacteristicNotification(stat, true)
                            val sd = stat.getDescriptor(UUID.fromString(CompanionProtocol.CCCD_UUID))
                            if (sd != null) {
                                sd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(sd)
                                return // SET_TIME is chained after THIS write completes
                            }
                        } catch (_: SecurityException) {
                        }
                        writeControl(CompanionProtocol.setTimePayload(System.currentTimeMillis()))
                        startStatusTicker()
                    } else if (chr.uuid.toString() == CompanionProtocol.STAT_UUID) {
                        // both subscriptions live: time-sync the companion so its
                        // record timestamps become epoch-ms, then poll STATUS
                        writeControl(CompanionProtocol.setTimePayload(System.currentTimeMillis()))
                        startStatusTicker()
                    }
                }

                // API 33+ delivers the value as a parameter; older platforms
                // (e.g. Android 9 test phone) call the legacy variant below.
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    onValueChanged(characteristic, value)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    onValueChanged(characteristic, characteristic.value ?: return)
                }

                private fun onValueChanged(
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    val uuidStr = characteristic.uuid.toString()
                    if (uuidStr == CompanionProtocol.STAT_UUID) {
                        CompanionProtocol.parseRecords(value).forEach { rec ->
                            if (rec is CompanionProtocol.Record.Status) {
                                _state.value =
                                    State.Connected(
                                        gatt?.device?.address ?: "?",
                                        rec.firmware,
                                        rec.mode,
                                    )
                                // keep the registry fresh for the settings UI
                                scope.launch {
                                    try {
                                        companionRepository.updateLinkStats(
                                            gatt?.device?.address ?: return@launch,
                                            records = rec.bleCount + rec.wifiCount,
                                            firmware = rec.firmware,
                                            mode = if (rec.mode == CompanionProtocol.MODE_CARRY) "CARRY" else "SENTINEL",
                                            stats = null,
                                        )
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            _records.tryEmit(rec)
                        }
                        return
                    }
                    if (uuidStr == CompanionProtocol.DATA_UUID) {
                        CompanionProtocol.parseRecords(value).forEach { _records.tryEmit(it) }
                    }
                }
            }

        private fun startStatusTicker() {
            statusTicker?.cancel()
            statusTicker =
                scope.launch {
                    while (isActive && wantConnected) {
                        delay(30_000)
                        writeControl(CompanionProtocol.reqStatusPayload())
                    }
                }
        }

        @SuppressLint("MissingPermission")
        private fun writeControl(payload: ByteArray) {
            val g = gatt ?: return
            val svc = g.getService(UUID.fromString(CompanionProtocol.SERVICE_UUID)) ?: return
            val ctrl = svc.getCharacteristic(UUID.fromString(CompanionProtocol.CTRL_UUID)) ?: return
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

        private fun closeGatt() {
            gatt?.let {
                try {
                    it.disconnect()
                    it.close()
                } catch (_: Exception) {
                }
            }
            gatt = null
        }
    }
