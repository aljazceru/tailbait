package com.tailbait.algorithm

import android.os.ParcelUuid
import com.tailbait.util.ManufacturerDataParser
import timber.log.Timber
import java.util.UUID

/**
 * Tracker detection from exact service UUIDs and validated service data.
 *
 * SmartTag FD5A and Tile FEED/FEEC/custom-128 UUIDs identify supported trackers.
 * Find Hub shares FEAA with Eddystone and requires a valid 0x40/0x41 frame;
 * FE2C Fast Pair and FD6F Exposure Notification are not tracker evidence.
 *
 * @see <a href="https://developers.google.com/nearby/fast-pair/specifications/extensions/fmdn#advertised-frames">Find Hub advertised frames</a>
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
     * Apple Continuity service UUID.
     * Used by various Apple devices for Continuity features.
     */
    val APPLE_CONTINUITY_SERVICE: UUID =
        UUID.fromString("0000FD6A-0000-1000-8000-00805F9B34FB")

    /**
     * Google Find Hub advertising service UUID, shared with Eddystone.
     * A validated Find Hub frame is required; the UUID alone is insufficient.
     */
    val GOOGLE_FIND_MY_SERVICE: UUID =
        UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")

    /**
     * Google Fast Pair service UUID.
     * FE2C identifies Fast Pair, not Find Hub advertising frames.
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
     * Tile's second SIG-assigned service UUID (both FEED and FEEC are
     * officially assigned to Tile, Inc.).
     */
    val TILE_SERVICE_FEEC: UUID =
        UUID.fromString("0000FEEC-0000-1000-8000-00805F9B34FB")

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
        val manufacturerName: String?,
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
        NOT_A_TRACKER,
    }

    // ============================================================================
    // DETECTION LOGIC
    // ============================================================================

    /**
     * Map of tracker service UUIDs to their types and manufacturers.
     */
    private val trackerServiceMap =
        mapOf(
            SAMSUNG_SMARTTAG_SERVICE to Pair(TrackerType.SAMSUNG_SMARTTAG, "Samsung"),
            TILE_SERVICE to Pair(TrackerType.TILE, "Tile"),
            TILE_SERVICE_ALT to Pair(TrackerType.TILE, "Tile"),
            TILE_SERVICE_FEEC to Pair(TrackerType.TILE, "Tile"),
        )

    /**
     * Set of all known tracker service UUIDs for quick lookup.
     */
    private val trackerServiceUuids = trackerServiceMap.keys

    /**
     * Detect supported trackers from exact UUIDs or validated Find Hub service data.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @param serviceData Service payloads without the UUID or AD type/length bytes
     * @return TrackerDetectionResult with detection details
     */
    fun detectTracker(
        serviceUuids: List<ParcelUuid>?,
        serviceData: Map<ParcelUuid, ByteArray> = emptyMap(),
    ): TrackerDetectionResult {
        val trackerService =
            serviceUuids?.firstOrNull { it.uuid in trackerServiceUuids }
                ?: serviceData.keys.firstOrNull { it.uuid in trackerServiceUuids }
        if (trackerService != null) {
            val (trackerType, manufacturerName) = trackerServiceMap.getValue(trackerService.uuid)
            Timber.d("Detected tracker via service UUID: $trackerType (${trackerService.uuid})")
            return TrackerDetectionResult(
                isTracker = true,
                trackerType = trackerType,
                confidence = 0.95f,
                detectedServiceUuid = trackerService.uuid,
                manufacturerName = manufacturerName,
            )
        }

        val findHubData = serviceData[findHubServiceUuid]
        if (findHubData != null && findHubEidLength(findHubData) != 0) {
            return TrackerDetectionResult(
                isTracker = true,
                trackerType = TrackerType.GOOGLE_FIND_MY_DEVICE,
                confidence = 0.95f,
                detectedServiceUuid = GOOGLE_FIND_MY_SERVICE,
                manufacturerName = "Google",
            )
        }

        return TrackerDetectionResult(
            isTracker = false,
            trackerType = TrackerType.NOT_A_TRACKER,
            confidence = 0.0f,
            detectedServiceUuid = null,
            manufacturerName = null,
        )
    }

    private val findHubServiceUuid = ParcelUuid(GOOGLE_FIND_MY_SERVICE)
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /**
     * Full advertised Find Hub EID, excluding the frame type and hashed status byte.
     * This is a rotating advertised identity, not a permanent device identifier.
     */
    fun findHubFingerprint(serviceData: Map<ParcelUuid, ByteArray>): String? {
        val data = serviceData[findHubServiceUuid] ?: return null
        val eidLength = findHubEidLength(data)
        if (eidLength == 0) return null
        return buildString(4 + eidLength * 2) {
            append("FHN:")
            for (index in 1..eidLength) {
                val value = data[index].toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    private fun findHubEidLength(data: ByteArray): Int {
        val eidLength =
            when (data.size) {
                21, 22 -> 20
                33, 34 -> 32
                else -> return 0
            }
        return when (data[0].toInt() and 0xFF) {
            0x40 -> eidLength
            // Hashed flags may be omitted only outside unwanted tracking protection mode.
            0x41 -> if (data.size == eidLength + 2) eidLength else 0
            else -> 0
        }
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
     * Detect Tile tracker.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return true if Tile service UUID is present
     */
    fun isTile(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { it.uuid == TILE_SERVICE || it.uuid == TILE_SERVICE_ALT || it.uuid == TILE_SERVICE_FEEC }
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
        manufacturerData: ByteArray?,
        serviceData: Map<ParcelUuid, ByteArray> = emptyMap(),
    ): TrackerDetectionResult {
        // Priority 1: Service UUID detection (most reliable)
        val serviceResult = detectTracker(serviceUuids, serviceData)
        if (serviceResult.isTracker) {
            return serviceResult
        }

        // Priority 2: Manufacturer data detection
        if (manufacturerId != null && manufacturerData != null) {
            val manufacturerInfo =
                ManufacturerDataParser.parseManufacturerData(
                    manufacturerId,
                    manufacturerData,
                )

            if (manufacturerInfo?.isTracker == true) {
                // Determine tracker type from manufacturer
                val trackerType =
                    when (manufacturerId) {
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
                        else -> TrackerType.UNKNOWN_TRACKER
                    }

                return TrackerDetectionResult(
                    isTracker = true,
                    trackerType = trackerType,
                    confidence = manufacturerInfo.identificationConfidence,
                    detectedServiceUuid = null,
                    manufacturerName = manufacturerInfo.manufacturerName,
                )
            }
        }

        // Priority 3: Check for tracker manufacturer IDs even without tracker-specific data
        if (manufacturerId != null) {
            val isTrackerManufacturer =
                manufacturerId in
                    listOf(
                        ManufacturerDataParser.ManufacturerId.TILE,
                        ManufacturerDataParser.ManufacturerId.CHIPOLO,
                    )

            if (isTrackerManufacturer) {
                val trackerType =
                    when (manufacturerId) {
                        ManufacturerDataParser.ManufacturerId.TILE -> TrackerType.TILE
                        ManufacturerDataParser.ManufacturerId.CHIPOLO -> TrackerType.CHIPOLO
                        else -> TrackerType.UNKNOWN_TRACKER
                    }

                return TrackerDetectionResult(
                    isTracker = true,
                    trackerType = trackerType,
                    // Lower confidence without service UUID
                    confidence = 0.85f,
                    detectedServiceUuid = null,
                    manufacturerName = ManufacturerDataParser.getManufacturerName(manufacturerId),
                )
            }
        }

        return TrackerDetectionResult(
            isTracker = false,
            trackerType = TrackerType.NOT_A_TRACKER,
            confidence = 0.0f,
            detectedServiceUuid = null,
            manufacturerName = null,
        )
    }
}
