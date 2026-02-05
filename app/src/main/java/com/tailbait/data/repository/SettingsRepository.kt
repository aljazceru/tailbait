package com.tailbait.data.repository

import com.tailbait.data.database.dao.AppSettingsDao
import com.tailbait.data.database.entities.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository interface for managing application settings.
 *
 * Provides high-level operations for reading and updating app configuration.
 */
interface SettingsRepository {
    /**
     * Get settings as a Flow. Ensures default settings are initialized.
     *
     * @return Flow of settings (never null)
     */
    fun getSettings(): Flow<AppSettings>

    /**
     * Get settings once (suspend function). Ensures default settings are initialized.
     *
     * @return Settings (never null)
     */
    suspend fun getSettingsOnce(): AppSettings

    /**
     * Update settings.
     *
     * @param settings New settings
     */
    suspend fun updateSettings(settings: AppSettings)

    /**
     * Update tracking enabled state.
     *
     * @param enabled New state
     */
    suspend fun updateTrackingEnabled(enabled: Boolean)

    /**
     * Update scan interval.
     *
     * @param intervalSeconds New interval in seconds
     */
    suspend fun updateScanInterval(intervalSeconds: Int)

    /**
     * Update scan duration.
     *
     * @param durationSeconds New duration in seconds
     */
    suspend fun updateScanDuration(durationSeconds: Int)

    /**
     * Start Learn Mode.
     */
    suspend fun startLearnMode()

    /**
     * Stop Learn Mode.
     */
    suspend fun stopLearnMode()

    /**
     * Check if Learn Mode is active.
     *
     * @return Flow of Learn Mode state
     */
    fun isLearnModeActive(): Flow<Boolean>

    /**
     * Get the current theme mode.
     *
     * @return Flow of theme mode ("SYSTEM", "LIGHT", "DARK")
     */
    fun getThemeMode(): Flow<String>

    /**
     * Update the theme mode.
     *
     * @param mode Theme mode ("SYSTEM", "LIGHT", "DARK")
     */
    suspend fun updateThemeMode(mode: String)
}

/**
 * Implementation of SettingsRepository.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val appSettingsDao: AppSettingsDao
) : SettingsRepository {

    override fun getSettings(): Flow<AppSettings> {
        return appSettingsDao.getSettingsFlow().map { settings ->
            settings ?: run {
                // Initialize default settings if not present
                val defaultSettings = AppSettings()
                appSettingsDao.insert(defaultSettings)
                defaultSettings
            }
        }
    }

    override suspend fun getSettingsOnce(): AppSettings {
        return appSettingsDao.getSettings() ?: run {
            // Initialize default settings if not present
            val defaultSettings = AppSettings()
            appSettingsDao.insert(defaultSettings)
            defaultSettings
        }
    }

    override suspend fun updateSettings(settings: AppSettings) {
        appSettingsDao.update(settings.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun updateTrackingEnabled(enabled: Boolean) {
        appSettingsDao.updateTrackingEnabled(enabled)
    }

    override suspend fun updateScanInterval(intervalSeconds: Int) {
        appSettingsDao.updateScanInterval(intervalSeconds)
    }

    override suspend fun updateScanDuration(durationSeconds: Int) {
        appSettingsDao.updateScanDuration(durationSeconds)
    }

    override suspend fun startLearnMode() {
        appSettingsDao.startLearnMode()
    }

    override suspend fun stopLearnMode() {
        appSettingsDao.stopLearnMode()
    }

    override fun isLearnModeActive(): Flow<Boolean> {
        return appSettingsDao.isLearnModeActive().map { it ?: false }
    }

    override fun getThemeMode(): Flow<String> {
        return appSettingsDao.getThemeMode().map { it ?: "SYSTEM" }
    }

    override suspend fun updateThemeMode(mode: String) {
        appSettingsDao.updateThemeMode(mode)
    }
}
