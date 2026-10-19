package com.tailbait.data.repository

import androidx.room.withTransaction
import com.tailbait.algorithm.ShadowKeyGenerator
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.ShadowLocationCount
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.util.DeviceFingerprinter
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.ManufacturerDataParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Link strength classification for device correlation.
 *
 * When correlating devices across MAC address rotations, we differentiate:
 * - STRONG: High confidence this is the same physical device
 * - WEAK: Circumstantial evidence only, could be wrong in crowded areas
 */
enum class LinkStrength {
    /**
     * Strong link based on matching full, validated Find My or Find Hub identity.
     */
    STRONG,

    /**
     * Legacy or manual circumstantial link; it does not establish physical identity
     * and must not propagate explicit trust.
     */
    WEAK,
}

/**
 * Repository interface for managing BLE device data.
 *
 * Provides high-level operations for device tracking, including CRUD operations,
 * device-location correlation, and queries for stalking detection.
 */
interface DeviceRepository {
    /**
     * Insert or update a device. If the device already exists (by MAC address),
     * it will be updated with new data.
     *
     * @param address MAC address of the device
     * @param name Device name (nullable)
     * @param lastSeen Timestamp when device was last seen
     * @param manufacturerData Raw manufacturer data as byte array
     * @return Device ID
     */
    suspend fun upsertDevice(
        address: String,
        name: String?,
        lastSeen: Long,
        manufacturerData: ByteArray?,
    ): Long

    /**
     * Insert or update a device with comprehensive identification data.
     *
     * This is the primary method for storing devices with full identification
     * including manufacturer info, device type, tracker detection, and BLE metadata.
     *
     * @param address MAC address of the device
     * @param name Device name (nullable)
     * @param advertisedName Advertised local name from BLE advertisement (nullable)
     * @param lastSeen Timestamp when device was last seen
     * @param manufacturerData Raw manufacturer data as byte array
     * @param manufacturerId Bluetooth SIG manufacturer ID
     * @param manufacturerName Human-readable manufacturer name
     * @param deviceType Inferred device type (PHONE, WATCH, TRACKER, etc.)
     * @param deviceModel Specific device model (AirTag, AirPods Pro, etc.)
     * @param isTracker Whether device is identified as a tracker
     * @param serviceUuids Comma-separated service UUIDs
     * @param appearance BLE appearance value
     * @param txPowerLevel TX power level in dBm
     * @param advertisingFlags BLE advertising flags
     * @param appleContinuityType Apple Continuity message type
     * @param identificationConfidence Confidence score (0.0-1.0)
     * @param identificationMethod Method used for identification
     * @return Device ID
     */
    @Suppress("LongParameterList")
    suspend fun upsertDeviceWithIdentification(
        address: String,
        name: String?,
        advertisedName: String?,
        lastSeen: Long,
        manufacturerData: ByteArray?,
        manufacturerId: Int?,
        manufacturerName: String?,
        deviceType: String?,
        deviceModel: String?,
        isTracker: Boolean,
        serviceUuids: String?,
        appearance: Int?,
        txPowerLevel: Int?,
        advertisingFlags: Int?,
        appleContinuityType: Int?,
        identificationConfidence: Float,
        identificationMethod: String?,
    ): Long

    /**
     * Insert a device-location correlation record.
     *
     * @param deviceId Device ID
     * @param locationId Location ID
     * @param rssi Signal strength
     * @param timestamp Detection timestamp
     * @param locationChanged Whether location changed since last scan
     * @param distanceFromLast Distance from last location in meters
     * @param scanTriggerType Type of scan that detected this device
     * @return Record ID
     */
    suspend fun insertDeviceLocationRecord(
        deviceId: Long,
        locationId: Long,
        rssi: Int,
        timestamp: Long,
        locationChanged: Boolean,
        distanceFromLast: Double?,
        scanTriggerType: String,
    ): Long

    /**
     * Get a device by its MAC address.
     *
     * @param address MAC address
     * @return Device or null if not found
     */
    suspend fun getDeviceByAddress(address: String): ScannedDevice?

    /**
     * Get device by ID.
     *
     * @param deviceId Device ID
     * @return Device or null if not found
     */
    suspend fun getDeviceById(deviceId: Long): ScannedDevice?

    /**
     * Get all devices as a Flow for reactive updates.
     *
     * @return Flow of all devices
     */
    fun getAllDevices(): Flow<List<ScannedDevice>>

