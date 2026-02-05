package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Update
import com.tailbait.data.database.entities.ScannedDevice
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for ScannedDevice entity operations.
 *
 * This DAO provides methods for managing BLE device records in the database,
 * including CRUD operations, device queries, and stalking detection queries.
 * All Flow-based queries are reactive and will emit updates when underlying
 * data changes.
 */
@Dao
interface ScannedDeviceDao {

    /**
     * Insert a new scanned device into the database.
     *
     * @param device The device to insert
     * @return The row ID of the newly inserted device
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: ScannedDevice): Long

    /**
     * Insert multiple devices in a single transaction.
     *
     * @param devices List of devices to insert
     * @return List of row IDs for the inserted devices
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<ScannedDevice>): List<Long>

    /**
     * Update an existing device record.
     *
     * @param device The device to update
     */
    @Update
    suspend fun update(device: ScannedDevice)

    /**
     * Delete a device from the database.
     * This will cascade delete all related records (device_location_records, whitelist_entries).
     *
     * @param device The device to delete
     */
    @Delete
    suspend fun delete(device: ScannedDevice)

    /**
     * Get a device by its unique ID.
     *
     * @param id The device ID
     * @return The device, or null if not found
     */
    @Query("SELECT * FROM scanned_devices WHERE id = :id")
    suspend fun getById(id: Long): ScannedDevice?

    /**
     * Get a device by its MAC address.
     *
     * @param address The MAC address
     * @return The device, or null if not found
     */
    @Query("SELECT * FROM scanned_devices WHERE address = :address")
    suspend fun getByAddress(address: String): ScannedDevice?

    /**
     * Get all scanned devices as a Flow for reactive updates.
     *
     * @return Flow emitting list of all devices
     */
    @Query("SELECT * FROM scanned_devices ORDER BY last_seen DESC")
    fun getAllDevices(): Flow<List<ScannedDevice>>

    /**
     * Get all devices seen at a specific location, excluding whitelisted devices.
     *
     * @param locationId The location ID to query
     * @return List of devices detected at the specified location
     */
    @Query("""
        SELECT DISTINCT sd.* FROM scanned_devices sd
        INNER JOIN device_location_records dlr ON sd.id = dlr.device_id
        WHERE dlr.location_id = :locationId
        AND sd.id NOT IN (SELECT device_id FROM whitelist_entries)
        ORDER BY dlr.timestamp DESC
    """)
    suspend fun getDevicesAtLocation(locationId: Long): List<ScannedDevice>

    /**
     * Get devices that have been detected at multiple distinct locations.
     * This is a key query for stalking detection.
     *
     * @param minLocationCount Minimum number of distinct locations required
     * @return Flow emitting list of suspicious devices with location counts
     */
    @RewriteQueriesToDropUnusedColumns
    @Query("""
        SELECT sd.*, COUNT(DISTINCT dlr.location_id) as location_count
        FROM scanned_devices sd
        INNER JOIN device_location_records dlr ON sd.id = dlr.device_id
        WHERE sd.id NOT IN (SELECT device_id FROM whitelist_entries)
        GROUP BY sd.id
        HAVING COUNT(DISTINCT dlr.location_id) >= :minLocationCount
        ORDER BY location_count DESC
    """)
    fun getSuspiciousDevices(minLocationCount: Int): Flow<List<ScannedDevice>>

    /**
     * Get recently seen devices within a time window.
     *
     * @param sinceTimestamp Timestamp threshold (devices seen after this time)
     * @return Flow emitting list of recently seen devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE last_seen >= :sinceTimestamp
        ORDER BY last_seen DESC
    """)
    fun getRecentDevices(sinceTimestamp: Long): Flow<List<ScannedDevice>>

    /**
     * Get total count of all scanned devices.
     *
     * @return Flow emitting total device count
     */
    @Query("SELECT COUNT(*) FROM scanned_devices")
    fun getDeviceCount(): Flow<Int>

    /**
     * Get count of devices detected in the last specified hours.
     *
     * @param hoursAgo Number of hours to look back
     * @return Flow emitting count of recently seen devices
     */
    @Query("""
        SELECT COUNT(*) FROM scanned_devices
        WHERE last_seen >= :sinceTimestamp
    """)
    fun getRecentDeviceCount(sinceTimestamp: Long): Flow<Int>

    /**
     * Search devices by name or address.
     *
     * @param query Search query string
     * @return Flow emitting list of matching devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE name LIKE '%' || :query || '%'
        OR address LIKE '%' || :query || '%'
        ORDER BY last_seen DESC
    """)
    fun searchDevices(query: String): Flow<List<ScannedDevice>>

    /**
     * Delete devices that haven't been seen since the specified timestamp.
     * Used for data retention cleanup.
     *
     * @param beforeTimestamp Delete devices last seen before this timestamp
     * @return Number of devices deleted
     */
    @Query("DELETE FROM scanned_devices WHERE last_seen < :beforeTimestamp")
    suspend fun deleteOldDevices(beforeTimestamp: Long): Int

