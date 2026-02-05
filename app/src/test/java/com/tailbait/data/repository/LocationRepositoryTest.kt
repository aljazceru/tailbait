package com.tailbait.data.repository

import android.content.Context
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.entities.Location
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LocationRepository.
 *
 * Tests cover:
 * - Location insertion
 * - Location retrieval
 * - Location queries
 * - Location deletion
 * - Permission handling
 */
class LocationRepositoryTest {

    private lateinit var context: Context
    private lateinit var locationDao: LocationDao
    private lateinit var repository: LocationRepository

    private val testLocation1 = Location(
        id = 1L,
        latitude = 37.7749,
        longitude = -122.4194,
        accuracy = 10.0f,
        altitude = 50.0,
        timestamp = 1000L,
        provider = "FUSED"
    )

    private val testLocation2 = Location(
        id = 2L,
        latitude = 37.7750,
        longitude = -122.4195,
        accuracy = 15.0f,
        altitude = 55.0,
        timestamp = 2000L,
        provider = "GPS"
    )

    private val testLocation3 = Location(
        id = 3L,
        latitude = 37.7751,
        longitude = -122.4196,
        accuracy = 20.0f,
        altitude = null,
        timestamp = 3000L,
        provider = "NETWORK"
    )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        locationDao = mockk(relaxed = true)

        // Setup context mock
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.tailbait.test"

        repository = LocationRepositoryImpl(context, locationDao)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Insert Operations Tests ==========

    @Test
    fun `insertLocation should insert location and return ID`() = runTest {
        // Given
        val expectedId = 123L
        coEvery { locationDao.insert(testLocation1) } returns expectedId

        // When
        val result = repository.insertLocation(testLocation1)

        // Then
        assertEquals(expectedId, result)
        coVerify { locationDao.insert(testLocation1) }
    }

    @Test
    fun `insertLocation should handle locations without altitude`() = runTest {
        // Given
        val locationWithoutAltitude = testLocation3.copy(altitude = null)
        val expectedId = 456L
        coEvery { locationDao.insert(locationWithoutAltitude) } returns expectedId

        // When
        val result = repository.insertLocation(locationWithoutAltitude)

        // Then
        assertEquals(expectedId, result)
        coVerify { locationDao.insert(locationWithoutAltitude) }
    }

    // ========== Query Operations Tests ==========

    @Test
    fun `getLastLocation should return most recent location`() = runTest {
        // Given
        coEvery { locationDao.getLastLocation() } returns testLocation3

        // When
        val result = repository.getLastLocation()

        // Then
        assertEquals(testLocation3, result)
        coVerify { locationDao.getLastLocation() }
    }

    @Test
    fun `getLastLocation should return null when no locations exist`() = runTest {
        // Given
        coEvery { locationDao.getLastLocation() } returns null

        // When
        val result = repository.getLastLocation()

        // Then
        assertNull(result)
    }

    @Test
    fun `getAllLocations should return all locations`() = runTest {
        // Given
        val locations = listOf(testLocation1, testLocation2, testLocation3)
        every { locationDao.getAllLocations() } returns flowOf(locations)

        // When
        val result = repository.getAllLocations().first()

        // Then
        assertEquals(locations, result)
    }

