package com.tailbait.service

import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.model.DetectionResult
import com.tailbait.data.repository.AlertRepository
import com.tailbait.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AlertGenerator.
 *
 * Tests cover:
 * - Alert generation from detection results
 * - Alert level determination based on threat scores
 * - Alert message template generation
 * - Duplicate alert prevention (throttling)
 * - Alert count queries
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlertGeneratorTest {
    private lateinit var alertRepository: AlertRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var alertGenerator: AlertGenerator

    @Before
    fun setup() {
        alertRepository = mockk()
        notificationHelper = mockk(relaxed = true)

        // Default: no previous alert for any device (first-time alert scenario)
        coEvery { alertRepository.getLatestAlertForDevice(any()) } returns null

        alertGenerator = AlertGenerator(alertRepository, notificationHelper)
    }

    @Test
    fun `generateAlert creates alert with correct level for CRITICAL threat score`() =
        runTest {
            // Given
            val device = createTestDevice("AA:BB:CC:DD:EE:FF", "Test Device")
            val locations = createTestLocations(5)
            val detectionResult =
                DetectionResult(
                    device = device,
                    locations = locations,
                    // CRITICAL
                    threatScore = 0.95,
                    maxDistance = 5000.0,
                    avgDistance = 3000.0,
                    detectionReason = "Test reason",
                )

            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 1L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then
            assertNotNull(alertId)
            assertEquals(1L, alertId)

            coVerify {
                alertRepository.insertAlertWithThrottling(
                    match { alert ->
                        alert.alertLevel == Constants.ALERT_LEVEL_CRITICAL &&
                            alert.threatScore == 0.95 &&
                            alert.title.contains("Critical", ignoreCase = true)
                    },
                    any(),
                )
            }
        }

    @Test
    fun `generateAlert creates alert with correct level for HIGH threat score`() =
        runTest {
            // Given
            val device = createTestDevice("AA:BB:CC:DD:EE:FF", "Test Device")
            val locations = createTestLocations(4)
            val detectionResult =
                DetectionResult(
                    device = device,
                    locations = locations,
                    // HIGH
                    threatScore = 0.80,
                    maxDistance = 3000.0,
                    avgDistance = 2000.0,
                    detectionReason = "Test reason",
                )

            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 2L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then
            assertNotNull(alertId)

            coVerify {
                alertRepository.insertAlertWithThrottling(
                    match { alert ->
                        alert.alertLevel == Constants.ALERT_LEVEL_HIGH &&
                            alert.title.contains("High", ignoreCase = true)
                    },
                    any(),
                )
            }
        }

    @Test
    fun `generateAlert creates alert with correct level for MEDIUM threat score`() =
        runTest {
            // Given
            val device = createTestDevice("AA:BB:CC:DD:EE:FF", "Test Device")
            val locations = createTestLocations(3)
            val detectionResult =
                DetectionResult(
                    device = device,
                    locations = locations,
                    // MEDIUM
                    threatScore = 0.65,
                    maxDistance = 2000.0,
                    avgDistance = 1500.0,
                    detectionReason = "Test reason",
                )

            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 3L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then
            assertNotNull(alertId)

            coVerify {
                alertRepository.insertAlertWithThrottling(
                    match { alert ->
                        alert.alertLevel == Constants.ALERT_LEVEL_MEDIUM &&
                            alert.title.contains("Medium", ignoreCase = true)
                    },
                    any(),
                )
            }
        }

    @Test
    fun `generateAlert creates alert with correct level for LOW threat score`() =
        runTest {
            // Given
            val device = createTestDevice("AA:BB:CC:DD:EE:FF", "Test Device")
            val locations = createTestLocations(3)
            val detectionResult =
                DetectionResult(
                    device = device,
                    locations = locations,
                    // LOW
                    threatScore = 0.55,
                    maxDistance = 1000.0,
                    avgDistance = 800.0,
                    detectionReason = "Test reason",
                )

            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 4L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then
            assertNotNull(alertId)

            coVerify {
                alertRepository.insertAlertWithThrottling(
                    match { alert ->
                        alert.alertLevel == Constants.ALERT_LEVEL_LOW &&
                            alert.title.contains("Low", ignoreCase = true)
                    },
                    any(),
                )
            }
        }

    @Test
    fun `generateAlert returns null when throttled`() =
        runTest {
            // Given
            val device = createTestDevice("AA:BB:CC:DD:EE:FF", "Test Device")
            val locations = createTestLocations(3)
            val detectionResult =
                DetectionResult(
                    device = device,
                    locations = locations,
                    threatScore = 0.75,
                    maxDistance = 2000.0,
                    avgDistance = 1500.0,
                    detectionReason = "Test reason",
                )

            // Simulate alert being throttled
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns null

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then
            assertNull(alertId)
        }

    @Test
    fun `generateAlert includes device information in message`() =
        runTest {
            // Given
            val deviceName = "Suspicious iPhone"
            val deviceAddress = "AA:BB:CC:DD:EE:FF"
            val device = createTestDevice(deviceAddress, deviceName)
            val locations = createTestLocations(4)
            val detectionResult =
                DetectionResult(
                    device = device,
                    locations = locations,
                    threatScore = 0.75,
                    maxDistance = 3000.0,
                    avgDistance = 2000.0,
                    detectionReason = "Test reason",
                )

            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 1L

            // When
            alertGenerator.generateAlert(detectionResult)

            // Then
            coVerify {
                alertRepository.insertAlertWithThrottling(
                    match { alert ->
                        alert.message.contains(deviceName) &&
                            alert.message.contains(deviceAddress)
                    },
                    any(),
                )
            }
        }

    @Test
    fun `generateAlerts processes multiple detection results`() =
        runTest {
            // Given
            val detectionResults =
                listOf(
                    createTestDetectionResult(0.95),
                    createTestDetectionResult(0.80),
                    createTestDetectionResult(0.65),
                )

            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returnsMany listOf(1L, 2L, 3L)

            // When
            val alertIds = alertGenerator.generateAlerts(detectionResults)

            // Then
            assertEquals(3, alertIds.size)
            assertTrue(alertIds.containsAll(listOf(1L, 2L, 3L)))

            coVerify(exactly = 3) {
                alertRepository.insertAlertWithThrottling(any(), any())
            }
        }

    @Test
    fun `generateAlerts handles throttled alerts correctly`() =
        runTest {
            // Given
            val detectionResults =
                listOf(
                    createTestDetectionResult(0.95),
                    createTestDetectionResult(0.80),
                    createTestDetectionResult(0.65),
                )

            // First and third succeed, second is throttled
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returnsMany listOf(1L, null, 3L)

            // When
            val alertIds = alertGenerator.generateAlerts(detectionResults)

            // Then
            assertEquals(2, alertIds.size)
            assertTrue(alertIds.containsAll(listOf(1L, 3L)))
        }

    @Test
    fun `shouldGenerateAlert returns true for valid detection above threshold`() =
        runTest {
            // Given
            val detectionResult = createTestDetectionResult(0.75)

            coEvery {
                alertRepository.hasSimilarRecentAlert(any(), any())
            } returns false

            // When
            val shouldGenerate =
                alertGenerator.shouldGenerateAlert(
                    detectionResult,
                    minThreatScore = 0.5,
                )

            // Then
            assertTrue(shouldGenerate)
        }

    @Test
    fun `shouldGenerateAlert returns false for detection below threshold`() =
        runTest {
            // Given
            val detectionResult = createTestDetectionResult(0.45)

            // When
            val shouldGenerate =
                alertGenerator.shouldGenerateAlert(
                    detectionResult,
                    minThreatScore = 0.5,
                )

            // Then
            assertFalse(shouldGenerate)
        }

    @Test
    fun `shouldGenerateAlert returns false when similar recent alert exists`() =
        runTest {
            // Given
            val detectionResult = createTestDetectionResult(0.75)

            coEvery {
                alertRepository.hasSimilarRecentAlert(any(), any())
            } returns true

            // When
            val shouldGenerate =
                alertGenerator.shouldGenerateAlert(
                    detectionResult,
                    minThreatScore = 0.5,
                )

            // Then
            assertFalse(shouldGenerate)
        }

    @Test
    fun `getActiveAlertCount returns correct count`() =
        runTest {
            // Given
            coEvery {
                alertRepository.getActiveAlertCount()
            } returns flowOf(5)

            // When
            val count = alertGenerator.getActiveAlertCount()

            // Then
            assertEquals(5, count)
        }

    @Test
    fun `getActiveAlertCountByLevel returns correct count`() =
        runTest {
            // Given
            coEvery {
                alertRepository.getActiveAlertCountByLevel(Constants.ALERT_LEVEL_CRITICAL)
            } returns flowOf(2)

            // When
            val count = alertGenerator.getActiveAlertCountByLevel(Constants.ALERT_LEVEL_CRITICAL)

            // Then
            assertEquals(2, count)
        }

    // ==================== Smart Throttling Tests ====================

    @Test
    fun `generateAlert suppresses re-alert within 6 hours`() =
        runTest {
            // Given - last alert was 2 hours ago, same score
            val now = System.currentTimeMillis()
            val twoHoursAgo = now - (2 * 60 * 60 * 1000L)
            val previousAlert =
                AlertHistory(
                    id = 10,
                    alertLevel = Constants.ALERT_LEVEL_HIGH,
                    title = "Previous",
                    message = "Previous alert",
                    timestamp = twoHoursAgo,
                    deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
                    locationIds = "[1,2,3]",
                    threatScore = 0.75,
                    detectionDetails = "{}",
                )
            val detectionResult = createTestDetectionResult(0.75)

            coEvery {
                alertRepository.getLatestAlertForDevice(any())
            } returns previousAlert
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 1L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then - should be suppressed (< 6 hours)
            assertNull("Should suppress re-alert within 6 hours", alertId)
        }

    @Test
    fun `generateAlert suppresses re-alert within 72 hours without escalation`() =
        runTest {
            // Given - last alert was 24 hours ago, same score (no escalation)
            val now = System.currentTimeMillis()
            val oneDayAgo = now - (24 * 60 * 60 * 1000L)
            val previousAlert =
                AlertHistory(
                    id = 10,
                    alertLevel = Constants.ALERT_LEVEL_HIGH,
                    title = "Previous",
                    message = "Previous alert",
                    timestamp = oneDayAgo,
                    deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
                    locationIds = "[1,2,3]",
                    threatScore = 0.75,
                    detectionDetails = "{}",
                )
            val detectionResult = createTestDetectionResult(0.80) // Only 0.05 increase, below 0.15 threshold

            coEvery {
                alertRepository.getLatestAlertForDevice(any())
            } returns previousAlert
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 1L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then - should be suppressed (< 72 hours AND score increase < 0.15)
            assertNull("Should suppress without significant escalation", alertId)
        }

    @Test
    fun `generateAlert allows re-alert when score escalates significantly`() =
        runTest {
            // Given - last alert was 12 hours ago, score jumped from 0.60 to 0.80 (+0.20)
            val now = System.currentTimeMillis()
            val twelveHoursAgo = now - (12 * 60 * 60 * 1000L)
            val previousAlert =
                AlertHistory(
                    id = 10,
                    alertLevel = Constants.ALERT_LEVEL_MEDIUM,
                    title = "Previous",
                    message = "Previous alert",
                    timestamp = twelveHoursAgo,
                    deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
                    locationIds = "[1,2,3]",
                    threatScore = 0.60,
                    detectionDetails = "{}",
                )
            val detectionResult = createTestDetectionResult(0.80) // +0.20, above 0.15 threshold

            coEvery {
                alertRepository.getLatestAlertForDevice(any())
            } returns previousAlert
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 11L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then - should allow (score escalated >= 0.15)
            assertNotNull("Should allow alert when score escalates", alertId)
        }

    @Test
    fun `generateAlert allows periodic reminder after 72 hours`() =
        runTest {
            // Given - last alert was 4 days ago
            val now = System.currentTimeMillis()
            val fourDaysAgo = now - (4 * 24 * 60 * 60 * 1000L)
            val previousAlert =
                AlertHistory(
                    id = 10,
                    alertLevel = Constants.ALERT_LEVEL_HIGH,
                    title = "Previous",
                    message = "Previous alert",
                    timestamp = fourDaysAgo,
                    deviceAddresses = "[\"AA:BB:CC:DD:EE:FF\"]",
                    locationIds = "[1,2,3]",
                    threatScore = 0.75,
                    detectionDetails = "{}",
                )
            val detectionResult = createTestDetectionResult(0.75) // Same score

            coEvery {
                alertRepository.getLatestAlertForDevice(any())
            } returns previousAlert
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 11L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then - should allow (>= 72 hours periodic reminder)
            assertNotNull("Should allow periodic reminder after 72 hours", alertId)
        }

    @Test
    fun `generateAlert allows first alert for new device`() =
        runTest {
            // Given - no previous alert for this device
            val detectionResult = createTestDetectionResult(0.75)

            coEvery {
                alertRepository.getLatestAlertForDevice(any())
            } returns null
            coEvery {
                alertRepository.insertAlertWithThrottling(any(), any())
            } returns 1L

            // When
            val alertId = alertGenerator.generateAlert(detectionResult)

            // Then - should allow (first alert)
            assertNotNull("Should allow first alert for new device", alertId)
        }

    // Helper functions

    private fun createTestDevice(
        address: String,
        name: String? = null,
    ): ScannedDevice {
        return ScannedDevice(
            id = 1L,
            address = address,
            name = name,
            // 1 day ago
            firstSeen = System.currentTimeMillis() - 86400000L,
            lastSeen = System.currentTimeMillis(),
            detectionCount = 10,
        )
    }

    private fun createTestLocations(count: Int): List<Location> {
        val currentTime = System.currentTimeMillis()
        return (0 until count).map { index ->
            Location(
                id = index.toLong() + 1,
                latitude = 40.7128 + index * 0.01,
                longitude = -74.0060 + index * 0.01,
                accuracy = 10.0f,
                // 1 hour apart
                timestamp = currentTime - (count - index) * 3600000L,
                provider = "GPS",
            )
        }
    }

    private fun createTestDetectionResult(threatScore: Double): DetectionResult {
        return DetectionResult(
            device = createTestDevice("AA:BB:CC:DD:EE:FF", "Test Device"),
            locations = createTestLocations(3),
            threatScore = threatScore,
            maxDistance = 2000.0,
            avgDistance = 1500.0,
            detectionReason = "Test detection reason",
        )
    }
}
