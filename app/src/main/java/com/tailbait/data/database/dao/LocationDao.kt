package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tailbait.data.database.entities.Location
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for Location entity operations.
 *
 * This DAO provides methods for managing GPS location records in the database,
 * including CRUD operations, spatial queries, and temporal queries for
 * location-based tracking and analysis.
 */
@Dao
interface LocationDao {

    /**
     * Insert a new location into the database.
     *
     * @param location The location to insert
     * @return The row ID of the newly inserted location
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: Location): Long

    /**
     * Insert multiple locations in a single transaction.
     *
     * @param locations List of locations to insert
     * @return List of row IDs for the inserted locations
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(locations: List<Location>): List<Long>

    /**
     * Update an existing location record.
     *
     * @param location The location to update
     */
    @Update
    suspend fun update(location: Location)

    /**
     * Delete a location from the database.
     * This will cascade delete all related device_location_records.
     *
     * @param location The location to delete
     */
    @Delete
    suspend fun delete(location: Location)

    /**
     * Get a location by its unique ID.
     *
     * @param id The location ID
     * @return The location, or null if not found
     */
    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: Long): Location?

    /**
     * Get all locations as a Flow for reactive updates.
     *
     * @return Flow emitting list of all locations ordered by timestamp descending
     */
    @Query("SELECT * FROM locations ORDER BY timestamp DESC")
    fun getAllLocations(): Flow<List<Location>>

    /**
     * Get the most recent location record.
     *
     * @return The most recent location, or null if no locations exist
     */
    @Query("SELECT * FROM locations ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLocation(): Location?

    /**
     * Get all locations where a specific device was detected.
     *
     * @param deviceId The device ID to query
     * @return Flow emitting list of locations where the device was seen
     */
    @Query("""
        SELECT l.* FROM locations l
        INNER JOIN device_location_records dlr ON l.id = dlr.location_id
        WHERE dlr.device_id = :deviceId
        ORDER BY l.timestamp DESC
    """)
    fun getLocationsForDevice(deviceId: Long): Flow<List<Location>>

    /**
     * Get all locations where a specific device was detected (one-time query).
     *
     * @param deviceId The device ID to query
     * @return List of locations where the device was seen
     */
    @Query("""
        SELECT DISTINCT l.* FROM locations l
        INNER JOIN device_location_records dlr ON l.id = dlr.location_id
        WHERE dlr.device_id = :deviceId
        ORDER BY dlr.timestamp DESC
    """)
    suspend fun getLocationsForDeviceOnce(deviceId: Long): List<Location>

    /**
     * Get all locations where a specific device OR its linked devices were detected (one-time query).
     * This is critical for detection algorithm to analyze the full path of a device across MAC rotations.
     *
     * @param deviceId The device ID to query
     * @return List of locations where the device (or linked devices) was seen
     */
    @Query("""
        SELECT DISTINCT l.* FROM locations l
        INNER JOIN device_location_records dlr ON l.id = dlr.location_id
        WHERE dlr.device_id = :deviceId
           OR dlr.device_id IN (SELECT id FROM scanned_devices WHERE linked_device_id = :deviceId)
        ORDER BY dlr.timestamp DESC
    """)
    suspend fun getLocationsForDeviceWithLinked(deviceId: Long): List<Location>

    /**
     * Get locations within a specific time range.
     *
     * @param startTimestamp Start of time range (inclusive)
     * @param endTimestamp End of time range (inclusive)
     * @return Flow emitting list of locations within the time range
     */
    @Query("""
        SELECT * FROM locations
        WHERE timestamp >= :startTimestamp AND timestamp <= :endTimestamp
        ORDER BY timestamp DESC
    """)
    fun getLocationsByTimeRange(startTimestamp: Long, endTimestamp: Long): Flow<List<Location>>

    /**
     * Get recent locations within a time window.
     *
     * @param sinceTimestamp Timestamp threshold (locations after this time)
     * @return Flow emitting list of recent locations
     */
    @Query("""
        SELECT * FROM locations
        WHERE timestamp >= :sinceTimestamp
        ORDER BY timestamp DESC
    """)
    fun getRecentLocations(sinceTimestamp: Long): Flow<List<Location>>

    /**
     * Get recent locations within a time window (suspend version).
     * Used for location deduplication.
     *
     * @param sinceTimestamp Timestamp threshold
     * @return List of recent locations
     */
    @Query("""
        SELECT * FROM locations
        WHERE timestamp >= :sinceTimestamp
        ORDER BY timestamp DESC
        LIMIT 100
    """)
    suspend fun getRecentLocationsOnce(sinceTimestamp: Long): List<Location>

    /**
     * Find nearest location within a given radius and time range.
     * Optimized coordinate-box query to avoid loading all locations into memory.
     *
     * @param latMin Minimum latitude (box bound)
     * @param latMax Maximum latitude (box bound)
     * @param lonMin Minimum longitude (box bound)
     * @param lonMax Maximum longitude (box bound)
     * @param sinceTimestamp Only consider locations more recent than this
     * @return List of candidate locations (further filtering needed for exact distance)
     */
    @Query("""
        SELECT * FROM locations
        WHERE latitude BETWEEN :latMin AND :latMax
          AND longitude BETWEEN :lonMin AND :lonMax
          AND timestamp >= :sinceTimestamp
        ORDER BY timestamp DESC
        LIMIT 5
    """)
    suspend fun getNearbyCandidates(
        latMin: Double, latMax: Double,
        lonMin: Double, lonMax: Double,
        sinceTimestamp: Long
    ): List<Location>

    /**
     * Update the timestamp of an existing location.
     * Used when reusing a nearby location to record the latest visit.
     *
     * @param locationId Location ID to update
     * @param timestamp New timestamp
     */
    @Query("UPDATE locations SET timestamp = :timestamp WHERE id = :locationId")
    suspend fun updateTimestamp(locationId: Long, timestamp: Long)

    /**
     * Get count of all stored locations.
     *
     * @return Flow emitting total location count
     */
    @Query("SELECT COUNT(*) FROM locations")
    fun getLocationCount(): Flow<Int>

    /**
     * Get locations with accuracy better than specified threshold.
     *
     * @param maxAccuracyMeters Maximum accuracy in meters
     * @return Flow emitting list of high-accuracy locations
     */
    @Query("""
        SELECT * FROM locations
        WHERE accuracy <= :maxAccuracyMeters
        ORDER BY timestamp DESC
    """)
    fun getHighAccuracyLocations(maxAccuracyMeters: Float): Flow<List<Location>>

    /**
     * Get locations by provider type.
     *
     * @param provider Location provider (e.g., "GPS", "NETWORK", "FUSED")
     * @return Flow emitting list of locations from the specified provider
     */
    @Query("""
        SELECT * FROM locations
        WHERE provider = :provider
        ORDER BY timestamp DESC
    """)
    fun getLocationsByProvider(provider: String): Flow<List<Location>>

    /**
     * Get distinct location clusters for analysis.
     * This query groups locations within a coordinate precision window.
     *
     * @param limit Maximum number of location clusters to return
     * @return List of representative locations from distinct clusters
     */
    @Query("""
        SELECT * FROM locations
        GROUP BY ROUND(latitude, 3), ROUND(longitude, 3)
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getLocationClusters(limit: Int = 100): List<Location>

    /**
     * Delete locations older than the specified timestamp.
     * Used for data retention cleanup.
     *
     * @param beforeTimestamp Delete locations before this timestamp
     * @return Number of locations deleted
     */
    @Query("DELETE FROM locations WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldLocations(beforeTimestamp: Long): Int

    /**
     * Delete all locations.
     * WARNING: This will cascade delete all related device_location_records.
     */
    @Query("DELETE FROM locations")
    suspend fun deleteAll()

    /**
     * Get locations within a bounding box (for map display).
     *
     * @param minLat Minimum latitude
     * @param maxLat Maximum latitude
     * @param minLon Minimum longitude
     * @param maxLon Maximum longitude
     * @return Flow emitting list of locations within the bounding box
     */
    @Query("""
        SELECT * FROM locations
        WHERE latitude BETWEEN :minLat AND :maxLat
        AND longitude BETWEEN :minLon AND :maxLon
        ORDER BY timestamp DESC
    """)
    fun getLocationsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): Flow<List<Location>>
}
