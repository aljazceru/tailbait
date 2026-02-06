package com.tailbait.algorithm

import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for DetectionAlgorithm.
 *
 * These tests verify the core stalking detection logic using mocked repositories.
 * Test coverage includes:
 * - Detection of devices at multiple locations
 * - Whitelist filtering (trusted devices are excluded)
 * - Distance threshold filtering
 * - Threat score calculation and thresholding
 * - Detection result generation
 * - Edge cases and error handling
 *
 * Test Methodology:
 * - Use MockK for repository mocking
 * - Test with realistic device and location data
 * - Verify algorithm behavior with various configurations
 * - Test integration with ThreatScoreCalculator
 * - Validate filtering and sorting logic
 */
class DetectionAlgorithmTest {

    // Mocked dependencies
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var whitelistRepository: WhitelistRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var threatScoreCalculator: ThreatScoreCalculator
    private lateinit var patternMatcher: PatternMatcher

    // System under test
    private lateinit var detectionAlgorithm: DetectionAlgorithm

    // Test data
    private lateinit var defaultSettings: AppSettings

    @Before
    fun setup() {
        // Create mocks
        deviceRepository = mockk()
        locationRepository = mockk()
        whitelistRepository = mockk()
        settingsRepository = mockk()
        threatScoreCalculator = mockk()
        patternMatcher = mockk()

        // Default settings for tests
        defaultSettings = AppSettings(
            id = 1,
            isTrackingEnabled = true,
            alertThresholdCount = 3,
            minDetectionDistanceMeters = 100.0
        )

        // Create algorithm instance with mock shadowAnalyzer
        val shadowAnalyzer = mockk<ShadowAnalyzer>(relaxed = true)
        coEvery { shadowAnalyzer.findSuspiciousShadows(any(), any()) } returns emptyList()

        detectionAlgorithm = DetectionAlgorithm(
            deviceRepository = deviceRepository,
            locationRepository = locationRepository,
            whitelistRepository = whitelistRepository,
            settingsRepository = settingsRepository,
            threatScoreCalculator = threatScoreCalculator,
            patternMatcher = patternMatcher,
            shadowAnalyzer = shadowAnalyzer
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Basic Detection Tests ====================

    @Test
    fun `runDetection - no suspicious devices - should return empty list`() = runTest {
        // Arrange
        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(emptyList())

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Should return empty list when no suspicious devices", results.isEmpty())
        coVerify { deviceRepository.getSuspiciousDevices(3) }
    }

    @Test
    fun `runDetection - device at 3+ locations with significant distance - should detect`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Phone", "PHONE")
        val locations = createTestLocations(3, 40.0, -74.0)
        val deviceRecords = createDeviceLocationRecords(1, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords
        coEvery { threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations) } returns 0.75

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertEquals("Should detect 1 suspicious device", 1, results.size)
        assertEquals("Should return correct device", device, results[0].device)
        assertEquals("Should return correct threat score", 0.75, results[0].threatScore, 0.01)
        assertTrue("Max distance should be 1500m", results[0].maxDistance >= 1500.0)
    }

    @Test
    fun `runDetection - whitelisted device - should be excluded`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "My Phone", "PHONE")
        val locations = createTestLocations(3, 40.0, -74.0)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(listOf(1L))
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Whitelisted device should be excluded", results.isEmpty())
        coVerify(exactly = 0) { locationRepository.getLocationsForDevice(any()) }
    }

    @Test
    fun `runDetection - device with insufficient location count - should be excluded`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
        val locations = createTestLocations(2, 40.0, -74.0) // Only 2 locations, need 3
        val deviceRecords = createDeviceLocationRecords(1, 2)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Device with insufficient locations should be excluded", results.isEmpty())
    }

    @Test
    fun `runDetection - device with no significant distances - should be excluded`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
        val baseTime = System.currentTimeMillis()
        // All locations very close together (< 100m threshold)
        val locations = listOf(
            createLocation(1, 40.0000, -74.0000, baseTime),
            createLocation(2, 40.0001, -74.0001, baseTime + 1000), // ~11m away
            createLocation(3, 40.0002, -74.0002, baseTime + 2000)  // ~22m away
        )
        val deviceRecords = createDeviceLocationRecords(1, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Device with no significant distances should be excluded", results.isEmpty())
    }

    @Test
    fun `runDetection - device with low threat score - should be excluded`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "HEADPHONES")
        val locations = createTestLocations(3, 40.0, -74.0)
        val deviceRecords = createDeviceLocationRecords(1, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords
        coEvery { threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations) } returns 0.3 // Below 0.5 threshold

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Device with low threat score should be excluded", results.isEmpty())
    }

    // ==================== Multiple Devices Tests ====================

    @Test
    fun `runDetection - multiple suspicious devices - should detect all`() = runTest {
        // Arrange
        val device1 = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Device 1", "PHONE")
        val device2 = createTestDevice(2, "11:22:33:44:55:66", "Device 2", "TABLET")
        val locations1 = createTestLocations(3, 40.0, -74.0)
        val locations2 = createTestLocations(4, 41.0, -73.0)
        val deviceRecords1 = createDeviceLocationRecords(1, 3)
        val deviceRecords2 = createDeviceLocationRecords(2, 4)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device1, device2))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations1)
        coEvery { locationRepository.getLocationsForDevice(2) } returns flowOf(locations2)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords1
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(2) } returns deviceRecords2
        coEvery { threatScoreCalculator.calculateEnhanced(device1, locations1, any(), deviceRecords1, userLocations) } returns 0.75
        coEvery { threatScoreCalculator.calculateEnhanced(device2, locations2, any(), deviceRecords2, userLocations) } returns 0.85

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertEquals("Should detect 2 suspicious devices", 2, results.size)
    }

    @Test
    fun `runDetection - results should be sorted by threat score descending`() = runTest {
        // Arrange
        val device1 = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Device 1", "PHONE")
        val device2 = createTestDevice(2, "11:22:33:44:55:66", "Device 2", "TABLET")
        val device3 = createTestDevice(3, "AA:AA:AA:AA:AA:AA", "Device 3", "WATCH")
        val locations1 = createTestLocations(3, 40.0, -74.0)
        val locations2 = createTestLocations(3, 41.0, -73.0)
        val locations3 = createTestLocations(3, 42.0, -72.0)
        val deviceRecords1 = createDeviceLocationRecords(1, 3)
        val deviceRecords2 = createDeviceLocationRecords(2, 3)
        val deviceRecords3 = createDeviceLocationRecords(3, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device1, device2, device3))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations1)
        coEvery { locationRepository.getLocationsForDevice(2) } returns flowOf(locations2)
        coEvery { locationRepository.getLocationsForDevice(3) } returns flowOf(locations3)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords1
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(2) } returns deviceRecords2
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(3) } returns deviceRecords3
        coEvery { threatScoreCalculator.calculateEnhanced(device1, locations1, any(), deviceRecords1, userLocations) } returns 0.65
        coEvery { threatScoreCalculator.calculateEnhanced(device2, locations2, any(), deviceRecords2, userLocations) } returns 0.90
        coEvery { threatScoreCalculator.calculateEnhanced(device3, locations3, any(), deviceRecords3, userLocations) } returns 0.75

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertEquals("Should have 3 results", 3, results.size)
        assertEquals("First result should have highest score", 0.90, results[0].threatScore, 0.01)
        assertEquals("Second result should have medium score", 0.75, results[1].threatScore, 0.01)
        assertEquals("Third result should have lowest score", 0.65, results[2].threatScore, 0.01)
    }

    // ==================== Detection Result Tests ====================

    @Test
    fun `runDetection - detection result should have correct properties`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Phone", "PHONE")
        val baseTime = System.currentTimeMillis()
        val locations = listOf(
            createLocation(1, 40.0, -74.0, baseTime),
            createLocation(2, 40.01, -74.01, baseTime + 1000),
            createLocation(3, 40.02, -74.02, baseTime + 2000)
        )
        val deviceRecords = createDeviceLocationRecords(1, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords
        coEvery { threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations) } returns 0.75

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        val result = results[0]
        assertEquals("Device should match", device, result.device)
        assertEquals("Locations should match", locations, result.locations)
        assertEquals("Threat score should match", 0.75, result.threatScore, 0.01)
        assertTrue("Max distance should be positive", result.maxDistance > 0.0)
        assertTrue("Avg distance should be positive", result.avgDistance > 0.0)
        assertNotNull("Detection reason should not be null", result.detectionReason)
        assertTrue("Detection reason should mention device", result.detectionReason.contains("Test Phone"))
    }

    @Test
    fun `runDetection - detection reason should include location count`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
        val locations = createTestLocations(5, 40.0, -74.0)
        val deviceRecords = createDeviceLocationRecords(1, 5)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords
        coEvery { threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations) } returns 0.75

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Detection reason should mention 5 locations",
            results[0].detectionReason.contains("5") &&
            results[0].detectionReason.contains("location"))
    }

    // ==================== Single Device Detection Tests ====================

    @Test
    fun `runDetectionForDevice - whitelisted device - should return null`() = runTest {
        // Arrange
        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(listOf(1L))

        // Act
        val result = detectionAlgorithm.runDetectionForDevice(1L)

        // Assert
        assertNull("Whitelisted device should return null", result)
    }

    @Test
    fun `runDetectionForDevice - device not found - should return null`() = runTest {
        // Arrange
        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getDeviceById(999L) } returns null

        // Act
        val result = detectionAlgorithm.runDetectionForDevice(999L)

        // Assert
        assertNull("Non-existent device should return null", result)
    }

    @Test
    fun `runDetectionForDevice - suspicious device - should return detection result`() = runTest {
        // Arrange
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
        val locations = createTestLocations(3, 40.0, -74.0)
        val deviceRecords = createDeviceLocationRecords(1, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getDeviceById(1L) } returns device
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords
        coEvery { threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations) } returns 0.75

        // Act
        val result = detectionAlgorithm.runDetectionForDevice(1L)

        // Assert
        assertNotNull("Should return detection result", result)
        assertEquals("Should return correct device", device, result?.device)
        assertEquals("Should return correct threat score", 0.75, result?.threatScore ?: 0.0, 0.01)
    }

    // ==================== Settings Integration Tests ====================

    @Test
    fun `runDetection - custom alertThresholdCount - should use correct threshold`() = runTest {
        // Arrange
        val customSettings = defaultSettings.copy(alertThresholdCount = 5)
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
        val locations = createTestLocations(5, 40.0, -74.0)
        val deviceRecords = createDeviceLocationRecords(1, 5)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(customSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(5) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords
        coEvery { threatScoreCalculator.calculateEnhanced(any(), any(), any(), any(), any()) } returns 0.75

        // Act
        detectionAlgorithm.runDetection()

        // Assert
        coVerify { deviceRepository.getSuspiciousDevices(5) }
    }

    @Test
    fun `runDetection - custom minDetectionDistance - should filter correctly`() = runTest {
        // Arrange
        val customSettings = defaultSettings.copy(minDetectionDistanceMeters = 500.0)
        val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
        val baseTime = System.currentTimeMillis()
        // Locations 200m apart (< 500m threshold)
        val locations = listOf(
            createLocation(1, 40.0000, -74.0000, baseTime),
            createLocation(2, 40.0018, -74.0000, baseTime + 1000), // ~200m
            createLocation(3, 40.0036, -74.0000, baseTime + 2000)  // ~400m from first
        )
        val deviceRecords = createDeviceLocationRecords(1, 3)
        val userLocations = createUserLocations(5)

        coEvery { settingsRepository.getSettings() } returns flowOf(customSettings)
        coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        coEvery { deviceRepository.getSuspiciousDevices(3) } returns flowOf(listOf(device))
        coEvery { locationRepository.getLocationsForDevice(1) } returns flowOf(locations)
        coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
        coEvery { deviceRepository.getDeviceLocationRecordsForDevice(1) } returns deviceRecords

        // Act
        val results = detectionAlgorithm.runDetection()

        // Assert
        assertTrue("Should exclude device with distances below threshold", results.isEmpty())
    }

    // ==================== Helper Methods ====================

    private fun createTestDevice(
        id: Long,
        address: String,
        name: String,
        deviceType: String?
    ): ScannedDevice {
        return ScannedDevice(
            id = id,
            address = address,
            name = name,
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis(),
            detectionCount = 1,
            deviceType = deviceType
        )
    }

    private fun createLocation(
        id: Long,
        latitude: Double,
        longitude: Double,
        timestamp: Long
    ): Location {
        return Location(
            id = id,
            latitude = latitude,
            longitude = longitude,
            accuracy = 10.0f,
            altitude = null,
            timestamp = timestamp,
            provider = "GPS"
        )
    }

    private fun createTestLocations(
        count: Int,
        baseLatitude: Double,
        baseLongitude: Double
    ): List<Location> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            createLocation(
                id = i.toLong() + 1,
                latitude = baseLatitude + (i * 0.01),
                longitude = baseLongitude + (i * 0.01),
                timestamp = baseTime + (i * 1000)
            )
        }
    }

    private fun createDeviceLocationRecords(
        deviceId: Long,
        count: Int
    ): List<DeviceLocationRecord> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            DeviceLocationRecord(
                id = i.toLong() + 1,
                deviceId = deviceId,
                locationId = i.toLong() + 1,
                timestamp = baseTime + (i * 1000),
                rssi = -70,
                scanTriggerType = "PERIODIC"
            )
        }
    }

    private fun createUserLocations(count: Int): List<Location> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            createLocation(
                id = i.toLong() + 100,  // Different IDs from device locations
                latitude = 40.0 + (i * 0.01),
                longitude = -74.0 + (i * 0.01),
                timestamp = baseTime + (i * 1000)
            )
        }
    }
}
