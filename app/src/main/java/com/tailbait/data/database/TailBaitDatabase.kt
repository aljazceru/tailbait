package com.tailbait.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tailbait.data.database.dao.AlertHistoryDao
import com.tailbait.data.database.dao.AppSettingsDao
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.UserPathDao
import com.tailbait.data.database.dao.WhitelistEntryDao
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main Room database for the TailBait application.
 *
 * This database manages all persistent data for the app including:
 * - Scanned BLE devices
 * - GPS location records
 * - Device-location correlation records
 * - Whitelist (trusted devices)
 * - Alert history
 * - Application settings
 *
 * The database uses version 1 as the initial schema. All entities have proper
 * indices defined for efficient querying, and foreign key constraints are
 * enforced with cascade delete behavior.
 *
 * Migration strategy:
 * - Version 1: Initial schema with all 6 entities
 * - Future versions: Will include migration paths (see MIGRATION_STRATEGY.md)
 *
 * @see ScannedDevice
 * @see Location
 * @see DeviceLocationRecord
 * @see WhitelistEntry
 * @see AlertHistory
 * @see AppSettings
 */
@Database(
    entities = [
        ScannedDevice::class,
        Location::class,
        DeviceLocationRecord::class,
        WhitelistEntry::class,
        AlertHistory::class,
        AppSettings::class,
        UserPath::class
    ],
    version = 11,
    exportSchema = true
)
abstract class TailBaitDatabase : RoomDatabase() {

    /**
     * Provides access to ScannedDevice DAO for device operations.
     */
    abstract fun scannedDeviceDao(): ScannedDeviceDao

    /**
     * Provides access to Location DAO for location operations.
     */
    abstract fun locationDao(): LocationDao

    /**
     * Provides access to DeviceLocationRecord DAO for correlation operations.
     */
    abstract fun deviceLocationRecordDao(): DeviceLocationRecordDao

    /**
     * Provides access to WhitelistEntry DAO for whitelist operations.
     */
    abstract fun whitelistEntryDao(): WhitelistEntryDao

    /**
     * Provides access to AlertHistory DAO for alert operations.
     */
    abstract fun alertHistoryDao(): AlertHistoryDao

    /**
     * Provides access to AppSettings DAO for settings operations.
     */
    abstract fun appSettingsDao(): AppSettingsDao

    /**
     * Provides access to UserPath DAO for movement history operations.
     */
    abstract fun userPathDao(): UserPathDao