    /**
     * Get devices that have been seen at multiple locations (suspicious devices).
     *
     * @param minLocationCount Minimum number of locations
     * @return Flow of suspicious devices
     */
    fun getSuspiciousDevices(minLocationCount: Int): Flow<List<ScannedDevice>>

    /**
     * Get count of distinct locations where a device has been seen.
     *
     * @param deviceId Device ID
     * @return Flow of location count
     */
    fun getDistinctLocationCountForDevice(deviceId: Long): Flow<Int>

    /**
     * Delete devices older than the specified timestamp.
     *
     * @param beforeTimestamp Timestamp threshold
     * @return Number of devices deleted
     */
    suspend fun deleteOldDevices(beforeTimestamp: Long): Int

    /**
     * Delete all devices from the database.
     * WARNING: This removes all device history.
     */
    suspend fun deleteAllDevices()

    /**
     * Get all device-location records for export.
     *
     * @return List of all device-location correlation records
     */
    suspend fun getAllDeviceLocationRecords(): List<DeviceLocationRecord>

    /**
     * Get device-location records for a specific device.
     * Used for movement correlation analysis.
     *
     * @param deviceId Device ID
     * @return List of device-location records
     */
    suspend fun getDeviceLocationRecordsForDevice(deviceId: Long): List<DeviceLocationRecord>

    /**
     * Get eligible records for a confirmed family (root plus direct STRONG children).
     *
     * @param deviceId Device or canonical root ID
     * @param sinceTimestamp Observation cutoff; zero intentionally includes all history
     * @return Records within the cutoff, excluding explicitly trusted families
     */
    suspend fun getDeviceLocationRecordsForDeviceWithLinked(
        deviceId: Long,
        sinceTimestamp: Long = 0,
    ): List<DeviceLocationRecord>

    // ============================================================================
    // FINGERPRINT-BASED DEVICE CORRELATION (AirTag MAC rotation handling)
    // ============================================================================

    /**
     * Get a device by its payload fingerprint.
     * Used to correlate devices across MAC address rotations.
     *
     * @param fingerprint The payload fingerprint
     * @return Device with matching fingerprint, or null
     */
    suspend fun getDeviceByFingerprint(fingerprint: String): ScannedDevice?

    /**
     * Get all devices linked to a primary device (same physical device, different MACs).
     *
     * @param primaryDeviceId The primary device ID
     * @return List of all linked devices including the primary
     */
    suspend fun getLinkedDevices(primaryDeviceId: Long): List<ScannedDevice>

    /**
     * Link a device to a primary device (for MAC rotation tracking).
     *
     * @param deviceId The device to link
     * @param primaryDeviceId The primary device to link to
     * @param linkStrength Strength of the link (STRONG or WEAK)
     * @param linkReason Explanation of why devices were linked
     */
    suspend fun linkDevice(
        deviceId: Long,
        primaryDeviceId: Long,
        linkStrength: String = LinkStrength.WEAK.name,
        linkReason: String = "manual_link",
    )

    /**
     * Get weak-linked devices for a primary device.
     * These should be weighted less in detection algorithm.
     *
     * @param primaryDeviceId The primary device ID
     * @return List of weakly linked devices
     */
    suspend fun getWeakLinkedDevices(primaryDeviceId: Long): List<ScannedDevice>

    /**
     * Get strong-linked devices for a primary device.
     * These have high confidence of being the same physical device.
     *
     * @param primaryDeviceId The primary device ID
     * @return List of strongly linked devices
     */
    suspend fun getStrongLinkedDevices(primaryDeviceId: Long): List<ScannedDevice>

    /**
     * Get suspicious devices, accounting for MAC rotation via fingerprint linking.
     * This considers all locations across all linked devices as one physical device.
     * Only considers device_location_records with timestamps >= sinceTimestamp.
     *
     * @param minLocationCount Minimum locations for suspicion
     * @param sinceTimestamp Only consider records after this timestamp (epoch ms)
     * @return Flow of suspicious devices
     */
    fun getSuspiciousDevicesWithLinked(
        minLocationCount: Int,
        sinceTimestamp: Long,
    ): Flow<List<ScannedDevice>>

