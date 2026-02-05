package com.tailbait.util

import android.os.ParcelUuid
import timber.log.Timber
import java.util.UUID

/**
 * Utility for detecting and classifying beacon types from BLE advertisement data.
 *
 * Learned from Nordic nRF-Connect-Device-Manager FilterUtils patterns.
 * Unlike that implementation which filters OUT beacons, we DETECT them
 * since trackers often masquerade as or alongside beacons.
 *
 * ## Supported Beacon Types
 * - **iBeacon**: Apple's proprietary beacon format (manufacturer data 0x02 0x15)
 * - **Eddystone**: Google's open beacon format (service UUID 0xFEAA)
 * - **AltBeacon**: Open beacon specification
 * - **AirDrop**: Apple proximity sharing (type 0x05/0x10)
 * - **Find My**: Apple's tracking network (type 0x12) - CRITICAL for AirTag detection
 *
 * ## References
 * - iBeacon: https://developer.apple.com/ibeacon/
 * - Eddystone: https://github.com/google/eddystone
 * - AltBeacon: https://altbeacon.org/
 */
object BeaconDetectionUtils {

    // ============================================================================
    // COMPANY IDENTIFIERS (Bluetooth SIG)
    // ============================================================================

    object CompanyId {
        const val APPLE = 0x004C
        const val MICROSOFT = 0x0006
        const val GOOGLE = 0x00E0
        const val NORDIC_SEMI = 0x0059
        const val SAMSUNG = 0x0075
        const val TILE = 0x0099
        const val CHIPOLO = 0x02E5
    }

    // ============================================================================
    // BEACON SERVICE UUIDS
    // ============================================================================

    /**
     * Eddystone service UUID (Google's open beacon protocol)
     */
    val EDDYSTONE_SERVICE_UUID: UUID = UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")

    /**
     * Eddystone frame types
     */
    object EddystoneFrameType {
        const val UID = 0x00    // Unique identifier
        const val URL = 0x10    // URL broadcast
        const val TLM = 0x20    // Telemetry
        const val EID = 0x30    // Ephemeral identifier
    }

    // ============================================================================
    // BEACON TYPE ENUM
    // ============================================================================

    /**
     * Classification of beacon types detected in BLE advertisements.
     */
    enum class BeaconType {
        IBEACON,           // Apple iBeacon (0x02 0x15 prefix)
        EDDYSTONE_UID,     // Eddystone UID frame
        EDDYSTONE_URL,     // Eddystone URL frame
        EDDYSTONE_TLM,     // Eddystone telemetry
        EDDYSTONE_EID,     // Eddystone ephemeral ID
        ALTBEACON,         // AltBeacon format
        MICROSOFT_BEACON,  // Microsoft beacon
        NORDIC_BEACON,     // Nordic Semiconductor beacon
        AIRDROP,           // Apple AirDrop proximity
        FIND_MY,           // Apple Find My network (AirTag!)
        NEARBY_INFO,       // Apple Nearby Info
        PROXIMITY_PAIRING, // Apple Proximity Pairing (AirPods)
        UNKNOWN
    }

    /**
     * Result of beacon detection analysis.
     */
    data class BeaconDetectionResult(
        val beaconType: BeaconType,
        val uuid: UUID? = null,           // iBeacon/Eddystone UUID
        val major: Int? = null,           // iBeacon major value
        val minor: Int? = null,           // iBeacon minor value
        val txPower: Int? = null,         // Measured TX power for distance calculation
        val url: String? = null,          // Eddystone URL
        val confidence: Float = 1.0f,     // Detection confidence (0.0-1.0)
        val rawData: ByteArray? = null    // Raw beacon payload for analysis
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BeaconDetectionResult

            if (beaconType != other.beaconType) return false
            if (uuid != other.uuid) return false
            if (major != other.major) return false
            if (minor != other.minor) return false
            if (txPower != other.txPower) return false
            if (url != other.url) return false
            if (confidence != other.confidence) return false
            if (rawData != null) {
                if (other.rawData == null) return false
                if (!rawData.contentEquals(other.rawData)) return false
            } else if (other.rawData != null) return false

            return true
        }

