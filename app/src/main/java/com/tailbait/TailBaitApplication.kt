package com.tailbait

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tailbait.service.DataCleanupWorker
import com.tailbait.service.DetectionWorker
import com.tailbait.util.Constants

import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application class for TailBait
 *
 * This class initializes:
 * - Hilt dependency injection
 * - Timber logging
 * - Notification channels
 * - WorkManager configuration
 * - Periodic detection worker
 */
@HiltAndroidApp
class TailBaitApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory



    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Create notification channels
        createNotificationChannels()

        // Schedule periodic detection worker
        scheduleDetectionWorker()

        // Schedule periodic data cleanup worker
        scheduleDataCleanupWorker()

        // Schedule periodic background scanning (every 15 minutes)


        Timber.d("TailBait Application initialized")
    }

    /**
     * Create notification channels for different alert levels and service notifications
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Foreground Service Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "TailBait Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for BLE scanning service"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Alert Channels
            val lowAlertChannel = NotificationChannel(
                CHANNEL_ID_ALERT_LOW,
                "Low Priority Alerts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low priority device detection alerts"
            }
            notificationManager.createNotificationChannel(lowAlertChannel)

            val mediumAlertChannel = NotificationChannel(
                CHANNEL_ID_ALERT_MEDIUM,
                "Medium Priority Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Medium priority device detection alerts"
            }
            notificationManager.createNotificationChannel(mediumAlertChannel)

            val highAlertChannel = NotificationChannel(
                CHANNEL_ID_ALERT_HIGH,
                "High Priority Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority device detection alerts"
            }
            notificationManager.createNotificationChannel(highAlertChannel)

            val criticalAlertChannel = NotificationChannel(
                CHANNEL_ID_ALERT_CRITICAL,
                "Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical device detection alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }
            notificationManager.createNotificationChannel(criticalAlertChannel)

            Timber.d("Notification channels created")
        }
    }

    /**
     * Schedule periodic detection worker.
     *
     * The detection worker runs every 15 minutes to analyze BLE scan data
     * and generate alerts for suspicious devices.
     */
    private fun scheduleDetectionWorker() {
        val constraints = Constraints.Builder()
            // Detection is critical, run regardless of battery level
            // No network required (all data is local)
            .build()

        val detectionWorkRequest = PeriodicWorkRequestBuilder<DetectionWorker>(
            Constants.DETECTION_WORKER_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(Constants.WORK_TAG_DETECTION)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            Constants.DETECTION_WORKER_NAME,
            // Keep existing work if already scheduled
            ExistingPeriodicWorkPolicy.KEEP,
            detectionWorkRequest
        )

        Timber.d("Detection worker scheduled (interval: ${Constants.DETECTION_WORKER_INTERVAL_MINUTES} minutes)")
    }

    /**
     * Schedule periodic data cleanup worker.
     *
     * The cleanup worker runs daily to remove old data based on the user's
     * retention settings and perform database maintenance operations.
     */
    private fun scheduleDataCleanupWorker() {
        val constraints = Constraints.Builder()
            // No specific constraints needed for cleanup
            // Runs regardless of battery or network status
            .build()

        val cleanupWorkRequest = PeriodicWorkRequestBuilder<DataCleanupWorker>(
            Constants.CLEANUP_WORKER_INTERVAL_DAYS,
            TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .addTag(Constants.WORK_TAG_CLEANUP)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            Constants.CLEANUP_WORKER_NAME,
            // Keep existing work if already scheduled
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWorkRequest
        )

        Timber.d("Data cleanup worker scheduled (interval: ${Constants.CLEANUP_WORKER_INTERVAL_DAYS} day(s))")
    }

    /**
     * Schedule periodic background scanning worker
     *
     * This worker runs every 15 minutes (WorkManager minimum) and performs
     * BLE scans when the device has moved more than 150m from the last location.
     */


    /**
     * Provide WorkManager configuration with Hilt worker factory
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    companion object {
        // Notification Channel IDs
        const val CHANNEL_ID_SERVICE = "tailbait_service"
        const val CHANNEL_ID_ALERT_LOW = "tailbait_alert_low"
        const val CHANNEL_ID_ALERT_MEDIUM = "tailbait_alert_medium"
        const val CHANNEL_ID_ALERT_HIGH = "tailbait_alert_high"
        const val CHANNEL_ID_ALERT_CRITICAL = "tailbait_alert_critical"
    }
}
