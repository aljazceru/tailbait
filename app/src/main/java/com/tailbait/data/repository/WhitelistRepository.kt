package com.tailbait.data.repository

import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.WhitelistEntryDao
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository interface for managing device whitelist.
 *
 * Provides high-level operations for managing trusted devices that should be
 * excluded from stalking detection. Devices can be whitelisted manually or
 * through Learn Mode, and are categorized for better organization.
 */
interface WhitelistRepository {

    /**
     * Device category constants for whitelist entries.
     */
    object Category {
        const val OWN = "OWN"
        const val PARTNER = "PARTNER"
        const val TRUSTED = "TRUSTED"

        /**
         * Get all valid category values.
         */
        fun values() = listOf(OWN, PARTNER, TRUSTED)
    }

    /**
     * Data class combining whitelist entry with device information.
     * Used for UI display.
     */
    data class WhitelistEntryWithDevice(
        val entry: WhitelistEntry,
        val device: ScannedDevice
    )
    /**
     * Add a device to the whitelist.
     *
     * @param deviceId Device ID to whitelist
     * @param label User-friendly label for the device
     * @param category Category ("OWN", "PARTNER", "TRUSTED")
     * @param addedViaLearnMode Whether added through Learn Mode
     * @param notes Optional notes
     * @return Whitelist entry ID
     */
    suspend fun addToWhitelist(
        deviceId: Long,
        label: String,
        category: String,
        addedViaLearnMode: Boolean = false,
        notes: String? = null
    ): Long

    /**
     * Add multiple devices to the whitelist in batch.
     *
     * @param entries List of whitelist entries
     * @return List of entry IDs
     */
    suspend fun addMultipleToWhitelist(entries: List<WhitelistEntry>): List<Long>

    /**
     * Remove a device from the whitelist.
     *
     * @param deviceId Device ID to remove
     * @return Number of entries removed
     */
    suspend fun removeFromWhitelist(deviceId: Long): Int

    /**
     * Check if a device is whitelisted.
     *
     * @param deviceId Device ID to check
     * @return True if whitelisted
     */
    suspend fun isDeviceWhitelisted(deviceId: Long): Boolean

    /**
     * Get all whitelist entries.
     *
     * @return Flow of all entries
     */
    fun getAllWhitelistEntries(): Flow<List<WhitelistEntry>>

    /**
     * Get all whitelist entries with associated device information.
     * This is the primary query for displaying the whitelist in UI.
     *
     * @return Flow of whitelist entries with device data
     */
    fun getAllEntriesWithDevices(): Flow<List<WhitelistEntryWithDevice>>

    /**
     * Get all whitelisted device IDs.
     *
     * @return Flow of device IDs
     */
    fun getAllWhitelistedDeviceIds(): Flow<List<Long>>

    /**
     * Get whitelist entries by category.
     *
     * @param category Category to filter
     * @return Flow of entries
     */
    fun getEntriesByCategory(category: String): Flow<List<WhitelistEntry>>

    /**
     * Get whitelist entries with devices filtered by category.
     *
     * @param category Category to filter by
     * @return Flow of entries with device data in the category
     */
    fun getEntriesWithDevicesByCategory(category: String): Flow<List<WhitelistEntryWithDevice>>

    /**
     * Get entries added via Learn Mode.
     *
     * @return Flow of Learn Mode entries
     */
    fun getLearnModeEntries(): Flow<List<WhitelistEntry>>

    /**
     * Update a whitelist entry.
     *
     * @param entry Entry to update
     */
    suspend fun updateEntry(entry: WhitelistEntry)

    /**
     * Search whitelist entries by label or notes.
     *
     * @param query Search query
     * @return Flow of matching entries
     */
    fun searchEntries(query: String): Flow<List<WhitelistEntry>>

    /**
     * Search whitelist entries with devices by label or notes.
     *
     * @param query Search query
     * @return Flow of matching entries with device data
     */
    fun searchEntriesWithDevices(query: String): Flow<List<WhitelistEntryWithDevice>>

    /**
     * Get total whitelist count.
     *
     * @return Flow of count
     */
    fun getWhitelistCount(): Flow<Int>

    /**
     * Delete all whitelist entries.
     */
    suspend fun clearWhitelist()

    /**
     * Get count of entries by category.
     *
     * @param category Category to count
     * @return Flow of count
     */
    fun getCountByCategory(category: String): Flow<Int>

    /**
     * Get a whitelist entry by ID.
     *
     * @param entryId Entry ID
     * @return WhitelistEntry or null if not found
     */
    suspend fun getEntryById(entryId: Long): WhitelistEntry?

    /**
     * Get a whitelist entry by device ID.
     *
     * @param deviceId Device ID
     * @return WhitelistEntry or null if device is not whitelisted
     */
    suspend fun getEntryByDeviceId(deviceId: Long): WhitelistEntry?

