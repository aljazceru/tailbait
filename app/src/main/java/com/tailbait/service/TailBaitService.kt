package com.tailbait.service

import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.tailbait.R
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * TailBait Foreground Service
 *
 * This service runs in the foreground to perform continuous BLE scanning for device tracking.
 * It extends NotificationService to provide notification management functionality.
 *
 * Key Features:
 * - Foreground service with persistent notification
 * - Integrates with BleScannerManager for BLE scanning operations
 * - Supports start/stop/pause/resume tracking actions
 * - Updates notification based on scan state (scanning, idle, error)
 * - Provides service binding interface for UI communication
 * - Handles service crashes/restarts with START_STICKY
 * - Respects different tracking modes (CONTINUOUS, PERIODIC, LOCATION_BASED)
 *
 * Service Actions:
 * - ACTION_START_TRACKING: Begin BLE tracking
 * - ACTION_STOP_TRACKING: Stop tracking and terminate service
 * - ACTION_PAUSE_TRACKING: Temporarily pause scanning
 * - ACTION_RESUME_TRACKING: Resume scanning after pause
 *
 * @property bleScannerManager Manager for BLE scanning operations
 * @property settingsRepository Repository for app settings
 */
@AndroidEntryPoint
class TailBaitService : NotificationService() {

    @Inject
    lateinit var bleScannerManager: BleScannerManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var locationTracker: LocationTracker

    // Service binder for UI communication
    private val binder = LocalBinder()
    
    // ... (rest of class)

    // ... (inside startTracking)
    private fun startTracking() {
        if (isTracking) {
            Timber.w("Tracking already started")
            return
        }

        lifecycleScope.launch {
            try {
                // Update settings to enable tracking
                settingsRepository.updateTrackingEnabled(true)
                isTracking = true

                // Start location tracking
                val locationResult = locationTracker.startLocationTracking()
                if (locationResult.isFailure) {
                    Timber.e(locationResult.exceptionOrNull(), "Failed to start location tracking")
                }

                // Trigger first scan immediately via the new mechanism
                // This ensures consistent behavior with periodic scans
                handleTriggerScan()

                // Observe scan state and update notification accordingly
                // WakeLock is now managed based on scan state for battery optimization
                bleScannerManager.scanState
                    .onEach { state ->
                        handleScanStateChange(state)
                    }
                    .launchIn(lifecycleScope)

                Timber.i("Tracking started successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start tracking")
                updateNotification(
                    NOTIFICATION_ID,
                    createTrackingNotification(
                        title = "BLE Tracker Error",
                        message = "Failed to start tracking: ${e.message}",
                        isScanning = false
                    )
                )
            }
        }
    }

    /**
     * Handle the trigger scan action.
     * Accuires WakeLock, performs scan, and schedules next alarm.
     */
    private fun handleTriggerScan() {
        if (!isTracking) {
            Timber.w("Scan triggered but tracking is disabled")
            return
        }

        // Acquire safety WakeLock IMMEDIATELY to prevent Doze mode from suspending
        // the app before the coroutine even starts.
        acquireWakeLock(30 * 1000L) // 30 seconds safety buffer

        lifecycleScope.launch {
            try {
                // Perform the scan (this will suspend until scan + processing is complete)
                bleScannerManager.performManualScan(Constants.SCAN_TRIGGER_PERIODIC)

                // Schedule the next scan
                scheduleNextScan()

            } catch (e: Exception) {
                Timber.e(e, "Error during triggered scan")
                // Even on error, try to reschedule to keep the loop alive
                scheduleNextScan()
            } finally {
                releaseWakeLock()
            }
        }
    }

