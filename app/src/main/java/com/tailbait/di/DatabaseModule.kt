package com.tailbait.di

import android.content.Context
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.dao.AlertHistoryDao
import com.tailbait.data.database.dao.AppSettingsDao
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.UserPathDao
import com.tailbait.data.database.dao.WhitelistEntryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing database-related dependencies.
 *
 * This module is installed in the SingletonComponent, ensuring that all database
 * instances and DAOs are singletons throughout the application lifecycle. The
 * module provides:
 * - The main Room database instance
 * - All DAO interfaces for database operations
 *
 * Dependencies provided by this module can be injected into:
 * - Repositories
 * - ViewModels (via repository dependencies)
 * - Services
 * - Workers
 * - Any other component that needs database access
 *
 * Example usage in a repository:
 * ```kotlin
 * @Singleton
 * class DeviceRepository @Inject constructor(
 *     private val scannedDeviceDao: ScannedDeviceDao,
 *     private val deviceLocationRecordDao: DeviceLocationRecordDao
 * ) {
 *     // Repository implementation
 * }
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Provides the singleton instance of the TailBait database.
     *
     * The database instance is created using the application context to prevent
     * memory leaks. Room's builder pattern is used with appropriate configuration
     * including WAL mode, migrations, and callbacks.
     *
     * @param context Application context provided by Hilt
     * @return Singleton TailBaitDatabase instance
     */
    @Provides
    @Singleton
    fun provideTailBaitDatabase(
        @ApplicationContext context: Context
    ): TailBaitDatabase {
        return TailBaitDatabase.getInstance(context)
    }

    /**
     * Provides the ScannedDevice DAO for device operations.
     *
     * This DAO handles all database operations related to discovered BLE devices,
     * including insertion, querying, and deletion of device records.
     *
     * @param database The TailBaitDatabase instance
     * @return ScannedDeviceDao instance
     */
    @Provides
    @Singleton
    fun provideScannedDeviceDao(
        database: TailBaitDatabase
    ): ScannedDeviceDao {
        return database.scannedDeviceDao()
    }

    /**
     * Provides the Location DAO for location operations.
     *
     * This DAO handles all database operations related to GPS locations,
     * including insertion, spatial queries, and temporal queries.
     *
     * @param database The TailBaitDatabase instance
     * @return LocationDao instance
     */
    @Provides
    @Singleton
    fun provideLocationDao(
        database: TailBaitDatabase
    ): LocationDao {
        return database.locationDao()
    }

    /**
     * Provides the DeviceLocationRecord DAO for correlation operations.
     *
     * This DAO handles the many-to-many relationship between devices and locations,
     * which is critical for stalking detection analysis.
     *
     * @param database The TailBaitDatabase instance
     * @return DeviceLocationRecordDao instance
     */
    @Provides
    @Singleton
    fun provideDeviceLocationRecordDao(
        database: TailBaitDatabase
    ): DeviceLocationRecordDao {
        return database.deviceLocationRecordDao()
    }

    /**
     * Provides the WhitelistEntry DAO for whitelist operations.
     *
     * This DAO manages the list of trusted devices that should be excluded
     * from stalking detection.
     *
     * @param database The TailBaitDatabase instance
     * @return WhitelistEntryDao instance
     */
    @Provides
    @Singleton
    fun provideWhitelistEntryDao(
        database: TailBaitDatabase
    ): WhitelistEntryDao {
        return database.whitelistEntryDao()
    }

    /**
     * Provides the AlertHistory DAO for alert operations.
     *
     * This DAO manages stalking detection alerts, including creation, dismissal,
     * and querying by severity level.
     *
     * @param database The TailBaitDatabase instance
     * @return AlertHistoryDao instance
     */
    @Provides
    @Singleton
    fun provideAlertHistoryDao(
        database: TailBaitDatabase
    ): AlertHistoryDao {
        return database.alertHistoryDao()
    }

    /**
     * Provides the AppSettings DAO for settings operations.
     *
     * This DAO manages application configuration and user preferences,
     * providing reactive access to settings changes.
     *
     * @param database The TailBaitDatabase instance
     * @return AppSettingsDao instance
     */
    @Provides
    @Singleton
    fun provideAppSettingsDao(
        database: TailBaitDatabase
    ): AppSettingsDao {
        return database.appSettingsDao()
    }

    /**
     * Provides the UserPath DAO for tracking user movement history.
     *
     * This DAO manages the raw sequence of user movements (breadcrumbs),
     * enabling accurate movement correlation analysis.
     *
     * @param database The TailBaitDatabase instance
     * @return UserPathDao instance
     */
    @Provides
    @Singleton
    fun provideUserPathDao(
        database: TailBaitDatabase
    ): UserPathDao {
        return database.userPathDao()
    }
}
