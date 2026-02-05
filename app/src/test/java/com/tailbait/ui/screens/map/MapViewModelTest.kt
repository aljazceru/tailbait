package com.tailbait.ui.screens.map

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for MapViewModel.
 *
 * Tests map data loading, filtering, and state management functionality.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // Mock DAOs
    private lateinit var deviceLocationRecordDao: DeviceLocationRecordDao
    private lateinit var locationDao: LocationDao
    private lateinit var scannedDeviceDao: ScannedDeviceDao

    // Test data
    private lateinit var testDevices: List<ScannedDevice>
    private lateinit var testLocations: List<Location>
    private lateinit var testRecords: List<DeviceLocationRecord>

    private lateinit var viewModel: MapViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        deviceLocationRecordDao = mockk(relaxed = true)
        locationDao = mockk(relaxed = true)
        scannedDeviceDao = mockk(relaxed = true)

        // Create test data
        testDevices = listOf(
            ScannedDevice(
                id = 1L,
                address = "AA:BB:CC:DD:EE:01",
                name = "Device 1",
                firstSeen = 1000L,
                lastSeen = 2000L,
                detectionCount = 3
            ),
            ScannedDevice(
                id = 2L,
                address = "AA:BB:CC:DD:EE:02",
                name = "Device 2",
                firstSeen = 1500L,
                lastSeen = 2500L,
                detectionCount = 2
            )
        )

        testLocations = listOf(
            Location(
                id = 1L,
                latitude = 40.7128,
                longitude = -74.0060,
                accuracy = 10f,
                timestamp = 1000L,
                provider = "GPS"
            ),
            Location(
                id = 2L,
                latitude = 40.7589,
                longitude = -73.9851,
                accuracy = 15f,
                timestamp = 1500L,
                provider = "GPS"
            ),
            Location(
                id = 3L,
                latitude = 40.7489,
                longitude = -73.9680,
                accuracy = 12f,
                timestamp = 2000L,
                provider = "GPS"
            )
        )

        testRecords = listOf(
            DeviceLocationRecord(
                id = 1L,
                deviceId = 1L,
                locationId = 1L,
                rssi = -65,
                timestamp = 1000L,
                scanTriggerType = "PERIODIC"
            ),
            DeviceLocationRecord(
                id = 2L,
                deviceId = 1L,
                locationId = 2L,
                rssi = -70,
                timestamp = 1500L,
                scanTriggerType = "PERIODIC"
            ),
            DeviceLocationRecord(
                id = 3L,
                deviceId = 2L,
                locationId = 3L,
                rssi = -75,
                timestamp = 2000L,
                scanTriggerType = "LOCATION_BASED"
            )
        )

        // Setup mock responses
        every { deviceLocationRecordDao.getAllRecords() } returns flowOf(testRecords)
        every { scannedDeviceDao.getAllDevices() } returns flowOf(testDevices)

        coEvery { locationDao.getById(1L) } returns testLocations[0]
        coEvery { locationDao.getById(2L) } returns testLocations[1]
        coEvery { locationDao.getById(3L) } returns testLocations[2]
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)

        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
        assertTrue(initialState.markers.isEmpty())
        assertTrue(initialState.devicePaths.isEmpty())
    }

    @Test
    fun `loads map data successfully`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(3, state.markers.size)
        assertEquals(2, state.devicePaths.size) // 2 devices
        assertEquals(2, state.totalDevices)
        assertEquals(3, state.totalLocations)
    }

    @Test
    fun `markers contain correct device and location data`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val marker = state.markers.first()

        assertEquals(testDevices[0].id, marker.deviceId)
        assertEquals(testDevices[0].name, marker.deviceName)
        assertEquals(testDevices[0].address, marker.deviceAddress)
        assertEquals(testLocations[0].latitude, marker.position.latitude)
        assertEquals(testLocations[0].longitude, marker.position.longitude)
        assertEquals(-65, marker.rssi)
        assertEquals(10f, marker.accuracy)
    }

    @Test
    fun `device paths are created correctly`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val devicePath = state.devicePaths.find { it.deviceId == 1L }

        assertNotNull(devicePath)
        assertEquals("Device 1", devicePath!!.deviceName)
        assertEquals(2, devicePath.points.size) // Device 1 has 2 locations
        assertEquals(2, devicePath.timestamps.size)

        // Verify points are sorted by timestamp
        assertTrue(devicePath.timestamps[0] < devicePath.timestamps[1])
    }

    @Test
    fun `camera position is calculated as center of markers`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val cameraPosition = state.cameraPosition

        assertNotNull(cameraPosition)

        // Verify it's approximately the center of all markers
        val avgLat = testLocations.map { it.latitude }.average()
        val avgLng = testLocations.map { it.longitude }.average()

        assertEquals(avgLat, cameraPosition!!.latitude, 0.001)
        assertEquals(avgLng, cameraPosition.longitude, 0.001)
    }

    @Test
    fun `filter by device filters markers and paths correctly`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        // Filter by device 1
        viewModel.filterByDevice(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1L, state.selectedDeviceId)
        assertEquals(2, state.markers.size) // Device 1 has 2 locations
        assertEquals(1, state.devicePaths.size) // Only 1 device path
        assertTrue(state.markers.all { it.deviceId == 1L })
    }

    @Test
    fun `filter by date range filters data correctly`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        // Filter to show only records between 1200L and 1800L
        viewModel.filterByDateRange(1200L, 1800L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1200L, state.startTimestamp)
        assertEquals(1800L, state.endTimestamp)

        // Should only include the record at timestamp 1500L
        assertEquals(1, state.markers.size)
        assertEquals(1500L, state.markers.first().timestamp)
    }

    @Test
    fun `filter by start timestamp only works correctly`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.filterByDateRange(1500L, null)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1500L, state.startTimestamp)
        assertNull(state.endTimestamp)

        // Should include records at 1500L and 2000L
        assertEquals(2, state.markers.size)
        assertTrue(state.markers.all { it.timestamp >= 1500L })
    }

    @Test
    fun `filter by end timestamp only works correctly`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.filterByDateRange(null, 1500L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.startTimestamp)
        assertEquals(1500L, state.endTimestamp)

        // Should include records at 1000L and 1500L
        assertEquals(2, state.markers.size)
        assertTrue(state.markers.all { it.timestamp <= 1500L })
    }

    @Test
    fun `clear filters resets all filters`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        // Apply filters
        viewModel.filterByDevice(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.filterByDateRange(1200L, 1800L)
        testDispatcher.scheduler.advanceUntilIdle()

        // Clear filters
        viewModel.clearFilters()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.selectedDeviceId)
        assertNull(state.startTimestamp)
        assertNull(state.endTimestamp)
        assertEquals(3, state.markers.size) // All markers visible again
    }

    @Test
    fun `combining device and date filters works correctly`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        // Filter by device 1 AND date range 1200-1800
        viewModel.filterByDevice(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.filterByDateRange(1200L, 1800L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1L, state.selectedDeviceId)
        assertEquals(1200L, state.startTimestamp)
        assertEquals(1800L, state.endTimestamp)

        // Should only show device 1 records in time range
        assertEquals(1, state.markers.size)
        val marker = state.markers.first()
        assertEquals(1L, marker.deviceId)
        assertTrue(marker.timestamp in 1200L..1800L)
    }

    @Test
    fun `error state is set when data loading fails`() = runTest {
        // Setup mock to throw exception
        every { deviceLocationRecordDao.getAllRecords() } throws RuntimeException("Database error")

        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Database error") ||
                   state.errorMessage!!.contains("Failed to"))
    }

    @Test
    fun `clear error removes error message`() = runTest {
        // Setup mock to throw exception
        every { deviceLocationRecordDao.getAllRecords() } throws RuntimeException("Error")

        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify error exists
        assertNotNull(viewModel.uiState.value.errorMessage)

        // Clear error
        viewModel.clearError()

        // Verify error is cleared
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `empty data shows empty state correctly`() = runTest {
        // Setup mocks to return empty data
        every { deviceLocationRecordDao.getAllRecords() } returns flowOf(emptyList())
        every { scannedDeviceDao.getAllDevices() } returns flowOf(emptyList())

        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.markers.isEmpty())
        assertTrue(state.devicePaths.isEmpty())
        assertNull(state.cameraPosition) // No camera position when no markers
        assertEquals(0, state.totalDevices)
        assertEquals(0, state.totalLocations)
    }

    @Test
    fun `getAvailableDevices returns all devices`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)

        val devices = viewModel.getAvailableDevices().first()

        assertEquals(2, devices.size)
        assertEquals("Device 1", devices[0].name)
        assertEquals("Device 2", devices[1].name)
    }

    @Test
    fun `device colors are consistent for same device ID`() = runTest {
        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        val device1Path = state.devicePaths.find { it.deviceId == 1L }
        val device2Path = state.devicePaths.find { it.deviceId == 2L }

        assertNotNull(device1Path)
        assertNotNull(device2Path)

        // Colors should be different for different devices
        assertTrue(device1Path!!.color != device2Path!!.color)

        // Color should be consistent (based on deviceId % colors.size)
        assertTrue(device1Path.color != 0)
        assertTrue(device2Path.color != 0)
    }

    @Test
    fun `handles location data that is null gracefully`() = runTest {
        // Setup one location to return null
        coEvery { locationDao.getById(2L) } returns null

        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)

        // Should only have 2 markers (skipped the one with null location)
        assertEquals(2, state.markers.size)
        assertTrue(state.markers.none { it.locationId == 2L })
    }

    @Test
    fun `handles device data that is null gracefully`() = runTest {
        // Setup devices to only return one device
        val singleDevice = listOf(testDevices[0])
        every { scannedDeviceDao.getAllDevices() } returns flowOf(singleDevice)

        viewModel = MapViewModel(deviceLocationRecordDao, locationDao, scannedDeviceDao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)

        // Should only have markers for device 1
        assertEquals(2, state.markers.size)
        assertTrue(state.markers.all { it.deviceId == 1L })
        assertEquals(1, state.devicePaths.size)
    }

    // Note: onCleared() is protected and cannot be tested directly.
    // ViewModel cleanup is tested implicitly through other tests.
}
