package com.tailbait.algorithm

import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.util.DistanceCalculator
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
        defaultSettings =
            AppSettings(
                id = 1,
                isTrackingEnabled = true,
                alertThresholdCount = 3,
                minDetectionDistanceMeters = 100.0,
            )

        // Default mock for getLinkedDevices (called during weak link discount calculation)
        coEvery { deviceRepository.getLinkedDevices(any()) } returns emptyList()

        every { locationRepository.getAllLocations() } returns flowOf(emptyList())
        // Default mock for computeBreakdown (called when building DetectionResult)
        every {
            threatScoreCalculator.computeBreakdown(any(), any(), any(), any(), any(), any())
        } returns
            mapOf(
                "locationScore" to 0.5,
                "distanceScore" to 0.5,
                "timeScore" to 0.5,
                "consistencyScore" to 0.5,
                "deviceTypeScore" to 0.5,
            )

        // Create algorithm instance with mock shadowAnalyzer
        val shadowAnalyzer = mockk<ShadowAnalyzer>(relaxed = true)
        coEvery { shadowAnalyzer.findSuspiciousShadows(any(), any(), any()) } returns emptyList()

        detectionAlgorithm =
            DetectionAlgorithm(
                deviceRepository = deviceRepository,
                locationRepository = locationRepository,
                whitelistRepository = whitelistRepository,
                settingsRepository = settingsRepository,
                threatScoreCalculator = threatScoreCalculator,
                shadowAnalyzer = shadowAnalyzer,
            )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Basic Detection Tests ====================

    @Test
    fun `runDetection - no suspicious devices - should return empty list`() =
        runTest {
            // Arrange
            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(emptyList())
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue("Should return empty list when no suspicious devices", results.isEmpty())
            coVerify { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) }
        }

    @Test
    fun `runDetection - device at 3+ locations with significant distance - should detect`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Phone", "PHONE")
            val locations = createTestLocations(3, 40.0, -74.0)
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery {
                threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations, any())
            } returns 0.75

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertEquals("Should detect 1 suspicious device", 1, results.size)
            assertEquals("Should return correct device", device, results[0].device)
            assertEquals("Should return correct threat score", 0.75, results[0].threatScore, 0.01)
            assertTrue("Max distance should be 1500m", results[0].maxDistance >= 1500.0)
        }

    @Test
    fun `runDetection - whitelisted device - should be excluded`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "My Phone", "PHONE")
            val locations = createTestLocations(3, 40.0, -74.0)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(listOf(1L))
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue("Whitelisted device should be excluded", results.isEmpty())
            coVerify(exactly = 0) { locationRepository.getLocationsForDeviceWithLinked(any()) }
        }

    @Test
    fun `runDetection - device with insufficient location count - should be excluded`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
            val locations = createTestLocations(2, 40.0, -74.0) // Only 2 locations, need 3
            val deviceRecords = createDeviceLocationRecords(1, 2)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue("Device with insufficient locations should be excluded", results.isEmpty())
        }

    @Test
    fun `runDetection - device with no significant distances - should be excluded`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
            val baseTime = System.currentTimeMillis()
            // All locations very close together (< 100m threshold)
            val locations =
                listOf(
                    createLocation(1, 40.0000, -74.0000, baseTime),
                    // ~11m away
                    createLocation(2, 40.0001, -74.0001, baseTime + 1000),
                    // ~22m away
                    createLocation(3, 40.0002, -74.0002, baseTime + 2000),
                )
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue("Device with no significant distances should be excluded", results.isEmpty())
        }

    @Test
    fun `runDetection - device with low threat score - should be excluded`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "HEADPHONES")
            val locations = createTestLocations(3, 40.0, -74.0)
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery {
                threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations, any())
            } returns 0.3 // Below 0.5 threshold

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue("Device with low threat score should be excluded", results.isEmpty())
        }

    // ==================== Multiple Devices Tests ====================

    @Test
    fun `runDetection - multiple suspicious devices - should detect all`() =
        runTest {
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
            coEvery {
                deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any())
            } returns flowOf(listOf(device1, device2))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations1
            coEvery { locationRepository.getLocationsForDeviceWithLinked(2) } returns locations2
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords1
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(2, any()) } returns deviceRecords2
            coEvery {
                threatScoreCalculator.calculateEnhanced(
                    device1, locations1, any(), deviceRecords1, userLocations, any(),
                )
            } returns 0.75
            coEvery {
                threatScoreCalculator.calculateEnhanced(
                    device2, locations2, any(), deviceRecords2, userLocations, any(),
                )
            } returns 0.85

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertEquals("Should detect 2 suspicious devices", 2, results.size)
        }

    @Test
    fun `runDetection - results should be sorted by threat score descending`() =
        runTest {
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
            coEvery {
                deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any())
            } returns flowOf(listOf(device1, device2, device3))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations1
            coEvery { locationRepository.getLocationsForDeviceWithLinked(2) } returns locations2
            coEvery { locationRepository.getLocationsForDeviceWithLinked(3) } returns locations3
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords1
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(2, any()) } returns deviceRecords2
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(3, any()) } returns deviceRecords3
            coEvery {
                threatScoreCalculator.calculateEnhanced(
                    device1, locations1, any(), deviceRecords1, userLocations, any(),
                )
            } returns 0.65
            coEvery {
                threatScoreCalculator.calculateEnhanced(
                    device2, locations2, any(), deviceRecords2, userLocations, any(),
                )
            } returns 0.90
            coEvery {
                threatScoreCalculator.calculateEnhanced(
                    device3, locations3, any(), deviceRecords3, userLocations, any(),
                )
            } returns 0.75

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
    fun `runDetection - detection result should have correct properties`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Phone", "PHONE")
            val baseTime = System.currentTimeMillis()
            val locations =
                listOf(
                    createLocation(1, 40.0, -74.0, baseTime),
                    createLocation(2, 40.01, -74.01, baseTime + 1000),
                    createLocation(3, 40.02, -74.02, baseTime + 2000),
                )
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery {
                threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations, any())
            } returns 0.75

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
    fun `runDetection - detection reason should include location count`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
            val locations = createTestLocations(5, 40.0, -74.0)
            val deviceRecords = createDeviceLocationRecords(1, 5)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery {
                threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations, any())
            } returns 0.75

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue(
                "Detection reason should mention 5 locations",
                results[0].detectionReason.contains("5") &&
                    results[0].detectionReason.contains("location"),
            )
        }

    // ==================== Single Device Detection Tests ====================

    @Test
    fun `runDetectionForDevice - whitelisted device - should return null`() =
        runTest {
            // Arrange
            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(listOf(1L))
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()

            // Act
            val result = detectionAlgorithm.runDetectionForDevice(1L)

            // Assert
            assertNull("Whitelisted device should return null", result)
        }

    @Test
    fun `runDetectionForDevice - device not found - should return null`() =
        runTest {
            // Arrange
            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getDeviceById(999L) } returns null
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()

            // Act
            val result = detectionAlgorithm.runDetectionForDevice(999L)

            // Assert
            assertNull("Non-existent device should return null", result)
        }

    @Test
    fun `runDetectionForDevice - suspicious device - should return detection result`() =
        runTest {
            // Arrange
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
            val locations = createTestLocations(3, 40.0, -74.0)
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getDeviceById(1L) } returns device
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery {
                threatScoreCalculator.calculateEnhanced(device, locations, any(), deviceRecords, userLocations, any())
            } returns 0.75

            // Act
            val result = detectionAlgorithm.runDetectionForDevice(1L)

            // Assert
            assertNotNull("Should return detection result", result)
            assertEquals("Should return correct device", device, result?.device)
            assertEquals("Should return correct threat score", 0.75, result?.threatScore ?: 0.0, 0.01)
        }

    // ==================== Location Clustering Tests ====================

    @Test
    fun `runDetection - neighborhood locations should cluster and exclude device`() =
        runTest {
            // Arrange: device seen at 7 GPS points spread ~30m apart in a line, spanning ~180m total.
            // Without clustering: 7 locations, max distance 180m > 100m threshold → detected.
            // With 100m clustering: collapses to 2 clusters → below threshold of 3 → excluded.
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Neighbor Phone", "PHONE")
            val baseTime = System.currentTimeMillis()
            // ~30m apart (0.00027 degrees latitude ≈ 30m)
            val locations =
                listOf(
                    createLocation(1, 40.00000, -74.0000, baseTime),
                    // ~30m
                    createLocation(2, 40.00027, -74.0000, baseTime + 1000),
                    // ~60m
                    createLocation(3, 40.00054, -74.0000, baseTime + 2000),
                    // ~90m
                    createLocation(4, 40.00081, -74.0000, baseTime + 3000),
                    // ~120m
                    createLocation(5, 40.00108, -74.0000, baseTime + 4000),
                    // ~150m
                    createLocation(6, 40.00135, -74.0000, baseTime + 5000),
                    // ~180m
                    createLocation(7, 40.00162, -74.0000, baseTime + 6000),
                )
            val deviceRecords = createDeviceLocationRecords(1, 7)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert - clusters reduce below threshold, device excluded
            assertTrue("Neighborhood locations should cluster and device should be excluded", results.isEmpty())
        }

    @Test
    fun `runDetection - distant locations should survive clustering`() =
        runTest {
            // Arrange: device at 3 truly distant locations (home, office, gym)
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Stalker Tracker", "TRACKER")
            val baseTime = System.currentTimeMillis()
            // 3 locations each >1km apart - should remain 3 clusters
            val locations =
                listOf(
                    // Home
                    createLocation(1, 40.0000, -74.0000, baseTime),
                    // ~1.4km away
                    createLocation(2, 40.0100, -74.0100, baseTime + 1000),
                    // ~2.8km away
                    createLocation(3, 40.0200, -74.0200, baseTime + 2000),
                )
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery { threatScoreCalculator.calculateEnhanced(any(), any(), any(), any(), any(), any()) } returns 0.75

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert - 3 distant locations survive clustering, device is detected
            assertEquals("Distant locations should survive clustering", 1, results.size)
        }

    // ==================== Explicit Trust Regression Tests ====================

    @Test
    fun `five-place close unknown follower remains detectable until explicitly trusted`() =
        runTest {
            val locations = createTestLocations(5, 40.0, -74.0)
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Unknown", null).copy(highestRssi = -30)
            val records = recordsAtLocations(device.id, locations)
            configureRealDetection(listOf(device), locations, mapOf(device.id to records))

            val detected = detectionAlgorithm.runDetection().single()
            assertEquals(device.id, detected.device.id)
            assertEquals(5, detected.locations.size)
            assertTrue(detected.threatScore >= 0.5)
            assertNotNull(detectionAlgorithm.runDetectionForDevice(device.id))

            every { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(listOf(device.id))
            assertTrue(detectionAlgorithm.runDetection().isEmpty())
            assertNull(detectionAlgorithm.runDetectionForDevice(device.id))
        }

    @Test
    fun `five-place close separated tracker is not treated as an owned companion`() =
        runTest {
            val locations = createTestLocations(5, 40.0, -74.0)
            val device =
                createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Separated tracker", "TRACKER").copy(
                    highestRssi = -30,
                    isTracker = true,
                    findMyStatus = 0,
                    findMyStatusTimestamp = locations.last().timestamp,
                    findMySeparated = true,
                )
            configureRealDetection(listOf(device), locations, mapOf(device.id to recordsAtLocations(device.id, locations)))

            val result = detectionAlgorithm.runDetection().single()
            assertEquals(5, result.locations.size)
            assertTrue(result.device.findMySeparated)
            assertTrue(result.detectionReason.contains("SEPARATED"))
            assertNotNull(detectionAlgorithm.runDetectionForDevice(device.id))
        }

    @Test
    fun `runDetection - device at most but not all locations with weak RSSI should not be skipped`() =
        runTest {
            // Arrange: device at 4 of 10 locations (40%) with weak RSSI - not a companion
            val device =
                createTestDevice(1, "DD:EE:FF:00:00:00", "Stalker Tag", "TRACKER").copy(
                    highestRssi = -75,
                )
            val baseTime = System.currentTimeMillis()
            val userLocations =
                List(10) { i ->
                    createLocation(
                        id = (i + 100).toLong(),
                        latitude = 40.0 + (i * 0.05),
                        longitude = -74.0 + (i * 0.05),
                        timestamp = baseTime + (i * 3600000L),
                    )
                }
            // Device only at 4 of the 10 locations, far apart
            val deviceLocations =
                listOf(
                    createLocation(1, userLocations[0].latitude, userLocations[0].longitude, baseTime),
                    createLocation(2, userLocations[3].latitude, userLocations[3].longitude, baseTime + 3600000L),
                    createLocation(3, userLocations[6].latitude, userLocations[6].longitude, baseTime + 7200000L),
                    createLocation(4, userLocations[9].latitude, userLocations[9].longitude, baseTime + 10800000L),
                )
            val deviceRecords = createDeviceLocationRecords(1, 4)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns deviceLocations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery { threatScoreCalculator.calculateEnhanced(any(), any(), any(), any(), any(), any()) } returns 0.75

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert - NOT a companion device, should be detected
            assertEquals("Non-companion device should be detected", 1, results.size)
        }

    // ==================== Detection Window Scoping Tests ====================

    @Test
    fun `runDetection - stale locations outside detection window should be excluded`() =
        runTest {
            // Arrange: device has 5 locations total, but only 2 are within the 3-day detection window.
            // The other 3 are from 30 days ago. Without window scoping, device passes threshold of 3.
            // With window scoping, only 2 recent locations count → below threshold → excluded.
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Old History Device", "PHONE")
            val now = System.currentTimeMillis()
            val thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000L)
            val oneDayAgo = now - (24 * 60 * 60 * 1000L)

            // 3 old locations (outside 3-day window) + 2 recent ones
            val locations =
                listOf(
                    // old
                    createLocation(1, 40.000, -74.000, thirtyDaysAgo),
                    // old
                    createLocation(2, 40.010, -74.010, thirtyDaysAgo + 3600000L),
                    // old
                    createLocation(3, 40.020, -74.020, thirtyDaysAgo + 7200000L),
                    // recent
                    createLocation(4, 40.030, -74.030, oneDayAgo),
                    // recent
                    createLocation(5, 40.040, -74.040, now),
                )
            // Records match the location timestamps
            val deviceRecords =
                listOf(
                    DeviceLocationRecord(1, 1, 1, -70, thirtyDaysAgo, scanTriggerType = "PERIODIC"),
                    DeviceLocationRecord(2, 1, 2, -70, thirtyDaysAgo + 3600000L, scanTriggerType = "PERIODIC"),
                    DeviceLocationRecord(3, 1, 3, -70, thirtyDaysAgo + 7200000L, scanTriggerType = "PERIODIC"),
                    DeviceLocationRecord(4, 1, 4, -70, oneDayAgo, scanTriggerType = "PERIODIC"),
                    DeviceLocationRecord(5, 1, 5, -70, now, scanTriggerType = "PERIODIC"),
                )
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(defaultSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery { deviceRepository.getDeviceById(device.id) } returns device

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert - only 2 recent locations, below threshold of 3
            assertTrue("Stale locations outside detection window should be excluded", results.isEmpty())
            assertNull(detectionAlgorithm.runDetectionForDevice(device.id))
        }

    // ==================== Settings Integration Tests ====================

    @Test
    fun `runDetection - custom alertThresholdCount - should use correct threshold`() =
        runTest {
            // Arrange
            val customSettings = defaultSettings.copy(alertThresholdCount = 5)
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
            val locations = createTestLocations(5, 40.0, -74.0)
            val deviceRecords = createDeviceLocationRecords(1, 5)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(customSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(5), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords
            coEvery { threatScoreCalculator.calculateEnhanced(any(), any(), any(), any(), any(), any()) } returns 0.75

            // Act
            detectionAlgorithm.runDetection()

            // Assert
            coVerify { deviceRepository.getSuspiciousDevicesWithLinked(eq(5), any()) }
        }

    @Test
    fun `runDetection - custom minDetectionDistance - should filter correctly`() =
        runTest {
            // Arrange
            val customSettings = defaultSettings.copy(minDetectionDistanceMeters = 500.0)
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Test Device", "PHONE")
            val baseTime = System.currentTimeMillis()
            // Locations 200m apart (< 500m threshold)
            val locations =
                listOf(
                    createLocation(1, 40.0000, -74.0000, baseTime),
                    // ~200m
                    createLocation(2, 40.0018, -74.0000, baseTime + 1000),
                    // ~400m from first
                    createLocation(3, 40.0036, -74.0000, baseTime + 2000),
                )
            val deviceRecords = createDeviceLocationRecords(1, 3)
            val userLocations = createUserLocations(5)

            coEvery { settingsRepository.getSettings() } returns flowOf(customSettings)
            coEvery { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
            coEvery { deviceRepository.getSuspiciousDevicesWithLinked(eq(3), any()) } returns flowOf(listOf(device))
            coEvery { locationRepository.getLocationsForDeviceWithLinked(1) } returns locations
            coEvery { locationRepository.getAllLocations() } returns flowOf(userLocations)
            coEvery { locationRepository.getUserPathSince(any()) } returns emptyList()
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(1, any()) } returns deviceRecords

            // Act
            val results = detectionAlgorithm.runDetection()

            // Assert
            assertTrue("Should exclude device with distances below threshold", results.isEmpty())
        }

    @Test
    fun `unlinked rotating shadow uses all MAC observations with no ordinary candidates`() =
        runTest {
            assertUnlinkedShadowDetected(includeOrdinaryCandidate = false)
        }

    @Test
    fun `unlinked rotating shadow survives an unrelated ordinary candidate`() =
        runTest {
            assertUnlinkedShadowDetected(includeOrdinaryCandidate = true)
        }

    private suspend fun assertUnlinkedShadowDetected(includeOrdinaryCandidate: Boolean) {
        val locations = createTestLocations(3, 40.0, -74.0)
        val shadowKey = "B:FIND_MY|C:12|M:004C|T:TRACKER|TR:1"
        val shadowDevices =
            locations.map { location ->
                createTestDevice(location.id, "AA:BB:CC:DD:EE:0${location.id}", "Rotating tracker", "TRACKER").copy(
                    shadowKey = shadowKey,
                    isTracker = true,
                    findMyStatus = 0,
                    findMyStatusTimestamp = location.timestamp,
                    findMySeparated = true,
                    highestRssi = -30,
                )
            }
        val shadowRecords =
            locations.mapIndexed { index, location ->
                recordsAtLocations(shadowDevices[index].id, listOf(location)).single()
            }
        val ordinary = createTestDevice(9, "99:BB:CC:DD:EE:FF", "Unrelated", "PHONE").copy(highestRssi = -30)
        val ordinaryDevices = if (includeOrdinaryCandidate) listOf(ordinary) else emptyList()
        val ordinaryRecords = if (includeOrdinaryCandidate) mapOf(ordinary.id to recordsAtLocations(ordinary.id, locations)) else emptyMap()
        configureRealDetection(ordinaryDevices, locations, ordinaryRecords)
        coEvery { deviceRepository.getSuspiciousShadowKeys(3, any()) } returns listOf(shadowKey)
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey, any()) } returns shadowDevices
        coEvery { deviceRepository.getDeviceLocationRecordsForShadow(shadowKey, any()) } returns shadowRecords

        val results = detectionAlgorithm.runDetection()
        assertEquals(if (includeOrdinaryCandidate) 2 else 1, results.size)
        val shadow = results.single { it.detectionReason.contains("Shadow detection") }
        assertEquals(locations, shadow.locations)
        assertEquals(shadowRecords.last().timestamp - shadowRecords.first().timestamp, shadow.timeSpanMs)
        assertTrue(shadow.threatScore >= 0.5)
        coVerify(exactly = 0) { locationRepository.getLocationsForDeviceWithLinked(match { it in 1L..3L }) }
    }

    @Test
    fun `latest eligible linked state replaces stale canonical root status`() =
        runTest {
            for (separated in listOf(false, true)) {
                val locations = createTestLocations(5, 40.0, -74.0)
                val root =
                    createTestDevice(1, "AA:BB:CC:DD:EE:01", "Root tracker", "TRACKER").copy(
                        lastSeen = locations.last().timestamp + 1000,
                        isTracker = true,
                        findMyStatus = if (separated) 4 else 0,
                        findMyStatusTimestamp = locations.first().timestamp - 24 * 60 * 60 * 1000L,
                        findMySeparated = !separated,
                    )
                val latest =
                    createTestDevice(2, "AA:BB:CC:DD:EE:02", "Rotated tracker", "TRACKER").copy(
                        lastSeen = locations.last().timestamp,
                        linkedDeviceId = root.id,
                        linkStrength = "STRONG",
                        isTracker = true,
                        findMyStatus = if (separated) 0 else 4,
                        findMyStatusTimestamp = locations.last().timestamp,
                        findMySeparated = separated,
                    )
                val records =
                    recordsAtLocations(root.id, locations.take(2)) +
                        recordsAtLocations(latest.id, locations.drop(2))
                configureRealDetection(listOf(root), locations, mapOf(root.id to records))
                coEvery { deviceRepository.getLinkedDevices(root.id) } returns listOf(root, latest)

                val result = detectionAlgorithm.runDetection().single()
                assertEquals(root.id, result.device.id)
                assertEquals(latest.findMyStatus, result.device.findMyStatus)
                assertEquals(separated, result.device.findMySeparated)
                assertEquals(separated, result.detectionReason.contains("SEPARATED"))
                assertEquals(separated, detectionAlgorithm.runDetectionForDevice(root.id)!!.device.findMySeparated)
            }
        }

    @Test
    fun `stale records and user paths cannot inflate current detection score`() =
        runTest {
            val locations = createTestLocations(5, 40.0, -74.0)
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Follower", null).copy(highestRssi = -1)
            val currentRecords = recordsAtLocations(device.id, locations)
            configureRealDetection(listOf(device), locations, mapOf(device.id to currentRecords))
            val baseline = detectionAlgorithm.runDetection().single()

            val staleTime = System.currentTimeMillis() - 4 * 24 * 60 * 60 * 1000L
            val staleLocation = createLocation(99, 42.0, -72.0, staleTime)
            val staleRecord = recordsAtLocations(device.id, listOf(staleLocation)).single().copy(rssi = -1)
            every { locationRepository.getAllLocations() } returns flowOf(locations + staleLocation)
            coEvery { locationRepository.getLocationsForDeviceWithLinked(device.id) } returns locations + staleLocation
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(device.id, any()) } returns
                currentRecords + staleRecord
            coEvery { locationRepository.getUserPathSince(any()) } returns
                (locations + staleLocation).map { UserPath(locationId = it.id, timestamp = it.timestamp, accuracy = it.accuracy) }

            val result = detectionAlgorithm.runDetection().single()
            assertEquals(baseline.locations, result.locations)
            assertEquals(baseline.threatScore, result.threatScore, 0.0)
            assertEquals(baseline.timeSpanMs, result.timeSpanMs)
            assertEquals(-30, result.device.highestRssi)
            assertEquals(baseline.threatScore, detectionAlgorithm.runDetectionForDevice(device.id)!!.threatScore, 0.0)
        }

    @Test
    fun `overlapping 200m uncertainty fixes do not prove three places`() =
        runTest {
            val now = System.currentTimeMillis()
            val locations =
                listOf(-0.0012, 0.0, 0.0012).mapIndexed { index, latitudeOffset ->
                    createLocation(index + 1L, 40.0 + latitudeOffset, -74.0, now + index * 1000L)
                        .copy(accuracy = 200f)
                }
            val device = createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Nearby", "TRACKER")
            configureRealDetection(listOf(device), locations, mapOf(device.id to recordsAtLocations(device.id, locations)))

            assertTrue(detectionAlgorithm.runDetection().isEmpty())
            assertNull(detectionAlgorithm.runDetectionForDevice(device.id))

            val accurateLocations = locations.map { it.copy(accuracy = 5f) }
            configureRealDetection(
                listOf(device),
                accurateLocations,
                mapOf(device.id to recordsAtLocations(device.id, accurateLocations)),
            )
            assertEquals(3, detectionAlgorithm.runDetection().single().locations.size)
            assertEquals(3, detectionAlgorithm.runDetectionForDevice(device.id)?.locations?.size)
        }

    @Test
    fun `accurate separated places respect a configured distance below 100m`() =
        runTest {
            val now = System.currentTimeMillis()
            val locations =
                List(5) { index ->
                    createLocation(index + 1L, 40.0 + index * 0.00045, -74.0, now + index * 1000L)
                        .copy(accuracy = 5f)
                }
            val device =
                createTestDevice(1, "AA:BB:CC:DD:EE:FF", "Follower", "TRACKER").copy(
                    isTracker = true,
                    findMyStatus = 0,
                    findMyStatusTimestamp = locations.last().timestamp,
                    findMySeparated = true,
                )
            configureRealDetection(listOf(device), locations, mapOf(device.id to recordsAtLocations(device.id, locations)))
            every { settingsRepository.getSettings() } returns flowOf(defaultSettings.copy(minDetectionDistanceMeters = 25.0))

            assertEquals(5, detectionAlgorithm.runDetection().single().locations.size)
            assertEquals(5, detectionAlgorithm.runDetectionForDevice(device.id)?.locations?.size)

            every { settingsRepository.getSettings() } returns flowOf(defaultSettings.copy(minDetectionDistanceMeters = 100.0))
            assertTrue(detectionAlgorithm.runDetection().isEmpty())
        }

    @Test
    fun `invalid accuracy cannot manufacture proven separation`() {
        for (accuracy in listOf(Float.NaN, Float.POSITIVE_INFINITY, -10f, 0f)) {
            assertEquals(0.0, DistanceCalculator.minimumSeparationMeters(1000.0, accuracy, 10f), 0.0)
            assertEquals(0.0, DistanceCalculator.minimumSeparationMeters(1000.0, 10f, accuracy), 0.0)
        }
        assertEquals(0.0, DistanceCalculator.minimumSeparationMeters(Double.NaN, 10f, 10f), 0.0)
        assertEquals(0.0, DistanceCalculator.minimumSeparationMeters(300.0, 200f, 200f), 0.0)
        assertEquals(180.0, DistanceCalculator.minimumSeparationMeters(200.0, 10f, 10f), 0.0)
    }

    private fun recordsAtLocations(
        deviceId: Long,
        locations: List<Location>,
    ): List<DeviceLocationRecord> =
        locations.map { location ->
            DeviceLocationRecord(
                id = deviceId * 100 + location.id,
                deviceId = deviceId,
                locationId = location.id,
                rssi = -30,
                timestamp = location.timestamp,
                scanTriggerType = "PERIODIC",
            )
        }

    private fun configureRealDetection(
        candidates: List<ScannedDevice>,
        locations: List<Location>,
        recordsByDevice: Map<Long, List<DeviceLocationRecord>>,
    ) {
        every { settingsRepository.getSettings() } returns flowOf(defaultSettings)
        every { whitelistRepository.getAllWhitelistedDeviceIds() } returns flowOf(emptyList())
        every { deviceRepository.getSuspiciousDevicesWithLinked(any(), any()) } returns flowOf(candidates)
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { locationRepository.getUserPathSince(any()) } returns
            locations.map { UserPath(locationId = it.id, timestamp = it.timestamp, accuracy = it.accuracy) }
        coEvery { deviceRepository.getSuspiciousShadowKeys(any(), any()) } returns emptyList()
        for (device in candidates) {
            coEvery { deviceRepository.getDeviceById(device.id) } returns device
            coEvery { deviceRepository.getDeviceLocationRecordsForDeviceWithLinked(device.id, any()) } returns
                recordsByDevice.getValue(device.id)
            coEvery { locationRepository.getLocationsForDeviceWithLinked(device.id) } returns locations
        }
        detectionAlgorithm =
            DetectionAlgorithm(
                deviceRepository,
                locationRepository,
                whitelistRepository,
                settingsRepository,
                ThreatScoreCalculator(MovementCorrelationCalculator()),
                ShadowAnalyzer(deviceRepository, locationRepository, MacRotationDetector()),
            )
    }

    // ==================== Helper Methods ====================

    private fun createTestDevice(
        id: Long,
        address: String,
        name: String,
        deviceType: String?,
    ): ScannedDevice {
        return ScannedDevice(
            id = id,
            address = address,
            name = name,
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis(),
            detectionCount = 1,
            deviceType = deviceType,
            highestRssi = -70,
        )
    }

    private fun createLocation(
        id: Long,
        latitude: Double,
        longitude: Double,
        timestamp: Long,
    ): Location {
        return Location(
            id = id,
            latitude = latitude,
            longitude = longitude,
            accuracy = 10.0f,
            altitude = null,
            timestamp = timestamp,
            provider = "GPS",
        )
    }

    private fun createTestLocations(
        count: Int,
        baseLatitude: Double,
        baseLongitude: Double,
    ): List<Location> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            createLocation(
                id = i.toLong() + 1,
                latitude = baseLatitude + (i * 0.01),
                longitude = baseLongitude + (i * 0.01),
                timestamp = baseTime + (i * 1000),
            )
        }
    }

    private fun createDeviceLocationRecords(
        deviceId: Long,
        count: Int,
    ): List<DeviceLocationRecord> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            DeviceLocationRecord(
                id = i.toLong() + 1,
                deviceId = deviceId,
                locationId = i.toLong() + 1,
                timestamp = baseTime + (i * 1000),
                rssi = -70,
                scanTriggerType = "PERIODIC",
            )
        }
    }

    private fun createUserLocations(count: Int): List<Location> {
        val baseTime = System.currentTimeMillis()
        return List(count) { i ->
            createLocation(
                // Different IDs from device locations
                id = i.toLong() + 100,
                latitude = 40.0 + (i * 0.01),
                longitude = -74.0 + (i * 0.01),
                timestamp = baseTime + (i * 1000),
            )
        }
    }
}
