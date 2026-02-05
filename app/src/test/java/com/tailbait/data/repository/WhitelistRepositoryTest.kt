package com.tailbait.data.repository

import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.WhitelistEntryDao
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WhitelistRepository.
 *
 * Tests cover:
 * - CRUD operations
 * - Category filtering
 * - Search functionality
 * - Bulk operations
 * - Device-entry correlation
 */
class WhitelistRepositoryTest {

    private lateinit var whitelistEntryDao: WhitelistEntryDao
    private lateinit var scannedDeviceDao: ScannedDeviceDao
    private lateinit var repository: WhitelistRepository

    private val testDevice1 = ScannedDevice(
        id = 1L,
        address = "AA:BB:CC:DD:EE:FF",
        name = "Test Device 1",
        firstSeen = 1000L,
        lastSeen = 2000L
    )

    private val testDevice2 = ScannedDevice(
        id = 2L,
        address = "11:22:33:44:55:66",
        name = "Test Device 2",
        firstSeen = 1500L,
        lastSeen = 2500L
    )

    private val testEntry1 = WhitelistEntry(
        id = 1L,
        deviceId = 1L,
        label = "My Phone",
        category = WhitelistRepository.Category.OWN,
        notes = "Personal device"
    )

    private val testEntry2 = WhitelistEntry(
        id = 2L,
        deviceId = 2L,
        label = "Partner's Watch",
        category = WhitelistRepository.Category.PARTNER,
        notes = null
    )

