package com.tailbait.algorithm

import android.os.ParcelUuid
import com.tailbait.util.ManufacturerDataParser
import timber.log.Timber
import java.util.UUID

/**
 * Service UUID-based tracker detection.
 *
 * Learned from Nordic nRF-Toolbox's ServiceManagerFactory pattern which maps
 * service UUIDs to device handlers. This approach complements manufacturer data
 * parsing by detecting trackers via their advertised service UUIDs.
 *
 * ## Why Service UUIDs Matter
 * Many trackers advertise specific service UUIDs that uniquely identify them:
 * - Samsung SmartTag: 0xFD5A (much more reliable than manufacturer data alone)
 * - Tile: Custom service UUID for Tile protocol
 * - Chipolo: Custom service UUID
 * - Google Find My Device Network: 0xFE2C
 *
 * This detection method is often MORE RELIABLE than manufacturer data parsing
 * because service UUIDs are standardized and less likely to change.
 *
 * ## References
 * - Bluetooth SIG 16-bit UUIDs: https://www.bluetooth.com/specifications/assigned-numbers/
 * - Samsung SmartTag analysis: https://arxiv.org/pdf/2210.14702
 */
object TrackerServiceDetector {

    // ============================================================================
    // TRACKER SERVICE UUIDS (16-bit UUIDs in 128-bit format)
    // ============================================================================

    /**
     * Samsung SmartTag service UUID.
     * FD5A is the 16-bit UUID assigned to Samsung for SmartTag.
     * This is the MOST RELIABLE way to detect SmartTags.
     */
    val SAMSUNG_SMARTTAG_SERVICE: UUID =
        UUID.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")

    /**
     * Apple Find My Network service UUID.
     * FD6F is used for Find My Network nearby interaction.
     * Indicates device participates in Apple's Find My network (AirTag, etc.)
     */
    val APPLE_FIND_MY_SERVICE: UUID =
        UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")

    /**
     * Apple Continuity service UUID.
     * Used by various Apple devices for Continuity features.
     */
    val APPLE_CONTINUITY_SERVICE: UUID =
        UUID.fromString("0000FD6A-0000-1000-8000-00805F9B34FB")

    /**
     * Google Find My Device Network service UUID (FMDN).
     * FE2C is used by Google's Find My Device network.
     */
    val GOOGLE_FIND_MY_SERVICE: UUID =
        UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

    /**
     * Google Fast Pair service UUID.
     * FE2C is also used for Fast Pair, need to distinguish by manufacturer data.
     */
    val GOOGLE_FAST_PAIR_SERVICE: UUID =
        UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

    /**
     * Tile tracker primary service UUID.
     * Custom 128-bit UUID for Tile protocol.
     */
    val TILE_SERVICE: UUID =
        UUID.fromString("FEED0001-C497-4476-A7ED-727DE7648AB1")

    /**
     * Tile tracker alternative service UUID (newer devices).
     */
    val TILE_SERVICE_ALT: UUID =
        UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB")

    /**
     * Chipolo tracker service UUID.
     * FE8C is the 16-bit UUID assigned to Chipolo.
     */
    val CHIPOLO_SERVICE: UUID =
        UUID.fromString("0000FE8C-0000-1000-8000-00805F9B34FB")

    /**
     * Pebblebee tracker service UUID.
     */
    val PEBBLEBEE_SERVICE: UUID =
        UUID.fromString("0000FE8D-0000-1000-8000-00805F9B34FB")

    /**
     * Cube tracker service UUID.
     */
    val CUBE_SERVICE: UUID =
        UUID.fromString("0000FE8E-0000-1000-8000-00805F9B34FB")

    /**
     * eufy Security tracker service UUID.
     */
    val EUFY_SERVICE: UUID =
        UUID.fromString("0000FE9F-0000-1000-8000-00805F9B34FB")

    /**
     * Jio Tag service UUID (Reliance JioTag).
     */
    val JIO_TAG_SERVICE: UUID =
        UUID.fromString("0000FEA0-0000-1000-8000-00805F9B34FB")

    // ============================================================================
    // TRACKER DETECTION RESULT
    // ============================================================================

    /**
     * Result of service UUID-based tracker detection.
     */
    data class TrackerDetectionResult(
        val isTracker: Boolean,
        val trackerType: TrackerType,
        val confidence: Float,
        val detectedServiceUuid: UUID?,
        val manufacturerName: String?
    )

