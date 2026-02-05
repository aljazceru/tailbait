package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for WhitelistEntry entity operations.
 *
 * This DAO provides methods for managing the device whitelist, which contains
 * trusted devices that should be excluded from stalking detection. It includes
 * queries for managing whitelist entries by category and checking device
 * whitelist status.
 */
@Dao
interface WhitelistEntryDao {

    /**
     * Insert a new whitelist entry into the database.
     *
     * @param entry The whitelist entry to insert
     * @return The row ID of the newly inserted entry
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: WhitelistEntry): Long

    /**
     * Insert multiple whitelist entries in a single transaction.
     * Useful for batch whitelisting devices after Learn Mode.
     *
     * @param entries List of entries to insert
     * @return List of row IDs for the inserted entries
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<WhitelistEntry>): List<Long>

    /**
     * Update an existing whitelist entry.
     *
     * @param entry The entry to update
     */
    @Update
    suspend fun update(entry: WhitelistEntry)

    /**
     * Delete a whitelist entry from the database.
     *
     * @param entry The entry to delete
     */
    @Delete
    suspend fun delete(entry: WhitelistEntry)

    /**
     * Get a whitelist entry by its unique ID.
     *
     * @param id The entry ID
     * @return The whitelist entry, or null if not found
     */
    @Query("SELECT * FROM whitelist_entries WHERE id = :id")
    suspend fun getById(id: Long): WhitelistEntry?

    /**
     * Get a whitelist entry by device ID.
     *
     * @param deviceId The device ID
     * @return The whitelist entry, or null if device is not whitelisted
     */
    @Query("SELECT * FROM whitelist_entries WHERE device_id = :deviceId")
    suspend fun getByDeviceId(deviceId: Long): WhitelistEntry?

    /**
     * Get all whitelist entries as a Flow for reactive updates.
     *
     * @return Flow emitting list of all whitelist entries ordered by creation date
     */
    @Query("SELECT * FROM whitelist_entries ORDER BY created_at DESC")
    fun getAllEntries(): Flow<List<WhitelistEntry>>

    /**
     * Get whitelist entries with associated device information.
     * This is a convenience query for displaying the whitelist in UI.
     *
     * @return Flow emitting list of whitelist entries with device details
     */
    @Query("""
        SELECT we.* FROM whitelist_entries we
        INNER JOIN scanned_devices sd ON we.device_id = sd.id
        ORDER BY we.created_at DESC
    """)
    fun getAllEntriesWithDevices(): Flow<List<WhitelistEntry>>

    /**
     * Get all whitelisted device IDs.
     * This is used by the detection algorithm to filter out trusted devices.
     *
     * @return Flow emitting set of whitelisted device IDs
     */
    @Query("SELECT device_id FROM whitelist_entries")
    fun getAllWhitelistedDeviceIds(): Flow<List<Long>>

    /**
     * Check if a device is whitelisted.
     *
     * @param deviceId The device ID to check
     * @return True if the device is whitelisted, false otherwise
     */
    @Query("SELECT EXISTS(SELECT 1 FROM whitelist_entries WHERE device_id = :deviceId)")
    suspend fun isDeviceWhitelisted(deviceId: Long): Boolean

    /**
     * Get whitelist entries by category.
     *
     * @param category The category to filter by (e.g., "OWN", "PARTNER", "TRUSTED")
     * @return Flow emitting list of entries in the specified category
     */
    @Query("""
        SELECT * FROM whitelist_entries
        WHERE category = :category
        ORDER BY created_at DESC
    """)
    fun getEntriesByCategory(category: String): Flow<List<WhitelistEntry>>

    /**
     * Get whitelist entries added via Learn Mode.
     *
     * @return Flow emitting list of entries added through Learn Mode
     */
    @Query("""
        SELECT * FROM whitelist_entries
        WHERE added_via_learn_mode = 1
        ORDER BY created_at DESC
    """)
    fun getLearnModeEntries(): Flow<List<WhitelistEntry>>

    /**
     * Get whitelist entries added manually.
     *
     * @return Flow emitting list of manually added entries
     */
    @Query("""
        SELECT * FROM whitelist_entries
        WHERE added_via_learn_mode = 0
        ORDER BY created_at DESC
    """)
    fun getManualEntries(): Flow<List<WhitelistEntry>>

    /**
     * Search whitelist entries by label.
     *
     * @param query Search query string
     * @return Flow emitting list of matching entries
     */
    @Query("""
        SELECT * FROM whitelist_entries
        WHERE label LIKE '%' || :query || '%'
        OR notes LIKE '%' || :query || '%'
        ORDER BY created_at DESC
    """)
    fun searchEntries(query: String): Flow<List<WhitelistEntry>>

    /**
     * Get total count of whitelisted devices.
     *
     * @return Flow emitting total whitelist count
     */
    @Query("SELECT COUNT(*) FROM whitelist_entries")
    fun getWhitelistCount(): Flow<Int>

    /**
     * Get count of whitelist entries by category.
     *
     * @param category The category to count
     * @return Flow emitting count of entries in the category
     */
    @Query("""
        SELECT COUNT(*) FROM whitelist_entries
        WHERE category = :category
    """)
    fun getCountByCategory(category: String): Flow<Int>

    /**
     * Delete a whitelist entry by device ID.
     *
     * @param deviceId The device ID to remove from whitelist
     * @return Number of entries deleted (should be 0 or 1)
     */
    @Query("DELETE FROM whitelist_entries WHERE device_id = :deviceId")
    suspend fun deleteByDeviceId(deviceId: Long): Int

    /**
     * Delete all whitelist entries in a specific category.
     *
     * @param category The category to clear
     * @return Number of entries deleted
     */
    @Query("DELETE FROM whitelist_entries WHERE category = :category")
    suspend fun deleteByCategory(category: String): Int

    /**
     * Delete all Learn Mode entries.
     *
     * @return Number of entries deleted
     */
    @Query("DELETE FROM whitelist_entries WHERE added_via_learn_mode = 1")
    suspend fun deleteLearnModeEntries(): Int

    /**
     * Delete all whitelist entries.
     * WARNING: This will remove all trusted devices.
     */
    @Query("DELETE FROM whitelist_entries")
    suspend fun deleteAll()
}