    /**
     * Schedule the next scan using AlarmManager.
     */
    private fun scheduleNextScan() {
        lifecycleScope.launch {
            try {
                val settings = settingsRepository.getSettingsOnce()
                if (!settings.isTrackingEnabled) return@launch

                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(this@TailBaitService, TailBaitService::class.java).apply {
                    action = ACTION_TRIGGER_SCAN
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getService(
                    this@TailBaitService,
                    REQUEST_CODE_TRIGGER_SCAN,
                    intent,
                    flags
                )

                // Use scanIntervalSeconds (default 300s = 5 min)
                val intervalMs = settings.scanIntervalSeconds * 1000L
                val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMs

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        Timber.w("SCHEDULE_EXACT_ALARM permission not granted, falling back to inexact alarm")
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }

                Timber.d("Scheduled next scan in ${settings.scanIntervalSeconds}s")
            } catch (e: Exception) {
                Timber.e(e, "Failed to schedule next scan")
            }
        }
    }

    // ...

    private fun stopTracking() {
        lifecycleScope.launch {
            try {
                // Release WakeLock
                releaseWakeLock()

                // Cancel any pending alarms
                cancelScheduledScan()

                // Update settings to disable tracking
                settingsRepository.updateTrackingEnabled(false)
                isTracking = false

                // Stop location tracking
                locationTracker.stopLocationTracking()

                // Stop BLE scanning
                bleScannerManager.stopScanning()

                Timber.i("Tracking stopped, terminating service")

                // Stop the foreground service
                stopSelf()
            } catch (e: Exception) {
                Timber.e(e, "Error stopping tracking")
                // Still try to stop the service even if there's an error
                stopSelf()
            }
        }
    }

