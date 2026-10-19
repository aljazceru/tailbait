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
        // Unique per (MAC, radio): the same MAC string may exist as a BLE
        // device and as a WiFi STA/AP — different namespaces.
        Index(value = ["address", "radio"], unique = true),
        Index(value = ["last_seen"]),
        Index(value = ["device_type"]),
        Index(value = ["is_tracker"]),
        Index(value = ["manufacturer_id"]),
        Index(value = ["payload_fingerprint"]), // For fingerprint-based device correlation
        Index(value = ["linked_device_id"]), // For finding linked devices
        Index(value = ["highest_rssi"]), // For signal strength queries
        Index(value = ["threat_level"]), // For threat-based filtering
        Index(value = ["shadow_key"]), // For shadow-based detection queries
        Index(value = ["radio"]), // For companion/wardriving radio filtering
    ],
)
data class ScannedDevice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // Core identification
    @ColumnInfo(name = "address")
    val address: String,
    /** Radio layer this device was observed on: BLE, WIFI_STA, WIFI_AP. */
    @ColumnInfo(name = "radio", defaultValue = "BLE")
    val radio: String = "BLE",
    /** WiFi SSID (AP beacon, directed probe, or network joined via assoc). */
    @ColumnInfo(name = "ssid")
    val ssid: String? = null,
    /** WiFi channel of the observation. */
    @ColumnInfo(name = "channel")
    val channel: Int? = null,
    /** Beacon flags from the AP (b0 = privacy/encrypted) — wardriving metadata. */
    @ColumnInfo(name = "wifi_flags")
    val wifiFlags: Int? = null,
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
    /**
     * Validated full advertised identity: FM: plus 44 uppercase hex digits, or
     * FHN: plus 40/64 uppercase hex digits. Equal identifiers can link MACs;
     * identity-key rotations and shared device profiles cannot.
     */
    @ColumnInfo(name = "payload_fingerprint")
    val payloadFingerprint: String? = null,
    /**
     * Latest validated Find My status byte. Bit 0x04 indicates a maintained or
     * recent owner connection, not separation from the owner.
     */
    @ColumnInfo(name = "find_my_status")
    val findMyStatus: Int? = null,
    /** Observation time of the latest validated Find My status, or null when unknown. */
    @ColumnInfo(name = "find_my_status_timestamp")
    val findMyStatusTimestamp: Long? = null,
    /**
     * Whether the latest valid status lacks the owner-connection bit (0x04).
     * This is a protocol inference, not proof of separation. False also covers
     * unknown state; consult findMyStatus and findMyStatusTimestamp for validity.
     */
    @ColumnInfo(name = "find_my_separated", defaultValue = "0")
    val findMySeparated: Boolean = false,
    /**
     * Existing, unlinked root of a confirmed full-identity match across MACs.
     * Only direct STRONG links form a family for detection and explicit trust.
     */
    @ColumnInfo(name = "linked_device_id")
    val linkedDeviceId: Long? = null,
    /**
     * STRONG links require a validated full FM/FHN identity, never names or profiles.
     * Legacy WEAK links do not combine observations or propagate explicit trust.
     * Null means this record is not linked.
     */
    @ColumnInfo(name = "link_strength")
    val linkStrength: String? = null,
    /** Reason for a confirmed link, formatted as fingerprint_match:<full FM/FHN identifier>. */
    @ColumnInfo(name = "link_reason")
    val linkReason: String? = null,
    /**
     * Timestamp when this device was last seen with a different MAC address.
     * Used to detect and track MAC address rotation patterns.
     */
    @ColumnInfo(name = "last_mac_rotation")
    val lastMacRotation: Long? = null,
    /**
     * ENHANCED SIGNAL AND BEACON DETECTION (v7).
     *
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
    val threatLevel: String? = null,
    /**
     * SHADOW DETECTION (MAC-agnostic device profiling).
     *
     * Coarse device profile key for shadow-based detection.
     * Built from stable BLE properties that survive MAC rotation:
     * manufacturer ID, device type, continuity type, tracker flag, etc.
     *
     * Two devices of the same make/model will share a shadow key.
     * Persistence scoring then determines if exactly one such device
     * consistently appears at the user's locations.
     *
     * @see com.tailbait.algorithm.ShadowKeyGenerator
     */
    @ColumnInfo(name = "shadow_key")
    val shadowKey: String? = null,
)