    companion object {
        /**
         * Database name used for the SQLite file.
         */
        const val DATABASE_NAME = "tailbait_database"

        /**
         * Singleton instance of the database.
         * Volatile ensures visibility across threads.
         */
        @Volatile
        private var instance: TailBaitDatabase? = null

        /**
         * Migration from version 1 to 2.
         * Adds an index on `last_seen` column in `scanned_devices` table for query optimization.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add index on last_seen column for better ORDER BY performance
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_last_seen` ON `scanned_devices` (`last_seen`)")
            }
        }

        /**
         * Migration from version 2 to 3.
         * Adds comprehensive device identification fields to scanned_devices table:
         * - Manufacturer identification (manufacturer_id, manufacturer_name)
         * - Device model and tracker flag
         * - BLE advertisement data (service_uuids, appearance, tx_power_level, advertising_flags)
         * - Apple Continuity protocol type
         * - Identification confidence metrics
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Manufacturer identification
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN manufacturer_id INTEGER")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN manufacturer_name TEXT")

                // Device model and tracker flag
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN device_model TEXT")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN is_tracker INTEGER NOT NULL DEFAULT 0")

                // BLE advertisement data
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN service_uuids TEXT")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN appearance INTEGER")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN tx_power_level INTEGER")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN advertising_flags INTEGER")

                // Apple-specific
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN apple_continuity_type INTEGER")

                // Identification confidence
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN identification_confidence REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN identification_method TEXT")

                // Add indices for new columns used in filtering/queries
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_device_type` ON `scanned_devices` (`device_type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_is_tracker` ON `scanned_devices` (`is_tracker`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_manufacturer_id` ON `scanned_devices` (`manufacturer_id`)")
            }
        }

        /**
         * Migration from version 3 to 4.
         * Adds the advertised_name column to scanned_devices table.
         * This column stores the local name from BLE advertisement, which may
         * differ from the cached device name.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN advertised_name TEXT")
            }
        }

        /**
         * Migration from version 4 to 5.
         * Adds the theme_mode column to app_settings table for dark theme support.
         * Default value is "SYSTEM" to follow the system theme setting.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN theme_mode TEXT NOT NULL DEFAULT 'SYSTEM'")
            }
        }

        /**
         * Migration from version 5 to 6.
         * Adds Find My network fingerprinting fields to scanned_devices table.
         *
         * These fields enable tracking AirTags and other Find My accessories across
         * MAC address rotations by using payload-based fingerprinting:
         *
         * - payload_fingerprint: Semi-stable identifier from Find My payload
         * - find_my_status: Raw status byte from advertisement
         * - find_my_separated: Whether device is separated from owner (CRITICAL for stalking detection)
         * - linked_device_id: Links devices with same fingerprint but different MAC
         * - last_mac_rotation: Timestamp of last detected MAC rotation
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add Find My fingerprinting columns
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN payload_fingerprint TEXT")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN find_my_status INTEGER")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN find_my_separated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN linked_device_id INTEGER")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN last_mac_rotation INTEGER")

                // Add indices for efficient fingerprint-based lookups
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_payload_fingerprint` ON `scanned_devices` (`payload_fingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_linked_device_id` ON `scanned_devices` (`linked_device_id`)")
            }
        }

        /**
         * Migration from version 6 to 7.
         * Adds enhanced signal strength and beacon detection fields.
         *
         * These fields improve device analysis:
         * - highest_rssi: Track strongest signal ever seen (closest proximity)
         * - signal_strength: Classified signal level (VERY_WEAK to VERY_STRONG)
         * - beacon_type: Detected beacon format (IBEACON, EDDYSTONE, FIND_MY, etc.)
         * - threat_level: Calculated threat assessment (NONE to CRITICAL)
         *
         * Learned from Nordic nRF-Connect-Device-Manager patterns.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add enhanced signal and beacon detection columns
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN highest_rssi INTEGER")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN signal_strength TEXT")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN beacon_type TEXT")
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN threat_level TEXT")

                // Add indices for efficient queries
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_highest_rssi` ON `scanned_devices` (`highest_rssi`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_threat_level` ON `scanned_devices` (`threat_level`)")
            }
        }

        /**
         * Migration from version 7 to 8.
         * Adds link strength differentiation for MAC address correlation.
         *
         * When correlating devices across MAC address rotations, we now distinguish:
         * - STRONG links: Based on stable identifiers (fingerprint, device name match)
         *   High confidence this is the same physical device.
         * - WEAK links: Based on circumstantial evidence (temporal proximity, RSSI similarity)
         *   Could be wrong in crowded areas.
         *
         * The detection algorithm should weight weak-linked locations differently.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add link strength classification
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN link_strength TEXT")
                // Add link reason for debugging/transparency
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN link_reason TEXT")
            }
        }

        /**
         * Migration from version 8 to 9.
         * Adds the user_path table for tracking raw movement history.
         *
         * This separates "Places" (Locations) from "Movements" (UserPath) to
         * enable accurate movement correlation without history collapsing.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_path` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `location_id` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `accuracy` REAL NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`location_id`) REFERENCES `locations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                
                // Add indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_path_location_id` ON `user_path` (`location_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_path_timestamp` ON `user_path` (`timestamp`)")
            }
        }

        /**
         * Migration from version 9 to 10.
         * Removes deprecated columns from app_settings during scanning simplification:
         * - tracking_mode
         * - location_change_threshold_meters
         *
         * Since SQLite doesn't support DROP COLUMN in older versions, we must recreate the table.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create temporary table with new schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_settings_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `is_tracking_enabled` INTEGER NOT NULL DEFAULT 1,
                        `scan_interval_seconds` INTEGER NOT NULL DEFAULT 300,
                        `scan_duration_seconds` INTEGER NOT NULL DEFAULT 60,
                        `min_detection_distance_meters` REAL NOT NULL DEFAULT 100.0,
                        `alert_threshold_count` INTEGER NOT NULL DEFAULT 3,
                        `alert_notification_enabled` INTEGER NOT NULL DEFAULT 1,
                        `alert_sound_enabled` INTEGER NOT NULL DEFAULT 1,
                        `alert_vibration_enabled` INTEGER NOT NULL DEFAULT 1,
                        `learn_mode_active` INTEGER NOT NULL DEFAULT 0,
                        `learn_mode_started_at` INTEGER,
                        `data_retention_days` INTEGER NOT NULL DEFAULT 30,
                        `battery_optimization_enabled` INTEGER NOT NULL DEFAULT 1,
                        `theme_mode` TEXT NOT NULL DEFAULT 'SYSTEM',
                        `updated_at` INTEGER NOT NULL
                    )
                """.trimIndent())

                // 2. Copy data from old table to new table (mapping columns)
                // Note: We ignore the removed columns (tracking_mode, location_change_threshold_meters)
                db.execSQL("""
                    INSERT INTO app_settings_new (
                        id, is_tracking_enabled, scan_interval_seconds, scan_duration_seconds,
                        min_detection_distance_meters, alert_threshold_count, alert_notification_enabled,
                        alert_sound_enabled, alert_vibration_enabled, learn_mode_active,
                        learn_mode_started_at, data_retention_days, battery_optimization_enabled,
                        theme_mode, updated_at
                    )
                    SELECT 
                        id, is_tracking_enabled, scan_interval_seconds, scan_duration_seconds,
                        min_detection_distance_meters, alert_threshold_count, alert_notification_enabled,
                        alert_sound_enabled, alert_vibration_enabled, learn_mode_active,
                        learn_mode_started_at, data_retention_days, battery_optimization_enabled,
                        theme_mode, updated_at
                    FROM app_settings
                """.trimIndent())

                // 3. Drop old table
                db.execSQL("DROP TABLE app_settings")

                // 4. Rename new table to original name
                db.execSQL("ALTER TABLE app_settings_new RENAME TO app_settings")
            }
        }

        /**
         * Migration from version 10 to 11.
         * Adds shadow_key column for MAC-agnostic device profiling.
         *
         * Shadow keys are coarse device profiles built from stable BLE properties
         * (manufacturer ID, device type, etc.) that survive MAC rotation. Used by
         * the shadow detection path to find suspicious devices without requiring
         * explicit MAC-to-MAC linking.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_devices ADD COLUMN shadow_key TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scanned_devices_shadow_key` ON `scanned_devices` (`shadow_key`)")
            }
        }

        /**
         * Gets the singleton database instance.
         * Creates a new instance if one doesn't exist, using double-checked locking
         * for thread safety.
         *
         * @param context Application context
         * @return The database instance
         */
        fun getInstance(context: Context): TailBaitDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        /**
         * Builds the Room database instance with proper configuration.
         *
         * Configuration includes:
         * - Fallback to destructive migration (development only)
         * - Database creation callback for initialization
         * - Write-ahead logging (WAL) mode for better concurrency
         *
         * @param context Application context
         * @return Configured database instance
         */
        private fun buildDatabase(context: Context): TailBaitDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TailBaitDatabase::class.java,
                DATABASE_NAME
            )
                // CRITICAL FIX: Removed fallbackToDestructiveMigration() to prevent data loss
                // This was deleting all user data on schema updates - UNACCEPTABLE for production
                //
                // Migration Strategy:
                // - Use AutoMigration for simple schema changes (add columns, etc.)
                // - Use manual Migration objects for complex changes
                // - Current version: 4
                // - Migrations:
                //   - 1 -> 2: Added index on scanned_devices.last_seen for query optimization
                //   - 2 -> 3: Added comprehensive device identification fields
                //   - 3 -> 4: Added advertised_name column for BLE local name

