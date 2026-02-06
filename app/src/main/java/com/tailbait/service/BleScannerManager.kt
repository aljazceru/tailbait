package com.tailbait.service

import android.content.Context
import android.os.ParcelUuid
import com.tailbait.algorithm.TrackerAnalyzerFactory
import com.tailbait.algorithm.TrackerServiceDetector
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.BeaconDetectionUtils
import com.tailbait.util.Constants
import com.tailbait.util.DeviceFingerprinter
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.DistanceCalculator
import com.tailbait.util.ManufacturerDataParser
import com.tailbait.util.SignalStrength
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE Scanner Manager
 *
 * Core component responsible for managing BLE device scanning operations.
 * Handles scan start/stop, result processing, duplicate filtering, location
 * correlation, and integration with the database.
 *
 * Key Features:
 * - Continuous and manual scanning modes
 * - Configurable scan settings (low latency vs battery modes)
 * - Duplicate device filtering within a scan window
 * - RSSI tracking and signal strength monitoring
 * - Manufacturer data parsing for device identification
 * - Error handling with exponential backoff retry
 * - Location change tracking between scans
 * - Manual scan trigger for Learn Mode
 *
 * @property deviceRepository Repository for device data operations
 * @property locationRepository Repository for location data operations
 * @property settingsRepository Repository for app settings
 * @property context Application context
 */
@Singleton
class BleScannerManager @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) {
    private val bleScanner = BleScanner(context)

    // Current scan job
    private var scanJob: Job? = null

    // Map to track scan results during a scan session (duplicate filtering)
    private val scanResults = mutableMapOf<String, ScanResultData>()

    // State flow for scan state
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // Track last scan timestamp explicitly (regardless of results)
    private val _lastScanTime = MutableStateFlow<Long>(0L)
    val lastScanTime: StateFlow<Long> = _lastScanTime.asStateFlow()

    // Last known location (for location change tracking)
    private var lastLocation: Location? = null

    // Retry configuration for error handling
    private var retryCount = 0
    private val maxRetries = 3
    private val baseRetryDelayMs = 5000L // 5 seconds

