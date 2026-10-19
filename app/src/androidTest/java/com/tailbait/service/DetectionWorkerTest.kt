package com.tailbait.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.flows.DetectionFixture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectionWorkerTest {
    private lateinit var context: Context
    private lateinit var fixture: DetectionFixture

    @Before
    fun setup() =
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            fixture = DetectionFixture(context)
            fixture.initialize()
        }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun workerDetectsFollower_andReportsPersistedAlertCounts() =
        runBlocking {
            val device = fixture.addDevice(fixture.addPath())

            val result = createWorker().doWork()

            val output = successOutput(result)
            assertEquals(1, output.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, -1))
            assertEquals(1, output.getInt(DetectionWorker.KEY_ALERTS_GENERATED, -1))
            val alerts = fixture.alertRepository.getAllAlerts().first()
            assertEquals(1, alerts.size)
            assertTrue(alerts.single().deviceAddresses.contains(device.address))
        }

    @Test
    fun emptyDatabase_completesWithoutAlerts() =
        runBlocking {
            val output = successOutput(createWorker().doWork())

            assertEquals(0, output.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, -1))
            assertEquals(0, output.getInt(DetectionWorker.KEY_ALERTS_GENERATED, -1))
            assertTrue(fixture.alertRepository.getAllAlerts().first().isEmpty())
        }

    @Test
    fun workerUsesCurrentPersistedThreshold() =
        runBlocking {
            val path = fixture.addPath()
            fixture.addDevice(path)
            fixture.settingsRepository.updateSettings(
                fixture.settingsRepository.getSettingsOnce().copy(alertThresholdCount = path.size + 1),
            )
            val output = successOutput(createWorker().doWork())

            assertEquals(0, output.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, -1))
            assertTrue(fixture.alertRepository.getAllAlerts().first().isEmpty())
            fixture.settingsRepository.updateSettings(
                fixture.settingsRepository.getSettingsOnce().copy(alertThresholdCount = 3),
            )
            val enabledOutput = successOutput(createWorker().doWork())
            assertEquals(1, enabledOutput.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, -1))
            assertEquals(1, enabledOutput.getInt(DetectionWorker.KEY_ALERTS_GENERATED, -1))
            assertEquals(1, fixture.alertRepository.getAllAlerts().first().size)
        }

    @Test
    fun repeatedWorkerRun_keepsDetectionButDoesNotDuplicatePersistedAlert() =
        runBlocking {
            fixture.addDevice(fixture.addPath())
            val input =
                Data.Builder()
                    .putLong(DetectionWorker.KEY_THROTTLE_WINDOW_MS, 6 * DetectionFixture.HOUR_MS)
                    .build()
            val first = successOutput(createWorker(input).doWork())
            assertEquals(1, first.getInt(DetectionWorker.KEY_ALERTS_GENERATED, -1))

            val repeated = successOutput(createWorker(input).doWork())

            assertEquals(1, repeated.getInt(DetectionWorker.KEY_DETECTIONS_FOUND, -1))
            assertEquals(0, repeated.getInt(DetectionWorker.KEY_ALERTS_GENERATED, -1))
            assertEquals(1, fixture.alertRepository.getAllAlerts().first().size)
        }

    @Test
    fun detectionFailure_retriesBeforeLimit_andFailsAtLimit() =
        runBlocking {
            val failingAlgorithm = mockk<DetectionAlgorithm>()
            coEvery { failingAlgorithm.runDetection() } throws IllegalStateException("Database unavailable")

            assertEquals(
                ListenableWorker.Result.retry(),
                createWorker(runAttemptCount = 0, algorithm = failingAlgorithm).doWork(),
            )
            assertEquals(
                ListenableWorker.Result.retry(),
                createWorker(runAttemptCount = 2, algorithm = failingAlgorithm).doWork(),
            )
            assertEquals(
                ListenableWorker.Result.failure(),
                createWorker(runAttemptCount = 3, algorithm = failingAlgorithm).doWork(),
            )
            assertTrue(fixture.alertRepository.getAllAlerts().first().isEmpty())
        }

    private fun createWorker(
        input: Data = Data.EMPTY,
        runAttemptCount: Int = 0,
        algorithm: DetectionAlgorithm = fixture.algorithm,
    ): DetectionWorker {
        // Only WorkManager's scheduling boundary is mocked; no WorkManager singleton or disk database is initialized.
        val parameters = mockk<WorkerParameters>(relaxed = true)
        every { parameters.inputData } returns input
        every { parameters.runAttemptCount } returns runAttemptCount
        return DetectionWorker(context, parameters, algorithm, fixture.alertGenerator)
    }

    private fun successOutput(result: ListenableWorker.Result): Data {
        assertTrue("Expected success, got $result", result is ListenableWorker.Result.Success)
        return (result as ListenableWorker.Result.Success).outputData
    }
}