    /**
     * Delete all scanned devices.
     * WARNING: This will cascade delete all related records.
     */
    @Query("DELETE FROM scanned_devices")
    suspend fun deleteAll()

    /**
     * Get devices by type.
     *
     * @param deviceType The device type to filter by (e.g., "PHONE", "WATCH")
     * @return Flow emitting list of devices of the specified type
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE device_type = :deviceType
        ORDER BY last_seen DESC
    """)
    fun getDevicesByType(deviceType: String): Flow<List<ScannedDevice>>

    // ============================================================================
    // FINGERPRINT-BASED DEVICE CORRELATION (AirTag MAC rotation handling)
    // ============================================================================

    /**
     * Get a device by its payload fingerprint.
     * Used to find devices across MAC address rotations.
     *
     * @param fingerprint The payload fingerprint
     * @return The device with matching fingerprint, or null if not found
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE payload_fingerprint = :fingerprint
        ORDER BY last_seen DESC
        LIMIT 1
    """)
    suspend fun getByFingerprint(fingerprint: String): ScannedDevice?

    /**
     * Get all devices with a specific payload fingerprint.
     * Used to find all MAC addresses associated with the same physical device.
     *
     * @param fingerprint The payload fingerprint
     * @return List of devices with matching fingerprint
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE payload_fingerprint = :fingerprint
        ORDER BY last_seen DESC
    """)
    suspend fun getAllByFingerprint(fingerprint: String): List<ScannedDevice>

    /**
     * Get all devices linked to a primary device ID.
     * Used to find all rotated MAC addresses for a single physical device.
     *
     * @param primaryDeviceId The ID of the primary device
     * @return List of linked devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE linked_device_id = :primaryDeviceId OR id = :primaryDeviceId
        ORDER BY last_seen DESC
    """)
    suspend fun getLinkedDevices(primaryDeviceId: Long): List<ScannedDevice>

    /**
     * Get Find My devices that are separated from their owner.
     * These are HIGHLY suspicious for stalking detection.
     *
     * @return Flow emitting list of separated Find My devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE find_my_separated = 1 AND is_tracker = 1
        ORDER BY last_seen DESC
    """)
    fun getSeparatedFindMyDevices(): Flow<List<ScannedDevice>>

    /**
     * Get suspicious devices including their linked devices (MAC rotations).
     * This query considers all locations across all linked devices as one.
     *
     * This is the CRITICAL query for detecting AirTags that rotate MACs.
     *
     * @param minLocationCount Minimum number of distinct locations required
     * @return Flow emitting list of suspicious devices
     */
    @RewriteQueriesToDropUnusedColumns
    @Query("""
        SELECT sd.*,
            (SELECT COUNT(DISTINCT dlr.location_id)
             FROM device_location_records dlr
             WHERE dlr.device_id = sd.id
                OR dlr.device_id IN (SELECT id FROM scanned_devices WHERE linked_device_id = sd.id)
            ) as total_location_count
        FROM scanned_devices sd
        WHERE sd.id NOT IN (SELECT device_id FROM whitelist_entries)
          AND sd.linked_device_id IS NULL
        GROUP BY sd.id
        HAVING total_location_count >= :minLocationCount
        ORDER BY total_location_count DESC
    """)
    fun getSuspiciousDevicesWithLinked(minLocationCount: Int): Flow<List<ScannedDevice>>

    /**
     * Update the linked_device_id for a device.
     * Used to link a new MAC address to an existing device.
     *
     * @param deviceId The device ID to update
     * @param linkedDeviceId The primary device ID to link to
     * @param lastMacRotation Timestamp of the MAC rotation
     * @param linkStrength Strength of the link (STRONG or WEAK)
     * @param linkReason Explanation of why devices were linked
     */
    @Query("""
        UPDATE scanned_devices
        SET linked_device_id = :linkedDeviceId,
            last_mac_rotation = :lastMacRotation,
            link_strength = :linkStrength,
            link_reason = :linkReason
        WHERE id = :deviceId
    """)
    suspend fun linkDevice(
        deviceId: Long,
        linkedDeviceId: Long,
        lastMacRotation: Long,
        linkStrength: String,
        linkReason: String
    )