    /**
     * Insert or update a device with fingerprint-based correlation.
     * Only a fresh, validated full identity key can link a new MAC to a canonical root.
     *
     * @return The observed MAC's own row ID, on both first and subsequent observations
     */
    @Suppress("LongParameterList")
    suspend fun upsertDeviceWithFingerprint(
        address: String,
        name: String?,
        advertisedName: String?,
        lastSeen: Long,
        manufacturerData: ByteArray?,
        manufacturerId: Int?,
        manufacturerName: String?,
        deviceType: String?,
        deviceModel: String?,
        isTracker: Boolean,
        serviceUuids: String?,
        appearance: Int?,
        txPowerLevel: Int?,
        advertisingFlags: Int?,
        appleContinuityType: Int?,
        identificationConfidence: Float,
        identificationMethod: String?,
        payloadFingerprint: String?,
        findMyStatus: Int?,
        findMySeparated: Boolean,
        // Enhanced fields from Nordic patterns (v7)
        highestRssi: Int? = null,
        signalStrength: String? = null,
        beaconType: String? = null,
        threatLevel: String? = null,
    ): Long

    // ============================================================================
    // COMPANION (ESP32) OBSERVATIONS — radio-aware upsert
    // ============================================================================

    /**
     * Insert or update a device observed via the companion's WiFi radio.
     * Keyed by (address, radio): WiFi STA and AP rows live beside BLE rows.
     * Companion BLE observations go through [upsertDeviceWithFingerprint]
     * instead (full identification pipeline).
     *
     * @return Device id
     */
    suspend fun upsertCompanionObservation(
        radio: String,
        address: String,
        ssid: String?,
        channel: Int?,
        rssi: Int,
        timestamp: Long,
        deviceType: String?,
        wifiFlags: Int?,
    ): Long

    // ============================================================================
    // SHADOW-BASED DETECTION (MAC-agnostic device profiling)
    // ============================================================================

    /**
     * Get shadow keys that appear at a minimum number of distinct locations.
     *
     * @param minLocationCount Minimum distinct locations required
     * @param sinceTimestamp Only consider eligible observations at or after this cutoff
     * @return List of suspicious shadow key strings
     */
    suspend fun getSuspiciousShadowKeys(
        minLocationCount: Int,
        sinceTimestamp: Long,
    ): List<String>

    /**
     * Get all devices matching a specific shadow key.
     *
     * @param shadowKey The shadow key to query
     * @param sinceTimestamp Only include devices observed at or after this cutoff
     * @return List of devices with this shadow profile
     */
    suspend fun getDevicesByShadowKey(
        shadowKey: String,
        sinceTimestamp: Long,
    ): List<ScannedDevice>

    /**
     * Get per-location device counts for a shadow key.
     *
     * @param shadowKey The shadow key to analyze
     * @param sinceTimestamp Only count eligible observations at or after this cutoff
     * @return List of (locationId, deviceCount, maxRssi) per location
     */
    suspend fun getShadowLocationDeviceCounts(
        shadowKey: String,
        sinceTimestamp: Long,
    ): List<ShadowLocationCount>

    /** Eligible shadow observations using the same cutoff and trust scope as shadow counts. */
    suspend fun getDeviceLocationRecordsForShadow(
        shadowKey: String,
        sinceTimestamp: Long,
    ): List<DeviceLocationRecord>

    // ============================================================================
    // TEMPORAL CLUSTERING (For devices without fingerprints)
    // ============================================================================

    /**
     * Find potential duplicate devices using temporal clustering.
     *
     * When a device doesn't have a payload fingerprint (e.g., generic phones),
     * we can try to correlate it with other devices that have similar
     * characteristics and were seen in a similar time window.
     *
     * @param device The device to find duplicates for
     * @param timeWindowMs Time window in milliseconds (default: 30 minutes)
     * @return List of potential duplicate devices
     */
    suspend fun findPotentialDuplicates(
        device: ScannedDevice,
        timeWindowMs: Long = 30 * 60 * 1000,
    ): List<ScannedDevice>

    /**
     * Find devices that "disappeared" around the time a new device appeared.
     *
     * This is useful for detecting MAC rotation patterns.
     *
     * @param newDevice The newly appeared device
     * @param timeWindowMs Time window in milliseconds
     * @return List of candidate devices that may be the same physical device
     */
    suspend fun findDisappearedDevicesNear(
        newDevice: ScannedDevice,
        timeWindowMs: Long = 5 * 60 * 1000,
    ): List<ScannedDevice>

    /**
     * Get devices that have no fingerprint and could benefit from temporal clustering.
     *
     * @param sinceTimestamp Only consider devices seen after this time
     * @return List of unfingerprinted devices
     */
    suspend fun getUnfingerprintedDevices(sinceTimestamp: Long): List<ScannedDevice>