    @Before
    fun setup() {
        whitelistEntryDao = mockk(relaxed = true)
        scannedDeviceDao = mockk(relaxed = true)
        repository = WhitelistRepositoryImpl(whitelistEntryDao, scannedDeviceDao)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== CRUD Operations Tests ==========

    @Test
    fun `addToWhitelist should insert entry and return ID`() = runTest {
        // Given
        val deviceId = 1L
        val label = "My Device"
        val category = WhitelistRepository.Category.OWN
        val notes = "Test notes"
        val expectedId = 123L

        coEvery { whitelistEntryDao.insert(any()) } returns expectedId

        // When
        val result = repository.addToWhitelist(
            deviceId = deviceId,
            label = label,
            category = category,
            notes = notes,
            addedViaLearnMode = false
        )

        // Then
        assertEquals(expectedId, result)
        coVerify { whitelistEntryDao.insert(any()) }
    }

    @Test
    fun `removeFromWhitelist should delete entry by ID`() = runTest {
        // Given
        val entryId = 1L
        coEvery { whitelistEntryDao.getById(entryId) } returns testEntry1

        // When
        repository.removeFromWhitelist(entryId)

        // Then
        coVerify { whitelistEntryDao.delete(testEntry1) }
    }

    @Test
    fun `updateEntry should update existing entry`() = runTest {
        // Given
        val updatedEntry = testEntry1.copy(
            label = "Updated Label",
            category = WhitelistRepository.Category.TRUSTED,
            notes = "Updated notes"
        )

        // When
        repository.updateEntry(updatedEntry)

        // Then
        coVerify {
            whitelistEntryDao.update(match { entry ->
                entry.label == "Updated Label" &&
                        entry.category == WhitelistRepository.Category.TRUSTED &&
                        entry.notes == "Updated notes"
            })
        }
    }

    @Test
    fun `getEntryById should return entry when exists`() = runTest {
        // Given
        val entryId = 1L
        coEvery { whitelistEntryDao.getById(entryId) } returns testEntry1

        // When
        val result = repository.getEntryById(entryId)

        // Then
        assertEquals(testEntry1, result)
    }

    @Test
    fun `getEntryById should return null when not exists`() = runTest {
        // Given
        val entryId = 999L
        coEvery { whitelistEntryDao.getById(entryId) } returns null

        // When
        val result = repository.getEntryById(entryId)

        // Then
        assertNull(result)
    }

    // ========== Bulk Operations Tests ==========

    @Test
    fun `addMultipleToWhitelist should insert all entries`() = runTest {
        // Given
        val entries = listOf(testEntry1, testEntry2)
        val expectedIds = listOf(1L, 2L)

        coEvery { whitelistEntryDao.insertAll(entries) } returns expectedIds

        // When
        val result = repository.addMultipleToWhitelist(entries)

        // Then
        assertEquals(expectedIds, result)
        coVerify { whitelistEntryDao.insertAll(entries) }
    }

    @Test
    fun `clearWhitelist should delete all entries`() = runTest {
        // When
        repository.clearWhitelist()

        // Then
        coVerify { whitelistEntryDao.deleteAll() }
    }

    // ========== Query Tests ==========

    @Test
    fun `isDeviceWhitelisted should return true when device is whitelisted`() = runTest {
        // Given
        val deviceId = 1L
        coEvery { whitelistEntryDao.isDeviceWhitelisted(deviceId) } returns true

        // When
        val result = repository.isDeviceWhitelisted(deviceId)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isDeviceWhitelisted should return false when device is not whitelisted`() = runTest {
        // Given
        val deviceId = 999L
        coEvery { whitelistEntryDao.isDeviceWhitelisted(deviceId) } returns false

        // When
        val result = repository.isDeviceWhitelisted(deviceId)

        // Then
        assertFalse(result)
    }

    @Test
    fun `getAllWhitelistEntries should return all entries`() = runTest {
        // Given
        val entries = listOf(testEntry1, testEntry2)
        every { whitelistEntryDao.getAllEntries() } returns flowOf(entries)

        // When
        val result = repository.getAllWhitelistEntries().first()

        // Then
        assertEquals(entries, result)
    }

    @Test
    fun `getAllEntriesWithDevices should combine entries with devices`() = runTest {
        // Given
        val entries = listOf(testEntry1, testEntry2)
        val devices = listOf(testDevice1, testDevice2)

        every { whitelistEntryDao.getAllEntries() } returns flowOf(entries)
        every { scannedDeviceDao.getAllDevices() } returns flowOf(devices)

        // When
        val result = repository.getAllEntriesWithDevices().first()

        // Then
        assertEquals(2, result.size)
        assertEquals(testEntry1, result[0].entry)
        assertEquals(testDevice1, result[0].device)
        assertEquals(testEntry2, result[1].entry)
        assertEquals(testDevice2, result[1].device)
    }

    @Test
    fun `getEntriesByCategory should filter by category`() = runTest {
        // Given
        val category = WhitelistRepository.Category.OWN
        val entries = listOf(testEntry1)

        every { whitelistEntryDao.getEntriesByCategory(category) } returns flowOf(entries)

        // When
        val result = repository.getEntriesByCategory(category).first()

        // Then
        assertEquals(entries, result)
        verify { whitelistEntryDao.getEntriesByCategory(category) }
    }

    @Test
    fun `getEntriesWithDevicesByCategory should filter by category with devices`() = runTest {
        // Given
        val category = WhitelistRepository.Category.OWN
        val entries = listOf(testEntry1)
        val devices = listOf(testDevice1)

        every { whitelistEntryDao.getEntriesByCategory(category) } returns flowOf(entries)
        every { scannedDeviceDao.getAllDevices() } returns flowOf(devices)

        // When
        val result = repository.getEntriesWithDevicesByCategory(category).first()

        // Then
        assertEquals(1, result.size)
        assertEquals(testEntry1, result[0].entry)
        assertEquals(testDevice1, result[0].device)
    }

    @Test
    fun `searchEntries should return matching entries`() = runTest {
        // Given
        val query = "Phone"
        val entries = listOf(testEntry1)

        every { whitelistEntryDao.searchEntries(query) } returns flowOf(entries)

        // When
        val result = repository.searchEntries(query).first()

        // Then
        assertEquals(entries, result)
        verify { whitelistEntryDao.searchEntries(query) }
    }

    @Test
    fun `searchEntriesWithDevices should return matching entries with devices`() = runTest {
        // Given
        val query = "Phone"
        val entries = listOf(testEntry1)
        val devices = listOf(testDevice1)

        every { whitelistEntryDao.searchEntries(query) } returns flowOf(entries)
        every { scannedDeviceDao.getAllDevices() } returns flowOf(devices)

        // When
        val result = repository.searchEntriesWithDevices(query).first()

        // Then
        assertEquals(1, result.size)
        assertEquals(testEntry1, result[0].entry)
        assertEquals(testDevice1, result[0].device)
    }

    @Test
    fun `getAllWhitelistedDeviceIds should return device IDs`() = runTest {
        // Given
        val deviceIds = listOf(1L, 2L, 3L)
        every { whitelistEntryDao.getAllWhitelistedDeviceIds() } returns flowOf(deviceIds)

        // When
        val result = repository.getAllWhitelistedDeviceIds().first()

        // Then
        assertEquals(deviceIds, result)
    }

    @Test
    fun `getWhitelistCount should return total count`() = runTest {
        // Given
        val count = 5
        every { whitelistEntryDao.getWhitelistCount() } returns flowOf(count)

        // When
        val result = repository.getWhitelistCount().first()

        // Then
        assertEquals(count, result)
    }

    @Test
    fun `getCountByCategory should return category count`() = runTest {
        // Given
        val category = WhitelistRepository.Category.OWN
        val count = 3
        every { whitelistEntryDao.getCountByCategory(category) } returns flowOf(count)

        // When
        val result = repository.getCountByCategory(category).first()

        // Then
        assertEquals(count, result)
    }

    @Test
    fun `getLearnModeEntries should return Learn Mode entries`() = runTest {
        // Given
        val learnModeEntry = testEntry1.copy(addedViaLearnMode = true)
        val entries = listOf(learnModeEntry)

        every { whitelistEntryDao.getLearnModeEntries() } returns flowOf(entries)

        // When
        val result = repository.getLearnModeEntries().first()

        // Then
        assertEquals(entries, result)
    }

    @Test
    fun `getManualEntries should return manually added entries`() = runTest {
        // Given
        val entries = listOf(testEntry1, testEntry2)

        every { whitelistEntryDao.getManualEntries() } returns flowOf(entries)

        // When
        val result = repository.getManualEntries().first()

        // Then
        assertEquals(entries, result)
    }

    // ========== Edge Cases Tests ==========

    @Test
    fun `getAllEntriesWithDevices should handle missing devices gracefully`() = runTest {
        // Given
        val entries = listOf(testEntry1, testEntry2)
        val devices = listOf(testDevice1) // Only device 1, device 2 is missing

        every { whitelistEntryDao.getAllEntries() } returns flowOf(entries)
        every { scannedDeviceDao.getAllDevices() } returns flowOf(devices)

        // When
        val result = repository.getAllEntriesWithDevices().first()

        // Then
        // Should only return entries with matching devices
        assertEquals(1, result.size)
        assertEquals(testEntry1, result[0].entry)
        assertEquals(testDevice1, result[0].device)
    }

    @Test
    fun `removeFromWhitelist should do nothing when entry does not exist`() = runTest {
        // Given
        val entryId = 999L
        coEvery { whitelistEntryDao.getById(entryId) } returns null

        // When
        repository.removeFromWhitelist(entryId)

        // Then
        coVerify(exactly = 0) { whitelistEntryDao.delete(any()) }
    }

    @Test
    fun `updateEntry should update entry in DAO`() = runTest {
        // Given
        val entryToUpdate = testEntry1.copy(
            label = "New Label",
            category = WhitelistRepository.Category.OWN,
            notes = null
        )

        // When
        repository.updateEntry(entryToUpdate)

        // Then
        coVerify { whitelistEntryDao.update(entryToUpdate) }
    }
}
