package com.tailbait.data.repository

import androidx.room.withTransaction
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.algorithm.ShadowKeyGenerator
import com.tailbait.data.database.dao.ShadowLocationCount
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
     * Strong link based on stable identifiers:
     * - Payload fingerprint match (AirTag, Find My, etc.)
     * - Device name match ("John's iPhone")
     * - Service UUID + manufacturer data pattern
     *
     * These links have HIGH confidence of being the same physical device.
     */
    STRONG,

    /**
     * Weak link based on circumstantial evidence:
     * - Temporal correlation (old MAC disappeared, new one appeared)
     * - Same manufacturer + device type
     * - Similar RSSI (proximity)
     *
     * These links COULD be wrong in crowded areas where multiple
     * similar devices exist. Detection algorithm should weight
     * weak-linked locations at reduced value.
     */
    WEAK
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
        manufacturerData: ByteArray?
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
        identificationMethod: String?
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
        scanTriggerType: String
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
        linkReason: String = "manual_link"
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
     * Update Find My status for a device.
     *
     * @param deviceId Device ID
     * @param findMyStatus Raw status byte
     * @param findMySeparated Whether device is separated from owner
     */
    suspend fun updateFindMyStatus(deviceId: Long, findMyStatus: Int, findMySeparated: Boolean)

    /**
     * Get suspicious devices, accounting for MAC rotation via fingerprint linking.
     * This considers all locations across all linked devices as one physical device.
     *
     * @param minLocationCount Minimum locations for suspicion
     * @return Flow of suspicious devices
     */
    fun getSuspiciousDevicesWithLinked(minLocationCount: Int): Flow<List<ScannedDevice>>

    /**
     * Insert or update a device with fingerprint-based correlation.
     * If a device with the same fingerprint exists, links the new MAC to it.
     *
     * @return Device ID (either existing primary or new device)
     */
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
        threatLevel: String? = null
    ): Long

    // ============================================================================
    // SHADOW-BASED DETECTION (MAC-agnostic device profiling)
    // ============================================================================

    /**
     * Get shadow keys that appear at a minimum number of distinct locations.
     *
     * @param minLocationCount Minimum distinct locations required
     * @return List of suspicious shadow key strings
     */
    suspend fun getSuspiciousShadowKeys(minLocationCount: Int): List<String>

    /**
     * Get all devices matching a specific shadow key.
     *
     * @param shadowKey The shadow key to query
     * @return List of devices with this shadow profile
     */
    suspend fun getDevicesByShadowKey(shadowKey: String): List<ScannedDevice>

    /**
     * Get per-location device counts for a shadow key.
     *
     * @param shadowKey The shadow key to analyze
     * @return List of (locationId, deviceCount, maxRssi) per location
     */
    suspend fun getShadowLocationDeviceCounts(shadowKey: String): List<ShadowLocationCount>

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
        timeWindowMs: Long = 30 * 60 * 1000
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
        timeWindowMs: Long = 5 * 60 * 1000
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
@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val database: TailBaitDatabase,
    private val scannedDeviceDao: ScannedDeviceDao,
    private val deviceLocationRecordDao: DeviceLocationRecordDao
) : DeviceRepository {

    override suspend fun upsertDevice(
        address: String,
        name: String?,
        lastSeen: Long,
        manufacturerData: ByteArray?
    ): Long {
        // Check if device already exists
        val existingDevice = scannedDeviceDao.getByAddress(address)
        val hexManufacturerData = manufacturerData?.let { bytesToHexString(it) }
        val deviceType = manufacturerData?.let { ManufacturerDataParser.extractDeviceType(it) }

        return if (existingDevice != null) {
            // Update existing device
            val updatedDevice = existingDevice.copy(
                name = name ?: existingDevice.name,
                lastSeen = lastSeen,
                detectionCount = existingDevice.detectionCount + 1,
                manufacturerData = hexManufacturerData ?: existingDevice.manufacturerData,
                deviceType = deviceType ?: existingDevice.deviceType
            )
            scannedDeviceDao.update(updatedDevice)
            existingDevice.id
        } else {
            // Insert new device
            val newDevice = ScannedDevice(
                address = address,
                name = name,
                firstSeen = lastSeen,
                lastSeen = lastSeen,
                detectionCount = 1,
                manufacturerData = hexManufacturerData,
                deviceType = deviceType
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
        identificationMethod: String?
    ): Long {
        // Check if device already exists
        val existingDevice = scannedDeviceDao.getByAddress(address)
        val hexManufacturerData = manufacturerData?.let { bytesToHexString(it) }

        return if (existingDevice != null) {
            // Determine final manufacturer name:
            // - If we have a chipset manufacturer ID, clear the name (don't show "Interplan" etc. to users)
            // - Otherwise, use new name or fall back to existing
            val finalManufacturerName = if (DeviceIdentifier.isChipsetManufacturer(manufacturerId)) {
                null // Clear chipset manufacturer names
            } else {
                manufacturerName ?: existingDevice.manufacturerName
            }

            // Update existing device with comprehensive identification
            val updatedDevice = existingDevice.copy(
                name = name ?: existingDevice.name,
                advertisedName = advertisedName ?: existingDevice.advertisedName,
                lastSeen = lastSeen,
                detectionCount = existingDevice.detectionCount + 1,
                // Manufacturer data
                manufacturerData = hexManufacturerData ?: existingDevice.manufacturerData,
                manufacturerId = manufacturerId ?: existingDevice.manufacturerId,
                manufacturerName = finalManufacturerName,
                // Device classification - prefer new data if confidence is higher
                deviceType = if (identificationConfidence > (existingDevice.identificationConfidence))
                    deviceType else existingDevice.deviceType,
                deviceModel = if (identificationConfidence > (existingDevice.identificationConfidence))
                    deviceModel else existingDevice.deviceModel,
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
                identificationConfidence = maxOf(identificationConfidence, existingDevice.identificationConfidence),
                identificationMethod = if (identificationConfidence > existingDevice.identificationConfidence)
                    identificationMethod else existingDevice.identificationMethod
            )
            scannedDeviceDao.update(updatedDevice)
            existingDevice.id
        } else {
            // Insert new device with full identification
            val newDevice = ScannedDevice(
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
                identificationMethod = identificationMethod
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
        scanTriggerType: String
    ): Long {
        val record = DeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = rssi,
            timestamp = timestamp,
            locationChanged = locationChanged,
            distanceFromLast = distanceFromLast,
            scanTriggerType = scanTriggerType
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
        linkReason: String
    ) {
        scannedDeviceDao.linkDevice(
            deviceId,
            primaryDeviceId,
            System.currentTimeMillis(),
            linkStrength,
            linkReason
        )
    }

    override suspend fun getWeakLinkedDevices(primaryDeviceId: Long): List<ScannedDevice> {
        return scannedDeviceDao.getWeakLinkedDevices(primaryDeviceId)
    }

    override suspend fun getStrongLinkedDevices(primaryDeviceId: Long): List<ScannedDevice> {
        return scannedDeviceDao.getStrongLinkedDevices(primaryDeviceId)
    }

    override suspend fun updateFindMyStatus(deviceId: Long, findMyStatus: Int, findMySeparated: Boolean) {
        scannedDeviceDao.updateFindMyStatus(deviceId, findMyStatus, findMySeparated)
    }

    override fun getSuspiciousDevicesWithLinked(minLocationCount: Int): Flow<List<ScannedDevice>> {
        return scannedDeviceDao.getSuspiciousDevicesWithLinked(minLocationCount)
    }

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
        threatLevel: String?
    ): Long {
        return database.withTransaction {
            // Check if device already exists by MAC address
            val existingDevice = scannedDeviceDao.getByAddress(address)
            val hexManufacturerData = manufacturerData?.let { bytesToHexString(it) }

            // If device exists by MAC, update it
            if (existingDevice != null) {
                val finalManufacturerName = if (DeviceIdentifier.isChipsetManufacturer(manufacturerId)) {
                    null
                } else {
                    manufacturerName ?: existingDevice.manufacturerName
                }

                // Track highest RSSI ever seen for this device
                val newHighestRssi = when {
                    highestRssi == null -> existingDevice.highestRssi
                    existingDevice.highestRssi == null -> highestRssi
                    else -> maxOf(highestRssi, existingDevice.highestRssi)
                }

                val updatedDevice = existingDevice.copy(
                    name = name ?: existingDevice.name,
                    advertisedName = advertisedName ?: existingDevice.advertisedName,
                    lastSeen = lastSeen,
                    detectionCount = existingDevice.detectionCount + 1,
                    manufacturerData = hexManufacturerData ?: existingDevice.manufacturerData,
                    manufacturerId = manufacturerId ?: existingDevice.manufacturerId,
                    manufacturerName = finalManufacturerName,
                    deviceType = if (identificationConfidence > existingDevice.identificationConfidence)
                        deviceType else existingDevice.deviceType,
                    deviceModel = if (identificationConfidence > existingDevice.identificationConfidence)
                        deviceModel else existingDevice.deviceModel,
                    isTracker = isTracker || existingDevice.isTracker,
                    serviceUuids = serviceUuids ?: existingDevice.serviceUuids,
                    appearance = appearance ?: existingDevice.appearance,
                    txPowerLevel = txPowerLevel ?: existingDevice.txPowerLevel,
                    advertisingFlags = advertisingFlags ?: existingDevice.advertisingFlags,
                    appleContinuityType = appleContinuityType ?: existingDevice.appleContinuityType,
                    identificationConfidence = maxOf(identificationConfidence, existingDevice.identificationConfidence),
                    identificationMethod = if (identificationConfidence > existingDevice.identificationConfidence)
                        identificationMethod else existingDevice.identificationMethod,
                    // Find My fingerprinting fields
                    payloadFingerprint = payloadFingerprint ?: existingDevice.payloadFingerprint,
                    findMyStatus = findMyStatus ?: existingDevice.findMyStatus,
                    findMySeparated = findMySeparated || existingDevice.findMySeparated,
                    // Enhanced fields from Nordic patterns (v7)
                    highestRssi = newHighestRssi,
                    signalStrength = signalStrength ?: existingDevice.signalStrength,
                    beaconType = beaconType ?: existingDevice.beaconType,
                    threatLevel = threatLevel ?: existingDevice.threatLevel
                )
                // Compute shadow key if not set or if new data yields a more specific key
                val deviceWithShadow = computeShadowKey(updatedDevice)
                scannedDeviceDao.update(deviceWithShadow)
                existingDevice.id // Return ID (Implicit return from lambda)
            } else {
                // New MAC address - check if we have a matching fingerprint
                // Use a 'run' block to allow early exit structures via 'return@run' if Kotlin allowed labeled returns from run 
                // but here we just restructure as if-else-if
                
                var resultId: Long? = null
                
                if (payloadFingerprint != null) {
                    val existingByFingerprint = scannedDeviceDao.getByFingerprint(payloadFingerprint)

                    if (existingByFingerprint != null) {
                        // FOUND MATCHING FINGERPRINT
                        // FM fingerprints use the rotating public key prefix (bytes 2-7)
                        // which changes every ~15 min with the MAC. If the matched device
                        // was last seen >20 min ago (rotation period + 5 min buffer), the
                        // fingerprint has likely rotated and this match is unreliable.
                        val timeSinceLastSeen = lastSeen - existingByFingerprint.lastSeen
                        val fmStaleThresholdMs = 20 * 60 * 1000L // 20 minutes
                        val isStaleFmMatch = payloadFingerprint.startsWith("FM:") &&
                            timeSinceLastSeen > fmStaleThresholdMs

                        val resolvedLinkStrength = if (isStaleFmMatch) {
                            LinkStrength.WEAK
                        } else {
                            LinkStrength.STRONG
                        }
                        val linkReason = if (isStaleFmMatch) {
                            "fingerprint_match:$payloadFingerprint:stale"
                        } else {
                            "fingerprint_match:$payloadFingerprint"
                        }

                        val newDevice = ScannedDevice(
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
                            payloadFingerprint = payloadFingerprint,
                            findMyStatus = findMyStatus,
                            findMySeparated = findMySeparated,
                            linkedDeviceId = existingByFingerprint.linkedDeviceId ?: existingByFingerprint.id,
                            lastMacRotation = System.currentTimeMillis(),
                            linkStrength = resolvedLinkStrength.name,
                            linkReason = linkReason,
                            highestRssi = highestRssi,
                            signalStrength = signalStrength,
                            beaconType = beaconType,
                            threatLevel = threatLevel
                        )

                        scannedDeviceDao.insert(computeShadowKey(newDevice))

                        val primaryId = existingByFingerprint.linkedDeviceId ?: existingByFingerprint.id
                        val primary = scannedDeviceDao.getById(primaryId)
                        if (primary != null) {
                            val newHighestRssi = when {
                                highestRssi == null -> primary.highestRssi
                                primary.highestRssi == null -> highestRssi
                                else -> maxOf(highestRssi, primary.highestRssi)
                            }
                            scannedDeviceDao.update(primary.copy(
                                lastSeen = lastSeen,
                                detectionCount = primary.detectionCount + 1,
                                findMyStatus = findMyStatus ?: primary.findMyStatus,
                                findMySeparated = findMySeparated || primary.findMySeparated,
                                highestRssi = newHighestRssi,
                                signalStrength = signalStrength ?: primary.signalStrength,
                                threatLevel = threatLevel ?: primary.threatLevel
                            ))
                        }
                        resultId = primaryId
                    }
                }

                if (resultId == null) {
                    // Try Temporal Correlation
                    // We must execute this only if fingerprint match failed
                    if (manufacturerId != null && deviceType != null) {
                         val temporalMatchResult = findTemporalCorrelationMatch(
                            manufacturerId = manufacturerId,
                            deviceType = deviceType,
                            currentRssi = highestRssi ?: -70,
                            currentTime = lastSeen,
                            excludeAddress = address,
                            deviceName = name ?: advertisedName,
                            appearance = appearance,
                            txPowerLevel = txPowerLevel
                        )

                        if (temporalMatchResult != null) {
                            val temporalMatch = temporalMatchResult.device
                            val linkStrength = if (temporalMatchResult.isStrongMatch) LinkStrength.STRONG else LinkStrength.WEAK
                            
                            timber.log.Timber.d(
                                "Temporal correlation: linking $address to ${temporalMatch.address} " +
                                "(${linkStrength.name} link, reason=${temporalMatchResult.linkReason})"
                            )

                            val newDevice = ScannedDevice(
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
                                payloadFingerprint = payloadFingerprint,
                                findMyStatus = findMyStatus,
                                findMySeparated = findMySeparated,
                                linkedDeviceId = temporalMatch.linkedDeviceId ?: temporalMatch.id,
                                lastMacRotation = System.currentTimeMillis(),
                                linkStrength = linkStrength.name,
                                linkReason = temporalMatchResult.linkReason,
                                highestRssi = highestRssi,
                                signalStrength = signalStrength,
                                beaconType = beaconType,
                                threatLevel = threatLevel
                            )

                            scannedDeviceDao.insert(computeShadowKey(newDevice))

                            val primaryId = temporalMatch.linkedDeviceId ?: temporalMatch.id
                            val primary = scannedDeviceDao.getById(primaryId)
                            if (primary != null) {
                                val newHighestRssi = when {
                                    highestRssi == null -> primary.highestRssi
                                    primary.highestRssi == null -> highestRssi
                                    else -> maxOf(highestRssi, primary.highestRssi)
                                }
                                scannedDeviceDao.update(primary.copy(
                                    lastSeen = lastSeen,
                                    detectionCount = primary.detectionCount + 1,
                                    highestRssi = newHighestRssi
                                ))
                            }
                            resultId = primaryId
                        }
                    }
                }

                if (resultId == null) {
                    // Fallback: Create completely new device
                     val newDevice = ScannedDevice(
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
                        payloadFingerprint = payloadFingerprint,
                        findMyStatus = findMyStatus,
                        findMySeparated = findMySeparated,
                        highestRssi = highestRssi,
                        signalStrength = signalStrength,
                        beaconType = beaconType,
                        threatLevel = threatLevel
                    )
                    val deviceWithShadow = computeShadowKey(newDevice)
                    resultId = scannedDeviceDao.insert(deviceWithShadow)
                }
                
                resultId!! // Should never be null here
            }
        }
    }

    /**
     * Result of temporal correlation matching.
     *
     * @property device The matched device
     * @property isStrongMatch True if the match is based on strong signals (name match)
     * @property linkReason Explanation of why devices were linked
     */
    private data class TemporalMatchResult(
        val device: ScannedDevice,
        val isStrongMatch: Boolean,
        val linkReason: String
    )

    /**
     * Find a device that likely rotated its MAC address based on temporal patterns.
     *
     * When a phone rotates its MAC:
     * 1. Old MAC stops being advertised (last_seen becomes stale)
     * 2. New MAC appears with same manufacturer/type/similar RSSI
     * 3. They're never seen simultaneously
     *
     * @return A TemporalMatchResult with device and link strength info, or null
     */
    private suspend fun findTemporalCorrelationMatch(
        manufacturerId: Int,
        deviceType: String,
        currentRssi: Int,
        currentTime: Long,
        excludeAddress: String,
        deviceName: String? = null,
        appearance: Int? = null,
        txPowerLevel: Int? = null
    ): TemporalMatchResult? {
        // Look for devices that disappeared in the last 5 minutes
        // MAC rotation typically happens every 15 minutes, so 5 min is conservative
        val timeWindowMs = 5 * 60 * 1000L

        val candidates = scannedDeviceDao.findDevicesDisappearedNear(
            newDeviceFirstSeen = currentTime,
            manufacturerId = manufacturerId,
            deviceType = deviceType,
            timeWindowMs = timeWindowMs,
            excludeAddress = excludeAddress
        )

        if (candidates.isEmpty()) return null

        // Score each candidate based on multiple correlation signals
        data class ScoredCandidate(
            val device: ScannedDevice,
            val score: Double,
            val hasNameMatch: Boolean,
            val matchedName: String?
        )

        val scoredCandidates = candidates.mapNotNull { candidate ->
            var score = 0.0
            var hasNameMatch = false
            var matchedName: String? = null

            // 1. RSSI similarity (max 30 points)
            val rssiDiff = kotlin.math.abs((candidate.highestRssi ?: -70) - currentRssi)
            if (rssiDiff > 20) return@mapNotNull null  // Too different, skip
            score += (20 - rssiDiff) * 1.5  // 0-30 points

            // 2. Recency (max 25 points) - more recent = better match
            val timeSinceDisappeared = currentTime - candidate.lastSeen
            val recencyScore = ((timeWindowMs - timeSinceDisappeared).toDouble() / timeWindowMs) * 25
            score += recencyScore.coerceAtLeast(0.0)

            // 3. Device name match (max 30 points) - STRONG signal if names match
            if (!deviceName.isNullOrBlank() && !candidate.name.isNullOrBlank()) {
                if (normalizeDeviceName(deviceName) == normalizeDeviceName(candidate.name)) {
                    score += 30  // Strong correlation signal
                    hasNameMatch = true
                    matchedName = candidate.name
                    timber.log.Timber.d("Name match found: '$deviceName' == '${candidate.name}'")
                }
            }

            // 4. Appearance match (max 10 points)
            if (appearance != null && candidate.appearance != null && appearance == candidate.appearance) {
                score += 10
            }

            // 5. TX Power match (max 5 points)
            if (txPowerLevel != null && candidate.txPowerLevel != null) {
                val txDiff = kotlin.math.abs(txPowerLevel - candidate.txPowerLevel)
                if (txDiff <= 3) score += 5
            }

            ScoredCandidate(candidate, score, hasNameMatch, matchedName)
        }

        if (scoredCandidates.isEmpty()) return null

        // Return the highest scoring candidate, but only if score is reasonable
        val bestMatch = scoredCandidates.maxByOrNull { it.score }
        return if (bestMatch != null && bestMatch.score >= 20) {
            val timeSinceDisappeared = (currentTime - bestMatch.device.lastSeen) / 1000

            // Build link reason
            val linkReason = if (bestMatch.hasNameMatch) {
                "name_match:${bestMatch.matchedName}"
            } else {
                "temporal:rssi=$currentRssi,disappeared=${timeSinceDisappeared}s"
            }

            timber.log.Timber.d(
                "Temporal match: ${bestMatch.device.address} with score ${bestMatch.score.toInt()}, " +
                "strong=${bestMatch.hasNameMatch}, reason=$linkReason"
            )

            TemporalMatchResult(
                device = bestMatch.device,
                isStrongMatch = bestMatch.hasNameMatch,
                linkReason = linkReason
            )
        } else {
            null
        }
    }

    /**
     * Normalize device name for comparison.
     * Removes variable parts like possessive apostrophes and numbers.
     */
    private fun normalizeDeviceName(name: String): String {
        return name
            .lowercase()
            .replace(Regex("['']s\\s+"), " ")  // "John's iPhone" -> "John iPhone"
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Convert byte array to hex string for storage.
     */
    private fun bytesToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute and set shadow key on a device if not already set
     * or if the new key is more specific (more components).
     */
    private fun computeShadowKey(device: ScannedDevice): ScannedDevice {
        val newKey = ShadowKeyGenerator.generate(device) ?: return device
        val existingKey = device.shadowKey
        if (existingKey != null) {
            val existingSpecificity = ShadowKeyGenerator.specificityScore(existingKey)
            val newSpecificity = ShadowKeyGenerator.specificityScore(newKey)
            if (newSpecificity <= existingSpecificity) return device
        }
        return device.copy(shadowKey = newKey)
    }

    // ============================================================================
    // SHADOW-BASED DETECTION IMPLEMENTATION
    // ============================================================================

    override suspend fun getSuspiciousShadowKeys(minLocationCount: Int): List<String> {
        return scannedDeviceDao.getSuspiciousShadowKeys(minLocationCount)
    }

    override suspend fun getDevicesByShadowKey(shadowKey: String): List<ScannedDevice> {
        return scannedDeviceDao.getDevicesByShadowKey(shadowKey)
    }

    override suspend fun getShadowLocationDeviceCounts(shadowKey: String): List<ShadowLocationCount> {
        return scannedDeviceDao.getShadowLocationDeviceCounts(shadowKey)
    }

    // ============================================================================
    // TEMPORAL CLUSTERING IMPLEMENTATION
    // ============================================================================

    override suspend fun findPotentialDuplicates(
        device: ScannedDevice,
        timeWindowMs: Long
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
            excludeAddress = device.address
        )
    }

    override suspend fun findDisappearedDevicesNear(
        newDevice: ScannedDevice,
        timeWindowMs: Long
    ): List<ScannedDevice> {
        val manufacturerId = newDevice.manufacturerId ?: return emptyList()
        val deviceType = newDevice.deviceType ?: return emptyList()

        return scannedDeviceDao.findDevicesDisappearedNear(
            newDeviceFirstSeen = newDevice.firstSeen,
            manufacturerId = manufacturerId,
            deviceType = deviceType,
            timeWindowMs = timeWindowMs,
            excludeAddress = newDevice.address
        )
    }

    override suspend fun getUnfingerprintedDevices(sinceTimestamp: Long): List<ScannedDevice> {
        return scannedDeviceDao.getUnfingerprintedDevices(sinceTimestamp)
    }

    override suspend fun getFingerprintedDeviceCount(): Int {
        return scannedDeviceDao.getFingerprintedDeviceCount()
    }
}
