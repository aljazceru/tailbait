package com.tailbait.data.repository

import app.cash.turbine.test
import com.tailbait.data.database.dao.AlertHistoryDao
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AlertStatistic
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for AlertRepository.
 *
 * Tests cover:
 * - CRUD operations
 * - Filtering queries (by level, date, dismissed status)
 * - Dismissal functionality
 * - Statistics queries (count by level, threat score aggregates)
 * - Flow-based reactive queries
 * - Edge cases and error handling
 */
class AlertRepositoryTest {

    private lateinit var alertHistoryDao: AlertHistoryDao
    private lateinit var repository: AlertRepository

    // Test data
    private val timestamp = System.currentTimeMillis()

    private val lowAlert = AlertHistory(
        id = 1L,
        alertLevel = AlertLevel.LOW,
        title = "Low Threat Alert",
        message = "Device detected at 2 locations",
        timestamp = timestamp,
        deviceAddresses = "[\"AA:BB:CC:DD:EE:01\"]",
        locationIds = "[1, 2]",
        threatScore = 0.3,
        detectionDetails = "{}",
        isDismissed = false,
        dismissedAt = null
    )

    private val mediumAlert = AlertHistory(
        id = 2L,
        alertLevel = AlertLevel.MEDIUM,
        title = "Medium Threat Alert",
        message = "Device detected at 3 locations",
        timestamp = timestamp - 1000,
        deviceAddresses = "[\"AA:BB:CC:DD:EE:02\"]",
        locationIds = "[1, 2, 3]",
        threatScore = 0.5,
        detectionDetails = "{}",
        isDismissed = false,
        dismissedAt = null
    )

    private val highAlert = AlertHistory(
        id = 3L,
        alertLevel = AlertLevel.HIGH,
        title = "High Threat Alert",
        message = "Device detected at 5 locations",
        timestamp = timestamp - 2000,
        deviceAddresses = "[\"AA:BB:CC:DD:EE:03\"]",
        locationIds = "[1, 2, 3, 4, 5]",
        threatScore = 0.75,
        detectionDetails = "{}",
        isDismissed = true,
        dismissedAt = timestamp - 1000
    )

    private val criticalAlert = AlertHistory(
        id = 4L,
        alertLevel = AlertLevel.CRITICAL,
        title = "Critical Threat Alert",
        message = "Device detected at 7 locations",
        timestamp = timestamp - 3000,
        deviceAddresses = "[\"AA:BB:CC:DD:EE:04\"]",
        locationIds = "[1, 2, 3, 4, 5, 6, 7]",
        threatScore = 0.95,
        detectionDetails = "{}",
        isDismissed = false,
        dismissedAt = null
    )

    private val allAlerts = listOf(lowAlert, mediumAlert, highAlert, criticalAlert)
    private val activeAlerts = listOf(lowAlert, mediumAlert, criticalAlert)
    private val dismissedAlerts = listOf(highAlert)

    @Before
    fun setup() {
        alertHistoryDao = mockk(relaxed = true)
        repository = AlertRepositoryImpl(alertHistoryDao)
    }

    // ========== CRUD Operations Tests ==========

    @Test
    fun `insertAlert returns valid ID`() = runTest {
        // Given
        val expectedId = 5L
        coEvery { alertHistoryDao.insert(lowAlert) } returns expectedId

        // When
        val actualId = repository.insertAlert(lowAlert)

        // Then
        assertEquals(expectedId, actualId)
        coVerify { alertHistoryDao.insert(lowAlert) }
    }

    @Test
    fun `insertAlerts returns list of IDs`() = runTest {
        // Given
        val alerts = listOf(lowAlert, mediumAlert)
        val expectedIds = listOf(1L, 2L)
        coEvery { alertHistoryDao.insertAll(alerts) } returns expectedIds

        // When
        val actualIds = repository.insertAlerts(alerts)

        // Then
        assertEquals(expectedIds, actualIds)
        coVerify { alertHistoryDao.insertAll(alerts) }
    }