    /**
     * Get entries added manually.
     *
     * @return Flow of manually added entries
     */
    fun getManualEntries(): Flow<List<WhitelistEntry>>
}

/**
 * Implementation of WhitelistRepository.
 */
@Singleton
class WhitelistRepositoryImpl @Inject constructor(
    private val whitelistEntryDao: WhitelistEntryDao,
    private val scannedDeviceDao: ScannedDeviceDao
) : WhitelistRepository {

    override suspend fun addToWhitelist(
        deviceId: Long,
        label: String,
        category: String,
        addedViaLearnMode: Boolean,
        notes: String?
    ): Long {
        val entry = WhitelistEntry(
            deviceId = deviceId,
            label = label,
            category = category,
            addedViaLearnMode = addedViaLearnMode,
            notes = notes
        )
        return whitelistEntryDao.insert(entry)
    }

    override suspend fun addMultipleToWhitelist(entries: List<WhitelistEntry>): List<Long> {
        return whitelistEntryDao.insertAll(entries)
    }

    override suspend fun removeFromWhitelist(deviceId: Long): Int {
        return whitelistEntryDao.deleteByDeviceId(deviceId)
    }

    override suspend fun isDeviceWhitelisted(deviceId: Long): Boolean {
        return whitelistEntryDao.isDeviceWhitelisted(deviceId)
    }

    override fun getAllWhitelistEntries(): Flow<List<WhitelistEntry>> {
        return whitelistEntryDao.getAllEntries()
    }

    override fun getAllEntriesWithDevices(): Flow<List<WhitelistRepository.WhitelistEntryWithDevice>> {
        return combine(
            whitelistEntryDao.getAllEntries(),
            scannedDeviceDao.getAllDevices()
        ) { entries, devices ->
            // Create a map for quick device lookup
            val deviceMap = devices.associateBy { it.id }

            // Combine entries with their corresponding devices
            entries.mapNotNull { entry ->
                deviceMap[entry.deviceId]?.let { device ->
                    WhitelistRepository.WhitelistEntryWithDevice(
                        entry = entry,
                        device = device
                    )
                }
            }
        }
    }

    override fun getAllWhitelistedDeviceIds(): Flow<List<Long>> {
        return whitelistEntryDao.getAllWhitelistedDeviceIds()
    }

    override fun getEntriesByCategory(category: String): Flow<List<WhitelistEntry>> {
        return whitelistEntryDao.getEntriesByCategory(category)
    }

    override fun getEntriesWithDevicesByCategory(
        category: String
    ): Flow<List<WhitelistRepository.WhitelistEntryWithDevice>> {
        return combine(
            whitelistEntryDao.getEntriesByCategory(category),
            scannedDeviceDao.getAllDevices()
        ) { entries, devices ->
            val deviceMap = devices.associateBy { it.id }
            entries.mapNotNull { entry ->
                deviceMap[entry.deviceId]?.let { device ->
                    WhitelistRepository.WhitelistEntryWithDevice(
                        entry = entry,
                        device = device
                    )
                }
            }
        }
    }

    override fun getLearnModeEntries(): Flow<List<WhitelistEntry>> {
        return whitelistEntryDao.getLearnModeEntries()
    }

    override suspend fun updateEntry(entry: WhitelistEntry) {
        whitelistEntryDao.update(entry)
    }

    override fun searchEntries(query: String): Flow<List<WhitelistEntry>> {
        return whitelistEntryDao.searchEntries(query)
    }

    override fun searchEntriesWithDevices(query: String): Flow<List<WhitelistRepository.WhitelistEntryWithDevice>> {
        return combine(
            whitelistEntryDao.searchEntries(query),
            scannedDeviceDao.getAllDevices()
        ) { entries, devices ->
            val deviceMap = devices.associateBy { it.id }
            entries.mapNotNull { entry ->
                deviceMap[entry.deviceId]?.let { device ->
                    WhitelistRepository.WhitelistEntryWithDevice(
                        entry = entry,
                        device = device
                    )
                }
            }
        }
    }

    override fun getWhitelistCount(): Flow<Int> {
        return whitelistEntryDao.getWhitelistCount()
    }

    override suspend fun clearWhitelist() {
        whitelistEntryDao.deleteAll()
    }

    override fun getCountByCategory(category: String): Flow<Int> {
        return whitelistEntryDao.getCountByCategory(category)
    }

    override suspend fun getEntryById(entryId: Long): WhitelistEntry? {
        return whitelistEntryDao.getById(entryId)
    }

    override suspend fun getEntryByDeviceId(deviceId: Long): WhitelistEntry? {
        return whitelistEntryDao.getByDeviceId(deviceId)
    }

    override fun getManualEntries(): Flow<List<WhitelistEntry>> {
        return whitelistEntryDao.getManualEntries()
    }
}