    /**
     * Get count of fingerprinted vs total devices for monitoring.
     *
     * @return Number of devices with fingerprints
     */
    suspend fun getFingerprintedDeviceCount(): Int
}

/**
 * Implementation of DeviceRepository.
 */
@Suppress("TooManyFunctions")
@Singleton
class DeviceRepositoryImpl
    @Inject
    constructor(
        private val database: TailBaitDatabase,
        private val scannedDeviceDao: ScannedDeviceDao,
        private val deviceLocationRecordDao: DeviceLocationRecordDao,
    ) : DeviceRepository {
        override suspend fun upsertDevice(
            address: String,
            name: String?,
            lastSeen: Long,
            manufacturerData: ByteArray?,
        ): Long {
            // Check if device already exists
            val existingDevice = scannedDeviceDao.getByAddress(address)
            val hexManufacturerData = manufacturerData?.let { bytesToHexString(it) }
            val deviceType = manufacturerData?.let { ManufacturerDataParser.extractDeviceType(it) }

            return if (existingDevice != null) {
                // Update existing device
                val updatedDevice =
                    existingDevice.copy(
                        name = name ?: existingDevice.name,
                        lastSeen = lastSeen,
                        detectionCount = existingDevice.detectionCount + 1,
                        manufacturerData = hexManufacturerData ?: existingDevice.manufacturerData,
                        deviceType = deviceType ?: existingDevice.deviceType,
                    )
                scannedDeviceDao.update(updatedDevice)
                existingDevice.id
            } else {
                // Insert new device
                val newDevice =
                    ScannedDevice(
                        address = address,
                        name = name,
                        firstSeen = lastSeen,
                        lastSeen = lastSeen,
                        detectionCount = 1,
                        manufacturerData = hexManufacturerData,
                        deviceType = deviceType,
                    )
                scannedDeviceDao.insert(newDevice)
            }
        }

        override suspend fun upsertDeviceWithIdentification(
            address: String,
            name: String?,
            advertisedName: String?,
            lastSeen: Long,
            manufacturerData: ByteArray?,
            manufacturerId: Int?,
            manufacturerName: String?,
            deviceType: String?,
            deviceModel: String?,
            isTracker: Boolean,
            serviceUuids: String?,
            appearance: Int?,
            txPowerLevel: Int?,
            advertisingFlags: Int?,
            appleContinuityType: Int?,
            identificationConfidence: Float,
            identificationMethod: String?,
        ): Long {
            // Check if device already exists
            val existingDevice = scannedDeviceDao.getByAddress(address)
            val hexManufacturerData = manufacturerData?.let { bytesToHexString(it) }

            return if (existingDevice != null) {
                // Determine final manufacturer name:
                // - If we have a chipset manufacturer ID, clear the name (don't show "Interplan" etc. to users)
                // - Otherwise, use new name or fall back to existing
                val finalManufacturerName =
                    if (DeviceIdentifier.isChipsetManufacturer(manufacturerId)) {
                        null // Clear chipset manufacturer names
                    } else {
                        manufacturerName ?: existingDevice.manufacturerName
                    }

                // Update existing device with comprehensive identification
                val updatedDevice =
                    existingDevice.copy(
                        name = name ?: existingDevice.name,
                        advertisedName = advertisedName ?: existingDevice.advertisedName,
                        lastSeen = lastSeen,
                        detectionCount = existingDevice.detectionCount + 1,
                        // Manufacturer data
                        manufacturerData = hexManufacturerData ?: existingDevice.manufacturerData,
                        manufacturerId = manufacturerId ?: existingDevice.manufacturerId,
                        manufacturerName = finalManufacturerName,
                        // Device classification - prefer new data if confidence is higher
                        deviceType =
                            if (identificationConfidence > (existingDevice.identificationConfidence)) {
                                deviceType
                            } else {
                                existingDevice.deviceType
                            },
                        deviceModel =
                            if (identificationConfidence > (existingDevice.identificationConfidence)) {
                                deviceModel
                            } else {
                                existingDevice.deviceModel
                            },
                        // Tracker flag - once marked as tracker, stays as tracker
                        isTracker = isTracker || existingDevice.isTracker,
                        // BLE advertisement data - update if new data available
                        serviceUuids = serviceUuids ?: existingDevice.serviceUuids,
                        appearance = appearance ?: existingDevice.appearance,
                        txPowerLevel = txPowerLevel ?: existingDevice.txPowerLevel,
                        advertisingFlags = advertisingFlags ?: existingDevice.advertisingFlags,
                        // Apple-specific
                        appleContinuityType = appleContinuityType ?: existingDevice.appleContinuityType,
                        // Identification confidence - keep the higher confidence
                        identificationConfidence =
                            maxOf(
                                identificationConfidence,
                                existingDevice.identificationConfidence,
                            ),
                        identificationMethod =
                            if (identificationConfidence > existingDevice.identificationConfidence) {
                                identificationMethod
                            } else {
                                existingDevice.identificationMethod
                            },
                    )
                scannedDeviceDao.update(updatedDevice)
                existingDevice.id
            } else {
                // Insert new device with full identification
                val newDevice =
                    ScannedDevice(
                        address = address,
                        name = name,
                        advertisedName = advertisedName,
                        firstSeen = lastSeen,
                        lastSeen = lastSeen,
                        detectionCount = 1,
                        manufacturerData = hexManufacturerData,
                        manufacturerId = manufacturerId,
                        manufacturerName = manufacturerName,
                        deviceType = deviceType,
                        deviceModel = deviceModel,
                        isTracker = isTracker,
                        serviceUuids = serviceUuids,
                        appearance = appearance,
                        txPowerLevel = txPowerLevel,
                        advertisingFlags = advertisingFlags,
                        appleContinuityType = appleContinuityType,
                        identificationConfidence = identificationConfidence,
                        identificationMethod = identificationMethod,
                    )
                scannedDeviceDao.insert(newDevice)
            }
        }

        override suspend fun insertDeviceLocationRecord(
            deviceId: Long,
            locationId: Long,
            rssi: Int,
            timestamp: Long,
            locationChanged: Boolean,
            distanceFromLast: Double?,
            scanTriggerType: String,
        ): Long {
            val record =
                DeviceLocationRecord(
                    deviceId = deviceId,
                    locationId = locationId,
                    rssi = rssi,
                    timestamp = timestamp,
                    locationChanged = locationChanged,
                    distanceFromLast = distanceFromLast,
                    scanTriggerType = scanTriggerType,
                )
            return deviceLocationRecordDao.insert(record)
        }

        override suspend fun getDeviceByAddress(address: String): ScannedDevice? {
            return scannedDeviceDao.getByAddress(address)
        }

        override suspend fun getDeviceById(deviceId: Long): ScannedDevice? {
            return scannedDeviceDao.getById(deviceId)
        }

        override fun getAllDevices(): Flow<List<ScannedDevice>> {
            return scannedDeviceDao.getAllDevices()
        }

        override fun getSuspiciousDevices(minLocationCount: Int): Flow<List<ScannedDevice>> {
            return scannedDeviceDao.getSuspiciousDevices(minLocationCount)
        }

        override fun getDistinctLocationCountForDevice(deviceId: Long): Flow<Int> {
            return deviceLocationRecordDao.getDistinctLocationCountForDevice(deviceId)
        }

        override suspend fun deleteOldDevices(beforeTimestamp: Long): Int {
            return scannedDeviceDao.deleteOldDevices(beforeTimestamp)
        }

        override suspend fun deleteAllDevices() {
            scannedDeviceDao.deleteAll()
            deviceLocationRecordDao.deleteAll()
        }

        override suspend fun getAllDeviceLocationRecords(): List<DeviceLocationRecord> {
            return deviceLocationRecordDao.getAllRecords().first()
        }

        override suspend fun getDeviceLocationRecordsForDevice(deviceId: Long): List<DeviceLocationRecord> {
            return deviceLocationRecordDao.getRecordsForDevice(deviceId).first()
        }

        override suspend fun getDeviceLocationRecordsForDeviceWithLinked(
            deviceId: Long,
            sinceTimestamp: Long,
        ): List<DeviceLocationRecord> {
            return deviceLocationRecordDao.getRecordsForDeviceWithLinked(deviceId, sinceTimestamp)
        }

        // ============================================================================
        // FINGERPRINT-BASED DEVICE CORRELATION (AirTag MAC rotation handling)
        // ============================================================================

        override suspend fun getDeviceByFingerprint(fingerprint: String): ScannedDevice? {
            return scannedDeviceDao.getByFingerprint(fingerprint)
        }

        override suspend fun getLinkedDevices(primaryDeviceId: Long): List<ScannedDevice> {
            return scannedDeviceDao.getLinkedDevices(primaryDeviceId)
        }

        override suspend fun linkDevice(
            deviceId: Long,
            primaryDeviceId: Long,
            linkStrength: String,
            linkReason: String,
        ) {
            scannedDeviceDao.linkDevice(
                deviceId,
                primaryDeviceId,
                System.currentTimeMillis(),
                linkStrength,
                linkReason,
            )
        }

        override suspend fun getWeakLinkedDevices(primaryDeviceId: Long): List<ScannedDevice> {
            return scannedDeviceDao.getWeakLinkedDevices(primaryDeviceId)
        }

        override suspend fun getStrongLinkedDevices(primaryDeviceId: Long): List<ScannedDevice> {
            return scannedDeviceDao.getStrongLinkedDevices(primaryDeviceId)
        }

        override fun getSuspiciousDevicesWithLinked(
            minLocationCount: Int,
            sinceTimestamp: Long,
        ): Flow<List<ScannedDevice>> {
            return scannedDeviceDao.getSuspiciousDevicesWithLinked(minLocationCount, sinceTimestamp)
        }

        @Suppress("LongMethod", "CyclomaticComplexMethod")
        override suspend fun upsertDeviceWithFingerprint(
            address: String,
            name: String?,
            advertisedName: String?,
            lastSeen: Long,
            manufacturerData: ByteArray?,
            manufacturerId: Int?,
            manufacturerName: String?,
            deviceType: String?,
            deviceModel: String?,
            isTracker: Boolean,
            serviceUuids: String?,
            appearance: Int?,
            txPowerLevel: Int?,
            advertisingFlags: Int?,
            appleContinuityType: Int?,
            identificationConfidence: Float,
            identificationMethod: String?,
            payloadFingerprint: String?,
            findMyStatus: Int?,
            findMySeparated: Boolean,
            // Enhanced fields from Nordic patterns (v7)
            highestRssi: Int?,
            signalStrength: String?,
            beaconType: String?,
            threatLevel: String?,
        ): Long {
            return database.withTransaction {
                // Check if device already exists by MAC address
                val existingDevice = scannedDeviceDao.getByAddress(address)
                val hexManufacturerData = manufacturerData?.let { bytesToHexString(it) }
                val identityFingerprint = payloadFingerprint?.takeIf(DeviceFingerprinter::isHighConfidenceFingerprint)
                val validFindMyStatus = findMyStatus?.takeIf { it in 0..255 }

                // If device exists by MAC, update it
                if (existingDevice != null) {
                    val finalManufacturerName =
                        if (DeviceIdentifier.isChipsetManufacturer(manufacturerId)) {
                            null
                        } else {
                            manufacturerName ?: existingDevice.manufacturerName
                        }

                    // Track highest RSSI ever seen for this device
                    val newHighestRssi =
                        when {
                            highestRssi == null -> existingDevice.highestRssi
                            existingDevice.highestRssi == null -> highestRssi
                            else -> maxOf(highestRssi, existingDevice.highestRssi)
                        }
                    val isLatestObservation = lastSeen >= existingDevice.lastSeen
                    val serviceTrackerEvidence =
                        isTracker && (identificationMethod == "SERVICE_UUID" || identificationMethod == "SERVICE_DATA")
                    val updateClassification =
                        isLatestObservation &&
                            (isTracker || !existingDevice.isTracker) &&
                            (serviceTrackerEvidence || identificationConfidence >= existingDevice.identificationConfidence)
                    val updateFindMyState =
                        validFindMyStatus != null && lastSeen >= (existingDevice.findMyStatusTimestamp ?: Long.MIN_VALUE)

                    val updatedDevice =
                        existingDevice.copy(
                            name = name ?: existingDevice.name,
                            advertisedName = advertisedName ?: existingDevice.advertisedName,
                            firstSeen = minOf(lastSeen, existingDevice.firstSeen),
                            lastSeen = maxOf(lastSeen, existingDevice.lastSeen),
                            detectionCount = existingDevice.detectionCount + 1,
                            manufacturerData = hexManufacturerData ?: existingDevice.manufacturerData,
                            manufacturerId = manufacturerId ?: existingDevice.manufacturerId,
                            manufacturerName = finalManufacturerName,
                            deviceType =
                                if (updateClassification) {
                                    deviceType
                                } else {
                                    existingDevice.deviceType
                                },
                            deviceModel =
                                if (updateClassification) {
                                    deviceModel
                                } else {
                                    existingDevice.deviceModel
                                },
                            isTracker = isTracker || existingDevice.isTracker,
                            serviceUuids = serviceUuids ?: existingDevice.serviceUuids,
                            appearance = appearance ?: existingDevice.appearance,
                            txPowerLevel = txPowerLevel ?: existingDevice.txPowerLevel,
                            advertisingFlags = advertisingFlags ?: existingDevice.advertisingFlags,
                            appleContinuityType = appleContinuityType ?: existingDevice.appleContinuityType,
                            identificationConfidence =
                                if (updateClassification) identificationConfidence else existingDevice.identificationConfidence,
                            identificationMethod =
                                if (updateClassification) {
                                    identificationMethod
                                } else {
                                    existingDevice.identificationMethod
                                },
                            // Find My fingerprinting fields
                            payloadFingerprint =
                                if (isLatestObservation && identityFingerprint != null) {
                                    identityFingerprint
                                } else {
                                    existingDevice.payloadFingerprint?.takeIf(DeviceFingerprinter::isHighConfidenceFingerprint)
                                },
                            findMyStatus = if (updateFindMyState) validFindMyStatus else existingDevice.findMyStatus,
                            findMySeparated = if (updateFindMyState) findMySeparated else existingDevice.findMySeparated,
                            findMyStatusTimestamp =
                                if (updateFindMyState) lastSeen else existingDevice.findMyStatusTimestamp,
                            // Enhanced fields from Nordic patterns (v7)
                            highestRssi = newHighestRssi,
                            signalStrength = signalStrength ?: existingDevice.signalStrength,
                            beaconType = beaconType ?: existingDevice.beaconType,
                            threatLevel = threatLevel ?: existingDevice.threatLevel,
                        )
                    val deviceWithShadow = computeShadowKey(updatedDevice)
                    scannedDeviceDao.update(deviceWithShadow)
                    existingDevice.id
                } else {
                    val matchingDevice =
                        identityFingerprint?.let { fingerprint ->
                            scannedDeviceDao.getByFingerprint(fingerprint)?.takeIf {
                                // Both protocols rotate identity keys; stale matches are not identity evidence.
                                val gap = lastSeen - it.lastSeen
                                it.radio == "BLE" && gap in -20 * 60 * 1000L..20 * 60 * 1000L
                            }
                        }
                    val primaryDevice =
                        when {
                            matchingDevice == null -> null
                            matchingDevice.linkedDeviceId == null -> matchingDevice
                            matchingDevice.linkStrength == LinkStrength.STRONG.name ->
                                scannedDeviceDao.getById(matchingDevice.linkedDeviceId)?.takeIf {
                                    it.linkedDeviceId == null && it.radio == "BLE"
                                }
                            else -> null
                        }
                    val newDevice =
                        ScannedDevice(
                            address = address,
                            name = name,
                            advertisedName = advertisedName,
                            firstSeen = lastSeen,
                            lastSeen = lastSeen,
                            detectionCount = 1,
                            manufacturerData = hexManufacturerData,
                            manufacturerId = manufacturerId,
                            manufacturerName = manufacturerName,
                            deviceType = deviceType,
                            deviceModel = deviceModel,
                            isTracker = isTracker,
                            serviceUuids = serviceUuids,
                            appearance = appearance,
                            txPowerLevel = txPowerLevel,
                            advertisingFlags = advertisingFlags,
                            appleContinuityType = appleContinuityType,
                            identificationConfidence = identificationConfidence,
                            identificationMethod = identificationMethod,
                            payloadFingerprint = identityFingerprint,
                            findMyStatus = validFindMyStatus,
                            findMySeparated = validFindMyStatus != null && findMySeparated,
                            findMyStatusTimestamp = if (validFindMyStatus != null) lastSeen else null,
                            linkedDeviceId = primaryDevice?.id,
                            lastMacRotation = if (primaryDevice != null) lastSeen else null,
                            linkStrength = if (primaryDevice != null) LinkStrength.STRONG.name else null,
                            linkReason = if (primaryDevice != null) "fingerprint_match:$identityFingerprint" else null,
                            highestRssi = highestRssi,
                            signalStrength = signalStrength,
                            beaconType = beaconType,
                            threatLevel = threatLevel,
                        )
                    scannedDeviceDao.insert(computeShadowKey(newDevice))
                }
            }
        }

        /**
         * Convert byte array to hex string for storage.
         */
        private fun bytesToHexString(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun computeShadowKey(device: ScannedDevice): ScannedDevice {
            val newKey = ShadowKeyGenerator.generate(device)
            return if (newKey == device.shadowKey) device else device.copy(shadowKey = newKey)
        }

        override suspend fun upsertCompanionObservation(
            radio: String,
            address: String,
            ssid: String?,
            channel: Int?,
            rssi: Int,
            timestamp: Long,
            deviceType: String?,
            wifiFlags: Int?,
        ): Long {
            val existing = scannedDeviceDao.getByAddressAndRadio(address, radio)
            return if (existing != null) {
                val updated =
                    existing.copy(
                        ssid = ssid ?: existing.ssid,
                        channel = channel ?: existing.channel,
                        wifiFlags = wifiFlags ?: existing.wifiFlags,
                        lastSeen = maxOf(timestamp, existing.lastSeen),
                        detectionCount = existing.detectionCount + 1,
                        highestRssi = maxOf(rssi, existing.highestRssi ?: rssi),
                        deviceType = deviceType ?: existing.deviceType,
                    )
                scannedDeviceDao.update(updated)
                existing.id
            } else {
                scannedDeviceDao.insert(
                    com.tailbait.data.database.entities.ScannedDevice(
                        address = address,
                        radio = radio,
                        ssid = ssid,
                        channel = channel,
                        wifiFlags = wifiFlags,
                        name =
                            when (radio) {
                                "WIFI_AP" -> ssid ?: address
                                else -> address
                            },
                        firstSeen = timestamp,
                        lastSeen = timestamp,
                        detectionCount = 1,
                        deviceType = deviceType,
                        highestRssi = rssi,
                    ),
                )
            }
        }

        // ============================================================================
        // SHADOW-BASED DETECTION IMPLEMENTATION
        // ============================================================================

        override suspend fun getSuspiciousShadowKeys(
            minLocationCount: Int,
            sinceTimestamp: Long,
        ): List<String> {
            return scannedDeviceDao.getSuspiciousShadowKeys(minLocationCount, sinceTimestamp)
        }

        override suspend fun getDevicesByShadowKey(
            shadowKey: String,
            sinceTimestamp: Long,
        ): List<ScannedDevice> {
            return scannedDeviceDao.getDevicesByShadowKey(shadowKey, sinceTimestamp)
        }

        override suspend fun getShadowLocationDeviceCounts(
            shadowKey: String,
            sinceTimestamp: Long,
        ): List<ShadowLocationCount> {
            return scannedDeviceDao.getShadowLocationDeviceCounts(shadowKey, sinceTimestamp)
        }

        override suspend fun getDeviceLocationRecordsForShadow(
            shadowKey: String,
            sinceTimestamp: Long,
        ): List<DeviceLocationRecord> {
            return deviceLocationRecordDao.getRecordsForShadow(shadowKey, sinceTimestamp)
        }

        // ============================================================================
        // TEMPORAL CLUSTERING IMPLEMENTATION
        // ============================================================================

        override suspend fun findPotentialDuplicates(
            device: ScannedDevice,
            timeWindowMs: Long,
        ): List<ScannedDevice> {
            // Need manufacturer ID and device type for temporal clustering
            val manufacturerId = device.manufacturerId ?: return emptyList()
            val deviceType = device.deviceType ?: return emptyList()
            val rssi = device.highestRssi ?: -70

            val startTime = device.lastSeen - timeWindowMs
            val endTime = device.lastSeen + timeWindowMs

            return scannedDeviceDao.findPotentialDuplicates(
                manufacturerId = manufacturerId,
                deviceType = deviceType,
                rssi = rssi,
                startTime = startTime,
                endTime = endTime,
                excludeAddress = device.address,
            )
        }

        override suspend fun findDisappearedDevicesNear(
            newDevice: ScannedDevice,
            timeWindowMs: Long,
        ): List<ScannedDevice> {
            val manufacturerId = newDevice.manufacturerId ?: return emptyList()
            val deviceType = newDevice.deviceType ?: return emptyList()

            return scannedDeviceDao.findDevicesDisappearedNear(
                newDeviceFirstSeen = newDevice.firstSeen,
                manufacturerId = manufacturerId,
                deviceType = deviceType,
                timeWindowMs = timeWindowMs,
                excludeAddress = newDevice.address,
            )
        }

        override suspend fun getUnfingerprintedDevices(sinceTimestamp: Long): List<ScannedDevice> {
            return scannedDeviceDao.getUnfingerprintedDevices(sinceTimestamp)
        }

        override suspend fun getFingerprintedDeviceCount(): Int {
            return scannedDeviceDao.getFingerprintedDeviceCount()
        }
    }
