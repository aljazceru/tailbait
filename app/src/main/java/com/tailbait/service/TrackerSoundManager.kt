package com.tailbait.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Manager for triggering sounds on BLE tracker devices (AirTags, etc.)
 *
 * This class handles:
 * - BLE GATT connections to tracker devices
 * - Service/characteristic discovery
 * - Sound trigger commands for supported trackers
 *
 * ## Supported Devices
 * - Apple AirTag (via Find My protocol)
 * - Samsung SmartTag (partial support)
 * - Tile trackers (partial support)
 *
 * ## Security Note
 * This feature is intended for anti-stalking purposes, allowing users to locate
 * unknown trackers that may be following them. This is consistent with Apple's
 * own "Tracker Detect" Android app functionality.
 */
@Singleton
class TrackerSoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // AirTag/Find My Service UUIDs (from reverse engineering research)
        // Primary service for Find My accessories
        private val FIND_MY_SERVICE_UUID = UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C")

        // Sound control characteristic
        private val SOUND_CONTROL_CHAR_UUID = UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C")

        // Alternative AirTag service (some firmware versions)
        private val AIRTAG_SERVICE_UUID = UUID.fromString("FD44FD5A-0000-1000-8000-00805F9B34FB")

        // Samsung SmartTag Service UUID
        private val SMARTTAG_SERVICE_UUID = UUID.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")

        // Sound commands
        private val SOUND_START_COMMAND = byteArrayOf(0x01)
        private val SOUND_STOP_COMMAND = byteArrayOf(0x00)

        // Connection timeout
        private const val CONNECTION_TIMEOUT_MS = 15000L
        private const val DISCOVERY_TIMEOUT_MS = 10000L
    }

    /**
     * Sound trigger state
     */
    sealed class SoundTriggerState {
        object Idle : SoundTriggerState()
        object Connecting : SoundTriggerState()
        object DiscoveringServices : SoundTriggerState()
        object TriggeringSound : SoundTriggerState()
        data class Success(val message: String) : SoundTriggerState()
        data class Error(val message: String) : SoundTriggerState()
    }

    private val _state = MutableStateFlow<SoundTriggerState>(SoundTriggerState.Idle)
    val state: StateFlow<SoundTriggerState> = _state.asStateFlow()

    private var currentGatt: BluetoothGatt? = null
    private var connectionJob: Job? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }

    /**
     * Trigger sound on an AirTag or compatible tracker device.
     *
     * @param deviceAddress MAC address of the tracker device
     * @return Result indicating success or failure with message
     */
    @SuppressLint("MissingPermission")
    suspend fun triggerSound(deviceAddress: String): Result<String> = withContext(Dispatchers.IO) {
        Timber.d("Attempting to trigger sound on device: $deviceAddress")

        // Cancel any existing connection attempt
        connectionJob?.cancel()
        disconnect()

        _state.value = SoundTriggerState.Connecting

        try {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                val error = "Bluetooth is not available or disabled"
                _state.value = SoundTriggerState.Error(error)
                return@withContext Result.failure(Exception(error))
            }

            val device = adapter.getRemoteDevice(deviceAddress)
            if (device == null) {
                val error = "Device not found: $deviceAddress"
                _state.value = SoundTriggerState.Error(error)
                return@withContext Result.failure(Exception(error))
            }

            // Connect to the device with timeout
            val gatt = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                connectToDevice(device)
            }

            if (gatt == null) {
                val error = "Connection timed out"
                _state.value = SoundTriggerState.Error(error)
                disconnect()
                return@withContext Result.failure(Exception(error))
            }

            currentGatt = gatt
            _state.value = SoundTriggerState.DiscoveringServices

            // Discover services with timeout
            val servicesDiscovered = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                discoverServices(gatt)
            }

            if (servicesDiscovered != true) {
                val error = "Service discovery timed out or failed"
                _state.value = SoundTriggerState.Error(error)
                disconnect()
                return@withContext Result.failure(Exception(error))
            }

            // Try to find and use the sound characteristic
            _state.value = SoundTriggerState.TriggeringSound

            val result = triggerSoundOnGatt(gatt)

            if (result.isSuccess) {
                _state.value = SoundTriggerState.Success(result.getOrDefault("Sound triggered successfully"))
                // Keep connection for a moment to ensure sound plays
                delay(2000)
            } else {
                _state.value = SoundTriggerState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }

            disconnect()
            return@withContext result

        } catch (e: CancellationException) {
            Timber.d("Sound trigger cancelled")
            disconnect()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error triggering sound on device $deviceAddress")
            _state.value = SoundTriggerState.Error(e.message ?: "Unknown error")
            disconnect()
            return@withContext Result.failure(e)
        }
    }

    /**
     * Connect to a BLE device
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice): BluetoothGatt? = suspendCoroutine { continuation ->
        var resumed = false

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Timber.d("Connection state changed: status=$status, newState=$newState")

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Timber.d("Connected to device")
                        if (!resumed) {
                            resumed = true
                            continuation.resume(gatt)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.d("Disconnected from device")
                        if (!resumed) {
                            resumed = true
                            continuation.resume(null)
                        }
                    }
                }
            }
        }

        try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initiate GATT connection")
            if (!resumed) {
                resumed = true
                continuation.resume(null)
            }
        }
    }

    /**
     * Discover services on connected GATT
     */
    @SuppressLint("MissingPermission")
    private suspend fun discoverServices(gatt: BluetoothGatt): Boolean = suspendCoroutine { continuation ->
        var resumed = false

        // We need to set up a new callback for service discovery
        // Since we can't change the callback, we'll use a workaround with polling
        val success = gatt.discoverServices()

        if (!success) {
            continuation.resume(false)
            return@suspendCoroutine
        }

        // Poll for service discovery completion
        // This is a workaround since we can't easily change GATT callbacks mid-connection
        kotlinx.coroutines.GlobalScope.launch {
            var attempts = 0
            while (attempts < 50 && !resumed) { // 5 seconds max
                delay(100)
                if (gatt.services.isNotEmpty()) {
                    resumed = true
                    continuation.resume(true)
                    return@launch
                }
                attempts++
            }
            if (!resumed) {
                resumed = true
                continuation.resume(false)
            }
        }
    }

    /**
     * Try to trigger sound using discovered services
     */
    @SuppressLint("MissingPermission")
    private suspend fun triggerSoundOnGatt(gatt: BluetoothGatt): Result<String> {
        val services = gatt.services
        Timber.d("Discovered ${services.size} services")

        for (service in services) {
            Timber.d("Service: ${service.uuid}")
            for (char in service.characteristics) {
                Timber.d("  Characteristic: ${char.uuid}, properties: ${char.properties}")
            }
        }

        // Try Find My service first (AirTag)
        var soundChar = gatt.getService(FIND_MY_SERVICE_UUID)?.getCharacteristic(SOUND_CONTROL_CHAR_UUID)

        // Try alternative AirTag service
        if (soundChar == null) {
            soundChar = gatt.getService(AIRTAG_SERVICE_UUID)?.let { service ->
                service.characteristics.firstOrNull { char ->
                    (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
            }
        }

        // Try any writable characteristic on known tracker services
        if (soundChar == null) {
            for (service in services) {
                // Look for writable characteristics
                val writableChar = service.characteristics.firstOrNull { char ->
                    val isWritable = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    isWritable
                }
                if (writableChar != null) {
                    Timber.d("Found writable characteristic: ${writableChar.uuid} in service ${service.uuid}")
                    soundChar = writableChar
                    break
                }
            }
        }

        if (soundChar == null) {
            return Result.failure(Exception("No sound control characteristic found. This device may not support remote sound triggering."))
        }

        Timber.d("Writing sound command to characteristic: ${soundChar.uuid}")

        // Write the sound command
        return try {
            soundChar.value = SOUND_START_COMMAND
            val writeType = if ((soundChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            soundChar.writeType = writeType

            val success = gatt.writeCharacteristic(soundChar)

            if (success) {
                // Give some time for the write to complete
                delay(500)
                Timber.d("Sound command sent successfully")
                Result.success("Sound triggered! The device should be beeping.")
            } else {
                Timber.w("Failed to write sound command")
                Result.failure(Exception("Failed to send sound command to device"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error writing sound command")
            Result.failure(e)
        }
    }

    /**
     * Stop the sound on a tracker device
     */
    @SuppressLint("MissingPermission")
    suspend fun stopSound(): Result<String> = withContext(Dispatchers.IO) {
        val gatt = currentGatt
        if (gatt == null) {
            return@withContext Result.failure(Exception("Not connected to any device"))
        }

        try {
            val soundChar = gatt.getService(FIND_MY_SERVICE_UUID)?.getCharacteristic(SOUND_CONTROL_CHAR_UUID)

            if (soundChar != null) {
                soundChar.value = SOUND_STOP_COMMAND
                gatt.writeCharacteristic(soundChar)
                delay(200)
            }

            disconnect()
            Result.success("Sound stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping sound")
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * Disconnect from the current device
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting")
        }
        currentGatt = null
        _state.value = SoundTriggerState.Idle
    }

    /**
     * Check if a device is potentially a tracker that supports sound
     */
    fun isTrackerWithSoundSupport(deviceType: String?, manufacturerName: String?, isTracker: Boolean): Boolean {
        if (isTracker) return true

        val type = deviceType?.uppercase() ?: ""
        val manufacturer = manufacturerName?.uppercase() ?: ""

        return type == "TRACKER" ||
                type.contains("AIRTAG") ||
                type.contains("SMARTTAG") ||
                type.contains("TILE") ||
                manufacturer == "APPLE" && (type.contains("FIND") || type.contains("TAG")) ||
                manufacturer == "SAMSUNG" && type.contains("TAG") ||
                manufacturer == "TILE"
    }

    /**
     * Reset state to idle
     */
    fun resetState() {
        _state.value = SoundTriggerState.Idle
    }
}
