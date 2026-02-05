package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.dto.DeviceLocationMapData
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for DeviceLocationRecord entity operations.
 *
 * This DAO provides methods for managing device-location correlation records,
 * which form the core of the stalking detection system. It includes queries
 * for analyzing device movement patterns, temporal correlations, and spatial
 * distributions.
 */
@Dao
interface DeviceLocationRecordDao {

    /**
     * Insert a new device-location record into the database.
     *
     * @param record The record to insert
     * @return The row ID of the newly inserted record
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DeviceLocationRecord): Long

    /**
     * Insert multiple records in a single transaction.
     * Useful for batch processing scan results.
     *
     * @param records List of records to insert
     * @return List of row IDs for the inserted records
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<DeviceLocationRecord>): List<Long>

    /**
     * Update an existing device-location record.
     *
     * @param record The record to update
     */
    @Update
    suspend fun update(record: DeviceLocationRecord)

    /**
     * Delete a device-location record from the database.
     *
     * @param record The record to delete
     */
    @Delete
    suspend fun delete(record: DeviceLocationRecord)

    /**
     * Get a record by its unique ID.
     *
     * @param id The record ID
     * @return The record, or null if not found
     */
    @Query("SELECT * FROM device_location_records WHERE id = :id")
    suspend fun getById(id: Long): DeviceLocationRecord?