                // Add migrations
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)

                // Enable Write-Ahead Logging for better concurrent access
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)

                // Add callback for database creation
                .addCallback(DatabaseCallback(context))

                .build()
        }

        /**
         * Callback invoked when the database is created.
         * Used to initialize default data such as app settings.
         *
         * Migration definitions for database schema changes will be added here
         * as the database schema evolves to preserve user data across app updates.
         * Each migration handles the transition from one version to the next.
         *
         * Example migration pattern (for future use):
         * private val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(database: SupportSQLiteDatabase) {
         *         // Example: Add a new column
         *         database.execSQL("ALTER TABLE scanned_devices ADD COLUMN new_field TEXT")
         *     }
         * }
         *
         * Note: For simple schema changes like adding nullable columns,
         * consider using @AutoMigration annotation in the @Database declaration:
         * @Database(..., autoMigrations = [AutoMigration(from = 1, to = 2)])
         */
        private class DatabaseCallback(
            private val context: Context
        ) : RoomDatabase.Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)

                // Initialize default settings on first database creation
                instance?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        database.appSettingsDao().insert(AppSettings())
                    }
                }
            }
        }
    }

    /**
     * Performs database maintenance operations.
     * This should be called periodically (e.g., daily) to:
     * - Remove old data based on retention settings
     * - Optimize database performance
     * - Clean up orphaned records
     */
    suspend fun performMaintenance() {
        val settings = appSettingsDao().getSettings() ?: return
        val retentionCutoff = System.currentTimeMillis() -
            (settings.dataRetentionDays * 24 * 60 * 60 * 1000L)

        // Delete old records based on retention policy
        deviceLocationRecordDao().deleteOldRecords(retentionCutoff)
        locationDao().deleteOldLocations(retentionCutoff)
        scannedDeviceDao().deleteOldDevices(retentionCutoff)
        alertHistoryDao().deleteOldDismissedAlerts(retentionCutoff)
    }
}
