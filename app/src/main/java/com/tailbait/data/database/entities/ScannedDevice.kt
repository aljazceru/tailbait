package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey



/**
 * Entity representing a unique BLE device that has been discovered during scanning.
 *
 * This entity stores comprehensive information about discovered BLE devices for
 * accurate identification and tracking detection. Each device is uniquely
 * identified by its MAC address (enforced via unique index).
 *
 * ## Core Identification Fields
 * @property id Auto-generated primary key for the device record
 * @property address MAC address of the BLE device (e.g., "AA:BB:CC:DD:EE:FF") - must be unique
 * @property name Human-readable name of the device (nullable if device doesn't broadcast name)
 * @property advertisedName Local name from BLE advertisement (may differ from cached name)
 *
 * ## Timing Fields
 * @property firstSeen Timestamp in milliseconds when device was first detected
 * @property lastSeen Timestamp in milliseconds when device was last detected
 * @property detectionCount Total number of times this device has been detected
 * @property createdAt Timestamp in milliseconds when this database record was created
 *
 * ## Manufacturer Identification
 * @property manufacturerData Raw manufacturer data as hex string (nullable)
 * @property manufacturerId Bluetooth SIG assigned manufacturer ID (e.g., 0x004C for Apple)
 * @property manufacturerName Human-readable manufacturer name (e.g., "Apple", "Samsung")
 *
 * ## Device Classification
 * @property deviceType Inferred device type (e.g., "PHONE", "WATCH", "TRACKER", nullable)
 * @property deviceModel Specific device model if identifiable (e.g., "AirPods Pro", "AirTag")
 * @property isTracker Whether this device is identified as a tracking device (AirTag, Tile, etc.)
 *
 * ## BLE Advertisement Data
 * @property serviceUuids Comma-separated list of advertised service UUIDs
 * @property appearance BLE GAP appearance value (standard device category indicator)
 * @property txPowerLevel Advertised transmission power level in dBm
 * @property advertisingFlags BLE advertising flags byte
 *
 * ## Apple-Specific (Continuity Protocol)
 * @property appleContinuityType Apple Continuity message type (0x07=AirPods, 0x10=NearbyInfo, 0x12=FindMy/AirTag)
 *
 * ## Identification Confidence
 * @property identificationConfidence Confidence score for device identification (0.0-1.0)
 * @property identificationMethod Method used for identification (e.g., "MANUFACTURER_DATA", "SERVICE_UUID", "APPEARANCE")
 */
@Entity(
    tableName = "scanned_devices",
    indices = [
        Index(value = ["address"], unique = true),
        Index(value = ["last_seen"]),
        Index(value = ["device_type"]),
        Index(value = ["is_tracker"]),
        Index(value = ["manufacturer_id"]),
        Index(value = ["payload_fingerprint"]),  // For fingerprint-based device correlation
        Index(value = ["linked_device_id"]),     // For finding linked devices
        Index(value = ["highest_rssi"]),         // For signal strength queries
        Index(value = ["threat_level"])          // For threat-based filtering
    ]
)