    @Test
    fun `getAlertById returns alert when exists`() = runTest {
        // Given
        coEvery { alertHistoryDao.getById(1L) } returns lowAlert

        // When
        val result = repository.getAlertById(1L)

        // Then
        assertNotNull(result)
        assertEquals(lowAlert, result)
        coVerify { alertHistoryDao.getById(1L) }
    }

    @Test
    fun `getAlertById returns null when not found`() = runTest {
        // Given
        coEvery { alertHistoryDao.getById(999L) } returns null

        // When
        val result = repository.getAlertById(999L)

        // Then
        assertNull(result)
        coVerify { alertHistoryDao.getById(999L) }
    }

    @Test
    fun `updateAlert calls DAO update`() = runTest {
        // Given
        val updatedAlert = lowAlert.copy(isDismissed = true)

        // When
        repository.updateAlert(updatedAlert)

        // Then
        coVerify { alertHistoryDao.update(updatedAlert) }
    }

    @Test
    fun `deleteAlert calls DAO delete`() = runTest {
        // When
        repository.deleteAlert(lowAlert)

        // Then
        coVerify { alertHistoryDao.delete(lowAlert) }
    }

    @Test
    fun `getLatestAlert returns most recent alert`() = runTest {
        // Given
        coEvery { alertHistoryDao.getLatestAlert() } returns lowAlert

        // When
        val result = repository.getLatestAlert()

        // Then
        assertNotNull(result)
        assertEquals(lowAlert, result)
        coVerify { alertHistoryDao.getLatestAlert() }
    }

    // ========== Flow-based Queries Tests ==========

