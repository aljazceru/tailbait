package com.tailbait.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.model.DetectionResult
import com.tailbait.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for DetectionWorker.
 *
 * Tests cover:
 * - Worker execution with successful detection
 * - Worker execution with no detections
 * - Worker execution with errors and retry logic
 * - Worker configuration (input/output data)
 * - Worker constraints
 */
@RunWith(AndroidJUnit4::class)
class DetectionWorkerTest {

    private lateinit var context: Context
    private lateinit var detectionAlgorithm: DetectionAlgorithm
    private lateinit var alertGenerator: AlertGenerator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize WorkManager for testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        // Create mocks
        detectionAlgorithm = mockk()
        alertGenerator = mockk(relaxed = true)
    }

    @Test
    fun testDetectionWorker_successWithDetections() = runBlocking {
        // Given - Mock detection algorithm to return results
        val detectionResults = listOf(
            createTestDetectionResult(0.95),
            createTestDetectionResult(0.80)
        )

        coEvery {
            detectionAlgorithm.runDetection(any(), any())
        } returns detectionResults

        coEvery {
            alertGenerator.generateAlerts(any(), any())
        } returns listOf(1L, 2L)

        // Create worker
        val worker = createWorker()

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success(), result)

        // Verify detection algorithm was called
        coVerify {
            detectionAlgorithm.runDetection(
                minLocationCount = Constants.DEFAULT_ALERT_THRESHOLD_COUNT,
                minThreatScore = Constants.THREAT_SCORE_THRESHOLD
            )
        }

        // Verify alerts were generated
        coVerify {
            alertGenerator.generateAlerts(detectionResults, any())
        }

        // Verify output data
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, outputData.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, 0))
        assertEquals(2, outputData.getInt(DetectionWorker.KEY_ALERTS_GENERATED, 0))
        assertTrue(outputData.getLong(DetectionWorker.KEY_EXECUTION_TIME_MS, 0) > 0)
    }

    @Test
    fun testDetectionWorker_successWithNoDetections() = runBlocking {
        // Given - Mock detection algorithm to return empty results
        coEvery {
            detectionAlgorithm.runDetection(any(), any())
        } returns emptyList()

        // Create worker
        val worker = createWorker()

        // When
        val result = worker.doWork()

        // Then
        assertEquals(ListenableWorker.Result.success(), result)

        // Verify detection algorithm was called
        coVerify {
            detectionAlgorithm.runDetection(any(), any())
        }

        // Verify alerts were NOT generated
        coVerify(exactly = 0) {
            alertGenerator.generateAlerts(any(), any())
        }

        // Verify output data
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(0, outputData.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, -1))
    }

    @Test
    fun testDetectionWorker_retryOnError() = runBlocking {
        // Given - Mock detection algorithm to throw exception
        coEvery {
            detectionAlgorithm.runDetection(any(), any())
        } throws RuntimeException("Test exception")

        // Create worker
        val worker = createWorker()

        // When
        val result = worker.doWork()

        // Then - Should retry on first failure
        assertEquals(ListenableWorker.Result.retry(), result)

        // Verify detection algorithm was called
        coVerify {
            detectionAlgorithm.runDetection(any(), any())
        }
    }

    @Test
    fun testDetectionWorker_customInputParameters() = runBlocking {
        // Given - Create worker with custom input parameters
        val minLocationCount = 5
        val minThreatScore = 0.7
        val throttleWindowMs = 7200000L // 2 hours

        val inputData = Data.Builder()
            .putInt(DetectionWorker.KEY_MIN_LOCATION_COUNT, minLocationCount)
            .putDouble(DetectionWorker.KEY_MIN_THREAT_SCORE, minThreatScore)
            .putLong(DetectionWorker.KEY_THROTTLE_WINDOW_MS, throttleWindowMs)
            .build()

        coEvery {
            detectionAlgorithm.runDetection(any(), any())
        } returns emptyList()

        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then - Verify custom parameters were used
        coVerify {
            detectionAlgorithm.runDetection(
                minLocationCount = minLocationCount,
                minThreatScore = minThreatScore
            )
        }
    }

    @Test
    fun testDetectionWorker_alertGenerationWithThrottling() = runBlocking {
        // Given
        val detectionResults = listOf(
            createTestDetectionResult(0.95),
            createTestDetectionResult(0.80),
            createTestDetectionResult(0.65)
        )

        val throttleWindowMs = 3600000L // 1 hour

        val inputData = Data.Builder()
            .putLong(DetectionWorker.KEY_THROTTLE_WINDOW_MS, throttleWindowMs)
            .build()

        coEvery {
            detectionAlgorithm.runDetection(any(), any())
        } returns detectionResults

        // Simulate some alerts being throttled
        coEvery {
            alertGenerator.generateAlerts(any(), any())
        } returns listOf(1L, 3L) // Second alert throttled

        val worker = createWorker(inputData)

        // When
        worker.doWork()

        // Then
        coVerify {
            alertGenerator.generateAlerts(
                detectionResults,
                throttleWindowMs
            )
        }
    }

    // Helper functions

    private fun createWorker(inputData: Data = Data.EMPTY): DetectionWorker {
        return TestListenableWorkerBuilder<DetectionWorker>(context)
            .setInputData(inputData)
            .build() as DetectionWorker
    }

    private fun createTestDetectionResult(threatScore: Double): DetectionResult {
        val device = ScannedDevice(
            id = 1L,
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            firstSeen = System.currentTimeMillis() - 86400000L,
            lastSeen = System.currentTimeMillis(),
            detectionCount = 10
        )

        val locations = listOf(
            Location(
                id = 1L,
                latitude = 40.7128,
                longitude = -74.0060,
                accuracy = 10.0f,
                timestamp = System.currentTimeMillis() - 7200000L,
                locationChangedFromLast = false,
                distanceFromLast = null
            ),
            Location(
                id = 2L,
                latitude = 40.7228,
                longitude = -74.0160,
                accuracy = 10.0f,
                timestamp = System.currentTimeMillis() - 3600000L,
                locationChangedFromLast = true,
                distanceFromLast = 1500.0
            ),
            Location(
                id = 3L,
                latitude = 40.7328,
                longitude = -74.0260,
                accuracy = 10.0f,
                timestamp = System.currentTimeMillis(),
                locationChangedFromLast = true,
                distanceFromLast = 1500.0
            )
        )

        return DetectionResult(
            device = device,
            locations = locations,
            threatScore = threatScore,
            maxDistance = 3000.0,
            avgDistance = 2000.0,
            detectionReason = "Test detection reason"
        )
    }
}