data class ScannedDevice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Core identification
    @ColumnInfo(name = "address")
    val address: String,

    @ColumnInfo(name = "name")
    val name: String?,

    @ColumnInfo(name = "advertised_name")
    val advertisedName: String? = null,

    // Timing
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,

    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,

    @ColumnInfo(name = "detection_count")
    val detectionCount: Int = 1,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    // Manufacturer identification
    @ColumnInfo(name = "manufacturer_data")
    val manufacturerData: String? = null,

    @ColumnInfo(name = "manufacturer_id")
    val manufacturerId: Int? = null,

    @ColumnInfo(name = "manufacturer_name")
    val manufacturerName: String? = null,

    // Device classification
    @ColumnInfo(name = "device_type")
    val deviceType: String? = null,

    @ColumnInfo(name = "device_model")
    val deviceModel: String? = null,

    @ColumnInfo(name = "is_tracker", defaultValue = "0")
    val isTracker: Boolean = false,

    // BLE advertisement data
    @ColumnInfo(name = "service_uuids")
    val serviceUuids: String? = null,

    @ColumnInfo(name = "appearance")
    val appearance: Int? = null,

    @ColumnInfo(name = "tx_power_level")
    val txPowerLevel: Int? = null,

    @ColumnInfo(name = "advertising_flags")
    val advertisingFlags: Int? = null,

    // Apple-specific
    @ColumnInfo(name = "apple_continuity_type")
    val appleContinuityType: Int? = null,

    // Identification confidence
    @ColumnInfo(name = "identification_confidence", defaultValue = "0.0")
    val identificationConfidence: Float = 0.0f,

    @ColumnInfo(name = "identification_method")
    val identificationMethod: String? = null,

    // ============================================================================
    // FIND MY NETWORK FINGERPRINTING (AirTag MAC rotation handling)
    // ============================================================================

    /**
     * Payload fingerprint for Find My network devices.
     * This is extracted from the semi-stable portion of the Find My advertisement
     * payload and remains consistent across MAC address rotations (every ~15 min).
     *
     * For AirTags/Find My accessories, this is derived from the public key portion
     * of the advertisement, providing a stable identifier for tracking the same
     * physical device across MAC rotations.
     *
     * Format: Hex string of the fingerprint bytes (typically 6-8 bytes)
     */
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String? = null,

    /**
     * Raw Find My status byte from the advertisement payload.
     * Contains device state flags including the critical "separated from owner" bit.
     *
     * Bit layout:
     * - Bit 0x04: Separated from owner (device is away from paired iPhone)
     * - Other bits: Battery level hints, device state, etc.
     */
    @ColumnInfo(name = "find_my_status")
    val findMyStatus: Int? = null,

    /**
     * Whether the Find My device is currently separated from its owner.
     * Extracted from bit 0x04 of the status byte.
     *
     * TRUE = Device is separated from owner's iPhone (HIGHLY SUSPICIOUS)
     * FALSE = Device is near its owner
     * NULL = Not a Find My device or status unknown
     *
     * A device that is both separated from owner AND following you is
     * a strong indicator of unwanted tracking.
     */
    @ColumnInfo(name = "find_my_separated", defaultValue = "0")
    val findMySeparated: Boolean = false,

    /**
     * ID of a linked device with the same payload fingerprint but different MAC.
     * Used to correlate the same physical device across MAC address rotations.
     *
     * When we detect a new MAC with a matching fingerprint, we link it to the
     * original device to maintain tracking continuity.
     */
    @ColumnInfo(name = "linked_device_id")
    val linkedDeviceId: Long? = null,

    /**
     * Strength/confidence of the link to the primary device.
     *
     * - "STRONG": Link based on stable identifiers (fingerprint, device name, etc.)
     *   High confidence this is the same physical device.
     *
     * - "WEAK": Link based only on circumstantial evidence (temporal proximity,
     *   same manufacturer/type, similar RSSI). Could be wrong in crowded areas.
     *
     * - null: Not linked (linkedDeviceId is null)
     *
     * Detection algorithm should weight weak links less when counting locations.
     */
    @ColumnInfo(name = "link_strength")
    val linkStrength: String? = null,

    /**
     * Detailed reason for the link, useful for debugging and transparency.
     * Examples:
     * - "fingerprint_match:FM:AABBCCDD"
     * - "name_match:John's iPhone"
     * - "temporal:rssi=-45,disappeared=120s"
     */
    @ColumnInfo(name = "link_reason")
    val linkReason: String? = null,

    /**
     * Timestamp when this device was last seen with a different MAC address.
     * Used to detect and track MAC address rotation patterns.
     */
    @ColumnInfo(name = "last_mac_rotation")
    val lastMacRotation: Long? = null,

    // ============================================================================
    // ENHANCED SIGNAL AND BEACON DETECTION (v7)
    // ============================================================================

    /**
     * Highest RSSI value ever recorded for this device.
     * Tracks the strongest signal seen, indicating closest proximity achieved.
     * Useful for determining if device has been very close to user.
     */
    @ColumnInfo(name = "highest_rssi")
    val highestRssi: Int? = null,

    /**
     * Most recent signal strength classification.
     * Stored as enum name: VERY_WEAK, WEAK, MEDIUM, STRONG, VERY_STRONG
     * @see com.tailbait.util.SignalStrength
     */
    @ColumnInfo(name = "signal_strength")
    val signalStrength: String? = null,

    /**
     * Detected beacon type from advertisement analysis.
     * Stored as enum name: IBEACON, EDDYSTONE_UID, FIND_MY, AIRDROP, etc.
     * @see com.tailbait.util.BeaconDetectionUtils.BeaconType
     */
    @ColumnInfo(name = "beacon_type")
    val beaconType: String? = null,

    /**
     * Threat level assessment based on device characteristics.
     * Stored as enum name: NONE, LOW, MEDIUM, HIGH, CRITICAL
     * @see com.tailbait.algorithm.TrackerAnalyzerFactory.ThreatLevel
     */
    @ColumnInfo(name = "threat_level")
    val threatLevel: String? = null
)