    /**
     * Get all weak-linked devices for a primary device.
     * Useful for applying reduced weight in detection algorithm.
     *
     * @param primaryDeviceId The ID of the primary device
     * @return List of weakly linked devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE linked_device_id = :primaryDeviceId
          AND link_strength = 'WEAK'
        ORDER BY last_seen DESC
    """)
    suspend fun getWeakLinkedDevices(primaryDeviceId: Long): List<ScannedDevice>

    /**
     * Get all strongly linked devices for a primary device.
     *
     * @param primaryDeviceId The ID of the primary device
     * @return List of strongly linked devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE linked_device_id = :primaryDeviceId
          AND link_strength = 'STRONG'
        ORDER BY last_seen DESC
    """)
    suspend fun getStrongLinkedDevices(primaryDeviceId: Long): List<ScannedDevice>

    /**
     * Update Find My status fields for a device.
     *
     * @param deviceId The device ID
     * @param findMyStatus The raw status byte
     * @param findMySeparated Whether device is separated from owner
     */
    @Query("""
        UPDATE scanned_devices
        SET find_my_status = :findMyStatus,
            find_my_separated = :findMySeparated
        WHERE id = :deviceId
    """)
    suspend fun updateFindMyStatus(deviceId: Long, findMyStatus: Int, findMySeparated: Boolean)

    // ============================================================================
    // TEMPORAL CLUSTERING (For devices without payload fingerprints)
    // ============================================================================

    /**
     * Find potential duplicate devices based on temporal patterns.
     *
     * This query finds devices that:
     * 1. Have the same manufacturer ID
     * 2. Have the same device type
     * 3. Have similar signal strength (within 15 dBm)
     * 4. Were seen within a time window
     * 5. Have different MAC addresses
     *
     * The idea is that when a device rotates its MAC, the old MAC "disappears"
     * and a new MAC with similar characteristics appears shortly after.
     *
     * @param manufacturerId The manufacturer ID to match
     * @param deviceType The device type to match
     * @param rssi The RSSI value (±15 dBm tolerance)
     * @param startTime Start of time window
     * @param endTime End of time window
     * @param excludeAddress MAC address to exclude (the current device)
     * @return List of potential duplicate devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE manufacturer_id = :manufacturerId
          AND device_type = :deviceType
          AND last_seen BETWEEN :startTime AND :endTime
          AND address != :excludeAddress
          AND (
              highest_rssi IS NULL
              OR ABS(COALESCE(highest_rssi, -70) - :rssi) <= 15
          )
        ORDER BY last_seen DESC
        LIMIT 10
    """)
    suspend fun findPotentialDuplicates(
        manufacturerId: Int,
        deviceType: String,
        rssi: Int,
        startTime: Long,
        endTime: Long,
        excludeAddress: String
    ): List<ScannedDevice>

    /**
     * Find devices that stopped being seen around the time a new device appeared.
     *
     * This helps detect MAC rotation: the old MAC's last_seen should be close to
     * the new MAC's first_seen, and they shouldn't overlap.
     *
     * @param newDeviceFirstSeen When the new device was first detected
     * @param manufacturerId The manufacturer ID to match
     * @param deviceType The device type to match
     * @param timeWindowMs Time window in milliseconds (default: 5 minutes)
     * @param excludeAddress MAC address to exclude
     * @return List of devices that "disappeared" when the new one appeared
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE manufacturer_id = :manufacturerId
          AND device_type = :deviceType
          AND last_seen BETWEEN (:newDeviceFirstSeen - :timeWindowMs) AND :newDeviceFirstSeen
          AND address != :excludeAddress
          AND linked_device_id IS NULL
        ORDER BY last_seen DESC
        LIMIT 5
    """)
    suspend fun findDevicesDisappearedNear(
        newDeviceFirstSeen: Long,
        manufacturerId: Int,
        deviceType: String,
        timeWindowMs: Long,
        excludeAddress: String
    ): List<ScannedDevice>

    /**
     * Get devices with no fingerprint that could benefit from temporal clustering.
     *
     * These are devices that:
     * 1. Have no payload fingerprint
     * 2. Are not already linked to another device
     * 3. Have been seen recently
     *
     * @param sinceTimestamp Only consider devices seen after this time
     * @return List of unfingerprinted devices
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE payload_fingerprint IS NULL
          AND linked_device_id IS NULL
          AND last_seen >= :sinceTimestamp
        ORDER BY last_seen DESC
    """)
    suspend fun getUnfingerprintedDevices(sinceTimestamp: Long): List<ScannedDevice>

    /**
     * Count devices with fingerprints vs without.
     * Useful for monitoring fingerprinting effectiveness.
     *
     * @return Total count of fingerprinted devices
     */
    @Query("SELECT COUNT(*) FROM scanned_devices WHERE payload_fingerprint IS NOT NULL")
    suspend fun getFingerprintedDeviceCount(): Int

    /**
     * Get recently seen devices grouped by fingerprint for correlation analysis.
     * Helps identify devices that may be rotating MACs.
     *
     * @param sinceTimestamp Only consider devices seen after this time
     * @return List of devices with fingerprints, ordered by fingerprint
     */
    @Query("""
        SELECT * FROM scanned_devices
        WHERE payload_fingerprint IS NOT NULL
          AND last_seen >= :sinceTimestamp
        ORDER BY payload_fingerprint, last_seen DESC
    """)
    suspend fun getDevicesGroupedByFingerprint(sinceTimestamp: Long): List<ScannedDevice>
}