    /**
     * Get all device-location records as a Flow for reactive updates.
     *
     * @return Flow emitting list of all records ordered by timestamp descending
     */
    @Query("SELECT * FROM device_location_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<DeviceLocationRecord>>

    /**
     * Get all records for a specific device.
     *
     * @param deviceId The device ID to query
     * @return Flow emitting list of records for the device
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE device_id = :deviceId
        ORDER BY timestamp DESC
    """)
    fun getRecordsForDevice(deviceId: Long): Flow<List<DeviceLocationRecord>>

    /**
     * Get all records at a specific location.
     *
     * @param locationId The location ID to query
     * @return Flow emitting list of records at the location
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE location_id = :locationId
        ORDER BY timestamp DESC
    """)
    fun getRecordsAtLocation(locationId: Long): Flow<List<DeviceLocationRecord>>

    /**
     * Get records for a specific device-location pair.
     *
     * @param deviceId The device ID
     * @param locationId The location ID
     * @return Flow emitting list of records for the device-location pair
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE device_id = :deviceId AND location_id = :locationId
        ORDER BY timestamp DESC
    """)
    fun getRecordsForDeviceAtLocation(deviceId: Long, locationId: Long): Flow<List<DeviceLocationRecord>>

    /**
     * Get count of distinct locations where a device has been seen.
     * Key metric for stalking detection.
     *
     * @param deviceId The device ID
     * @return Flow emitting count of distinct locations
     */
    @Query("""
        SELECT COUNT(DISTINCT location_id) FROM device_location_records
        WHERE device_id = :deviceId
    """)
    fun getDistinctLocationCountForDevice(deviceId: Long): Flow<Int>

    /**
     * Get count of devices seen at a specific location.
     *
     * @param locationId The location ID
     * @return Flow emitting count of distinct devices
     */
    @Query("""
        SELECT COUNT(DISTINCT device_id) FROM device_location_records
        WHERE location_id = :locationId
    """)
    fun getDeviceCountAtLocation(locationId: Long): Flow<Int>

    /**
     * Get records within a specific time range.
     *
     * @param startTimestamp Start of time range (inclusive)
     * @param endTimestamp End of time range (inclusive)
     * @return Flow emitting list of records within the time range
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE timestamp >= :startTimestamp AND timestamp <= :endTimestamp
        ORDER BY timestamp DESC
    """)
    fun getRecordsByTimeRange(startTimestamp: Long, endTimestamp: Long): Flow<List<DeviceLocationRecord>>

    /**
     * Get recent records within a time window.
     *
     * @param sinceTimestamp Timestamp threshold (records after this time)
     * @return Flow emitting list of recent records
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE timestamp >= :sinceTimestamp
        ORDER BY timestamp DESC
    """)
    fun getRecentRecords(sinceTimestamp: Long): Flow<List<DeviceLocationRecord>>

    /**
     * Get records by scan trigger type.
     *
     * @param scanTriggerType The scan trigger type (e.g., "MANUAL", "CONTINUOUS", "PERIODIC")
     * @return Flow emitting list of records from the specified scan type
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE scan_trigger_type = :scanTriggerType
        ORDER BY timestamp DESC
    """)
    fun getRecordsByScanType(scanTriggerType: String): Flow<List<DeviceLocationRecord>>

    /**
     * Get records where location changed since previous detection.
     * Useful for analyzing device mobility patterns.
     *
     * @return Flow emitting list of records with location changes
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE location_changed = 1
        ORDER BY timestamp DESC
    """)
    fun getRecordsWithLocationChange(): Flow<List<DeviceLocationRecord>>

    /**
     * Get records with strong signal strength (high RSSI).
     * Useful for determining device proximity.
     *
     * @param minRssi Minimum RSSI threshold (e.g., -60 for very close devices)
     * @return Flow emitting list of records with strong signals
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE rssi >= :minRssi
        ORDER BY rssi DESC, timestamp DESC
    """)
    fun getRecordsWithStrongSignal(minRssi: Int): Flow<List<DeviceLocationRecord>>

    /**
     * Get average RSSI for a device across all detections.
     *
     * @param deviceId The device ID
     * @return Average RSSI value, or null if no records exist
     */
    @Query("""
        SELECT AVG(rssi) FROM device_location_records
        WHERE device_id = :deviceId
    """)
    suspend fun getAverageRssiForDevice(deviceId: Long): Double?

    /**
     * Get the most recent record for a device.
     *
     * @param deviceId The device ID
     * @return The most recent record, or null if no records exist
     */
    @Query("""
        SELECT * FROM device_location_records
        WHERE device_id = :deviceId
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getLatestRecordForDevice(deviceId: Long): DeviceLocationRecord?

    /**
     * Get total count of all device-location records.
     *
     * @return Flow emitting total record count
     */
    @Query("SELECT COUNT(*) FROM device_location_records")
    fun getRecordCount(): Flow<Int>

    /**
     * Delete records older than the specified timestamp.
     * Used for data retention cleanup.
     *
     * @param beforeTimestamp Delete records before this timestamp
     * @return Number of records deleted
     */
    @Query("DELETE FROM device_location_records WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldRecords(beforeTimestamp: Long): Int

    /**
     * Delete all records for a specific device.
     *
     * @param deviceId The device ID
     * @return Number of records deleted
     */
    @Query("DELETE FROM device_location_records WHERE device_id = :deviceId")
    suspend fun deleteRecordsForDevice(deviceId: Long): Int

    /**
     * Delete all records at a specific location.
     *
     * @param locationId The location ID
     * @return Number of records deleted
     */
    @Query("DELETE FROM device_location_records WHERE location_id = :locationId")
    suspend fun deleteRecordsAtLocation(locationId: Long): Int


    /**
     * Delete all device-location records.
     * WARNING: This removes all device detection history.
     */
    @Query("DELETE FROM device_location_records")
    suspend fun deleteAll()

    /**
     * Optimized query for map data to avoid N+1 problem.
     * Joins all necessary tables and filters at the database level.
     */
    @Query("""
        SELECT 
            dlr.id,
            dlr.device_id as deviceId,
            dlr.location_id as locationId,
            l.latitude,
            l.longitude,
            l.accuracy,
            dlr.timestamp,
            dlr.rssi,
            sd.address as deviceAddress,
            sd.device_type as deviceType,
            sd.manufacturer_data as manufacturerData
        FROM device_location_records dlr
        INNER JOIN locations l ON dlr.location_id = l.id
        INNER JOIN scanned_devices sd ON dlr.device_id = sd.id
        WHERE (:deviceId IS NULL OR dlr.device_id = :deviceId)
          AND (:startTimestamp IS NULL OR dlr.timestamp >= :startTimestamp)
          AND (:endTimestamp IS NULL OR dlr.timestamp <= :endTimestamp)
        ORDER BY dlr.timestamp DESC
    """)
    fun getMapData(
        deviceId: Long? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null
    ): Flow<List<DeviceLocationMapData>>
}