    /**
     * Types of trackers that can be detected via service UUIDs.
     */
    enum class TrackerType {
        SAMSUNG_SMARTTAG,
        APPLE_AIRTAG,
        APPLE_FIND_MY_ACCESSORY,
        GOOGLE_FIND_MY_DEVICE,
        TILE,
        CHIPOLO,
        PEBBLEBEE,
        CUBE,
        EUFY,
        JIO_TAG,
        UNKNOWN_TRACKER,
        NOT_A_TRACKER
    }

    // ============================================================================
    // DETECTION LOGIC
    // ============================================================================

    /**
     * Map of tracker service UUIDs to their types and manufacturers.
     */
    private val trackerServiceMap = mapOf(
        SAMSUNG_SMARTTAG_SERVICE to Pair(TrackerType.SAMSUNG_SMARTTAG, "Samsung"),
        APPLE_FIND_MY_SERVICE to Pair(TrackerType.APPLE_FIND_MY_ACCESSORY, "Apple"),
        TILE_SERVICE to Pair(TrackerType.TILE, "Tile"),
        TILE_SERVICE_ALT to Pair(TrackerType.TILE, "Tile"),
        CHIPOLO_SERVICE to Pair(TrackerType.CHIPOLO, "Chipolo"),
        PEBBLEBEE_SERVICE to Pair(TrackerType.PEBBLEBEE, "Pebblebee"),
        CUBE_SERVICE to Pair(TrackerType.CUBE, "Cube"),
        EUFY_SERVICE to Pair(TrackerType.EUFY, "eufy"),
        JIO_TAG_SERVICE to Pair(TrackerType.JIO_TAG, "Jio"),
        GOOGLE_FIND_MY_SERVICE to Pair(TrackerType.GOOGLE_FIND_MY_DEVICE, "Google")
    )

    /**
     * Set of all known tracker service UUIDs for quick lookup.
     */
    private val trackerServiceUuids = trackerServiceMap.keys

    /**
     * Detect if a device is a tracker based on its advertised service UUIDs.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return TrackerDetectionResult with detection details
     */
    fun detectTracker(serviceUuids: List<ParcelUuid>?): TrackerDetectionResult {
        if (serviceUuids.isNullOrEmpty()) {
            return TrackerDetectionResult(
                isTracker = false,
                trackerType = TrackerType.NOT_A_TRACKER,
                confidence = 0.0f,
                detectedServiceUuid = null,
                manufacturerName = null
            )
        }

        // Check each service UUID against known tracker UUIDs
        for (parcelUuid in serviceUuids) {
            val uuid = parcelUuid.uuid
            val trackerInfo = trackerServiceMap[uuid]

            if (trackerInfo != null) {
                val (trackerType, manufacturerName) = trackerInfo

                Timber.d("Detected tracker via service UUID: $trackerType ($uuid)")

                return TrackerDetectionResult(
                    isTracker = true,
                    trackerType = trackerType,
                    confidence = 0.95f,  // High confidence - service UUIDs are very reliable
                    detectedServiceUuid = uuid,
                    manufacturerName = manufacturerName
                )
            }
        }

        return TrackerDetectionResult(
            isTracker = false,
            trackerType = TrackerType.NOT_A_TRACKER,
            confidence = 0.0f,
            detectedServiceUuid = null,
            manufacturerName = null
        )
    }

    /**
     * Check if any of the service UUIDs indicate a tracker.
     * Quick boolean check without full detection details.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if any tracker service UUID is found
     */
    fun isTracker(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false

        return serviceUuids.any { parcelUuid ->
            trackerServiceUuids.contains(parcelUuid.uuid)
        }
    }

    /**
     * Get the tracker type for a specific service UUID.
     *
     * @param serviceUuid The service UUID to check
     * @return TrackerType if known tracker, null otherwise
     */
    fun getTrackerType(serviceUuid: UUID): TrackerType? {
        return trackerServiceMap[serviceUuid]?.first
    }

    /**
     * Get the manufacturer name for a specific service UUID.
     *
     * @param serviceUuid The service UUID to check
     * @return Manufacturer name if known tracker, null otherwise
     */
    fun getManufacturerName(serviceUuid: UUID): String? {
        return trackerServiceMap[serviceUuid]?.second
    }

    /**
     * Detect Samsung SmartTag specifically.
     * SmartTag is best detected via service UUID rather than manufacturer data.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if Samsung SmartTag service UUID is present
     */
    fun isSamsungSmartTag(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { it.uuid == SAMSUNG_SMARTTAG_SERVICE }
    }

    /**
     * Detect Apple Find My network device.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if Apple Find My service UUID is present
     */
    fun isAppleFindMy(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { it.uuid == APPLE_FIND_MY_SERVICE }
    }

