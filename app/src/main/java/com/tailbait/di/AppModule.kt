package com.tailbait.di

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Main Application-level Hilt module.
 *
 * This module provides fundamental application-wide dependencies that are
 * used across all layers of the app. It serves as the foundation for the
 * dependency injection graph.
 *
 * Provided Dependencies:
 * - Application Context: For system service access
 * - Coroutine Dispatchers: For controlling thread execution
 * - System Services: NotificationManager, etc.
 * - Build Information: For version-specific logic
 * - Logging: Timber for consistent logging
 *
 * Design Principles:
 * - All dependencies are singletons for consistent state
 * - Use qualifiers to distinguish between similar types
 * - Provide abstractions rather than concrete implementations where possible
 * - Keep module focused on truly application-level concerns
 *
 * Module Organization:
 * - AppModule: Application fundamentals (this file)
 * - DatabaseModule: Room database and DAOs
 * - CentralManagerModule: Nordic BLE setup
 * - RepositoryModule: Repository layer bindings
 * - ServiceModule: Services and background workers
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the application context.
     *
     * The application context is safe to hold as a singleton because it lives
     * for the entire lifetime of the application process. Use this instead of
     * activity context to prevent memory leaks.
     *
     * @param context Application context injected by Hilt
     * @return Application Context
     */
    @Provides
    @Singleton
    fun provideApplicationContext(
        @ApplicationContext context: Context
    ): Context = context

    /**
     * Provides Timber debug tree for logging.
     *
     * Timber provides a simple logging facade that can be configured
     * differently for debug and release builds. In debug builds, we use
     * DebugTree which logs to Logcat.
     *
     * Usage:
     * ```kotlin
     * Timber.d("Debug message")
     * Timber.e(exception, "Error occurred")
     * ```
     *
     * @return Timber.Tree instance for logging
     */
    @Provides
    @Singleton
    fun provideTimberTree(): Timber.Tree {
        return Timber.DebugTree()
    }

    /**
     * Provides NotificationManager for creating and managing notifications.
     *
     * Used by:
     * - NotificationHelper: For creating alert notifications
     * - TailBaitService: For foreground service notifications
     * - DetectionWorker: For sending detection alerts
     *
     * @param context Application context
     * @return NotificationManager system service
     */
    @Provides
    @Singleton
    fun provideNotificationManager(
        @ApplicationContext context: Context
    ): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Provides IO dispatcher for background work.
     *
     * Use this dispatcher for:
     * - Database operations
     * - Network requests
     * - File I/O
     * - Any blocking operations
     *
     * @return IO CoroutineDispatcher
     */
    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides Default dispatcher for CPU-intensive work.
     *
     * Use this dispatcher for:
     * - Complex calculations (threat scoring)
     * - Data parsing
     * - Sorting/filtering large datasets
     *
     * @return Default CoroutineDispatcher
     */
    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides Main dispatcher for UI updates.
     *
     * Use this dispatcher for:
     * - Updating UI state
     * - Collecting Flows in ViewModels
     * - Any UI-related operations
     *
     * @return Main CoroutineDispatcher
     */
    @Provides
    @Singleton
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    /**
     * Provides the Android SDK version for version-specific logic.
     *
     * Use this for handling differences between Android versions:
     * - Permission handling (different requirements for Android 10+, 12+, 13+)
     * - Notification channels (Android 8+)
     * - Foreground service types (Android 9+)
     * - Background restrictions (Android 12+)
     *
     * Example:
     * ```kotlin
     * if (sdkVersion >= Build.VERSION_CODES.S) {
     *     // Android 12+ specific code
     * }
     * ```
     *
     * @return Current device SDK version
     */
    @Provides
    @Singleton
    fun provideSdkVersion(): Int = Build.VERSION.SDK_INT
}

/**
 * Qualifier for IO dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for Default dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for Main dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
