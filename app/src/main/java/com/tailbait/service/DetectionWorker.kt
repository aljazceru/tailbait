package com.tailbait.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * WorkManager worker for periodic device detection.
 *
 * The DetectionWorker runs periodically (default: every 15 minutes) to:
 * 1. Execute the detection algorithm to find suspicious devices
 * 2. Generate alerts for any detected suspicious devices
 * 3. Handle errors and retries with exponential backoff
 *
 * This worker is configured to run with constraints:
 * - Battery not low (optional, can be configured)
 * - No network required (all data is local)
 *
 * The worker uses Hilt for dependency injection, allowing it to inject
 * the DetectionAlgorithm and AlertGenerator services.
 *
 * Scheduling:
 * The worker is scheduled by TailBaitApplication on app startup and runs
 * periodically using PeriodicWorkRequest with the interval defined in Constants.
 *
 * Retry Policy:
 * - Linear backoff (10 seconds initial delay)
 * - Maximum of 3 retry attempts
 * - Automatically retries on failure
 *
 * Work Constraints:
 * - Runs regardless of battery level (stalking detection is critical)
 * - Runs regardless of network status (all data is local)
 * - Runs in background (does not require foreground service)
 *
 * Result Types:
 * - Success: Detection completed successfully (with or without findings)
 * - Retry: Temporary failure, will retry with backoff
 * - Failure: Permanent failure after max retries
 *
 * Usage:
 * This worker is automatically scheduled by the application and should not
 * be manually enqueued. To trigger a one-time detection, use WorkManager
 * to enqueue a OneTimeWorkRequest with the same worker class.
 */
@HiltWorker
class DetectionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val detectionAlgorithm: DetectionAlgorithm,
    private val alertGenerator: AlertGenerator
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DetectionWorker"

        // Input data keys
        const val KEY_MIN_LOCATION_COUNT = "min_location_count"
        const val KEY_MIN_THREAT_SCORE = "min_threat_score"
        const val KEY_THROTTLE_WINDOW_MS = "throttle_window_ms"

        // Output data keys
        const val KEY_DETECTIONS_FOUND = "detections_found"
        const val KEY_ALERTS_GENERATED = "alerts_generated"
        const val KEY_EXECUTION_TIME_MS = "execution_time_ms"
    }

    /**
     * Execute the detection worker.
     *
     * This method:
     * 1. Retrieves configuration from input data
     * 2. Runs the detection algorithm
     * 3. Generates alerts for suspicious devices
     * 4. Returns success/failure result
     *
     * @return Result indicating success or failure
     */
    override suspend fun doWork(): Result {
        Timber.d("[$TAG] Starting detection worker (run attempt: $runAttemptCount)")
        val startTime = System.currentTimeMillis()

        try {
            // Get configuration from input data or use defaults
            val throttleWindowMs = inputData.getLong(
                KEY_THROTTLE_WINDOW_MS,
                60 * 60 * 1000L // 1 hour default
            )

            // Run detection algorithm
            Timber.d("[$TAG] Running detection algorithm...")
            val detectionResults = detectionAlgorithm.runDetection()

            Timber.d("[$TAG] Detection complete: found ${detectionResults.size} suspicious devices")

            // Generate alerts for detections
            if (detectionResults.isNotEmpty()) {
                Timber.d("[$TAG] Generating alerts for ${detectionResults.size} detections...")
                val alertIds = alertGenerator.generateAlerts(
                    detectionResults = detectionResults,
                    throttleWindowMs = throttleWindowMs
                )
                Timber.d(
                    "[$TAG] Generated ${alertIds.size} alerts " +
                        "(${detectionResults.size - alertIds.size} throttled)"
                )
            } else {
                Timber.d("[$TAG] No suspicious devices detected")
            }

            // Calculate execution time
            val executionTime = System.currentTimeMillis() - startTime

            // Build output data
            val outputData = androidx.work.Data.Builder()
                .putInt(KEY_DETECTIONS_FOUND, detectionResults.size)
                .putInt(
                    KEY_ALERTS_GENERATED,
                    if (detectionResults.isNotEmpty()) detectionResults.size else 0
                )
                .putLong(KEY_EXECUTION_TIME_MS, executionTime)
                .build()

            Timber.d("[$TAG] Detection worker completed successfully in ${executionTime}ms")
            return Result.success(outputData)

        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Error during detection worker execution")

            // Determine if we should retry
            return if (runAttemptCount < 3) {
                Timber.w("[$TAG] Will retry (attempt ${runAttemptCount + 1}/3)")
                Result.retry()
            } else {
                Timber.e("[$TAG] Max retries exceeded, failing permanently")
                Result.failure()
            }
        }
    }
}
