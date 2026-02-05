package com.tailbait.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.entities.ScannedDevice
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for DeviceRepository and DeviceRepositoryImpl.
 *
 * Tests device data management, upsert logic, and device-location correlation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // Mock DAOs
    private lateinit var scannedDeviceDao: ScannedDeviceDao
    private lateinit var deviceLocationRecordDao: DeviceLocationRecordDao

    private lateinit var repository: DeviceRepository

    // Test data
    private val testAddress = "AA:BB:CC:DD:EE:01"
    private val testDevice = ScannedDevice(
        id = 1L,
        address = testAddress,
        name = "Test Device",
        firstSeen = 1000L,
        lastSeen = 2000L,
        detectionCount = 1,
        manufacturerData = "0102030405"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        scannedDeviceDao = mockk(relaxed = true)
        deviceLocationRecordDao = mockk(relaxed = true)

        // Create repository
        repository = DeviceRepositoryImpl(scannedDeviceDao, deviceLocationRecordDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== Upsert Device Tests ====================

    @Test
    fun `upsertDevice inserts new device when not exists`() = runTest {
        // Setup mock - device doesn't exist
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
        coEvery { scannedDeviceDao.insert(any()) } returns 1L

        // Execute
        val result = repository.upsertDevice(
            address = testAddress,
            name = "New Device",
            lastSeen = 1000L,
            manufacturerData = null
        )

        // Verify
        assertEquals(1L, result)
        coVerify {
            scannedDeviceDao.insert(
                match {
                    it.address == testAddress &&
                    it.name == "New Device" &&
                    it.firstSeen == 1000L &&
                    it.lastSeen == 1000L &&
                    it.detectionCount == 1
                }
            )
        }
    }

    @Test
    fun `upsertDevice updates existing device when exists`() = runTest {
        // Setup mock - device exists
        val existingDevice = testDevice.copy(detectionCount = 5)
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns existingDevice
        coEvery { scannedDeviceDao.update(any()) } just Runs

        // Execute - update with new lastSeen and increment count
        val result = repository.upsertDevice(
            address = testAddress,
            name = "Updated Device",
            lastSeen = 3000L,
            manufacturerData = null
        )

        // Verify
        assertEquals(existingDevice.id, result)
        coVerify {
            scannedDeviceDao.update(
                match {
                    it.id == existingDevice.id &&
                    it.address == testAddress &&
                    it.lastSeen == 3000L &&
                    it.detectionCount == 6 // Incremented from 5 to 6
                }
            )
        }
    }

    @Test
    fun `upsertDevice preserves original name when new name is null`() = runTest {
        // Setup mock - device exists with a name
        val existingDevice = testDevice.copy(name = "Original Name")
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns existingDevice
        coEvery { scannedDeviceDao.update(any()) } just Runs

        // Execute - update without providing new name
        repository.upsertDevice(
            address = testAddress,
            name = null,
            lastSeen = 3000L,
            manufacturerData = null
        )

        // Verify original name is preserved
        coVerify {
            scannedDeviceDao.update(
                match { it.name == "Original Name" }
            )
        }
    }

    @Test
    fun `upsertDevice replaces name when new name is provided`() = runTest {
        // Setup mock - device exists
        val existingDevice = testDevice.copy(name = "Old Name")
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns existingDevice
        coEvery { scannedDeviceDao.update(any()) } just Runs

        // Execute - update with new name
        repository.upsertDevice(
            address = testAddress,
            name = "New Name",
            lastSeen = 3000L,
            manufacturerData = null
        )

        // Verify name is replaced
        coVerify {
            scannedDeviceDao.update(
                match { it.name == "New Name" }
            )
        }
    }

    @Test
    fun `upsertDevice converts manufacturer data to hex string`() = runTest {
        // Setup mock - device doesn't exist
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
        coEvery { scannedDeviceDao.insert(any()) } returns 1L

        // Execute with byte array manufacturer data
        val manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        repository.upsertDevice(
            address = testAddress,
            name = "Test",
            lastSeen = 1000L,
            manufacturerData = manufacturerData
        )

        // Verify manufacturer data is converted to hex string
        coVerify {
            scannedDeviceDao.insert(
                match { it.manufacturerData == "010203ff" }
            )
        }
    }

    // ==================== Insert Device Location Record Tests ====================

    @Test
    fun `insertDeviceLocationRecord creates record successfully`() = runTest {
        // Setup mock
        coEvery { deviceLocationRecordDao.insert(any()) } returns 1L

        // Execute
        val result = repository.insertDeviceLocationRecord(
            deviceId = 1L,
            locationId = 2L,
            rssi = -65,
            timestamp = 1000L,
            locationChanged = true,
            distanceFromLast = 150.0,
            scanTriggerType = "PERIODIC"
        )

        // Verify
        assertEquals(1L, result)
        coVerify {
            deviceLocationRecordDao.insert(
                match {
                    it.deviceId == 1L &&
                    it.locationId == 2L &&
                    it.rssi == -65 &&
                    it.timestamp == 1000L &&
                    it.locationChanged &&
                    it.distanceFromLast == 150.0 &&
                    it.scanTriggerType == "PERIODIC"
                }
            )
        }
    }

    @Test
    fun `insertDeviceLocationRecord handles null distanceFromLast`() = runTest {
        // Setup mock
        coEvery { deviceLocationRecordDao.insert(any()) } returns 1L

        // Execute with null distance
        repository.insertDeviceLocationRecord(
            deviceId = 1L,
            locationId = 2L,
            rssi = -70,
            timestamp = 1000L,
            locationChanged = false,
            distanceFromLast = null,
            scanTriggerType = "MANUAL"
        )

        // Verify
        coVerify {
            deviceLocationRecordDao.insert(
                match {
                    it.distanceFromLast == null &&
                    !it.locationChanged
                }
            )
        }
    }

    // ==================== Get Device Tests ====================

    @Test
    fun `getDeviceByAddress returns device when exists`() = runTest {
        // Setup mock
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns testDevice

        // Execute
        val result = repository.getDeviceByAddress(testAddress)

        // Verify
        assertNotNull(result)
        assertEquals(testDevice.address, result!!.address)
        assertEquals(testDevice.name, result.name)
    }

    @Test
    fun `getDeviceByAddress returns null when not exists`() = runTest {
        // Setup mock
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null

        // Execute
        val result = repository.getDeviceByAddress(testAddress)

        // Verify
        assertNull(result)
    }

    // ==================== Get All Devices Tests ====================

    @Test
    fun `getAllDevices returns all devices`() = runTest {
        // Setup mock
        val devices = listOf(
            testDevice,
            testDevice.copy(id = 2L, address = "AA:BB:CC:DD:EE:02", name = "Device 2")
        )
        every { scannedDeviceDao.getAllDevices() } returns flowOf(devices)

        // Execute
        val result = repository.getAllDevices().first()

        // Verify
        assertEquals(2, result.size)
        assertEquals("Test Device", result[0].name)
        assertEquals("Device 2", result[1].name)
    }

    @Test
    fun `getAllDevices returns empty list when no devices`() = runTest {
        // Setup mock
        every { scannedDeviceDao.getAllDevices() } returns flowOf(emptyList())

        // Execute
        val result = repository.getAllDevices().first()

        // Verify
        assertEquals(0, result.size)
    }

    // ==================== Get Suspicious Devices Tests ====================

    @Test
    fun `getSuspiciousDevices filters by location count`() = runTest {
        // Setup mock
        val suspiciousDevices = listOf(
            testDevice.copy(id = 1L, name = "Suspicious Device 1"),
            testDevice.copy(id = 2L, name = "Suspicious Device 2")
        )
        every { scannedDeviceDao.getSuspiciousDevices(3) } returns flowOf(suspiciousDevices)

        // Execute
        val result = repository.getSuspiciousDevices(3).first()

        // Verify
        assertEquals(2, result.size)
        coVerify { scannedDeviceDao.getSuspiciousDevices(3) }
    }

    @Test
    fun `getSuspiciousDevices returns empty list when none found`() = runTest {
        // Setup mock
        every { scannedDeviceDao.getSuspiciousDevices(5) } returns flowOf(emptyList())

        // Execute
        val result = repository.getSuspiciousDevices(5).first()

        // Verify
        assertEquals(0, result.size)
    }

    // ==================== Get Distinct Location Count Tests ====================

    @Test
    fun `getDistinctLocationCountForDevice returns correct count`() = runTest {
        // Setup mock
        every { deviceLocationRecordDao.getDistinctLocationCountForDevice(1L) } returns flowOf(5)

        // Execute
        val result = repository.getDistinctLocationCountForDevice(1L).first()

        // Verify
        assertEquals(5, result)
    }

    @Test
    fun `getDistinctLocationCountForDevice returns zero when no locations`() = runTest {
        // Setup mock
        every { deviceLocationRecordDao.getDistinctLocationCountForDevice(99L) } returns flowOf(0)

        // Execute
        val result = repository.getDistinctLocationCountForDevice(99L).first()

        // Verify
        assertEquals(0, result)
    }

    // ==================== Delete Old Devices Tests ====================

    @Test
    fun `deleteOldDevices removes devices before timestamp`() = runTest {
        // Setup mock
        coEvery { scannedDeviceDao.deleteOldDevices(5000L) } returns 3

        // Execute
        val result = repository.deleteOldDevices(5000L)

        // Verify
        assertEquals(3, result)
        coVerify { scannedDeviceDao.deleteOldDevices(5000L) }
    }

    @Test
    fun `deleteOldDevices returns zero when no devices to delete`() = runTest {
        // Setup mock
        coEvery { scannedDeviceDao.deleteOldDevices(1000L) } returns 0

        // Execute
        val result = repository.deleteOldDevices(1000L)

        // Verify
        assertEquals(0, result)
    }

    // ==================== Delete All Devices Tests ====================

    @Test
    fun `deleteAllDevices removes all devices and location records`() = runTest {
        // Setup mocks
        coEvery { scannedDeviceDao.deleteAll() } just Runs
        coEvery { deviceLocationRecordDao.deleteAll() } just Runs

        // Execute
        repository.deleteAllDevices()

        // Verify both DAOs were called
        coVerify { scannedDeviceDao.deleteAll() }
        coVerify { deviceLocationRecordDao.deleteAll() }
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `upsertDevice handles empty address`() = runTest {
        // Setup mock
        coEvery { scannedDeviceDao.getByAddress("") } returns null
        coEvery { scannedDeviceDao.insert(any()) } returns 1L

        // Execute
        val result = repository.upsertDevice(
            address = "",
            name = "Test",
            lastSeen = 1000L,
            manufacturerData = null
        )

        // Verify
        assertEquals(1L, result)
        coVerify { scannedDeviceDao.insert(match { it.address == "" }) }
    }

    @Test
    fun `upsertDevice handles very long device name`() = runTest {
        // Setup mock
        val longName = "A".repeat(1000)
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
        coEvery { scannedDeviceDao.insert(any()) } returns 1L

        // Execute
        repository.upsertDevice(
            address = testAddress,
            name = longName,
            lastSeen = 1000L,
            manufacturerData = null
        )

        // Verify
        coVerify { scannedDeviceDao.insert(match { it.name == longName }) }
    }

    @Test
    fun `insertDeviceLocationRecord handles negative RSSI values`() = runTest {
        // Setup mock
        coEvery { deviceLocationRecordDao.insert(any()) } returns 1L

        // Execute with very weak signal
        repository.insertDeviceLocationRecord(
            deviceId = 1L,
            locationId = 2L,
            rssi = -120, // Very weak signal
            timestamp = 1000L,
            locationChanged = true,
            distanceFromLast = 50.0,
            scanTriggerType = "CONTINUOUS"
        )

        // Verify
        coVerify {
            deviceLocationRecordDao.insert(
                match { it.rssi == -120 }
            )
        }
    }

    @Test
    fun `upsertDevice handles empty manufacturer data byte array`() = runTest {
        // Setup mock
        coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
        coEvery { scannedDeviceDao.insert(any()) } returns 1L

        // Execute with empty byte array
        repository.upsertDevice(
            address = testAddress,
            name = "Test",
            lastSeen = 1000L,
            manufacturerData = byteArrayOf() // Empty array
        )

        // Verify manufacturer data is empty string
        coVerify {
            scannedDeviceDao.insert(
                match { it.manufacturerData == "" }
            )
        }
    }
}
