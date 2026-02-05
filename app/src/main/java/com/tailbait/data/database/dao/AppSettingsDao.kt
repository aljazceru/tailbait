package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tailbait.data.database.entities.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for AppSettings entity operations.
 *
 * This DAO provides methods for managing application settings and user preferences.
 * The settings table is designed as a singleton (single row with id=1) to store
 * global app configuration. All queries use Flow for reactive updates to ensure
 * the UI and services respond immediately to configuration changes.
 */
@Dao
interface AppSettingsDao {

    /**
     * Insert or replace the settings record.
     * Since settings is a singleton table, this will always update the single row.
     *
     * @param settings The settings to insert/update
     * @return The row ID (always 1)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: AppSettings): Long

    /**
     * Update the existing settings record.
     *
     * @param settings The settings to update
     */
    @Update
    suspend fun update(settings: AppSettings)

    /**
     * Get the current app settings.
     * Since this is a singleton table, this always returns the single settings row.
     *
     * @return The app settings, or null if not initialized
     */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettings?

    /**
     * Get the current app settings as a Flow for reactive updates.
     * This is the preferred method for accessing settings in ViewModels and services.
     *
     * @return Flow emitting the current settings
     */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AppSettings?>

    /**
     * Check if tracking is currently enabled.
     *
     * @return Flow emitting the tracking enabled state
     */
    @Query("SELECT is_tracking_enabled FROM app_settings WHERE id = 1")
    fun isTrackingEnabled(): Flow<Boolean?>

    /**
     * Check if Learn Mode is currently active.
     *
     * @return Flow emitting the Learn Mode active state
     */
    @Query("SELECT learn_mode_active FROM app_settings WHERE id = 1")
    fun isLearnModeActive(): Flow<Boolean?>

    /**
     * Update the tracking enabled state.
     *
     * @param enabled The new tracking enabled state
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET is_tracking_enabled = :enabled, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateTrackingEnabled(enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update scan interval for periodic mode.
     *
     * @param intervalSeconds New scan interval in seconds
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET scan_interval_seconds = :intervalSeconds, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateScanInterval(intervalSeconds: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update scan duration.
     *
     * @param durationSeconds New scan duration in seconds
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET scan_duration_seconds = :durationSeconds, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateScanDuration(durationSeconds: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update minimum detection distance for stalking detection.
     *
     * @param distanceMeters New minimum distance in meters
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET min_detection_distance_meters = :distanceMeters, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateMinDetectionDistance(distanceMeters: Double, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update alert threshold count (minimum locations for detection).
     *
     * @param count New threshold count
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET alert_threshold_count = :count, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateAlertThresholdCount(count: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update alert notification preferences.
     *
     * @param notificationEnabled Enable/disable notifications
     * @param soundEnabled Enable/disable sound
     * @param vibrationEnabled Enable/disable vibration
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET alert_notification_enabled = :notificationEnabled,
            alert_sound_enabled = :soundEnabled,
            alert_vibration_enabled = :vibrationEnabled,
            updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateAlertPreferences(
        notificationEnabled: Boolean,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Start Learn Mode.
     *
     * @param startedAt Timestamp when Learn Mode was started
     */
    @Query("""
        UPDATE app_settings
        SET learn_mode_active = 1,
            learn_mode_started_at = :startedAt,
            updated_at = :startedAt
        WHERE id = 1
    """)
    suspend fun startLearnMode(startedAt: Long = System.currentTimeMillis())

    /**
     * Stop Learn Mode.
     *
     * @param updatedAt Timestamp when Learn Mode was stopped
     */
    @Query("""
        UPDATE app_settings
        SET learn_mode_active = 0,
            learn_mode_started_at = NULL,
            updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun stopLearnMode(updatedAt: Long = System.currentTimeMillis())

    /**
     * Update data retention period.
     *
     * @param days Number of days to retain data
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET data_retention_days = :days, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateDataRetentionDays(days: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * Update battery optimization setting.
     *
     * @param enabled Enable/disable battery optimization
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET battery_optimization_enabled = :enabled, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateBatteryOptimization(enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    /**
     * Get the timestamp when settings were last updated.
     *
     * @return Flow emitting the last update timestamp
     */
    @Query("SELECT updated_at FROM app_settings WHERE id = 1")
    fun getLastUpdateTime(): Flow<Long?>

    /**
     * Get the current theme mode.
     *
     * @return Flow emitting the theme mode ("SYSTEM", "LIGHT", "DARK")
     */
    @Query("SELECT theme_mode FROM app_settings WHERE id = 1")
    fun getThemeMode(): Flow<String?>

    /**
     * Update the theme mode.
     *
     * @param mode The new theme mode ("SYSTEM", "LIGHT", "DARK")
     * @param updatedAt Timestamp when the setting was updated
     */
    @Query("""
        UPDATE app_settings
        SET theme_mode = :mode, updated_at = :updatedAt
        WHERE id = 1
    """)
    suspend fun updateThemeMode(mode: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * Reset settings to default values.
     * This deletes the current settings row, which will trigger re-initialization
     * with default values on the next access.
     */
    @Query("DELETE FROM app_settings WHERE id = 1")
    suspend fun resetToDefaults()

    /**
     * Initialize settings with default values if not already initialized.
     * This should be called on first app launch.
     *
     * @return True if settings were initialized, false if they already existed
     */
    suspend fun initializeDefaults(): Boolean {
        val existing = getSettings()
        if (existing == null) {
            insert(AppSettings())
            return true
        }
        return false
    }
}