    @Test
    fun `getAllAlerts emits all alerts`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getAllAlerts().test {
            val alerts = awaitItem()
            assertEquals(4, alerts.size)
            assertEquals(allAlerts, alerts)
            awaitComplete()
        }
    }

    @Test
    fun `getActiveAlerts emits only non-dismissed alerts`() = runTest {
        // Given
        every { alertHistoryDao.getActiveAlerts() } returns flowOf(activeAlerts)

        // When & Then
        repository.getActiveAlerts().test {
            val alerts = awaitItem()
            assertEquals(3, alerts.size)
            assertTrue(alerts.all { !it.isDismissed })
            awaitComplete()
        }
    }

    @Test
    fun `getDismissedAlerts emits only dismissed alerts`() = runTest {
        // Given
        every { alertHistoryDao.getDismissedAlerts() } returns flowOf(dismissedAlerts)

        // When & Then
        repository.getDismissedAlerts().test {
            val alerts = awaitItem()
            assertEquals(1, alerts.size)
            assertTrue(alerts.all { it.isDismissed })
            awaitComplete()
        }
    }

    @Test
    fun `getUndismissedAlerts returns active alerts`() = runTest {
        // Given
        every { alertHistoryDao.getActiveAlerts() } returns flowOf(activeAlerts)

        // When & Then
        repository.getUndismissedAlerts().test {
            val alerts = awaitItem()
            assertEquals(3, alerts.size)
            assertEquals(activeAlerts, alerts)
            awaitComplete()
        }
    }

    // ========== Filtering Queries Tests ==========

    @Test
    fun `getAlertsByLevel filters by severity level`() = runTest {
        // Given
        val lowAlerts = listOf(lowAlert)
        every { alertHistoryDao.getAlertsByLevel(AlertLevel.LOW) } returns flowOf(lowAlerts)

        // When & Then
        repository.getAlertsByLevel(AlertLevel.LOW).test {
            val alerts = awaitItem()
            assertEquals(1, alerts.size)
            assertEquals(AlertLevel.LOW, alerts[0].alertLevel)
            awaitComplete()
        }
    }

    @Test
    fun `getActiveAlertsByLevel filters by level and active status`() = runTest {
        // Given
        val activeLowAlerts = listOf(lowAlert)
        every { alertHistoryDao.getActiveAlertsByLevel(AlertLevel.LOW) } returns flowOf(activeLowAlerts)

        // When & Then
        repository.getActiveAlertsByLevel(AlertLevel.LOW).test {
            val alerts = awaitItem()
            assertEquals(1, alerts.size)
            assertEquals(AlertLevel.LOW, alerts[0].alertLevel)
            assertFalse(alerts[0].isDismissed)
            awaitComplete()
        }
    }

    @Test
    fun `getAlertsByTimeRange filters by timestamp`() = runTest {
        // Given
        val startTime = timestamp - 5000
        val endTime = timestamp
        val recentAlerts = listOf(lowAlert, mediumAlert)
        every {
            alertHistoryDao.getAlertsByTimeRange(startTime, endTime)
        } returns flowOf(recentAlerts)

        // When & Then
        repository.getAlertsByTimeRange(startTime, endTime).test {
            val alerts = awaitItem()
            assertEquals(2, alerts.size)
            assertTrue(alerts.all { it.timestamp in startTime..endTime })
            awaitComplete()
        }
    }

    @Test
    fun `getRecentAlerts returns alerts after threshold`() = runTest {
        // Given
        val sinceTimestamp = timestamp - 2500
        val recentAlerts = listOf(lowAlert, mediumAlert)
        every { alertHistoryDao.getRecentAlerts(sinceTimestamp) } returns flowOf(recentAlerts)

        // When & Then
        repository.getRecentAlerts(sinceTimestamp).test {
            val alerts = awaitItem()
            assertEquals(2, alerts.size)
            awaitComplete()
        }
    }

    @Test
    fun `getRecentActiveAlerts returns recent non-dismissed alerts`() = runTest {
        // Given
        val sinceTimestamp = timestamp - 2500
        val recentActive = listOf(lowAlert, mediumAlert)
        every {
            alertHistoryDao.getRecentActiveAlerts(sinceTimestamp)
        } returns flowOf(recentActive)

        // When & Then
        repository.getRecentActiveAlerts(sinceTimestamp).test {
            val alerts = awaitItem()
            assertEquals(2, alerts.size)
            assertTrue(alerts.all { !it.isDismissed })
            awaitComplete()
        }
    }

    @Test
    fun `getHighThreatAlerts filters by minimum threat score`() = runTest {
        // Given
        val minScore = 0.7
        val highThreatAlerts = listOf(highAlert, criticalAlert)
        every { alertHistoryDao.getHighThreatAlerts(minScore) } returns flowOf(highThreatAlerts)

        // When & Then
        repository.getHighThreatAlerts(minScore).test {
            val alerts = awaitItem()
            assertEquals(2, alerts.size)
            assertTrue(alerts.all { it.threatScore >= minScore })
            awaitComplete()
        }
    }

    @Test
    fun `getTopThreatAlerts returns N highest threat score alerts`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getTopThreatAlerts(2).test {
            val alerts = awaitItem()
            assertEquals(2, alerts.size)
            assertEquals(criticalAlert.id, alerts[0].id) // Highest score first
            assertEquals(highAlert.id, alerts[1].id)
            awaitComplete()
        }
    }

    @Test
    fun `getTopThreatAlerts with default limit returns 10 alerts`() = runTest {
        // Given
        val manyAlerts = (1..15).map { i ->
            lowAlert.copy(id = i.toLong(), threatScore = i * 0.05)
        }
        every { alertHistoryDao.getAllAlerts() } returns flowOf(manyAlerts)

        // When & Then
        repository.getTopThreatAlerts().test {
            val alerts = awaitItem()
            assertEquals(10, alerts.size)
            // Verify sorted by threat score descending
            for (i in 0 until alerts.size - 1) {
                assertTrue(alerts[i].threatScore >= alerts[i + 1].threatScore)
            }
            awaitComplete()
        }
    }

    // ========== Dismissal Functionality Tests ==========

    @Test
    fun `dismissAlert marks alert as dismissed successfully`() = runTest {
        // Given
        coEvery { alertHistoryDao.dismissAlert(any(), any()) } returns Unit

        // When
        val result = repository.dismissAlert(1L)

        // Then
        assertTrue(result)
        coVerify { alertHistoryDao.dismissAlert(1L, any()) }
    }

    @Test
    fun `dismissAlert returns false on exception`() = runTest {
        // Given
        coEvery { alertHistoryDao.dismissAlert(any(), any()) } throws Exception("Database error")

        // When
        val result = repository.dismissAlert(1L)

        // Then
        assertFalse(result)
    }

    @Test
    fun `dismissAlerts marks multiple alerts as dismissed`() = runTest {
        // Given
        val alertIds = listOf(1L, 2L, 3L)
        coEvery { alertHistoryDao.dismissAlerts(any(), any()) } returns Unit

        // When
        val result = repository.dismissAlerts(alertIds)

        // Then
        assertTrue(result)
        coVerify { alertHistoryDao.dismissAlerts(alertIds, any()) }
    }

    @Test
    fun `dismissAlerts returns false on exception`() = runTest {
        // Given
        coEvery { alertHistoryDao.dismissAlerts(any(), any()) } throws Exception("Database error")

        // When
        val result = repository.dismissAlerts(listOf(1L, 2L))

        // Then
        assertFalse(result)
    }

    @Test
    fun `dismissAllAlerts returns count of dismissed alerts`() = runTest {
        // Given
        val expectedCount = 5
        coEvery { alertHistoryDao.dismissAllAlerts(any()) } returns expectedCount

        // When
        val actualCount = repository.dismissAllAlerts()

        // Then
        assertEquals(expectedCount, actualCount)
        coVerify { alertHistoryDao.dismissAllAlerts(any()) }
    }

    // ========== Statistics Queries Tests ==========

    @Test
    fun `getAlertCount returns total alert count`() = runTest {
        // Given
        every { alertHistoryDao.getAlertCount() } returns flowOf(4)

        // When & Then
        repository.getAlertCount().test {
            val count = awaitItem()
            assertEquals(4, count)
            awaitComplete()
        }
    }

    @Test
    fun `getActiveAlertCount returns active alert count`() = runTest {
        // Given
        every { alertHistoryDao.getActiveAlertCount() } returns flowOf(3)

        // When & Then
        repository.getActiveAlertCount().test {
            val count = awaitItem()
            assertEquals(3, count)
            awaitComplete()
        }
    }

    @Test
    fun `getAlertCountByLevel returns count for specific level`() = runTest {
        // Given
        every { alertHistoryDao.getAlertCountByLevel(AlertLevel.HIGH) } returns flowOf(1)

        // When & Then
        repository.getAlertCountByLevel(AlertLevel.HIGH).test {
            val count = awaitItem()
            assertEquals(1, count)
            awaitComplete()
        }
    }

    @Test
    fun `getActiveAlertCountByLevel returns active count for level`() = runTest {
        // Given
        every { alertHistoryDao.getActiveAlertCountByLevel(AlertLevel.LOW) } returns flowOf(1)

        // When & Then
        repository.getActiveAlertCountByLevel(AlertLevel.LOW).test {
            val count = awaitItem()
            assertEquals(1, count)
            awaitComplete()
        }
    }

    @Test
    fun `getAlertStatistics returns list of alert statistics`() = runTest {
        // Given
        val expectedStats = listOf(
            AlertStatistic(AlertLevel.LOW, 1),
            AlertStatistic(AlertLevel.MEDIUM, 1),
            AlertStatistic(AlertLevel.HIGH, 1),
            AlertStatistic(AlertLevel.CRITICAL, 1)
        )
        coEvery { alertHistoryDao.getAlertStatistics() } returns expectedStats

        // When
        val actualStats = repository.getAlertStatistics()

        // Then
        assertEquals(expectedStats, actualStats)
        coVerify { alertHistoryDao.getAlertStatistics() }
    }

    @Test
    fun `getThreatScoreStatistics calculates correct aggregates`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getThreatScoreStatistics().test {
            val stats = awaitItem()

            assertEquals(4, stats.count)
            assertEquals(0.95, stats.maximum, 0.001)
            assertEquals(0.3, stats.minimum, 0.001)

            // Calculate expected average: (0.3 + 0.5 + 0.75 + 0.95) / 4 = 0.625
            assertEquals(0.625, stats.average, 0.001)

            awaitComplete()
        }
    }

    @Test
    fun `getThreatScoreStatistics returns zeros for empty list`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(emptyList())

        // When & Then
        repository.getThreatScoreStatistics().test {
            val stats = awaitItem()

            assertEquals(0, stats.count)
            assertEquals(0.0, stats.maximum, 0.001)
            assertEquals(0.0, stats.minimum, 0.001)
            assertEquals(0.0, stats.average, 0.001)

            awaitComplete()
        }
    }

    @Test
    fun `getAverageThreatScore returns correct average`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getAverageThreatScore().test {
            val average = awaitItem()
            // (0.3 + 0.5 + 0.75 + 0.95) / 4 = 0.625
            assertEquals(0.625, average, 0.001)
            awaitComplete()
        }
    }

    @Test
    fun `getAverageThreatScore returns zero for empty list`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(emptyList())

        // When & Then
        repository.getAverageThreatScore().test {
            val average = awaitItem()
            assertEquals(0.0, average, 0.001)
            awaitComplete()
        }
    }

    @Test
    fun `getMaxThreatScore returns highest score`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getMaxThreatScore().test {
            val max = awaitItem()
            assertEquals(0.95, max, 0.001)
            awaitComplete()
        }
    }

    @Test
    fun `getMaxThreatScore returns zero for empty list`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(emptyList())

        // When & Then
        repository.getMaxThreatScore().test {
            val max = awaitItem()
            assertEquals(0.0, max, 0.001)
            awaitComplete()
        }
    }

    // ========== Data Cleanup Tests ==========

    @Test
    fun `deleteOldAlerts removes alerts before timestamp`() = runTest {
        // Given
        val beforeTimestamp = timestamp - 10000
        val expectedCount = 3
        coEvery { alertHistoryDao.deleteOldAlerts(beforeTimestamp) } returns expectedCount

        // When
        val actualCount = repository.deleteOldAlerts(beforeTimestamp)

        // Then
        assertEquals(expectedCount, actualCount)
        coVerify { alertHistoryDao.deleteOldAlerts(beforeTimestamp) }
    }

    @Test
    fun `deleteOldDismissedAlerts removes old dismissed alerts`() = runTest {
        // Given
        val beforeTimestamp = timestamp - 10000
        val expectedCount = 2
        coEvery {
            alertHistoryDao.deleteOldDismissedAlerts(beforeTimestamp)
        } returns expectedCount

        // When
        val actualCount = repository.deleteOldDismissedAlerts(beforeTimestamp)

        // Then
        assertEquals(expectedCount, actualCount)
        coVerify { alertHistoryDao.deleteOldDismissedAlerts(beforeTimestamp) }
    }

    @Test
    fun `deleteAllAlerts removes all alerts`() = runTest {
        // When
        repository.deleteAllAlerts()

        // Then
        coVerify { alertHistoryDao.deleteAll() }
    }

    // ========== Edge Cases Tests ==========

    @Test
    fun `repository handles empty alert list gracefully`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(emptyList())

        // When & Then
        repository.getAllAlerts().test {
            val alerts = awaitItem()
            assertTrue(alerts.isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `getTopThreatAlerts handles limit larger than list size`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getTopThreatAlerts(100).test {
            val alerts = awaitItem()
            assertEquals(4, alerts.size) // Returns all 4 alerts
            awaitComplete()
        }
    }

    @Test
    fun `getTopThreatAlerts handles zero limit`() = runTest {
        // Given
        every { alertHistoryDao.getAllAlerts() } returns flowOf(allAlerts)

        // When & Then
        repository.getTopThreatAlerts(0).test {
            val alerts = awaitItem()
            assertTrue(alerts.isEmpty())
            awaitComplete()
        }
    }

    @Test
    fun `alert level constants have correct values`() {
        // Verify alert level constants
        assertEquals("LOW", AlertLevel.LOW)
        assertEquals("MEDIUM", AlertLevel.MEDIUM)
        assertEquals("HIGH", AlertLevel.HIGH)
        assertEquals("CRITICAL", AlertLevel.CRITICAL)
    }
}