    @Test
    fun `getAllLocations should return empty list when no locations exist`() = runTest {
        // Given
        every { locationDao.getAllLocations() } returns flowOf(emptyList())

        // When
        val result = repository.getAllLocations().first()

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getLocationsForDevice should return device-specific locations`() = runTest {
        // Given
        val deviceId = 42L
        val deviceLocations = listOf(testLocation1, testLocation2)
        every { locationDao.getLocationsForDevice(deviceId) } returns flowOf(deviceLocations)

        // When
        val result = repository.getLocationsForDevice(deviceId).first()

        // Then
        assertEquals(deviceLocations, result)
        verify { locationDao.getLocationsForDevice(deviceId) }
    }

    @Test
    fun `getLocationsForDevice should return empty list when device has no locations`() = runTest {
        // Given
        val deviceId = 999L
        every { locationDao.getLocationsForDevice(deviceId) } returns flowOf(emptyList())

        // When
        val result = repository.getLocationsForDevice(deviceId).first()

        // Then
        assertTrue(result.isEmpty())
    }

    // ========== Delete Operations Tests ==========

    @Test
    fun `deleteOldLocations should delete locations before timestamp`() = runTest {
        // Given
        val cutoffTimestamp = 2500L
        val deletedCount = 2
        coEvery { locationDao.deleteOldLocations(cutoffTimestamp) } returns deletedCount

        // When
        val result = repository.deleteOldLocations(cutoffTimestamp)

        // Then
        assertEquals(deletedCount, result)
        coVerify { locationDao.deleteOldLocations(cutoffTimestamp) }
    }

    @Test
    fun `deleteOldLocations should return 0 when no old locations exist`() = runTest {
        // Given
        val cutoffTimestamp = 500L
        coEvery { locationDao.deleteOldLocations(cutoffTimestamp) } returns 0

        // When
        val result = repository.deleteOldLocations(cutoffTimestamp)

        // Then
        assertEquals(0, result)
    }

    @Test
    fun `deleteOldLocations should handle large timestamp values`() = runTest {
        // Given
        val cutoffTimestamp = System.currentTimeMillis() + 1000000L
        val deletedCount = 100
        coEvery { locationDao.deleteOldLocations(cutoffTimestamp) } returns deletedCount

        // When
        val result = repository.deleteOldLocations(cutoffTimestamp)

        // Then
        assertEquals(deletedCount, result)
    }

    // ========== Edge Cases Tests ==========

    @Test
    fun `insertLocation should handle location with very high accuracy value`() = runTest {
        // Given
        val locationWithHighAccuracy = testLocation1.copy(accuracy = 10000.0f)
        val expectedId = 789L
        coEvery { locationDao.insert(locationWithHighAccuracy) } returns expectedId

        // When
        val result = repository.insertLocation(locationWithHighAccuracy)

        // Then
        assertEquals(expectedId, result)
    }

    @Test
    fun `insertLocation should handle location with negative altitude`() = runTest {
        // Given (below sea level)
        val locationBelowSeaLevel = testLocation1.copy(altitude = -50.0)
        val expectedId = 999L
        coEvery { locationDao.insert(locationBelowSeaLevel) } returns expectedId

        // When
        val result = repository.insertLocation(locationBelowSeaLevel)

        // Then
        assertEquals(expectedId, result)
    }

    @Test
    fun `insertLocation should handle extreme coordinates`() = runTest {
        // Given (testing boundary conditions)
        val extremeLocation = testLocation1.copy(
            latitude = 90.0,  // North pole
            longitude = 180.0  // International date line
        )
        val expectedId = 111L
        coEvery { locationDao.insert(extremeLocation) } returns expectedId

        // When
        val result = repository.insertLocation(extremeLocation)

        // Then
        assertEquals(expectedId, result)
    }

    @Test
    fun `getAllLocations should handle very large location lists`() = runTest {
        // Given
        val largeLocationList = List(1000) { index ->
            testLocation1.copy(
                id = index.toLong(),
                timestamp = index.toLong() * 1000
            )
        }
        every { locationDao.getAllLocations() } returns flowOf(largeLocationList)

        // When
        val result = repository.getAllLocations().first()

        // Then
        assertEquals(1000, result.size)
    }

    @Test
    fun `getLocationsForDevice should handle non-existent device ID`() = runTest {
        // Given
        val nonExistentDeviceId = -1L
        every { locationDao.getLocationsForDevice(nonExistentDeviceId) } returns flowOf(emptyList())

        // When
        val result = repository.getLocationsForDevice(nonExistentDeviceId).first()

        // Then
        assertTrue(result.isEmpty())
    }

    // ========== Data Validation Tests ==========

    @Test
    fun `locations should maintain chronological order`() = runTest {
        // Given
        val chronologicalLocations = listOf(testLocation1, testLocation2, testLocation3)
        every { locationDao.getAllLocations() } returns flowOf(chronologicalLocations)

        // When
        val result = repository.getAllLocations().first()

        // Then
        assertEquals(3, result.size)
        assertTrue(result[0].timestamp < result[1].timestamp)
        assertTrue(result[1].timestamp < result[2].timestamp)
    }

    @Test
    fun `location providers should be preserved`() = runTest {
        // Given
        coEvery { locationDao.insert(testLocation1) } returns 1L
        coEvery { locationDao.insert(testLocation2) } returns 2L
        coEvery { locationDao.insert(testLocation3) } returns 3L

        // When
        repository.insertLocation(testLocation1)
        repository.insertLocation(testLocation2)
        repository.insertLocation(testLocation3)

        // Then
        coVerify { locationDao.insert(match { it.provider == "FUSED" }) }
        coVerify { locationDao.insert(match { it.provider == "GPS" }) }
        coVerify { locationDao.insert(match { it.provider == "NETWORK" }) }
    }
}