    private fun cancelScheduledScan() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, TailBaitService::class.java).apply {
                action = ACTION_TRIGGER_SCAN
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_TRIGGER_SCAN,
                intent,
                flags
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Timber.d("Cancelled scheduled scans")
        } catch (e: Exception) {
            Timber.e(e, "Error cancelling scheduled scan")
        }
    }

    private fun pauseTracking() {
        lifecycleScope.launch {
            try {
                // Update settings to disable tracking (but keep service running)
                settingsRepository.updateTrackingEnabled(false)
                isTracking = false

                // Stop location tracking
                locationTracker.stopLocationTracking()

                // Stop scanning
                bleScannerManager.stopScanning()

                // Update notification to show paused state
                updateNotification(
                    NOTIFICATION_ID,
                    createTrackingNotification(
                        title = "BLE Tracker Paused",
                        message = "Tracking paused",
                        isScanning = false,
                        showResumeAction = true
                    )
                )

                Timber.i("Tracking paused")
            } catch (e: Exception) {
                Timber.e(e, "Error pausing tracking")
            }
        }
    }

    // Track if service is currently tracking
    private var isTracking = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private val WAKE_LOCK_TAG = "TailBait:WakeLock"

    /**
     * Local binder for service binding.
     * Provides direct access to the service instance for bound components.
     */
    inner class LocalBinder : Binder() {
        /**
         * Get the service instance.
         *
         * @return TailBaitService instance
         */
        fun getService(): TailBaitService = this@TailBaitService
    }

    // NotificationService abstract properties
    override val notificationChannelId: String = NOTIFICATION_CHANNEL_ID
    override val notificationChannelName: String = "TailBait Service"
    override val notificationChannelDescription: String = "Foreground service for BLE device tracking"

    /**
     * Service creation lifecycle callback.
     * Initializes the service and starts foreground notification.
     */
    override fun onCreate() {
        super.onCreate()
        Timber.i("TailBaitService created")

        // Initialize WakeLock once and keep it for the service lifetime
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(true)
        }

        // Note: WakeLock is acquired during active scans to save battery.
        // The foreground service notification is sufficient to keep the service alive.

        startForegroundService()
    }

    /**
     * Service start command handler.
     * Processes intent actions for start/stop/pause/resume tracking.
     *
     * @param intent Intent containing action
     * @param flags Additional data about start request
     * @param startId Unique ID for this start request
     * @return START_STICKY to restart service if killed by system
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Timber.d("onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")

        when (intent?.action) {
            ACTION_START_TRACKING -> {
                Timber.i("Starting tracking")
                startTracking()
            }
            ACTION_STOP_TRACKING -> {
                Timber.i("Stopping tracking")
                stopTracking()
            }
            ACTION_PAUSE_TRACKING -> {
                Timber.i("Pausing tracking")
                pauseTracking()
            }
            ACTION_RESUME_TRACKING -> {
                Timber.i("Resuming tracking")
                resumeTracking()
            }
            ACTION_TRIGGER_SCAN -> {
                Timber.i("Triggering periodic scan")
                handleTriggerScan()
            }
            else -> {
                Timber.w("Unknown action: ${intent?.action}")
            }
        }

        // Return START_STICKY to ensure service restarts if killed by system
        return START_STICKY
    }

    /**
     * Service binding callback.
     * Allows activities/fragments to bind to the service for direct communication.
     *
     * @param intent Intent used to bind to the service
     * @return IBinder for service communication
     */
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Timber.d("Service bound")
        return binder
    }

    /**
     * Service destruction lifecycle callback.
     * Cleans up resources when service is destroyed.
     */
    override fun onDestroy() {
        Timber.i("TailBaitService destroyed")

        // Release WakeLock if held (force cleanup)
        try {
            if (::wakeLock.isInitialized) {
                while (wakeLock.isHeld) {
                    wakeLock.release()
                    Timber.w("WakeLock released in onDestroy (was still held)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing WakeLock in onDestroy")
        }

        bleScannerManager.stopScanning()
        isTracking = false
        super.onDestroy()
    }

    /**
     * Start the foreground service with initial notification.
     * This must be called within 5 seconds of starting the service on Android 8.0+.
     */
    private fun startForegroundService() {
        val notification = createTrackingNotification(
            title = "BLE Tracker",
            message = "Service initialized",
            isScanning = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Timber.d("Foreground service started with notification")
    }

    /**
     * Acquire WakeLock to prevent system from killing the service during active scanning.
     * Uses a partial WakeLock to keep CPU running without keeping screen on.
     * Uses reference counting to safely handle nested acquisitions.
     *
     * @param timeoutMs Timeout in milliseconds (default: 2 minutes as safety buffer)
     */
    private fun acquireWakeLock(timeoutMs: Long = 2 * 60 * 1000L) {
        try {
            if (!::wakeLock.isInitialized) return

            // Always acquire to increment reference count and update timeout
            wakeLock.acquire(timeoutMs)
            Timber.i("WakeLock acquired (timeout: ${timeoutMs / 1000}s)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire WakeLock")
        }
    }

    /**
     * Release WakeLock when operation completes.
     * Decrements reference count.
     */
    private fun releaseWakeLock() {
        try {
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
                Timber.i("WakeLock released")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to release WakeLock")
        }
    }


    // Track if the scan state observer currently holds a WakeLock
    private var scanStateWakeLockHeld = false

    /**
     * Handle scan state changes with proper WakeLock management.
     * Acquires WakeLock only during active scans and releases it during idle periods.
     * Uses a flag to ensure we only hold 1 reference for the duration of the scan session,
     * preventing leaks when multiple Scanning(count) updates occur.
     */
    private fun handleScanStateChange(state: BleScannerManager.ScanState) {
        val shouldHoldLock = when (state) {
            is BleScannerManager.ScanState.Scanning,
            is BleScannerManager.ScanState.Processing -> true
            else -> false
        }

        if (shouldHoldLock) {
            if (!scanStateWakeLockHeld) {
                acquireWakeLock()
                scanStateWakeLockHeld = true
            }
        } else {
            if (scanStateWakeLockHeld) {
                releaseWakeLock()
                scanStateWakeLockHeld = false
            }
        }
        updateNotificationForScanState(state)
    }



    /**
     * Resume BLE tracking after pause.
     * Restarts scanning and observing scan state.
     */
    private fun resumeTracking() {
        Timber.i("Resuming tracking")
        startTracking()
    }

    /**
     * Update notification based on current scan state.
     *
     * @param scanState Current scan state from BleScannerManager
     */
    private fun updateNotificationForScanState(scanState: BleScannerManager.ScanState) {
        val notification = when (scanState) {
            is BleScannerManager.ScanState.Scanning -> {
                createTrackingNotification(
                    title = "BLE Tracker Active",
                    message = "Scanning for devices... (${scanState.devicesFound} found)",
                    isScanning = true
                )
            }
            is BleScannerManager.ScanState.Processing -> {
                createTrackingNotification(
                    title = "BLE Tracker Active",
                    message = "Processing scan results...",
                    isScanning = true // Show progress bar during processing too
                )
            }
            is BleScannerManager.ScanState.Idle -> {
                createTrackingNotification(
                    title = "BLE Tracker Active",
                    message = "Waiting for next scan",
                    isScanning = false
                )
            }
            is BleScannerManager.ScanState.Error -> {
                createTrackingNotification(
                    title = "BLE Tracker Error",
                    message = scanState.message,
                    isScanning = false
                )
            }
        }

        updateNotification(NOTIFICATION_ID, notification)
    }

    /**
     * Create notification for tracking service.
     * Includes action buttons for pause/stop or resume/stop.
     *
     * @param title Notification title
     * @param message Notification message
     * @param isScanning Whether currently scanning
     * @param showResumeAction Whether to show resume action (for paused state)
     * @return Built notification
     */
    private fun createTrackingNotification(
        title: String,
        message: String,
        isScanning: Boolean,
        showResumeAction: Boolean = false
    ): Notification {
        return createNotification(
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_LOW,
            onGoing = true
        ) {
            // Add action buttons
            if (showResumeAction) {
                // Paused state: show Resume and Stop buttons
                addAction(
                    createNotificationAction(
                        iconResId = R.drawable.ic_notification,
                        title = "Resume",
                        pendingIntent = createActionPendingIntent(
                            ACTION_RESUME_TRACKING,
                            REQUEST_CODE_RESUME
                        )
                    )
                )
            } else {
                // Active state: show Pause and Stop buttons
                addAction(
                    createNotificationAction(
                        iconResId = R.drawable.ic_notification,
                        title = "Pause",
                        pendingIntent = createActionPendingIntent(
                            ACTION_PAUSE_TRACKING,
                            REQUEST_CODE_PAUSE
                        )
                    )
                )
            }

            // Always show Stop button
            addAction(
                createNotificationAction(
                    iconResId = R.drawable.ic_notification,
                    title = "Stop",
                    pendingIntent = createActionPendingIntent(
                        ACTION_STOP_TRACKING,
                        REQUEST_CODE_STOP
                    )
                )
            )

            // Set style for expanded notification with progress if scanning
            if (isScanning) {
                // Indeterminate progress
                setProgress(0, 0, true)
            }
        }
    }

    /**
     * Get current tracking state.
     *
     * @return True if tracking is active
     */
    fun isTracking(): Boolean = isTracking

    /**
     * Get current scan state.
     *
     * @return Current scan state from BleScannerManager
     */
    fun getScanState(): BleScannerManager.ScanState = bleScannerManager.scanState.value

    companion object {
        // Notification configuration
        private const val NOTIFICATION_ID = Constants.SERVICE_NOTIFICATION_ID
        private const val NOTIFICATION_CHANNEL_ID = Constants.NOTIFICATION_CHANNEL_SERVICE

        // Service actions (use constants from Constants.kt)
        const val ACTION_START_TRACKING = Constants.ACTION_START_TRACKING
        const val ACTION_STOP_TRACKING = Constants.ACTION_STOP_TRACKING
        const val ACTION_PAUSE_TRACKING = Constants.ACTION_PAUSE_TRACKING
        const val ACTION_RESUME_TRACKING = Constants.ACTION_RESUME_TRACKING
        const val ACTION_TRIGGER_SCAN = Constants.ACTION_TRIGGER_SCAN

        // Request codes for PendingIntents
        private const val REQUEST_CODE_PAUSE = 1001
        private const val REQUEST_CODE_STOP = 1002
        private const val REQUEST_CODE_RESUME = 1003
        private const val REQUEST_CODE_TRIGGER_SCAN = 1004
    }
}
