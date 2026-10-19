package com.tailbait.data.database

import android.content.Context
import android.os.ParcelUuid
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tailbait.algorithm.ShadowKeyGenerator
import com.tailbait.data.database.dao.AlertHistoryDao
import com.tailbait.data.database.dao.AppSettingsDao
import com.tailbait.data.database.dao.CompanionDeviceDao
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.UserPathDao
import com.tailbait.data.database.dao.WhitelistEntryDao
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.CompanionDevice
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.util.BeaconDetectionUtils
import com.tailbait.util.DeviceFingerprinter
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.ManufacturerDataParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

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
 * Schema upgrades use explicit migrations; a missing path fails rather than
 * deleting user data. Version 14 discards unverifiable legacy identity metadata
 * while preserving observations, original IDs, explicit trust, and settings.
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
        UserPath::class,
        CompanionDevice::class,
    ],
    version = 14,
    exportSchema = true,
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

    /** Companion device registry (paired ESP32 units). */
    abstract fun companionDeviceDao(): CompanionDeviceDao

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
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Add index on last_seen column for better ORDER BY performance
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_last_seen` ON `scanned_devices` (`last_seen`)",
                    )
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
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
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
                    db.execSQL(
                        "ALTER TABLE scanned_devices ADD COLUMN identification_confidence REAL NOT NULL DEFAULT 0.0",
                    )
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN identification_method TEXT")

                    // Add indices for new columns used in filtering/queries
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_device_type` ON `scanned_devices` (`device_type`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_is_tracker` ON `scanned_devices` (`is_tracker`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_manufacturer_id` ON `scanned_devices` (`manufacturer_id`)",
                    )
                }
            }

        /**
         * Migration from version 3 to 4.
         * Adds the advertised_name column to scanned_devices table.
         * This column stores the local name from BLE advertisement, which may
         * differ from the cached device name.
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN advertised_name TEXT")
                }
            }

        /**
         * Migration from version 4 to 5.
         * Adds the theme_mode column to app_settings table for dark theme support.
         * Default value is "SYSTEM" to follow the system theme setting.
         */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN theme_mode TEXT NOT NULL DEFAULT 'SYSTEM'")
                }
            }

        /**
         * Migration from version 5 to 6.
         * Adds Find My network fingerprinting fields to scanned_devices table.
         *
         * Legacy payload-based identity and owner-state fields are retained for
         * compatibility with the schema upgrade path. Version 14 discards
         * unverifiable identity metadata and resets the old status interpretation.
         */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Add Find My fingerprinting columns
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN payload_fingerprint TEXT")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN find_my_status INTEGER")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN find_my_separated INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN linked_device_id INTEGER")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN last_mac_rotation INTEGER")

                    // Add indices for efficient fingerprint-based lookups
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_payload_fingerprint` ON `scanned_devices` (`payload_fingerprint`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_linked_device_id` ON `scanned_devices` (`linked_device_id`)",
                    )
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
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Add enhanced signal and beacon detection columns
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN highest_rssi INTEGER")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN signal_strength TEXT")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN beacon_type TEXT")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN threat_level TEXT")

                    // Add indices for efficient queries
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_highest_rssi` ON `scanned_devices` (`highest_rssi`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_threat_level` ON `scanned_devices` (`threat_level`)",
                    )
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
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
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
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `user_path` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `location_id` INTEGER NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `accuracy` REAL NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            FOREIGN KEY(`location_id`) REFERENCES `locations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )

                    // Add indices
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_user_path_location_id` ON `user_path` (`location_id`)",
                    )
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
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. Create temporary table with new schema
                    db.execSQL(
                        """
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
                        """.trimIndent(),
                    )

                    // 2. Copy data from old table to new table (mapping columns)
                    // Note: We ignore the removed columns (tracking_mode, location_change_threshold_meters)
                    db.execSQL(
                        """
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
                        """.trimIndent(),
                    )

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
        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN shadow_key TEXT")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_shadow_key` ON `scanned_devices` (`shadow_key`)",
                    )
                }
            }

        /**
         * Migration from version 11 to 12.
         * Companion-device integration (ESP32 tailbait-companion):
         * - scanned_devices gains radio/ssid/channel/wifi_flags columns for
         *   WiFi observations (STA probes/assoc + AP beacons). Uniqueness
         *   moves from (address) to (address, radio).
         * - app_settings gains companion_enabled toggle.
         * - New companion_devices table (paired ESP32 registry).
         */
        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN radio TEXT NOT NULL DEFAULT 'BLE'")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN ssid TEXT")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN channel INTEGER")
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN wifi_flags INTEGER")
                    db.execSQL("DROP INDEX IF EXISTS `index_scanned_devices_address`")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_scanned_devices_address_radio`" +
                            " ON `scanned_devices` (`address`, `radio`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_scanned_devices_radio`" +
                            " ON `scanned_devices` (`radio`)",
                    )
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN companion_enabled INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `companion_devices` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `address` TEXT NOT NULL,
                            `name` TEXT,
                            `firmware_version` TEXT,
                            `mode` TEXT,
                            `is_enabled` INTEGER NOT NULL,
                            `records_received` INTEGER NOT NULL,
                            `last_connected_at` INTEGER,
                            `created_at` INTEGER NOT NULL,
                            `last_stats_json` TEXT
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_devices_address` ON `companion_devices` (`address`)",
                    )
                }
            }

        /**
         * Migration from version 12 to 13.
         * Camera glasses presence alerts (zuckoff.app-style "camera in the room"):
         * app_settings gains camera_glasses_alerts_enabled toggle (off by default).
         */
        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE app_settings ADD COLUMN camera_glasses_alerts_enabled INTEGER NOT NULL DEFAULT 0")
                }
            }

        /**
         * Add observation time for validated Find My state and reclassify legacy BLE
         * evidence, never discarding observations or explicit trust.
         * Only full FM/FHN identifiers can support a direct STRONG link to a root.
         */
        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE scanned_devices ADD COLUMN find_my_status_timestamp INTEGER")
                    db.execSQL("UPDATE scanned_devices SET payload_fingerprint = NULL WHERE radio != 'BLE'")
                    reclassifyLegacyBleDevices(db)
                    db.execSQL(
                        """
                        UPDATE scanned_devices
                        SET linked_device_id = NULL, link_strength = NULL,
                            link_reason = NULL, last_mac_rotation = NULL
                        WHERE id NOT IN (
                            SELECT child.id
                            FROM scanned_devices child
                            JOIN scanned_devices root ON root.id = child.linked_device_id
                            WHERE root.linked_device_id IS NULL
                                AND child.link_strength = 'STRONG'
                                AND child.radio = 'BLE' AND root.radio = 'BLE'
                                AND child.payload_fingerprint IS NOT NULL
                                AND child.payload_fingerprint = root.payload_fingerprint
                        )
                        """.trimIndent(),
                    )
                    // Legacy OR-latching and cross-MAC updates lost validated latest-state provenance.
                    db.execSQL(
                        "UPDATE scanned_devices SET find_my_status = NULL, find_my_status_timestamp = NULL, find_my_separated = 0",
                    )
                }

                private fun reclassifyLegacyBleDevices(db: SupportSQLiteDatabase) {
                    db.compileStatement(
                        """
                        UPDATE scanned_devices SET payload_fingerprint = ?, is_tracker = ?,
                            device_type = ?, device_model = ?, identification_confidence = ?,
                            identification_method = ?, apple_continuity_type = ?, beacon_type = ?,
                            shadow_key = ? WHERE id = ?
                        """.trimIndent(),
                    ).use { update ->
                        db.query(
                            """
                            SELECT id, address, name, advertised_name, first_seen, last_seen, created_at,
                                manufacturer_id, manufacturer_data, service_uuids, appearance,
                                tx_power_level, payload_fingerprint
                            FROM scanned_devices WHERE radio = 'BLE'
                            """.trimIndent(),
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val manufacturerId = if (cursor.isNull(7)) null else cursor.getInt(7)
                                val manufacturerData = legacyManufacturerData(cursor.getString(8))
                                val serviceUuids = legacyServiceUuids(cursor.getString(9))
                                val appearance = if (cursor.isNull(10)) null else cursor.getInt(10)
                                val fingerprint = cursor.getString(12)?.takeIf(DeviceFingerprinter::isHighConfidenceFingerprint)
                                val identification =
                                    DeviceIdentifier.identifyDevice(
                                        manufacturerId,
                                        manufacturerData,
                                        serviceUuids,
                                        appearance,
                                        cursor.getString(3) ?: cursor.getString(2),
                                    )
                                // A complete identity survives even when its raw advertisement was overwritten.
                                val identityOnly = fingerprint != null && !identification.isTracker
                                val device =
                                    ScannedDevice(
                                        id = cursor.getLong(0),
                                        address = cursor.getString(1),
                                        name = cursor.getString(2),
                                        firstSeen = cursor.getLong(4),
                                        lastSeen = cursor.getLong(5),
                                        createdAt = cursor.getLong(6),
                                        manufacturerId = manufacturerId,
                                        serviceUuids = cursor.getString(9),
                                        txPowerLevel = if (cursor.isNull(11)) null else cursor.getInt(11),
                                        deviceType =
                                            if (fingerprint != null) {
                                                ManufacturerDataParser.DeviceType.TRACKER.name
                                            } else {
                                                identification.deviceType.name
                                            },
                                        deviceModel = if (identityOnly) null else identification.deviceModel,
                                        isTracker = identification.isTracker || identityOnly,
                                        appleContinuityType = identification.appleContinuityType,
                                        identificationConfidence = if (identityOnly) 0.0f else identification.confidence,
                                        identificationMethod = if (identityOnly) null else identification.identificationMethod,
                                        beaconType =
                                            BeaconDetectionUtils.detectBeacon(
                                                manufacturerId,
                                                manufacturerData,
                                                serviceUuids,
                                            )?.beaconType?.name,
                                    )
                                update.clearBindings()
                                fingerprint?.let { update.bindString(1, it) }
                                update.bindLong(2, if (device.isTracker) 1 else 0)
                                update.bindString(3, device.deviceType!!)
                                device.deviceModel?.let { update.bindString(4, it) }
                                update.bindDouble(5, device.identificationConfidence.toDouble())
                                device.identificationMethod?.let { update.bindString(6, it) }
                                device.appleContinuityType?.let { update.bindLong(7, it.toLong()) }
                                device.beaconType?.let { update.bindString(8, it) }
                                // State remains unknown; do not recreate SEP:1 from old OR-latched flags.
                                ShadowKeyGenerator.generate(device)?.let { update.bindString(9, it) }
                                update.bindLong(10, device.id)
                                update.executeUpdateDelete()
                            }
                        }
                    }
                }

                @OptIn(ExperimentalStdlibApi::class)
                private fun legacyManufacturerData(value: String?): ByteArray? =
                    try {
                        value?.hexToByteArray()
                    } catch (_: IllegalArgumentException) {
                        null
                    }

                private fun legacyServiceUuids(value: String?): List<ParcelUuid> =
                    DeviceIdentifier.stringToServiceUuids(value).mapNotNull { stored ->
                        val expanded = if (stored.length == 4) "0000$stored-0000-1000-8000-00805F9B34FB" else stored
                        try {
                            val uuid = UUID.fromString(expanded)
                            // UUID.fromString also accepts abbreviated groups, which were never stored UUIDs.
                            if (uuid.toString().equals(expanded, ignoreCase = true)) ParcelUuid(uuid) else null
                        } catch (_: IllegalArgumentException) {
                            null
                        }
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

        /** Test-only: close and reset the singleton so migration tests can reopen fresh. */
        fun closeInstanceForTest() {
            instance?.close()
            instance = null
        }

        /**
         * Builds the Room database instance with proper configuration.
         *
         * Configuration includes:
         * - Explicit, non-destructive migrations
         * - Database creation callback for initialization
         * - Write-ahead logging (WAL) mode for better concurrency
         *
         * @param context Application context
         * @return Configured database instance
         */
        internal fun buildDatabase(
            context: Context,
            databaseName: String = DATABASE_NAME,
        ): TailBaitDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TailBaitDatabase::class.java,
                databaseName,
            )
                // Missing migrations must fail rather than erase observations, trust, or settings.
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14,
                )
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
            private val context: Context,
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
     * This should be called periodically (daily, via DataCleanupWorker) to:
     * - Enforce the retention policy (time-based pruning)
     * - Enforce hard row caps (size guardrails — see Constants.MAX_*)
     * - Return freed space to the OS (WAL truncate + VACUUM)
     *
     * Order matters: caps first (they may trim newer-but-excess rows), then
     * time-based retention, then space reclamation.
     */
    suspend fun performMaintenance() {
        val settings = appSettingsDao().getSettings() ?: return
        val retentionCutoff =
            System.currentTimeMillis() -
                (settings.dataRetentionDays * 24 * 60 * 60 * 1000L)

        // ---- Hard caps (size guardrails) ----
        // RF-dense environments can ingest 90k+ observation records/day
        // (companion streaming); retention alone would allow ~270 MB+.
        deviceLocationRecordDao().trimToNewestRecords(com.tailbait.util.Constants.MAX_OBSERVATION_RECORDS)
        userPathDao().trimToNewestPaths(com.tailbait.util.Constants.MAX_USER_PATH_POINTS)
        alertHistoryDao().trimUndismissedAlerts(com.tailbait.util.Constants.MAX_UNDISMISSED_ALERTS)

        // ---- Time-based retention ----
        deviceLocationRecordDao().deleteOldRecords(retentionCutoff)
        locationDao().deleteOldLocations(retentionCutoff)
        scannedDeviceDao().deleteOldDevices(retentionCutoff)
        userPathDao().deleteOldPaths(retentionCutoff) // was never pruned before
        alertHistoryDao().deleteOldDismissedAlerts(retentionCutoff)

        // ---- Space reclamation ----
        // WAL auto-checkpoint keeps the -wal file bounded, but deleted pages
        // stay inside the main DB file until a VACUUM. Reclaim both.
        reclaimSpace()
    }

    /**
     * Truncate the WAL and vacuum the main file so deleted rows actually
     * return space to the OS. Safe to run from the background worker;
     * a ≤50 MB TailBait DB vacuums in well under a second on mid-range HW.
     */
    private suspend fun reclaimSpace() {
        try {
            val db = openHelper.writableDatabase
            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            db.execSQL("VACUUM")
        } catch (e: Exception) {
            // Maintenance is best-effort; never fail the worker on reclamation
            android.util.Log.w("TailBaitDatabase", "space reclamation failed", e)
        }
    }
}