        override fun hashCode(): Int {
            var result = beaconType.hashCode()
            result = 31 * result + (uuid?.hashCode() ?: 0)
            result = 31 * result + (major ?: 0)
            result = 31 * result + (minor ?: 0)
            result = 31 * result + (txPower ?: 0)
            result = 31 * result + (url?.hashCode() ?: 0)
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (rawData?.contentHashCode() ?: 0)
            return result
        }
    }

    // ============================================================================
    // IBEACON DETECTION
    // ============================================================================

    /**
     * iBeacon advertisement format:
     * Byte 0: Type (0x02)
     * Byte 1: Length (0x15 = 21)
     * Bytes 2-17: UUID (16 bytes)
     * Bytes 18-19: Major (big-endian)
     * Bytes 20-21: Minor (big-endian)
     * Byte 22: TX Power (signed)
     */
    private const val IBEACON_TYPE = 0x02
    private const val IBEACON_LENGTH = 0x15  // 21 bytes
    private const val IBEACON_TOTAL_LENGTH = 23  // Type + Length + 21 bytes data

    /**
     * Detect if manufacturer data represents an iBeacon.
     *
     * @param manufacturerId The Bluetooth SIG company identifier
     * @param data The manufacturer-specific data payload
     * @return BeaconDetectionResult if iBeacon detected, null otherwise
     */
    fun detectIBeacon(manufacturerId: Int, data: ByteArray?): BeaconDetectionResult? {
        if (data == null || data.size < IBEACON_TOTAL_LENGTH) return null

        // iBeacons can be from Apple or Nordic
        if (manufacturerId != CompanyId.APPLE && manufacturerId != CompanyId.NORDIC_SEMI) {
            return null
        }

        // Check iBeacon header
        if (data[0].toInt() and 0xFF != IBEACON_TYPE) return null
        if (data[1].toInt() and 0xFF != IBEACON_LENGTH) return null

        try {
            // Extract UUID (bytes 2-17)
            val uuidBytes = data.copyOfRange(2, 18)
            val uuid = bytesToUuid(uuidBytes)

            // Extract Major (bytes 18-19, big-endian)
            val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)

            // Extract Minor (bytes 20-21, big-endian)
            val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)

            // Extract TX Power (byte 22, signed)
            val txPower = data[22].toInt()

            Timber.d("Detected iBeacon: UUID=$uuid, Major=$major, Minor=$minor, TxPower=$txPower")

            return BeaconDetectionResult(
                beaconType = BeaconType.IBEACON,
                uuid = uuid,
                major = major,
                minor = minor,
                txPower = txPower,
                confidence = 0.95f,
                rawData = data
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing iBeacon data")
            return null
        }
    }

    /**
     * Check if data represents an iBeacon (simple check without full parsing).
     */
    fun isIBeacon(manufacturerId: Int, data: ByteArray?): Boolean {
        if (data == null || data.size < 2) return false
        if (manufacturerId != CompanyId.APPLE && manufacturerId != CompanyId.NORDIC_SEMI) return false
        return data[0].toInt() and 0xFF == IBEACON_TYPE &&
               data[1].toInt() and 0xFF == IBEACON_LENGTH &&
               data.size >= IBEACON_TOTAL_LENGTH
    }

    // ============================================================================
    // EDDYSTONE DETECTION
    // ============================================================================

    /**
     * Detect Eddystone beacon from service data.
     *
     * @param serviceUuids List of service UUIDs from advertisement
     * @param serviceData Service data map (UUID -> data bytes)
     * @return BeaconDetectionResult if Eddystone detected, null otherwise
     */
    fun detectEddystone(
        serviceUuids: List<ParcelUuid>?,
        serviceData: Map<ParcelUuid, ByteArray?>?
    ): BeaconDetectionResult? {
        if (serviceUuids == null) return null

        // Check for Eddystone service UUID
        val eddystoneUuid = ParcelUuid(EDDYSTONE_SERVICE_UUID)
        if (!serviceUuids.contains(eddystoneUuid)) return null

        // Get Eddystone service data
        val data = serviceData?.get(eddystoneUuid)
        if (data == null || data.isEmpty()) {
            return BeaconDetectionResult(
                beaconType = BeaconType.EDDYSTONE_UID,
                confidence = 0.7f
            )
        }

        // Determine frame type
        val frameType = data[0].toInt() and 0xFF

        return when (frameType) {
            EddystoneFrameType.UID -> parseEddystoneUid(data)
            EddystoneFrameType.URL -> parseEddystoneUrl(data)
            EddystoneFrameType.TLM -> BeaconDetectionResult(
                beaconType = BeaconType.EDDYSTONE_TLM,
                confidence = 0.9f,
                rawData = data
            )
            EddystoneFrameType.EID -> BeaconDetectionResult(
                beaconType = BeaconType.EDDYSTONE_EID,
                confidence = 0.9f,
                rawData = data
            )
            else -> BeaconDetectionResult(
                beaconType = BeaconType.EDDYSTONE_UID,
                confidence = 0.6f,
                rawData = data
            )
        }
    }

    /**
     * Check if service UUIDs contain Eddystone.
     */
    fun isEddystone(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids == null) return false
        return serviceUuids.any { it.uuid == EDDYSTONE_SERVICE_UUID }
    }

    private fun parseEddystoneUid(data: ByteArray): BeaconDetectionResult {
        if (data.size < 18) {
            return BeaconDetectionResult(
                beaconType = BeaconType.EDDYSTONE_UID,
                confidence = 0.7f,
                rawData = data
            )
        }

        val txPower = data[1].toInt()

        return BeaconDetectionResult(
            beaconType = BeaconType.EDDYSTONE_UID,
            txPower = txPower,
            confidence = 0.95f,
            rawData = data
        )
    }

    private fun parseEddystoneUrl(data: ByteArray): BeaconDetectionResult {
        if (data.size < 3) {
            return BeaconDetectionResult(
                beaconType = BeaconType.EDDYSTONE_URL,
                confidence = 0.7f,
                rawData = data
            )
        }

        val txPower = data[1].toInt()
        val url = try {
            decodeEddystoneUrl(data)
        } catch (e: Exception) {
            Timber.e(e, "Error decoding Eddystone URL")
            null
        }

        return BeaconDetectionResult(
            beaconType = BeaconType.EDDYSTONE_URL,
            txPower = txPower,
            url = url,
            confidence = 0.95f,
            rawData = data
        )
    }

    // ============================================================================
    // MICROSOFT BEACON DETECTION
    // ============================================================================

    /**
     * Detect Microsoft beacon format.
     * Microsoft beacons have data[0] == 0x01
     */
    fun detectMicrosoftBeacon(manufacturerId: Int, data: ByteArray?): BeaconDetectionResult? {
        if (manufacturerId != CompanyId.MICROSOFT) return null
        if (data == null || data.isEmpty()) return null

        if (data[0].toInt() and 0xFF == 0x01) {
            return BeaconDetectionResult(
                beaconType = BeaconType.MICROSOFT_BEACON,
                confidence = 0.85f,
                rawData = data
            )
        }

        return null
    }

    /**
     * Check if data represents a Microsoft beacon.
     */
    fun isMicrosoftBeacon(manufacturerId: Int, data: ByteArray?): Boolean {
        if (manufacturerId != CompanyId.MICROSOFT) return false
        if (data == null || data.isEmpty()) return false
        return data[0].toInt() and 0xFF == 0x01
    }

    // ============================================================================
    // APPLE CONTINUITY DETECTION
    // ============================================================================

    /**
     * Apple Continuity message types (from ManufacturerDataParser)
     */
    object AppleContinuityType {
        const val AIRDROP = 0x05
        const val PROXIMITY_PAIRING = 0x07  // AirPods, Beats
        const val NEARBY_ACTION = 0x0F
        const val NEARBY_INFO = 0x10
        const val FIND_MY = 0x12  // AirTag! CRITICAL!
    }

    /**
     * Detect Apple Continuity protocol beacon types.
     *
     * @param manufacturerId The manufacturer ID (must be Apple)
     * @param data The manufacturer data payload
     * @return BeaconDetectionResult if Apple beacon detected, null otherwise
     */
    fun detectAppleContinuity(manufacturerId: Int, data: ByteArray?): BeaconDetectionResult? {
        if (manufacturerId != CompanyId.APPLE) return null
        if (data == null || data.isEmpty()) return null

        val messageType = data[0].toInt() and 0xFF

        return when (messageType) {
            AppleContinuityType.FIND_MY -> {
                Timber.d("Detected Apple Find My beacon (AirTag/Find My accessory)")
                BeaconDetectionResult(
                    beaconType = BeaconType.FIND_MY,
                    confidence = 0.98f,  // Very high - this is an AirTag!
                    rawData = data
                )
            }
            AppleContinuityType.AIRDROP -> {
                BeaconDetectionResult(
                    beaconType = BeaconType.AIRDROP,
                    confidence = 0.9f,
                    rawData = data
                )
            }
            AppleContinuityType.NEARBY_INFO -> {
                BeaconDetectionResult(
                    beaconType = BeaconType.NEARBY_INFO,
                    confidence = 0.85f,
                    rawData = data
                )
            }
            AppleContinuityType.PROXIMITY_PAIRING -> {
                BeaconDetectionResult(
                    beaconType = BeaconType.PROXIMITY_PAIRING,
                    confidence = 0.9f,
                    rawData = data
                )
            }
            else -> null
        }
    }

    /**
     * Check if data represents AirDrop.
     */
    fun isAirDrop(manufacturerId: Int, data: ByteArray?): Boolean {
        if (manufacturerId != CompanyId.APPLE) return false
        if (data == null || data.isEmpty()) return false
        return data[0].toInt() and 0xFF == AppleContinuityType.AIRDROP
    }

    /**
     * Check if data represents Find My network (AirTag).
     */
    fun isFindMy(manufacturerId: Int, data: ByteArray?): Boolean {
        if (manufacturerId != CompanyId.APPLE) return false
        if (data == null || data.isEmpty()) return false
        return data[0].toInt() and 0xFF == AppleContinuityType.FIND_MY
    }

    // ============================================================================
    // COMPREHENSIVE BEACON DETECTION
    // ============================================================================

    /**
     * Perform comprehensive beacon detection on advertisement data.
     *
     * Checks all known beacon formats and returns the most likely match.
     *
     * @param manufacturerId Bluetooth SIG company identifier
     * @param manufacturerData Manufacturer-specific data payload
     * @param serviceUuids List of advertised service UUIDs
     * @param serviceData Map of service data (UUID -> bytes)
     * @return BeaconDetectionResult or null if no beacon detected
     */
    fun detectBeacon(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<ParcelUuid>?,
        serviceData: Map<ParcelUuid, ByteArray?>? = null
    ): BeaconDetectionResult? {
        // Priority 1: Apple Find My (AirTag detection is critical!)
        if (manufacturerId == CompanyId.APPLE && manufacturerData != null) {
            detectAppleContinuity(manufacturerId, manufacturerData)?.let { return it }
        }

        // Priority 2: iBeacon (Apple or Nordic)
        if (manufacturerId != null && manufacturerData != null) {
            detectIBeacon(manufacturerId, manufacturerData)?.let { return it }
        }

        // Priority 3: Eddystone
        detectEddystone(serviceUuids, serviceData)?.let { return it }

        // Priority 4: Microsoft beacon
        if (manufacturerId != null && manufacturerData != null) {
            detectMicrosoftBeacon(manufacturerId, manufacturerData)?.let { return it }
        }

        return null
    }

    /**
     * Check if any beacon type is detected.
     */
    fun isAnyBeacon(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<ParcelUuid>?
    ): Boolean {
        return detectBeacon(manufacturerId, manufacturerData, serviceUuids) != null
    }

    // ============================================================================
    // UTILITY FUNCTIONS
    // ============================================================================

    /**
     * Convert 16 bytes to UUID.
     */
    private fun bytesToUuid(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "UUID requires 16 bytes" }

        var msb: Long = 0
        var lsb: Long = 0

        for (i in 0..7) {
            msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        }
        for (i in 8..15) {
            lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        }

        return UUID(msb, lsb)
    }

    /**
     * Eddystone URL scheme prefixes.
     */
    private val URL_SCHEMES = arrayOf(
        "http://www.", "https://www.", "http://", "https://"
    )

    /**
     * Eddystone URL encoded suffixes.
     */
    private val URL_CODES = arrayOf(
        ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
        ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov"
    )

    /**
     * Decode Eddystone URL from service data.
     */
    private fun decodeEddystoneUrl(data: ByteArray): String? {
        if (data.size < 3) return null

        val schemeIndex = data[2].toInt() and 0xFF
        if (schemeIndex >= URL_SCHEMES.size) return null

        val sb = StringBuilder(URL_SCHEMES[schemeIndex])

        for (i in 3 until data.size) {
            val b = data[i].toInt() and 0xFF
            if (b < URL_CODES.size) {
                sb.append(URL_CODES[b])
            } else {
                sb.append(b.toChar())
            }
        }

        return sb.toString()
    }
}
