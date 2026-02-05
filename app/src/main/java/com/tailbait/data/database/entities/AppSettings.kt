package com.tailbait.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey



/**
 * Entity representing application settings and user preferences.
 *
 * This entity stores all configurable settings for the BLE tracking app, including
 * tracking mode, scan parameters, detection thresholds, alert preferences, and
 * Learn Mode state. The entity is designed as a singleton (single row with id=1)
 * to store global app configuration.
 *
 * Settings can be accessed reactively through Flow to automatically update the UI
 * and services when configuration changes.
 *
 * @property id Primary key - always 1 (single row table)
 * @property isTrackingEnabled Master switch for enabling/disabling BLE tracking
 * @property trackingMode Operating mode ("CONTINUOUS", "PERIODIC", "LOCATION_BASED")
 * @property scanIntervalSeconds Interval in seconds between scans in PERIODIC mode (default: 300 = 5 minutes)
 * @property scanDurationSeconds Duration in seconds for each scan session (default: 30 seconds)
 * @property locationChangeThresholdMeters Minimum location change in meters to trigger scan in
 * LOCATION_BASED mode (default: 50.0m)
 * @property minDetectionDistanceMeters Minimum distance in meters between locations for stalking
 * detection (default: 100.0m)
 * @property alertThresholdCount Minimum number of distinct locations to trigger alert (default: 3)
 * @property alertNotificationEnabled Enable/disable push notifications for alerts
 * @property alertSoundEnabled Enable/disable sound for alert notifications
 * @property alertVibrationEnabled Enable/disable vibration for alert notifications
 * @property learnModeActive Flag indicating if Learn Mode is currently active
 * @property learnModeStartedAt Timestamp in milliseconds when Learn Mode was started (null if not active)
 * @property dataRetentionDays Number of days to retain historical data before auto-deletion (default: 30)
 * @property batteryOptimizationEnabled Enable battery-saving optimizations (reduced scan frequency/duration)
 * @property updatedAt Timestamp in milliseconds when settings were last updated
 */
@Entity(tableName = "app_settings")

data class AppSettings(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "is_tracking_enabled")
    val isTrackingEnabled: Boolean = true,

    @ColumnInfo(name = "scan_interval_seconds")
    val scanIntervalSeconds: Int = 300,

    @ColumnInfo(name = "scan_duration_seconds")
    val scanDurationSeconds: Int = 60,

    @ColumnInfo(name = "min_detection_distance_meters")
    val minDetectionDistanceMeters: Double = 100.0,

    @ColumnInfo(name = "alert_threshold_count")
    val alertThresholdCount: Int = 3,

    @ColumnInfo(name = "alert_notification_enabled")
    val alertNotificationEnabled: Boolean = true,

    @ColumnInfo(name = "alert_sound_enabled")
    val alertSoundEnabled: Boolean = true,

    @ColumnInfo(name = "alert_vibration_enabled")
    val alertVibrationEnabled: Boolean = true,

    @ColumnInfo(name = "learn_mode_active")
    val learnModeActive: Boolean = false,

    @ColumnInfo(name = "learn_mode_started_at")
    val learnModeStartedAt: Long? = null,

    @ColumnInfo(name = "data_retention_days")
    val dataRetentionDays: Int = 30,

    @ColumnInfo(name = "battery_optimization_enabled")
    val batteryOptimizationEnabled: Boolean = true,

    /**
     * Theme mode preference.
     * Values: "SYSTEM" (follow system), "LIGHT" (always light), "DARK" (always dark)
     */
    @ColumnInfo(name = "theme_mode")
    val themeMode: String = "SYSTEM",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
    }
}