    /**
     * Data class to hold comprehensive scan result information during a scan session.
     *
     * Captures all available BLE advertisement data for device identification:
     * - Basic info (address, name, RSSI)
     * - Advertised local name (from advertisement, may differ from system cached name)
     * - Manufacturer ID (Bluetooth SIG company identifier)
     * - Manufacturer data (for Apple Continuity, tracker detection)
     * - Service UUIDs (for Samsung SmartTag, Tile detection)
     * - BLE appearance value (standard device category)
     * - TX power level (signal strength calibration)
     * - Advertising flags (device capabilities)
     * - Raw advertisement bytes (for advanced parsing)
     * - Signal strength classification (VERY_WEAK to VERY_STRONG)
     * - Beacon type detection (iBeacon, Eddystone, Find My, etc.)
     * - Tracker analysis result
     *
     * Enhanced with patterns from Nordic nRF-Connect-Device-Manager.
     */
    data class ScanResultData(
        val address: String,
        val name: String?,  // System cached device name
        val advertisedName: String?,  // Local name from advertisement packet
        val rssi: Int,
        val highestRssi: Int,  // Track strongest signal seen
        val manufacturerId: Int?,  // Bluetooth SIG company identifier (key in sparse array)
        val manufacturerData: ByteArray?,  // Manufacturer-specific data (value in sparse array)
        val serviceUuids: List<ParcelUuid>?,
        val appearance: Int?,
        val txPowerLevel: Int?,
        val advertisingFlags: Int?,
        val rawAdvertisementBytes: ByteArray?,  // Raw advertisement data
        val firstSeen: Long,
        val lastSeen: Long,
        // Enhanced fields from Nordic patterns
        val signalStrength: SignalStrength,  // Classified signal level
        val beaconType: BeaconDetectionUtils.BeaconType?,  // Detected beacon format
        val trackerAnalysis: TrackerAnalyzerFactory.TrackerAnalysis?,  // Manufacturer-specific analysis
        val manufacturerInfo: ManufacturerDataParser.ManufacturerInfo? // Parsed manufacturer info (cached)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ScanResultData

            if (address != other.address) return false
            if (name != other.name) return false
            if (advertisedName != other.advertisedName) return false
            if (rssi != other.rssi) return false
            if (highestRssi != other.highestRssi) return false
            if (manufacturerId != other.manufacturerId) return false
            if (manufacturerData != null) {
                if (other.manufacturerData == null) return false
                if (!manufacturerData.contentEquals(other.manufacturerData)) return false
            } else if (other.manufacturerData != null) return false
            if (serviceUuids != other.serviceUuids) return false
            if (appearance != other.appearance) return false
            if (txPowerLevel != other.txPowerLevel) return false
            if (advertisingFlags != other.advertisingFlags) return false
            if (rawAdvertisementBytes != null) {
                if (other.rawAdvertisementBytes == null) return false
                if (!rawAdvertisementBytes.contentEquals(other.rawAdvertisementBytes)) return false
            } else if (other.rawAdvertisementBytes != null) return false
            if (firstSeen != other.firstSeen) return false
            if (lastSeen != other.lastSeen) return false
            if (signalStrength != other.signalStrength) return false
            if (beaconType != other.beaconType) return false
            if (trackerAnalysis != other.trackerAnalysis) return false
            if (manufacturerInfo != other.manufacturerInfo) return false


            return true
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + (advertisedName?.hashCode() ?: 0)
            result = 31 * result + rssi
            result = 31 * result + highestRssi
            result = 31 * result + (manufacturerId ?: 0)
            result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
            result = 31 * result + (serviceUuids?.hashCode() ?: 0)
            result = 31 * result + (appearance ?: 0)
            result = 31 * result + (txPowerLevel ?: 0)
            result = 31 * result + (advertisingFlags ?: 0)
            result = 31 * result + (rawAdvertisementBytes?.contentHashCode() ?: 0)
            result = 31 * result + firstSeen.hashCode()
            result = 31 * result + lastSeen.hashCode()
            result = 31 * result + signalStrength.hashCode()
            result = 31 * result + (beaconType?.hashCode() ?: 0)
            result = 31 * result + (trackerAnalysis?.hashCode() ?: 0)
            result = 31 * result + (manufacturerInfo?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Sealed class representing the current scan state.
     */
    sealed class ScanState {
        object Idle : ScanState()
        data class Scanning(val devicesFound: Int = 0) : ScanState()
        object Processing : ScanState() // New state for DB operations
        data class Error(val message: String, val canRetry: Boolean = true) : ScanState()
    }

    /**
     * Perform a manual scan for Learn Mode or on-demand scanning.
     *
     * Executes a single scan session with the configured duration, processes
     * results, and returns the number of devices found.
     *
     * @param scanTriggerType Type of scan trigger (default: "MANUAL")
     * @return Number of devices found during the scan
     * @throws CancellationException if the scan is cancelled
     */
    suspend fun performManualScan(scanTriggerType: String = Constants.SCAN_TRIGGER_MANUAL): Int = withContext(Dispatchers.Default) {
        Timber.i("Starting manual scan with trigger type: $scanTriggerType")

        val settings = settingsRepository.getSettingsOnce()
        val scanDurationMs = settings.scanDurationSeconds * 1000L

        try {
            // Manual scan: autoResetState = false so we can transition to Processing manually
            performScan(scanDurationMs, settings.batteryOptimizationEnabled, autoResetState = false)
            
            // Transition to Processing state (keeps WakeLock active)
            _scanState.value = ScanState.Processing
            
            val devicesFound = processScanResults(scanTriggerType)

            Timber.i("Manual scan completed. Found $devicesFound devices")
            retryCount = 0 // Reset retry count on success

            devicesFound
        } catch (e: CancellationException) {
            Timber.w("Manual scan cancelled")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Manual scan failed")
            _scanState.value = ScanState.Error(e.message ?: "Scan failed", canRetry = true)
            throw e
        } finally {
            // Always return to Idle manual scan finish
            _scanState.value = ScanState.Idle
        }
    }

    /**
     * Start continuous background scanning.
     *
     * Initiates a continuous scan loop that runs at the configured interval.
     * The scan will continue until stopScanning() is called or the coroutine
     * scope is cancelled.
     *
     * @param scope CoroutineScope to run the continuous scanning
     */
    /**
     * Start continuous background scanning.
     * DEPRECATED: Scanning is now scheduled by TailBaitService using AlarmManager.
     * This method is retained only to avoid breaking binary compatibility if needed,
     * but strictly throws an error to indicate incorrect usage.
     */
    suspend fun startContinuousScanning(scope: CoroutineScope) {
        Timber.e("startContinuousScanning is deprecated. Use TailBaitService scheduling instead.")
        throw UnsupportedOperationException("startContinuousScanning is deprecated")
    }

    /**
     * Perform a single scan session.
     *
     * Executes BLE scanning for the specified duration using the Nordic BLE library.
     * Collects scan results and stores them in the scanResults map for duplicate filtering.
     *
     * @param durationMs Duration of the scan in milliseconds
     * @param batteryOptimized Whether to use battery-optimized scan settings
     * @param autoResetState Whether to automatically reset state to Idle (default: true)
     * @throws Exception if scan fails
     */
    private suspend fun performScan(durationMs: Long, batteryOptimized: Boolean = false, autoResetState: Boolean = true) {
        // Add a small cool-down to prevent "Application registration failed" (error 2)
        // which happens when scans are started too quickly after stopping.
        delay(200)

        Timber.d("Starting BLE scan for ${durationMs}ms (battery optimized: $batteryOptimized)")

        _scanState.value = ScanState.Scanning(devicesFound = 0)
        scanResults.clear()

        // Configure scan settings based on battery optimization
        val scanMode = if (batteryOptimized) {
            BleScanMode.SCAN_MODE_LOW_POWER
        } else {
            BleScanMode.SCAN_MODE_LOW_LATENCY
        }

        val settings = BleScannerSettings(scanMode = scanMode)

        try {
            // Use withTimeout to limit scan duration
            withTimeout(durationMs) {
                bleScanner.scan(settings = settings)
                    .catch { error ->
                        Timber.e(error, "BLE scan error")
                        throw error
                    }
                    .collect { scanResult ->
                        handleScanResult(scanResult)

                        // Update state with current device count
                        _scanState.value = ScanState.Scanning(devicesFound = scanResults.size)
                    }
            }
        } catch (e: TimeoutCancellationException) {
            // Normal timeout after scan duration - this is expected
            Timber.d("Scan duration completed")
        } catch (e: Exception) {
            Timber.e(e, "Scan failed")
            throw e
        } finally {
            // Update last scan time on completion (success or failure)
            _lastScanTime.value = System.currentTimeMillis()
            if (autoResetState) {
                _scanState.value = ScanState.Idle
            }
        }

        Timber.d("Scan completed. Found ${scanResults.size} unique devices")
    }

    /**
     * Handle a single scan result.
     *
     * Processes each BLE scan result, implementing duplicate filtering by
     * tracking devices within the scan window. Extracts comprehensive BLE
     * advertisement data including:
     * - Manufacturer data (for Apple Continuity, tracker detection)
     * - Service UUIDs (for Samsung SmartTag, Tile detection)
     * - Appearance value (standard device category)
     * - TX power level
     * - Advertising flags
     * - Signal strength classification (4-level system from Nordic patterns)
     * - Beacon type detection (iBeacon, Eddystone, Find My, etc.)
     * - Manufacturer-specific tracker analysis
     *
     * Enhanced with patterns from Nordic nRF-Connect-Device-Manager.
     *
     * @param scanResult Nordic BLE scan result
     */
    private fun handleScanResult(scanResult: BleScanResult) {
        val device = scanResult.device
        val data = scanResult.data ?: return
        val scanRecord = data.scanRecord

        val address = device.address
        val currentTime = System.currentTimeMillis()
        val rssi = data.rssi
        val deviceName = device.name

        // DEBUG: Log raw result to diagnose "0 devices" issue
        // Timber.v("Raw scan: $address, RSSI: $rssi, Name: $deviceName")

        // Extract manufacturer ID and data from sparse array
        // The key is the Bluetooth SIG company identifier, value is manufacturer-specific data
        val manufacturerSpecificData = scanRecord?.manufacturerSpecificData

        // Debug logging to understand manufacturer data structure
        if (manufacturerSpecificData != null && manufacturerSpecificData.size() > 0) {
            val allKeys = (0 until manufacturerSpecificData.size()).map { manufacturerSpecificData.keyAt(it) }
            val allData = (0 until manufacturerSpecificData.size()).map { idx ->
                val bytes = manufacturerSpecificData.valueAt(idx)?.value
                bytes?.joinToString("") { "%02X".format(it) } ?: "null"
            }
            Timber.d("Device $address - Manufacturer IDs: $allKeys (hex: ${allKeys.map { "0x${it.toString(16).uppercase().padStart(4, '0')}" }}), Data: $allData")
        } else {
            Timber.d("Device $address - No manufacturer data in advertisement")
        }

        val manufacturerId: Int? = manufacturerSpecificData
            ?.takeIf { it.size() > 0 }
            ?.keyAt(0)  // Key is the manufacturer ID!
        val manufacturerData: ByteArray? = manufacturerSpecificData
            ?.takeIf { it.size() > 0 }
            ?.valueAt(0)
            ?.value

        // Extract service UUIDs (important for Samsung SmartTag, Tile detection)
        val serviceUuids = scanRecord?.serviceUuids

        // Extract the advertised local name (may differ from device.name which is cached)
        val advertisedName = scanRecord?.deviceName

        // Extract appearance value (standard BLE device category)
        // Note: Nordic scanner may expose this differently - check for raw bytes if needed
        val appearance: Int? = null  // TODO: Extract if available from Nordic API

        // Extract TX power level
        val txPowerLevel = scanRecord?.txPowerLevel

        // Extract advertising flags
        val advertisingFlags = scanRecord?.advertiseFlag

        // Extract raw advertisement bytes for advanced parsing
        val rawBytes = scanRecord?.bytes?.value

        // ================================================================
        // ENHANCED DETECTION (from Nordic patterns)
        // ================================================================

        // Classify signal strength (4-level system from Nordic nRF-Connect-Device-Manager)
        val signalStrength = SignalStrength.fromRssi(rssi)

        // Equal check for optimization
        val existingResult = scanResults[address]
        
        // OPTIMIZATION: Check if data changed to avoid expensive parsing
        val dataChanged = existingResult == null ||
                !java.util.Arrays.equals(existingResult.manufacturerData, manufacturerData) ||
                existingResult.serviceUuids != serviceUuids
        
        // Reuse existing analysis if data hasn't changed
        val manufacturerInfo = if (dataChanged) {
            if (manufacturerId != null && manufacturerData != null) {
                ManufacturerDataParser.parseManufacturerData(manufacturerId, manufacturerData)
            } else null
        } else {
            existingResult?.manufacturerInfo
        }

        val beaconType = if (dataChanged) {
             BeaconDetectionUtils.detectBeacon(
                manufacturerId = manufacturerId,
                manufacturerData = manufacturerData,
                serviceUuids = serviceUuids
            )?.beaconType
        } else {
             existingResult?.beaconType
        }

        val trackerAnalysis = if (dataChanged) {
            TrackerAnalyzerFactory.analyze(
                manufacturerId = manufacturerId,
                manufacturerData = manufacturerData,
                serviceUuids = serviceUuids,
                rssi = rssi,
                deviceName = deviceName
            )
        } else {
            existingResult?.trackerAnalysis
        }

        // Filter out very weak signals
        if (rssi < Constants.MIN_RSSI_THRESHOLD) {
            // Timber.d("Ignored weak signal: $address, RSSI: $rssi < ${Constants.MIN_RSSI_THRESHOLD}")
            return
        }

        if (existingResult != null) {
            // Update existing result - keep best RSSI and merge data
            val newHighestRssi = maxOf(existingResult.highestRssi, rssi)
            val newSignalStrength = SignalStrength.fromRssi(newHighestRssi)

            scanResults[address] = existingResult.copy(
                rssi = maxOf(existingResult.rssi, rssi),
                highestRssi = newHighestRssi,
                lastSeen = currentTime,
                // Update with new data if better/available
                advertisedName = advertisedName ?: existingResult.advertisedName,
                manufacturerId = manufacturerId ?: existingResult.manufacturerId,
                manufacturerData = manufacturerData ?: existingResult.manufacturerData,
                serviceUuids = if (!serviceUuids.isNullOrEmpty()) serviceUuids else existingResult.serviceUuids,
                appearance = appearance ?: existingResult.appearance,
                txPowerLevel = txPowerLevel ?: existingResult.txPowerLevel,
                advertisingFlags = advertisingFlags ?: existingResult.advertisingFlags,
                rawAdvertisementBytes = rawBytes ?: existingResult.rawAdvertisementBytes,
                // Update enhanced fields
                signalStrength = newSignalStrength,
                beaconType = beaconType, // Reused if dataChanged is false
                trackerAnalysis = trackerAnalysis, // Reused if dataChanged is false
                manufacturerInfo = manufacturerInfo // Reused if dataChanged is false
            )
            Timber.v("Updated device: $address, RSSI: $rssi, Signal: ${signalStrength.name}")
        } else {
            // New device found
            scanResults[address] = ScanResultData(
                address = address,
                name = deviceName,
                advertisedName = advertisedName,
                rssi = rssi,
                highestRssi = rssi,
                manufacturerId = manufacturerId,
                manufacturerData = manufacturerData,
                serviceUuids = serviceUuids,
                appearance = appearance,
                txPowerLevel = txPowerLevel,
                advertisingFlags = advertisingFlags,
                rawAdvertisementBytes = rawBytes,
                firstSeen = currentTime,
                lastSeen = currentTime,
                signalStrength = signalStrength,
                beaconType = beaconType,
                trackerAnalysis = trackerAnalysis,
                manufacturerInfo = manufacturerInfo
            )


            // Log with enhanced identification info
            val isTracker = trackerAnalysis?.isTracker == true ||
                    DeviceIdentifier.isTrackerByServiceUuid(serviceUuids) ||
                    ManufacturerDataParser.isTrackingDevice(manufacturerData)
            val trackerInfo = if (isTracker) {
                val type = trackerAnalysis?.trackerType?.name ?: "UNKNOWN"
                val threat = trackerAnalysis?.threatLevel?.name ?: "UNKNOWN"
                " [TRACKER: $type, Threat: $threat]"
            } else ""

            val beaconInfo = beaconType?.let { " [Beacon: ${it.name}]" } ?: ""

            // Show both system cached name and advertised name if they differ
            val nameInfo = when {
                deviceName != null && advertisedName != null && deviceName != advertisedName ->
                    "$deviceName (advertised: $advertisedName)"
                deviceName != null -> deviceName
                advertisedName != null -> "(advertised: $advertisedName)"
                else -> "Unknown"
            }

            Timber.d("New device: $address ($nameInfo), RSSI: $rssi (${signalStrength.name})$trackerInfo$beaconInfo")
        }
    }


    /**
     * Process all scan results and store them in the database.
     *
     * Correlates scan results with the current GPS location, calculates
     * location changes, and stores device-location records in the database.
     *
     * All database operations run on IO dispatcher to prevent blocking the main thread.
     *
     * @param scanTriggerType Type of scan that produced these results
     * @return Number of devices processed
     */
    private suspend fun processScanResultsInternal(scanTriggerType: String): Int = withContext(Dispatchers.IO) {
        if (scanResults.isEmpty()) {
            Timber.i("No devices found in scan")
            return@withContext 0
        }

        Timber.i("Processing ${scanResults.size} scan results")

        // Get current location with fallbacks
        // 1. Try fresh high-accuracy location
        var currentLocation = locationRepository.getCurrentLocation()

        if (currentLocation == null) {
            Timber.w("Fresh location unavailable, trying last known system location")
            // 2. Try last known system location (cache)
            currentLocation = locationRepository.getLastKnownLocation()
        }

        if (currentLocation == null) {
            Timber.w("System location unavailable, trying last database location")
            // 3. Try last stored location in database (might be old, but better than nothing for device linking)
            currentLocation = locationRepository.getLastLocation()
        }

        // Use smart location deduplication to prevent creating duplicate location records
        // when the user hasn't actually moved. This is critical for preventing
        // false positives from MAC rotation of nearby devices.
        val (locationId, isNewLocation) = if (currentLocation != null) {
            // Find existing nearby location or create new one (50m radius)
            locationRepository.findOrCreateLocation(currentLocation, radiusMeters = 50.0)
        } else {
            Timber.e("CRITICAL: Location unavailable after all attempts. Storing devices without location (will not be visible on map/timeline)")
            Pair(null, false)
        }

        // Calculate if location changed since last scan
        val locationChanged = if (currentLocation != null) {
            val last = lastLocation
            if (last != null) {
                val distance = DistanceCalculator.calculateDistance(
                    last.latitude, last.longitude,
                    currentLocation.latitude, currentLocation.longitude
                )
                distance > Constants.DEFAULT_LOCATION_CHANGE_THRESHOLD_METERS
            } else {
                // First scan always counts as location changed
                true
            }
        } else {
            // First scan always counts as location changed
            true
        }

        // Calculate distance from last location
        val distanceFromLast = if (currentLocation != null) {
            val last = lastLocation
            if (last != null) {
                DistanceCalculator.calculateDistance(
                    last.latitude, last.longitude,
                    currentLocation.latitude, currentLocation.longitude
                )
            } else {
                null
            }
        } else {
            null
        }

        Timber.d("Location changed: $locationChanged, Distance from last: $distanceFromLast meters")

        // Record breadcrumb in user movement history (UserPath)
        // This preserves the sequence of locations visited, even if they are the same "Place"
        if (locationId != null && currentLocation != null) {
            try {
                locationRepository.insertUserPath(locationId, currentLocation)
            } catch (e: Exception) {
                Timber.e(e, "Failed to record user path")
            }
        }

        // Process each device
        var devicesProcessed = 0
        scanResults.values.forEach { result ->
            try {
                // Perform comprehensive device identification using all available signals
                val identification = DeviceIdentifier.identifyDevice(
                    manufacturerId = result.manufacturerId,
                    manufacturerData = result.manufacturerData,
                    serviceUuids = result.serviceUuids,
                    appearance = result.appearance,
                    deviceName = result.name
                )

                // Extract Find My payload info for fingerprinting
                // OPTIMIZATION: Use cached result if available
                val manufacturerInfo = result.manufacturerInfo ?: 
                    if (result.manufacturerId != null && result.manufacturerData != null) {
                        ManufacturerDataParser.parseManufacturerData(result.manufacturerId, result.manufacturerData)
                    } else null

                val findMyInfo = manufacturerInfo?.findMyInfo

                // ================================================================
                // ENHANCED FINGERPRINTING (handles all device types)
                // Priority: Apple payload > Service UUID > Composite
                // ================================================================
                // OPTIMIZATION: Use cached payload fingerprint if available
                val payloadFingerprint = manufacturerInfo?.payloadFingerprint ?: 
                    ManufacturerDataParser.extractBestFingerprint(
                        manufacturerId = result.manufacturerId,
                        manufacturerData = result.manufacturerData,
                        serviceUuids = result.serviceUuids
                    ) ?: run {
                    // Try composite fingerprint as last resort
                    DeviceFingerprinter.generateFingerprint(
                        manufacturerId = result.manufacturerId,
                        manufacturerData = result.manufacturerData,
                        serviceUuids = result.serviceUuids,
                        deviceType = identification.deviceType.name,
                        appearance = result.appearance,
                        txPowerLevel = result.txPowerLevel,
                        deviceName = result.name
                    )?.fingerprint
                }


                // Convert service UUIDs to storable string
                val serviceUuidsString = DeviceIdentifier.serviceUuidsToString(result.serviceUuids)

                // Insert or update device using fingerprint-based correlation
                // This handles AirTag MAC rotation by linking devices with the same fingerprint
                val deviceId = deviceRepository.upsertDeviceWithFingerprint(
                    address = result.address,
                    name = result.name,
                    advertisedName = result.advertisedName,
                    lastSeen = result.lastSeen,
                    manufacturerData = result.manufacturerData,
                    manufacturerId = identification.manufacturerId,
                    manufacturerName = identification.manufacturerName,
                    deviceType = identification.deviceType.name,
                    deviceModel = identification.deviceModel,
                    isTracker = identification.isTracker || (result.trackerAnalysis?.isTracker == true),
                    serviceUuids = serviceUuidsString,
                    appearance = result.appearance,
                    txPowerLevel = result.txPowerLevel,
                    advertisingFlags = result.advertisingFlags,
                    appleContinuityType = identification.appleContinuityType,
                    identificationConfidence = identification.confidence,
                    identificationMethod = identification.identificationMethod,
                    // Find My fingerprinting fields
                    payloadFingerprint = payloadFingerprint,
                    findMyStatus = findMyInfo?.statusByte,
                    findMySeparated = findMyInfo?.separatedFromOwner ?: false,
                    // Enhanced fields from Nordic patterns (v7)
                    highestRssi = result.highestRssi,
                    signalStrength = result.signalStrength.name,
                    beaconType = result.beaconType?.name,
                    threatLevel = result.trackerAnalysis?.threatLevel?.name
                )

                // Create device-location correlation record for ALL devices
                if (locationId != null) {
                    deviceRepository.insertDeviceLocationRecord(
                        deviceId = deviceId,
                        locationId = locationId,
                        rssi = result.rssi,
                        timestamp = result.lastSeen,
                        locationChanged = locationChanged ?: false,
                        distanceFromLast = distanceFromLast,
                        scanTriggerType = scanTriggerType
                    )
                }

                devicesProcessed++

                // Enhanced logging with tracker detection and Find My info
                val trackerFlag = if (identification.isTracker) " [TRACKER]" else ""
                val findMyFlag = if (findMyInfo != null) {
                    val separated = if (findMyInfo.separatedFromOwner) " SEPARATED!" else ""
                    " [FindMy:${payloadFingerprint?.take(8)}$separated]"
                } else ""
                Timber.v(
                    "Processed device: ${result.address} (${result.name ?: "Unknown"}), " +
                            "Type: ${identification.deviceType}, " +
                            "Model: ${identification.deviceModel ?: "Unknown"}, " +
                            "Confidence: ${(identification.confidence * 100).toInt()}%, " +
                            "Method: ${identification.identificationMethod}$trackerFlag$findMyFlag"
                )
            } catch (e: Exception) {
                Timber.e(e, "Error processing device: ${result.address}")
            }
        }

        // Update last location
        lastLocation = currentLocation

        // Clear scan results
        scanResults.clear()

        Timber.i("Processed $devicesProcessed devices successfully")
        devicesProcessed
    }

    /**
     * Public method to process scan results for external callers (like WorkManager)
     */
    suspend fun processScanResults(scanTriggerType: String): Int {
        return processScanResultsInternal(scanTriggerType)
    }

    /**
     * Perform a single scan for background workers
     */
    suspend fun performSingleScan(durationMs: Long, batteryOptimized: Boolean = false) {
        Timber.d("Starting single scan for ${durationMs}ms (battery optimized: $batteryOptimized)")

        _scanState.value = ScanState.Scanning(devicesFound = 0)
        performScan(durationMs, batteryOptimized)

        _scanState.value = ScanState.Idle
        Timber.d("Single scan completed")
    }

    /**
     * Stop scanning and save any pending results.
     *
     * Cancels the current scan job, processes any collected results,
     * and ensures data is persisted before resetting state.
     */
    suspend fun stopAndSaveScanResults() {
        Timber.i("Stopping scanner and saving results")

        // 1. Cancel active scan job to stop collecting new data
        scanJob?.cancel()
        scanJob = null

        // 2. Process any accumulated results
        // This will save to DB and clear scanResults map
        if (scanResults.isNotEmpty()) {
            _scanState.value = ScanState.Processing
            try {
                processScanResults(Constants.SCAN_TRIGGER_MANUAL)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save final scan results")
            }
        }

        // 3. Reset state
        _scanState.value = ScanState.Idle
        scanResults.clear() // Safety clear in case processScanResults failed or was empty
        retryCount = 0

        Timber.i("Scanner stopped and results saved")
    }

    /**
     * Stop all scanning operations.
     *
     * Cancels the continuous scan job if running and clears all scan state.
     */
    fun stopScanning() {
        Timber.i("Stopping BLE scanner")

        scanJob?.cancel()
        scanJob = null

        _scanState.value = ScanState.Idle
        scanResults.clear()
        retryCount = 0

        Timber.i("BLE scanner stopped")
    }

    /**
     * Check if scanner is currently active.
     *
     * @return True if a scan is in progress
     */
    fun isScanning(): Boolean {
        return _scanState.value is ScanState.Scanning
    }

    /**
     * Get the current number of devices found in the active scan.
     *
     * @return Number of devices or 0 if not scanning
     */
    fun getCurrentDeviceCount(): Int {
        return when (val state = _scanState.value) {
            is ScanState.Scanning -> state.devicesFound
            else -> 0
        }
    }
}
