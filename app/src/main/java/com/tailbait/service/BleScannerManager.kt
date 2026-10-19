package com.tailbait.service

import android.content.Context
import android.os.ParcelUuid
import com.tailbait.algorithm.TrackerAnalyzerFactory
import com.tailbait.data.database.entities.Location
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.util.BeaconDetectionUtils
import com.tailbait.util.Constants
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.DistanceCalculator
import com.tailbait.util.ManufacturerDataParser
import com.tailbait.util.SignalStrength
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.core.scanner.FilteredServiceUuid
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
 * - Cancellation-safe, single-owner persistence
 * - Location change tracking between scans
 * - Manual scan trigger for Learn Mode
 *
 * @property deviceRepository Repository for device data operations
 * @property locationRepository Repository for location data operations
 * @property settingsRepository Repository for app settings
 */
@Singleton
class BleScannerManager internal constructor(
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val whitelistRepository: WhitelistRepository,
    private val alertGenerator: AlertGenerator,
    private val scanSource: (List<BleScanFilter>, BleScannerSettings) -> Flow<BleScanResult>,
    private val scanDispatcher: CoroutineDispatcher,
    private val persistenceDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        deviceRepository: DeviceRepository,
        locationRepository: LocationRepository,
        settingsRepository: SettingsRepository,
        whitelistRepository: WhitelistRepository,
        alertGenerator: AlertGenerator,
        @ApplicationContext context: Context,
    ) : this(
        deviceRepository,
        locationRepository,
        settingsRepository,
        whitelistRepository,
        alertGenerator,
        BleScanner(context)::scan,
        Dispatchers.Default,
        Dispatchers.IO,
    )

    private val ownershipLock = Any()
    private var scanJob: Deferred<Int>? = null
    private var stopRequests = 0
    private var lastCompletedDeviceCount = 0

    // AOSP requires at least one nonempty filter; OR's catch-all retains unknown/no-UUID devices.
    // shortcut: AOSP's any-nonempty policy is platform-specific; recheck no-UUID RF coverage after OS updates.
    private val scanFilters =
        listOf(
            BleScanFilter(),
            BleScanFilter(
                serviceUuid = FilteredServiceUuid(ParcelUuid.fromString("00001800-0000-1000-8000-00805F9B34FB")),
            ),
        )

    // State flow for scan state
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // Track last scan timestamp explicitly (regardless of results)
    private val _lastScanTime = MutableStateFlow<Long>(0L)
    val lastScanTime: StateFlow<Long> = _lastScanTime.asStateFlow()

    // Last known location (for location change tracking)
    private var lastLocation: Location? = null

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
        // System cached device name
        val name: String?,
        // Local name from advertisement packet
        val advertisedName: String?,
        val rssi: Int,
        // Track strongest signal seen
        val highestRssi: Int,
        // Bluetooth SIG company identifier (key in sparse array)
        val manufacturerId: Int?,
        // Manufacturer-specific data (value in sparse array)
        val manufacturerData: ByteArray?,
        val serviceUuids: List<ParcelUuid>?,
        val serviceData: Map<ParcelUuid, ByteArray>,
        val appearance: Int?,
        val txPowerLevel: Int?,
        val advertisingFlags: Int?,
        // Raw advertisement data
        val rawAdvertisementBytes: ByteArray?,
        val firstSeen: Long,
        val lastSeen: Long,
        // Enhanced fields from Nordic patterns
        // Classified signal level
        val signalStrength: SignalStrength,
        // Detected beacon format
        val beaconType: BeaconDetectionUtils.BeaconType?,
        // Manufacturer-specific analysis
        val trackerAnalysis: TrackerAnalyzerFactory.TrackerAnalysis?,
        // Parsed manufacturer info (cached)
        val manufacturerInfo: ManufacturerDataParser.ManufacturerInfo?,
    ) {
        @Suppress("CyclomaticComplexMethod")
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
            } else if (other.manufacturerData != null) {
                return false
            }
            if (serviceUuids != other.serviceUuids) return false
            if (!sameServiceData(serviceData, other.serviceData)) return false
            if (appearance != other.appearance) return false
            if (txPowerLevel != other.txPowerLevel) return false
            if (advertisingFlags != other.advertisingFlags) return false
            if (rawAdvertisementBytes != null) {
                if (other.rawAdvertisementBytes == null) return false
                if (!rawAdvertisementBytes.contentEquals(other.rawAdvertisementBytes)) return false
            } else if (other.rawAdvertisementBytes != null) {
                return false
            }
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
            result = 31 * result + serviceData.entries.sumOf { (uuid, data) -> uuid.hashCode() xor data.contentHashCode() }
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
    suspend fun performManualScan(scanTriggerType: String = Constants.SCAN_TRIGGER_MANUAL): Int = runOwnedScan(scanTriggerType)

    private suspend fun runOwnedScan(
        scanTriggerType: String,
        durationMs: Long? = null,
        batteryOptimized: Boolean = false,
    ): Int =
        coroutineScope {
            val owner =
                synchronized(ownershipLock) {
                    if (stopRequests > 0) throw CancellationException("Scanner is stopping")
                    scanJob?.takeUnless { it.isCompleted }
                        ?: async(scanDispatcher, start = CoroutineStart.LAZY) {
                            collectAndPersist(scanTriggerType, durationMs, batteryOptimized)
                        }.also { job ->
                            scanJob = job
                            lastCompletedDeviceCount = 0
                            job.invokeOnCompletion {
                                synchronized(ownershipLock) {
                                    if (scanJob === job) scanJob = null
                                }
                            }
                        }
                }
            owner.start()
            owner.await()
        }

    private suspend fun collectAndPersist(
        scanTriggerType: String,
        durationMs: Long?,
        batteryOptimized: Boolean,
    ): Int {
        val results = mutableMapOf<String, ScanResultData>()
        var processed = 0
        var freshLocation: Location? = null
        var scanFailure: Throwable? = null
        try {
            val appSettings = if (durationMs == null) settingsRepository.getSettingsOnce() else null
            val scanDurationMs = durationMs ?: (requireNotNull(appSettings).scanDurationSeconds * 1000L)
            val optimized = appSettings?.batteryOptimizationEnabled ?: batteryOptimized
            // Preserve the controller registration cooldown and the full configured scan window.
            delay(200)
            _scanState.value = ScanState.Scanning()
            val settings =
                BleScannerSettings(
                    scanMode = if (optimized) BleScanMode.SCAN_MODE_LOW_POWER else BleScanMode.SCAN_MODE_LOW_LATENCY,
                )
            withTimeoutOrNull(scanDurationMs) {
                scanSource(scanFilters, settings).collect { result ->
                    currentCoroutineContext().ensureActive()
                    handleScanResult(result, results)
                    _scanState.value = ScanState.Scanning(results.size)
                }
            }
            if (results.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                _scanState.value = ScanState.Processing
                freshLocation = locationRepository.getCurrentLocation()
            }
        } catch (e: Throwable) {
            scanFailure = e
            throw e
        } finally {
            // Flow cancellation joins its producer (including Nordic stopScan) before this snapshot.
            withContext(NonCancellable) {
                try {
                    if (results.isNotEmpty()) {
                        _scanState.value = ScanState.Processing
                        processed = processScanResultsInternal(results.values.toList(), scanTriggerType, freshLocation)
                    }
                    synchronized(ownershipLock) { lastCompletedDeviceCount = processed }
                } catch (e: Exception) {
                    if (scanFailure == null) throw e
                    scanFailure.addSuppressed(e)
                    Timber.e(e, "Failed to save partial scan results")
                } finally {
                    _lastScanTime.value = System.currentTimeMillis()
                    _scanState.value = ScanState.Idle
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return processed
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
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun handleScanResult(
        scanResult: BleScanResult,
        scanResults: MutableMap<String, ScanResultData>,
    ) {
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
            val allData =
                (0 until manufacturerSpecificData.size()).map { idx ->
                    val bytes = manufacturerSpecificData.valueAt(idx)?.value
                    bytes?.joinToString("") { "%02X".format(it) } ?: "null"
                }
            Timber.d(
                "Device $address - Manufacturer IDs: $allKeys (hex: ${allKeys.map {
                    "0x${it.toString(
                        16,
                    ).uppercase().padStart(4, '0')}"
                }}), Data: $allData",
            )
        } else {
            Timber.d("Device $address - No manufacturer data in advertisement")
        }

        val manufacturerId: Int? =
            manufacturerSpecificData
                ?.takeIf { it.size() > 0 }
                ?.keyAt(0) // Key is the manufacturer ID!
        val manufacturerData: ByteArray? =
            manufacturerSpecificData
                ?.takeIf { it.size() > 0 }
                ?.valueAt(0)
                ?.value

        // Extract service UUIDs (important for Samsung SmartTag, Tile detection)
        val serviceUuids = scanRecord?.serviceUuids
        val serviceData = scanRecord?.serviceData?.mapValues { it.value.value } ?: emptyMap()

        // Extract the advertised local name (may differ from device.name which is cached)
        val advertisedName = scanRecord?.deviceName

        // Extract appearance value (standard BLE device category)
        // Note: Nordic scanner may expose this differently - check for raw bytes if needed
        val appearance: Int? = null // TODO: Extract if available from Nordic API

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
        val dataChanged =
            existingResult == null ||
                existingResult.manufacturerId != manufacturerId ||
                !java.util.Arrays.equals(existingResult.manufacturerData, manufacturerData) ||
                existingResult.serviceUuids != serviceUuids ||
                !sameServiceData(existingResult.serviceData, serviceData)

        // Reuse existing analysis if data hasn't changed
        val manufacturerInfo =
            if (dataChanged) {
                if (manufacturerId != null && manufacturerData != null) {
                    ManufacturerDataParser.parseManufacturerData(manufacturerId, manufacturerData)
                } else {
                    null
                }
            } else {
                existingResult?.manufacturerInfo
            }

        val beaconType =
            if (dataChanged) {
                BeaconDetectionUtils.detectBeacon(
                    manufacturerId = manufacturerId,
                    manufacturerData = manufacturerData,
                    serviceUuids = serviceUuids,
                )?.beaconType
            } else {
                existingResult?.beaconType
            }

        val trackerAnalysis =
            if (dataChanged) {
                TrackerAnalyzerFactory.analyze(
                    manufacturerId = manufacturerId,
                    manufacturerData = manufacturerData,
                    serviceUuids = serviceUuids,
                    rssi = rssi,
                    deviceName = deviceName,
                    serviceData = serviceData,
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

            scanResults[address] =
                existingResult.copy(
                    rssi = maxOf(existingResult.rssi, rssi),
                    highestRssi = newHighestRssi,
                    lastSeen = currentTime,
                    // Update with new data if better/available
                    advertisedName = advertisedName ?: existingResult.advertisedName,
                    manufacturerId = manufacturerId ?: existingResult.manufacturerId,
                    manufacturerData = manufacturerData ?: existingResult.manufacturerData,
                    serviceUuids = if (!serviceUuids.isNullOrEmpty()) serviceUuids else existingResult.serviceUuids,
                    serviceData = if (serviceData.isNotEmpty()) serviceData else existingResult.serviceData,
                    appearance = appearance ?: existingResult.appearance,
                    txPowerLevel = txPowerLevel ?: existingResult.txPowerLevel,
                    advertisingFlags = advertisingFlags ?: existingResult.advertisingFlags,
                    rawAdvertisementBytes = rawBytes ?: existingResult.rawAdvertisementBytes,
                    // Update enhanced fields
                    signalStrength = newSignalStrength,
                    // Reused if dataChanged is false
                    beaconType = beaconType,
                    // Reused if dataChanged is false
                    trackerAnalysis = trackerAnalysis,
                    // Reused if dataChanged is false
                    manufacturerInfo = manufacturerInfo,
                )
            Timber.v("Updated device: $address, RSSI: $rssi, Signal: ${signalStrength.name}")
        } else {
            // New device found
            scanResults[address] =
                ScanResultData(
                    address = address,
                    name = deviceName,
                    advertisedName = advertisedName,
                    rssi = rssi,
                    highestRssi = rssi,
                    manufacturerId = manufacturerId,
                    manufacturerData = manufacturerData,
                    serviceUuids = serviceUuids,
                    serviceData = serviceData,
                    appearance = appearance,
                    txPowerLevel = txPowerLevel,
                    advertisingFlags = advertisingFlags,
                    rawAdvertisementBytes = rawBytes,
                    firstSeen = currentTime,
                    lastSeen = currentTime,
                    signalStrength = signalStrength,
                    beaconType = beaconType,
                    trackerAnalysis = trackerAnalysis,
                    manufacturerInfo = manufacturerInfo,
                )

            // Log with enhanced identification info
            val isTracker = trackerAnalysis?.isTracker == true
            val trackerInfo =
                if (isTracker) {
                    val type = trackerAnalysis?.trackerType?.name ?: "UNKNOWN"
                    val threat = trackerAnalysis?.threatLevel?.name ?: "UNKNOWN"
                    " [TRACKER: $type, Threat: $threat]"
                } else {
                    ""
                }

            val beaconInfo = beaconType?.let { " [Beacon: ${it.name}]" } ?: ""

            // Show both system cached name and advertised name if they differ
            val nameInfo =
                when {
                    deviceName != null && advertisedName != null && deviceName != advertisedName ->
                        "$deviceName (advertised: $advertisedName)"
                    deviceName != null -> deviceName
                    advertisedName != null -> "(advertised: $advertisedName)"
                    else -> "Unknown"
                }

            Timber.d(
                "New device: $address ($nameInfo), RSSI: $rssi (${signalStrength.name})$trackerInfo$beaconInfo",
            )
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
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun processScanResultsInternal(
        results: List<ScanResultData>,
        scanTriggerType: String,
        freshLocation: Location?,
    ): Int =
        withContext(persistenceDispatcher) {
            if (results.isEmpty()) {
                Timber.i("No devices found in scan")
                return@withContext 0
            }

            Timber.i("Processing ${results.size} scan results")

            // Partial saves must not start new GPS work after the scan owner has been cancelled.
            var currentLocation = freshLocation

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
            val (locationId, isNewLocation) =
                if (currentLocation != null) {
                    // Find existing nearby location or create new one (50m radius)
                    locationRepository.findOrCreateLocation(currentLocation, radiusMeters = 50.0)
                } else {
                    Timber.e(
                        "CRITICAL: Location unavailable after all attempts. " +
                            "Storing devices without location (will not be visible on map/timeline)",
                    )
                    Pair(null, false)
                }

            // Calculate if location changed since last scan
            val locationChanged =
                if (currentLocation != null) {
                    val last = lastLocation
                    if (last != null) {
                        val distance =
                            DistanceCalculator.calculateDistance(
                                last.latitude,
                                last.longitude,
                                currentLocation.latitude,
                                currentLocation.longitude,
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
            val distanceFromLast =
                if (currentLocation != null) {
                    val last = lastLocation
                    if (last != null) {
                        DistanceCalculator.calculateDistance(
                            last.latitude,
                            last.longitude,
                            currentLocation.latitude,
                            currentLocation.longitude,
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to record user path")
                }
            }

            // Camera glasses presence alerts: read the toggle once per scan batch
            val glassesAlertsEnabled =
                try {
                    settingsRepository.getSettingsOnce().cameraGlassesAlertsEnabled
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read camera glasses alerts setting")
                    false
                }

            // Process each device
            var devicesProcessed = 0
            results.forEach { result ->
                try {
                    // Perform comprehensive device identification using all available signals
                    val identification =
                        DeviceIdentifier.identifyDevice(
                            manufacturerId = result.manufacturerId,
                            manufacturerData = result.manufacturerData,
                            serviceUuids = result.serviceUuids,
                            appearance = result.appearance,
                            deviceName = result.name,
                            serviceData = result.serviceData,
                        )

                    // Extract Find My payload info for fingerprinting
                    // OPTIMIZATION: Use cached result if available
                    val manufacturerInfo =
                        result.manufacturerInfo
                            ?: if (result.manufacturerId != null && result.manufacturerData != null) {
                                ManufacturerDataParser.parseManufacturerData(result.manufacturerId, result.manufacturerData)
                            } else {
                                null
                            }

                    val findMyInfo = manufacturerInfo?.findMyInfo

                    val payloadFingerprint =
                        ManufacturerDataParser.extractBestFingerprint(
                            manufacturerId = result.manufacturerId,
                            manufacturerData = result.manufacturerData,
                            serviceUuids = result.serviceUuids,
                            serviceData = result.serviceData,
                        )

                    // Convert service UUIDs to storable string
                    val serviceUuidsString = DeviceIdentifier.serviceUuidsToString(result.serviceUuids)

                    // Insert or update device using fingerprint-based correlation
                    // This handles AirTag MAC rotation by linking devices with the same fingerprint
                    val deviceId =
                        deviceRepository.upsertDeviceWithFingerprint(
                            address = result.address,
                            name = result.name,
                            advertisedName = result.advertisedName,
                            lastSeen = result.lastSeen,
                            manufacturerData = result.manufacturerData,
                            manufacturerId = identification.manufacturerId,
                            manufacturerName = identification.manufacturerName,
                            deviceType = identification.deviceType.name,
                            deviceModel = identification.deviceModel,
                            isTracker = identification.isTracker,
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
                            threatLevel = result.trackerAnalysis?.threatLevel?.name,
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
                            scanTriggerType = scanTriggerType,
                        )
                    }

                    // Camera glasses presence alert ("camera in the room", zuckoff.app-style).
                    // Fires on a single sighting when the toggle is on; whitelisted devices
                    // (e.g. your own glasses) are skipped.
                    if (glassesAlertsEnabled &&
                        identification.deviceType == ManufacturerDataParser.DeviceType.CAMERA_GLASSES &&
                        !whitelistRepository.isDeviceWhitelisted(deviceId)
                    ) {
                        alertGenerator.generateCameraGlassesPresenceAlert(
                            deviceId = deviceId,
                            address = result.address,
                            deviceName = result.name ?: result.advertisedName,
                            deviceModel = identification.deviceModel,
                            manufacturerName = identification.manufacturerName,
                        )
                    }

                    devicesProcessed++

                    // Enhanced logging with tracker detection and Find My info
                    val trackerFlag = if (identification.isTracker) " [TRACKER]" else ""
                    val findMyFlag =
                        if (findMyInfo != null) {
                            val ownerState = if (findMyInfo.separatedFromOwner) " NO RECENT OWNER CONNECTION" else ""
                            " [FindMy:${payloadFingerprint?.take(8)}$ownerState]"
                        } else {
                            ""
                        }
                    Timber.v(
                        "Processed device: ${result.address} (${result.name ?: "Unknown"}), " +
                            "Type: ${identification.deviceType}, " +
                            "Model: ${identification.deviceModel ?: "Unknown"}, " +
                            "Confidence: ${(identification.confidence * 100).toInt()}%, " +
                            "Method: ${identification.identificationMethod}$trackerFlag$findMyFlag",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing device: ${result.address}")
                }
            }

            // Update last location
            lastLocation = currentLocation

            Timber.i("Processed $devicesProcessed devices successfully")
            devicesProcessed
        }

    /**
     * Await the owned scan's persistence; a completed batch is never written again.
     */
    suspend fun processScanResults(scanTriggerType: String): Int {
        Timber.d("Awaiting scan results for $scanTriggerType")
        val owner = synchronized(ownershipLock) { scanJob }
        return owner?.await() ?: synchronized(ownershipLock) { lastCompletedDeviceCount }
    }

    /**
     * Background scans use the same ownership and persistence boundary as manual scans.
     */
    suspend fun performSingleScan(
        durationMs: Long,
        batteryOptimized: Boolean = false,
    ) {
        runOwnedScan(Constants.SCAN_TRIGGER_PERIODIC, durationMs, batteryOptimized)
    }

    /**
     * Cancel collection and join the owner through its single stable-snapshot save.
     */
    suspend fun stopAndSaveScanResults() {
        val owner =
            synchronized(ownershipLock) {
                stopRequests++
                scanJob
            }
        try {
            withContext(NonCancellable) { owner?.cancelAndJoin() }
        } finally {
            synchronized(ownershipLock) { stopRequests-- }
        }
        currentCoroutineContext().ensureActive()
    }

    /**
     * Request cancellation; callers needing a completion barrier use stopAndSaveScanResults.
     */
    fun stopScanning() {
        synchronized(ownershipLock) { scanJob?.cancel() }
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

    companion object {
        private fun sameServiceData(
            first: Map<ParcelUuid, ByteArray>,
            second: Map<ParcelUuid, ByteArray>,
        ): Boolean = first.size == second.size && first.all { (uuid, bytes) -> bytes.contentEquals(second[uuid]) }
    }
}
