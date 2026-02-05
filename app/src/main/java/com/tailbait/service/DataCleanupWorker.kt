package com.tailbait.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tailbait.data.database.TailBaitDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * WorkManager worker for periodic data cleanup.
 *
 * The DataCleanupWorker runs daily to:
 * 1. Remove old records based on the user's data retention settings
 * 2. Optimize database performance by cleaning up orphaned records
 * 3. Free up storage space by deleting stale data
 *
 * This worker is configured to run with constraints:
 * - No network required (all operations are local)
 * - Preferably when device is charging (to minimize battery impact)
 * - Runs in background (does not require foreground service)
 *
 * The worker uses Hilt for dependency injection, allowing it to inject
 * the database instance.
 *
 * Scheduling:
 * The worker is scheduled by TailBaitApplication on app startup and runs
 * daily using PeriodicWorkRequest with the interval defined in Constants.
 *
 * Retry Policy:
 * - Linear backoff (1 hour initial delay)
 * - Maximum of 3 retry attempts
 * - Automatically retries on failure
 *
 * Work Constraints:
 * - Runs regardless of network status (all data is local)
 * - Preferably runs when charging (optional constraint)
 * - Runs in background (does not require foreground service)
 *
 * Result Types:
 * - Success: Cleanup completed successfully
 * - Retry: Temporary failure, will retry with backoff
 * - Failure: Permanent failure after max retries
 *
 * Usage:
 * This worker is automatically scheduled by the application and should not
 * be manually enqueued. To trigger a one-time cleanup, use WorkManager
 * to enqueue a OneTimeWorkRequest with the same worker class.
 *
 * @property database The BLE Tracker database instance
 */
@HiltWorker
class DataCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: TailBaitDatabase
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DataCleanupWorker"

        // Output data keys
        const val KEY_RECORDS_DELETED = "records_deleted"
        const val KEY_EXECUTION_TIME_MS = "execution_time_ms"
        const val KEY_CLEANUP_SUCCESS = "cleanup_success"
    }

    /**
     * Execute the data cleanup worker.
     *
     * This method:
     * 1. Retrieves the current data retention settings
     * 2. Deletes old records based on retention policy
     * 3. Performs database optimization
     * 4. Returns success/failure result with statistics
     *
     * @return Result indicating success or failure
     */
    override suspend fun doWork(): Result {
        Timber.d("[$TAG] Starting data cleanup worker (run attempt: $runAttemptCount)")
        val startTime = System.currentTimeMillis()

        try {
            // Get current settings to determine retention policy
            val settings = database.appSettingsDao().getSettings()
            if (settings == null) {
                Timber.w("[$TAG] No settings found, skipping cleanup")
                return Result.success()
            }

            val retentionDays = settings.dataRetentionDays
            Timber.i("[$TAG] Running cleanup with ${retentionDays}-day retention policy")

            // Perform database maintenance (includes deletion of old records)
            database.performMaintenance()

            // Calculate execution time
            val executionTime = System.currentTimeMillis() - startTime

            // Build output data
            val outputData = androidx.work.Data.Builder()
                .putBoolean(KEY_CLEANUP_SUCCESS, true)
                .putLong(KEY_EXECUTION_TIME_MS, executionTime)
                .build()

            Timber.i("[$TAG] Data cleanup completed successfully in ${executionTime}ms")
            return Result.success(outputData)

        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Error during data cleanup worker execution")

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