    /**
     * Detect Google Find My Device network participant.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if Google Find My service UUID is present
     */
    fun isGoogleFindMy(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { it.uuid == GOOGLE_FIND_MY_SERVICE }
    }

    /**
     * Detect Tile tracker.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if Tile service UUID is present
     */
    fun isTile(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { it.uuid == TILE_SERVICE || it.uuid == TILE_SERVICE_ALT }
    }

    /**
     * Detect Chipolo tracker.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if Chipolo service UUID is present
     */
    fun isChipolo(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { it.uuid == CHIPOLO_SERVICE }
    }

    // ============================================================================
    // COMBINED DETECTION (Service UUID + Manufacturer Data)
    // ============================================================================

    /**
     * Perform comprehensive tracker detection using both service UUIDs
     * and manufacturer data.
     *
     * This combines the reliability of service UUID detection with the
     * detailed information available from manufacturer data parsing.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @param manufacturerId Bluetooth SIG company identifier
     * @param manufacturerData Manufacturer-specific data payload
     * @return TrackerDetectionResult with comprehensive detection details
     */
    fun detectTrackerComprehensive(
        serviceUuids: List<ParcelUuid>?,
        manufacturerId: Int?,
        manufacturerData: ByteArray?
    ): TrackerDetectionResult {
        // Priority 1: Service UUID detection (most reliable)
        val serviceResult = detectTracker(serviceUuids)
        if (serviceResult.isTracker) {
            return serviceResult
        }

        // Priority 2: Manufacturer data detection
        if (manufacturerId != null && manufacturerData != null) {
            val manufacturerInfo = ManufacturerDataParser.parseManufacturerData(
                manufacturerId,
                manufacturerData
            )

            if (manufacturerInfo?.isTracker == true) {
                // Determine tracker type from manufacturer
                val trackerType = when (manufacturerId) {
                    ManufacturerDataParser.ManufacturerId.APPLE -> {
                        if (manufacturerInfo.appleContinuityType == ManufacturerDataParser.AppleContinuityType.FIND_MY) {
                            TrackerType.APPLE_AIRTAG
                        } else {
                            TrackerType.APPLE_FIND_MY_ACCESSORY
                        }
                    }
                    ManufacturerDataParser.ManufacturerId.SAMSUNG -> TrackerType.SAMSUNG_SMARTTAG
                    ManufacturerDataParser.ManufacturerId.TILE -> TrackerType.TILE
                    ManufacturerDataParser.ManufacturerId.CHIPOLO -> TrackerType.CHIPOLO
                    ManufacturerDataParser.ManufacturerId.PEBBLEBEE -> TrackerType.PEBBLEBEE
                    ManufacturerDataParser.ManufacturerId.CUBE -> TrackerType.CUBE
                    else -> TrackerType.UNKNOWN_TRACKER
                }

                return TrackerDetectionResult(
                    isTracker = true,
                    trackerType = trackerType,
                    confidence = manufacturerInfo.identificationConfidence,
                    detectedServiceUuid = null,
                    manufacturerName = manufacturerInfo.manufacturerName
                )
            }
        }

        // Priority 3: Check for tracker manufacturer IDs even without tracker-specific data
        if (manufacturerId != null) {
            val isTrackerManufacturer = manufacturerId in listOf(
                ManufacturerDataParser.ManufacturerId.TILE,
                ManufacturerDataParser.ManufacturerId.CHIPOLO,
                ManufacturerDataParser.ManufacturerId.PEBBLEBEE,
                ManufacturerDataParser.ManufacturerId.CUBE
            )

            if (isTrackerManufacturer) {
                val trackerType = when (manufacturerId) {
                    ManufacturerDataParser.ManufacturerId.TILE -> TrackerType.TILE
                    ManufacturerDataParser.ManufacturerId.CHIPOLO -> TrackerType.CHIPOLO
                    ManufacturerDataParser.ManufacturerId.PEBBLEBEE -> TrackerType.PEBBLEBEE
                    ManufacturerDataParser.ManufacturerId.CUBE -> TrackerType.CUBE
                    else -> TrackerType.UNKNOWN_TRACKER
                }

                return TrackerDetectionResult(
                    isTracker = true,
                    trackerType = trackerType,
                    confidence = 0.85f,  // Lower confidence without service UUID
                    detectedServiceUuid = null,
                    manufacturerName = ManufacturerDataParser.getManufacturerName(manufacturerId)
                )
            }
        }

        return TrackerDetectionResult(
            isTracker = false,
            trackerType = TrackerType.NOT_A_TRACKER,
            confidence = 0.0f,
            detectedServiceUuid = null,
            manufacturerName = null
        )
    }
}
