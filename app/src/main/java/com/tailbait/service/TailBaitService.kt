package com.tailbait.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
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
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.companion.CompanionIngestor
import com.tailbait.companion.CompanionLinkManager
import com.tailbait.data.repository.CompanionDeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.Constants
import com.tailbait.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    lateinit var locationRepository: LocationRepository

    @Inject
    lateinit var locationTracker: LocationTracker

    @Inject
    lateinit var detectionAlgorithm: DetectionAlgorithm

    @Inject
    lateinit var companionLink: CompanionLinkManager

    @Inject
    lateinit var companionIngestor: CompanionIngestor

    @Inject
    lateinit var companionRepository: CompanionDeviceRepository

    @Inject
    lateinit var alertGenerator: AlertGenerator

    @Inject
    lateinit var permissionHelper: PermissionHelper

    private val transitionMutex = Mutex()
    private var settingsJob: Job? = null
    private var scanJob: Job? = null
    private var scanStateJob: Job? = null
    private var locationStateJob: Job? = null
    private var scanIntervalSeconds: Int? = null
    private var companionAddress: String? = null
    private var destroyed = false
    private var foregroundStarted = false
    private val binder = LocalBinder()

    private suspend fun transition(action: suspend () -> Unit) {
        transitionMutex.withLock {
            if (destroyed) return
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Tracking transition failed")
                stopResources()
                stopSelf()
            }
        }
    }

    private fun observeSettings() {
        if (settingsJob?.isActive == true) return
        settingsJob =
            lifecycleScope.launch {
                combine(settingsRepository.getSettings(), companionRepository.getFirst()) { settings, paired ->
                    Triple(
                        settings.isTrackingEnabled,
                        settings.scanIntervalSeconds,
                        paired?.takeIf { settings.companionEnabled && it.isEnabled }?.address,
                    )
                }.distinctUntilChanged().collect {
                    // Read current persisted values inside the transition, not a queued stale emission.
                    transition { applySettings() }
                }
            }
    }

    private fun hasTrackingPermissions(): Boolean = permissionHelper.hasLocationPermissions() && permissionHelper.hasBluetoothPermissions()

    private suspend fun applySettings() {
        val settings = settingsRepository.getSettingsOnce()
        if (!settings.isTrackingEnabled || !hasTrackingPermissions()) {
            stopResources()
            return
        }
        val paired = companionRepository.getFirst().firstOrNull()
        val address = paired?.takeIf { settings.companionEnabled && it.isEnabled }?.address
        if (!foregroundStarted) {
            startForegroundService()
            foregroundStarted = true
        }
        val starting = !isTracking
        val intervalChanged = scanIntervalSeconds != settings.scanIntervalSeconds
        isTracking = true
        if (starting || intervalChanged) {
            locationTracker.startLocationTracking(settings.scanIntervalSeconds).getOrThrow()
            scanIntervalSeconds = settings.scanIntervalSeconds
        }
        if (starting) {
            scanStateJob =
                bleScannerManager.scanState.onEach(::handleScanStateChange).launchIn(lifecycleScope)
            locationStateJob =
                locationTracker.locationState.onEach { state ->
                    if (state is LocationTracker.LocationState.LocationUpdated) {
                        handleLocationUpdate(state.location)
                    }
                }.launchIn(lifecycleScope)
        }
        if (companionAddress != address) {
            companionLink.stop()
            companionIngestor.detach()
            companionAddress = address
            if (address != null) {
                companionIngestor.attach(companionLink, lifecycleScope)
                companionLink.start(address)
            } else if (!starting) {
                handleTriggerScan()
            }
        }
        if (starting) {
            handleTriggerScan()
        } else if (intervalChanged) {
            scheduleNextScan()
        }
    }

    /**
     * Handle continuous location updates from LocationTracker.
     * Persists location and user path data regardless of scan results.
     */
    private suspend fun handleLocationUpdate(androidLocation: android.location.Location) {
        if (!isTracking) return
        try {
            val location =
                com.tailbait.data.database.entities.Location(
                    latitude = androidLocation.latitude,
                    longitude = androidLocation.longitude,
                    accuracy = androidLocation.accuracy,
                    altitude = if (androidLocation.hasAltitude()) androidLocation.altitude else null,
                    timestamp = androidLocation.time,
                    provider = androidLocation.provider ?: "FUSED",
                )
            val (locationId, _) = locationRepository.findOrCreateLocation(location, 50.0)
            locationRepository.insertUserPath(locationId, location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to record user path point")
        }
    }

    private fun handleTriggerScan() {
        if (!isTracking || destroyed) return
        scheduleNextScan()
        // Connected is published only after the companion protocol is ready.
        if (scanJob?.isActive == true || companionLink.state.value is CompanionLinkManager.State.Connected) return
        acquireWakeLock(45 * 1000L)
        scanJob =
            lifecycleScope.launch {
                try {
                    bleScannerManager.performManualScan(Constants.SCAN_TRIGGER_PERIODIC)
                    val results = detectionAlgorithm.runDetection()
                    if (results.isNotEmpty()) alertGenerator.generateAlerts(results)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error during triggered scan")
                } finally {
                    releaseWakeLock()
                }
            }
    }

    private fun scanPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(this, TailBaitService::class.java).setAction(ACTION_TRIGGER_SCAN)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, REQUEST_CODE_TRIGGER_SCAN, intent, flags)
        } else {
            PendingIntent.getService(this, REQUEST_CODE_TRIGGER_SCAN, intent, flags)
        }
    }

    private fun scheduleNextScan() {
        val interval = scanIntervalSeconds ?: return
        if (!isTracking || destroyed) return
        try {
            cancelScheduledScan()
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent =
                scanPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return
            val triggerAtMillis = SystemClock.elapsedRealtime() + interval * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule next scan")
        }
    }

    private suspend fun stopResources() {
        isTracking = false
        scanIntervalSeconds = null
        companionAddress = null
        cancelScheduledScan()
        locationTracker.stopLocationTracking()
        companionLink.stop()
        scanStateJob?.cancel()
        locationStateJob?.cancel()
        scanJob?.cancel()
        releaseAllWakeLocks()
        try {
            bleScannerManager.stopAndSaveScanResults()
        } finally {
            try {
                scanJob?.cancelAndJoin()
                scanStateJob?.cancelAndJoin()
                locationStateJob?.cancelAndJoin()
            } finally {
                companionIngestor.detach()
            }
        }
        scanJob = null
        scanStateJob = null
        locationStateJob = null
    }

    private fun cancelScheduledScan() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            scanPendingIntent(flags)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
            // Remove the pre-cutover getService alarm as well as the foreground-service alarm.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(this, TailBaitService::class.java).setAction(ACTION_TRIGGER_SCAN)
                PendingIntent.getService(this, REQUEST_CODE_TRIGGER_SCAN, intent, flags)?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cancelling scheduled scan")
        }
    }

    private suspend fun pauseTracking() {
        settingsRepository.updateTrackingEnabled(false)
        stopResources()
        updateNotification(
            NOTIFICATION_ID,
            createTrackingNotification(
                title = "BLE Tracker Paused",
                message = "Tracking paused",
                isScanning = false,
                showResumeAction = true,
            ),
        )
    }

    // Track if service is currently tracking
    private var isTracking = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private val wakeLockTag = "TailBait:WakeLock"

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
     * Initializes resources without starting radios for a bound-only client.
     */
    override fun onCreate() {
        super.onCreate()
        Timber.i("TailBaitService created")

        // Initialize WakeLock once and keep it for the service lifetime
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                wakeLockTag,
            ).apply {
                setReferenceCounted(true)
            }
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
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        Timber.d("onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")

        lifecycleScope.launch {
            transition {
                when (intent?.action) {
                    ACTION_START_TRACKING, ACTION_RESUME_TRACKING -> {
                        if (hasTrackingPermissions()) {
                            settingsRepository.updateTrackingEnabled(true)
                            applySettings()
                            observeSettings()
                        } else {
                            stopResources()
                            stopSelf()
                        }
                    }
                    ACTION_STOP_TRACKING -> {
                        settingsJob?.cancel()
                        settingsRepository.updateTrackingEnabled(false)
                        stopResources()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        foregroundStarted = false
                        stopSelf()
                    }
                    ACTION_PAUSE_TRACKING -> {
                        pauseTracking()
                        observeSettings()
                    }
                    null, ACTION_TRIGGER_SCAN -> {
                        val alreadyTracking = isTracking
                        applySettings()
                        if (isTracking) {
                            if (alreadyTracking && intent?.action == ACTION_TRIGGER_SCAN) handleTriggerScan()
                            observeSettings()
                        } else {
                            stopSelf()
                        }
                    }
                    else -> Timber.w("Unknown action: ${intent?.action}")
                }
            }
        }
        return if (intent?.action == ACTION_STOP_TRACKING) START_NOT_STICKY else START_STICKY
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
        destroyed = true
        isTracking = false
        cancelScheduledScan()
        locationTracker.stopLocationTracking()
        companionLink.stop()
        bleScannerManager.stopScanning()
        // Cancels commands, path persistence and the service-owned companion collector together.
        lifecycleScope.cancel()
        releaseAllWakeLocks()
        super.onDestroy()
    }

    /**
     * Start the foreground service with initial notification.
     * This must be called within 5 seconds of starting the service on Android 8.0+.
     */
    private fun startForegroundService() {
        val notification =
            createTrackingNotification(
                title = "BLE Tracker",
                message = "Service initialized",
                isScanning = false,
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
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

    private fun releaseAllWakeLocks() {
        scanStateWakeLockHeld = false
        try {
            if (::wakeLock.isInitialized) {
                while (wakeLock.isHeld) wakeLock.release()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing WakeLock")
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
        if (!isTracking || destroyed) return
        val shouldHoldLock =
            when (state) {
                is BleScannerManager.ScanState.Scanning,
                is BleScannerManager.ScanState.Processing,
                -> true
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
     * Update notification based on current scan state.
     *
     * @param scanState Current scan state from BleScannerManager
     */
    private fun updateNotificationForScanState(scanState: BleScannerManager.ScanState) {
        val notification =
            when (scanState) {
                is BleScannerManager.ScanState.Scanning -> {
                    createTrackingNotification(
                        title = "BLE Tracker Active",
                        message = "Scanning for devices... (${scanState.devicesFound} found)",
                        isScanning = true,
                    )
                }
                is BleScannerManager.ScanState.Processing -> {
                    createTrackingNotification(
                        title = "BLE Tracker Active",
                        message = "Processing scan results...",
                        // Show progress bar during processing too
                        isScanning = true,
                    )
                }
                is BleScannerManager.ScanState.Idle -> {
                    createTrackingNotification(
                        title = "BLE Tracker Active",
                        message = "Waiting for next scan",
                        isScanning = false,
                    )
                }
                is BleScannerManager.ScanState.Error -> {
                    createTrackingNotification(
                        title = "BLE Tracker Error",
                        message = scanState.message,
                        isScanning = false,
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
        showResumeAction: Boolean = false,
    ): Notification {
        return createNotification(
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_LOW,
            onGoing = true,
        ) {
            // Add action buttons
            if (showResumeAction) {
                // Paused state: show Resume and Stop buttons
                addAction(
                    createNotificationAction(
                        iconResId = R.drawable.ic_notification,
                        title = "Resume",
                        pendingIntent =
                            createActionPendingIntent(
                                ACTION_RESUME_TRACKING,
                                REQUEST_CODE_RESUME,
                            ),
                    ),
                )
            } else {
                // Active state: show Pause and Stop buttons
                addAction(
                    createNotificationAction(
                        iconResId = R.drawable.ic_notification,
                        title = "Pause",
                        pendingIntent =
                            createActionPendingIntent(
                                ACTION_PAUSE_TRACKING,
                                REQUEST_CODE_PAUSE,
                            ),
                    ),
                )
            }

            // Always show Stop button
            addAction(
                createNotificationAction(
                    iconResId = R.drawable.ic_notification,
                    title = "Stop",
                    pendingIntent =
                        createActionPendingIntent(
                            ACTION_STOP_TRACKING,
                            REQUEST_CODE_STOP,
                        ),
                ),
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
