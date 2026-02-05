package com.tailbait.di

import android.content.Context
import androidx.work.WorkManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing Service layer dependencies.
 *
 * This module configures and provides dependencies for:
 * - Foreground Services (BLE scanning, location tracking)
 * - Background Workers (periodic detection, data cleanup)
 * - Location Services (Google Play Services integration)
 * - Work Management (WorkManager for reliable background execution)
 *
 * Service Layer Architecture:
 * ```
 * UI Layer
 *    ↓
 * ViewModel
 *    ↓
 * Repository
 *    ↓
 * [Service Layer] ← This Module
 *    ├── TailBaitService (Foreground Service)
 *    ├── BleScannerManager (BLE Scanning Logic)
 *    ├── LocationTracker (GPS Tracking)
 *    ├── DetectionWorker (WorkManager - Detection Analysis)
 *    └── BackgroundScanWorker (WorkManager - Background Scans)
 * ```
 *
 * Key Components:
 *
 * 1. **BLE Services** (Phase 1)
 *    - TailBaitService: Foreground service for continuous BLE scanning
 *    - BleScannerManager: Manages BLE scan lifecycle and results processing
 *    - Requires: CentralManager (from CentralManagerModule)
 *
 * 2. **Location Services** (Phase 2)
 *    - LocationTracker: Manages GPS location updates
 *    - Requires: FusedLocationProviderClient (provided here)
 *
 * 3. **Background Workers** (Phase 4)
 *    - DetectionWorker: Runs stalking detection algorithm periodically
 *    - BackgroundScanWorker: Performs periodic/location-based BLE scans
 *    - DataCleanupWorker: Removes old data based on retention settings
 *    - Requires: WorkManager (provided here)
 *
 * 4. **Notification Management** (Phase 5)
 *    - NotificationHelper: Creates and manages system notifications
 *    - Handles alert notifications and foreground service notifications
 *
 * Dependencies:
 * - Services and Workers use @AndroidEntryPoint for automatic injection
 * - They receive their dependencies through constructor injection
 * - Singleton scope ensures consistent state across the app
 *
 * Example Service Injection:
 * ```kotlin
 * @AndroidEntryPoint
 * class TailBaitService : LifecycleService() {
 *     @Inject lateinit var bleScannerManager: BleScannerManager
 *     @Inject lateinit var locationTracker: LocationTracker
 *     // ...
 * }
 * ```
 *
 * Example Worker Injection:
 * ```kotlin
 * @HiltWorker
 * class DetectionWorker @AssistedInject constructor(
 *     @Assisted context: Context,
 *     @Assisted params: WorkerParameters,
 *     private val detectionAlgorithm: DetectionAlgorithm
 * ) : CoroutineWorker(context, params) { ... }
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    /**
     * Provides FusedLocationProviderClient for location tracking.
     *
     * The Fused Location Provider is Google Play Services' recommended API
     * for location tracking. It provides:
     * - Automatic selection of best location source (GPS, Network, etc.)
     * - Battery-efficient location updates
     * - High accuracy when needed, low power when not
     *
     * Used by:
     * - LocationTracker: For continuous location updates
     * - Repositories: For one-time location queries
     *
     * @param context Application context
     * @return Singleton FusedLocationProviderClient instance
     */
    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    /**
     * Provides WorkManager for reliable background task execution.
     *
     * WorkManager is the recommended solution for deferrable, guaranteed
     * background work. It provides:
     * - Guaranteed execution even if app is killed
     * - Battery-conscious scheduling
     * - Constraint-based execution (battery, network, etc.)
     * - Automatic retry with exponential backoff
     *
     * Used by:
     * - DetectionWorker: Periodic stalking detection analysis (every 15 min)
     * - BackgroundScanWorker: Periodic BLE scans when not in foreground mode
     * - DataCleanupWorker: Daily cleanup of old records
     *
     * Configuration:
     * - Configured in TailBaitApplication with HiltWorkerFactory
     * - Supports Hilt dependency injection in Workers via @HiltWorker
     *
     * @param context Application context
     * @return WorkManager instance (system service, not a singleton)
     */
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager {
        return WorkManager.getInstance(context)
    }

    // Note: Services and Workers use @AndroidEntryPoint/@HiltWorker for injection
    // They don't need to be provided here - Hilt handles their creation automatically

    // Phase 1: BLE Scanning - IMPLEMENTED

    /**
     * Provides BleScannerManager as a singleton.
     *
     * BleScannerManager is the core component for BLE device scanning operations.
     * It handles:
     * - Continuous and manual scanning modes
     * - Scan result processing and duplicate filtering
     * - Location correlation
     * - Error handling with retry logic
     *
     * Note: Uses constructor injection via @Inject, so this provider is optional.
     * Including it here for explicit dependency graph documentation.
     *
     * @param deviceRepository Repository for device data operations
     * @param locationRepository Repository for location operations
     * @param settingsRepository Repository for app settings
     * @param context Application context
     * @return BleScannerManager singleton instance
     */
    @Provides
    @Singleton
    fun provideBleScannerManager(
        deviceRepository: com.tailbait.data.repository.DeviceRepository,
        locationRepository: com.tailbait.data.repository.LocationRepository,
        settingsRepository: com.tailbait.data.repository.SettingsRepository,
        @ApplicationContext context: Context
    ): com.tailbait.service.BleScannerManager {
        return com.tailbait.service.BleScannerManager(
            deviceRepository,
            locationRepository,
            settingsRepository,
            context
        )
    }

    // Phase 4: Detection Algorithm - IMPLEMENTED
    // The following components use @Singleton with @Inject constructor for automatic injection:
    // - DetectionAlgorithm: Core stalking detection logic
    // - ThreatScoreCalculator: Multi-factor threat scoring
    // - PatternMatcher: Pattern detection utilities
    // These are automatically injectable via Hilt without explicit providers

    // CSV Export Utility - IMPLEMENTED
    // CsvDataExporter: @Singleton class with @Inject constructor for automatic injection

    // Service classes to be implemented in subsequent phases:
    //
    // Phase 1: BLE Scanning (Remaining)
    // - TailBaitService: @AndroidEntryPoint for automatic injection
    //
    // Phase 2: Location Tracking
    // - LocationTracker: @Singleton class with @Inject constructor
    //
    // Phase 4: Detection & Workers (Remaining)
    // - DetectionWorker: @HiltWorker with @AssistedInject constructor
    // - BackgroundScanWorker: @HiltWorker with @AssistedInject constructor
    //
    // Phase 5: Notifications
    // - NotificationHelper: @Singleton class with @Inject constructor
    //
    // Phase 7: Optimization
    // - DataCleanupWorker: @HiltWorker with @AssistedInject constructor

    /**
     * Future providers that will be added as development progresses:
     *
     * @Provides
     * @Singleton
     * fun provideNotificationManager(
     *     @ApplicationContext context: Context
     * ): NotificationManager {
     *     return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
     * }
     */
}
