package com.tailbait.data.repository

import com.tailbait.data.database.dao.AlertHistoryDao
import com.tailbait.data.database.entities.AlertHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alert severity levels for classification.
 */
object AlertLevel {
    const val LOW = "LOW"
    const val MEDIUM = "MEDIUM"
    const val HIGH = "HIGH"
    const val CRITICAL = "CRITICAL"
}

/**
 * Data class for threat score aggregate statistics.
 */
data class ThreatScoreStats(
    val average: Double,
    val maximum: Double,
    val minimum: Double,
    val count: Int
)

/**
 * Repository interface for managing alert data.
 *
 * Provides high-level operations for alert management, including CRUD operations,
 * filtering, dismissal, and statistics.
 */
interface AlertRepository {
    /**
     * Insert a new alert into the database.
     *
     * @param alert The alert to insert
     * @return The alert ID
     */
    suspend fun insertAlert(alert: AlertHistory): Long

    /**
     * Insert multiple alerts in a batch.
     *
     * @param alerts List of alerts to insert
     * @return List of alert IDs
     */
    suspend fun insertAlerts(alerts: List<AlertHistory>): List<Long>

    /**
     * Insert an alert with throttling check to prevent duplicate alerts.
     *
     * CRITICAL FIX: Added to prevent runtime crashes in AlertGenerator.
     * This method checks if a similar alert exists within the throttle window
     * before inserting to avoid spamming users with duplicate notifications.
     *
     * @param alert The alert to insert
     * @param throttleWindowMs Time window in milliseconds to check for duplicates
     * @return The alert ID if inserted, null if throttled (duplicate found)
     */
    suspend fun insertAlertWithThrottling(alert: AlertHistory, throttleWindowMs: Long): Long?

    /**
     * Check if a similar recent alert exists within a time threshold.
     *
     * CRITICAL FIX: Added to prevent runtime crashes in AlertGenerator.
     * This method checks if an alert with similar device addresses exists
     * within the specified time window.
     *
     * @param deviceAddresses JSON array string of device addresses to check
     * @param afterTimestamp Only check alerts created after this timestamp
     * @return True if a similar alert exists, false otherwise
     */
    suspend fun hasSimilarRecentAlert(deviceAddresses: String, afterTimestamp: Long): Boolean

    /**
     * Get an alert by its ID.
     *
     * @param alertId The alert ID
     * @return The alert or null if not found
     */
    suspend fun getAlertById(alertId: Long): AlertHistory?

    /**
     * Get all alerts as a Flow for reactive updates.
     *
     * @return Flow emitting list of all alerts
     */
    fun getAllAlerts(): Flow<List<AlertHistory>>

    /**
     * Get active (non-dismissed) alerts.
     *
     * @return Flow emitting list of active alerts
     */
    fun getActiveAlerts(): Flow<List<AlertHistory>>

    /**
     * Get dismissed alerts.
     *
     * @return Flow emitting list of dismissed alerts
     */
    fun getDismissedAlerts(): Flow<List<AlertHistory>>

    /**
     * Get recent alerts within a time window.
     *
     * @param sinceTimestamp Timestamp threshold
     * @return Flow emitting list of recent alerts
     */
    fun getRecentAlerts(sinceTimestamp: Long): Flow<List<AlertHistory>>

    /**
     * Get recent active alerts within a time window.
     *
     * @param sinceTimestamp Timestamp threshold
     * @return Flow emitting list of recent active alerts
     */
    fun getRecentActiveAlerts(sinceTimestamp: Long): Flow<List<AlertHistory>>

    /**
     * Get alerts by severity level.
     *
     * @param alertLevel The severity level (e.g., "LOW", "MEDIUM", "HIGH", "CRITICAL")
     * @return Flow emitting list of alerts at the specified level
     */
    fun getAlertsByLevel(alertLevel: String): Flow<List<AlertHistory>>

    /**
     * Get active alerts by severity level.
     *
     * @param alertLevel The severity level
     * @return Flow emitting list of active alerts at the specified level
     */
    fun getActiveAlertsByLevel(alertLevel: String): Flow<List<AlertHistory>>

    /**
     * Get alerts with threat score above a threshold.
     *
     * @param minThreatScore Minimum threat score (0.0 to 1.0)
     * @return Flow emitting list of high-threat alerts
     */
    fun getHighThreatAlerts(minThreatScore: Double): Flow<List<AlertHistory>>

    /**
     * Get alerts within a time range.
     *
     * @param startTimestamp Start of time range (inclusive)
     * @param endTimestamp End of time range (inclusive)
     * @return Flow emitting list of alerts within the time range
     */
    fun getAlertsByTimeRange(startTimestamp: Long, endTimestamp: Long): Flow<List<AlertHistory>>

    /**
     * Get the most recent alert.
     *
     * @return The most recent alert or null if no alerts exist
     */
    suspend fun getLatestAlert(): AlertHistory?

    /**
     * Get count of all alerts.
     *
     * @return Flow emitting total alert count
     */
    fun getAlertCount(): Flow<Int>

    /**
     * Get count of active (non-dismissed) alerts.
     *
     * @return Flow emitting active alert count
     */
    fun getActiveAlertCount(): Flow<Int>

    /**
     * Get count of alerts by severity level.
     *
     * @param alertLevel The severity level
     * @return Flow emitting count of alerts at the specified level
     */
    fun getAlertCountByLevel(alertLevel: String): Flow<Int>

    /**
     * Get count of active alerts by severity level.
     *
     * @param alertLevel The severity level
     * @return Flow emitting count of active alerts at the specified level
     */
    fun getActiveAlertCountByLevel(alertLevel: String): Flow<Int>

    /**
     * Get alert statistics grouped by severity level.
     *
     * @return Map of alert level to count
     */
    suspend fun getAlertStatistics(): Map<String, Int>

    /**
     * Get undismissed (active) alerts only.
     * Convenience method equivalent to getActiveAlerts().
     *
     * @return Flow emitting list of undismissed alerts
     */
    fun getUndismissedAlerts(): Flow<List<AlertHistory>>

    /**
     * Get top N alerts with highest threat scores.
     *
     * @param limit Maximum number of alerts to return (default: 10)
     * @return Flow emitting list of top threat alerts
     */
    fun getTopThreatAlerts(limit: Int = 10): Flow<List<AlertHistory>>

    /**
     * Get aggregate threat score statistics.
     *
     * @return Flow emitting threat score statistics (average, max, min)
     */
    fun getThreatScoreStatistics(): Flow<ThreatScoreStats>

    /**
     * Get average threat score for all alerts.
     *
     * @return Flow emitting average threat score
     */
    fun getAverageThreatScore(): Flow<Double>

    /**
     * Get maximum threat score from all alerts.
     *
     * @return Flow emitting maximum threat score
     */
    fun getMaxThreatScore(): Flow<Double>

    /**
     * Mark an alert as dismissed.
     *
     * @param alertId The alert ID to dismiss
     * @return True if successful
     */
    suspend fun dismissAlert(alertId: Long): Boolean

    /**
     * Mark multiple alerts as dismissed.
     *
     * @param alertIds List of alert IDs to dismiss
     * @return True if successful
     */
    suspend fun dismissAlerts(alertIds: List<Long>): Boolean

    /**
     * Mark all active alerts as dismissed.
     *
     * @return Number of alerts dismissed
     */
    suspend fun dismissAllAlerts(): Int

    /**
     * Update an alert.
     *
     * @param alert The alert to update
     */
    suspend fun updateAlert(alert: AlertHistory)

    /**
     * Delete an alert.
     *
     * @param alert The alert to delete
     */
    suspend fun deleteAlert(alert: AlertHistory)

    /**
     * Delete alerts older than the specified timestamp.
     *
     * @param beforeTimestamp Delete alerts before this timestamp
     * @return Number of alerts deleted
     */
    suspend fun deleteOldAlerts(beforeTimestamp: Long): Int

    /**
     * Delete dismissed alerts older than the specified timestamp.
     *
     * @param beforeTimestamp Delete dismissed alerts before this timestamp
     * @return Number of alerts deleted
     */
    suspend fun deleteOldDismissedAlerts(beforeTimestamp: Long): Int

    /**
     * Delete all alerts.
     * WARNING: This removes all alert history.
     */
    suspend fun deleteAllAlerts()
}

/**
 * Implementation of AlertRepository.
 */
@Singleton
class AlertRepositoryImpl @Inject constructor(
    private val alertHistoryDao: AlertHistoryDao
) : AlertRepository {

    override suspend fun insertAlert(alert: AlertHistory): Long {
        return alertHistoryDao.insert(alert)
    }

    override suspend fun insertAlerts(alerts: List<AlertHistory>): List<Long> {
        return alertHistoryDao.insertAll(alerts)
    }

    /**
     * CRITICAL FIX: Implementation of insertAlertWithThrottling.
     * Prevents duplicate alerts from spamming users.
     */
    override suspend fun insertAlertWithThrottling(
        alert: AlertHistory,
        throttleWindowMs: Long
    ): Long? {
        // Calculate throttle threshold timestamp
        val throttleThreshold = alert.timestamp - throttleWindowMs

        // Check if a similar alert already exists within the throttle window
        val hasSimilar = hasSimilarRecentAlert(alert.deviceAddresses, throttleThreshold)

        if (hasSimilar) {
            // Throttle: don't insert duplicate alert
            return null
        }

        // No similar alert found, insert the new alert
        return insertAlert(alert)
    }

    /**
     * CRITICAL FIX: Implementation of hasSimilarRecentAlert.
     * Checks for duplicate alerts based on device addresses and timestamp.
     */
    override suspend fun hasSimilarRecentAlert(
        deviceAddresses: String,
        afterTimestamp: Long
    ): Boolean {
        // Get recent alerts after the threshold timestamp
        val recentAlerts = getRecentAlerts(afterTimestamp).first()

        // Check if any recent alert has the same device addresses
        return recentAlerts.any { alert ->
            alert.deviceAddresses == deviceAddresses
        }
    }

    override suspend fun getAlertById(alertId: Long): AlertHistory? {
        return alertHistoryDao.getById(alertId)
    }

    override fun getAllAlerts(): Flow<List<AlertHistory>> {
        return alertHistoryDao.getAllAlerts()
    }

    override fun getActiveAlerts(): Flow<List<AlertHistory>> {
        return alertHistoryDao.getActiveAlerts()
    }

    override fun getDismissedAlerts(): Flow<List<AlertHistory>> {
        return alertHistoryDao.getDismissedAlerts()
    }

    override fun getRecentAlerts(sinceTimestamp: Long): Flow<List<AlertHistory>> {
        return alertHistoryDao.getRecentAlerts(sinceTimestamp)
    }

    override fun getRecentActiveAlerts(sinceTimestamp: Long): Flow<List<AlertHistory>> {
        return alertHistoryDao.getRecentActiveAlerts(sinceTimestamp)
    }

    override fun getAlertsByLevel(alertLevel: String): Flow<List<AlertHistory>> {
        return alertHistoryDao.getAlertsByLevel(alertLevel)
    }

    override fun getActiveAlertsByLevel(alertLevel: String): Flow<List<AlertHistory>> {
        return alertHistoryDao.getActiveAlertsByLevel(alertLevel)
    }

    override fun getHighThreatAlerts(minThreatScore: Double): Flow<List<AlertHistory>> {
        return alertHistoryDao.getHighThreatAlerts(minThreatScore)
    }

    override fun getAlertsByTimeRange(
        startTimestamp: Long,
        endTimestamp: Long
    ): Flow<List<AlertHistory>> {
        return alertHistoryDao.getAlertsByTimeRange(startTimestamp, endTimestamp)
    }

    override suspend fun getLatestAlert(): AlertHistory? {
        return alertHistoryDao.getLatestAlert()
    }

    override fun getAlertCount(): Flow<Int> {
        return alertHistoryDao.getAlertCount()
    }

    override fun getActiveAlertCount(): Flow<Int> {
        return alertHistoryDao.getActiveAlertCount()
    }

    override fun getAlertCountByLevel(alertLevel: String): Flow<Int> {
        return alertHistoryDao.getAlertCountByLevel(alertLevel)
    }

    override fun getActiveAlertCountByLevel(alertLevel: String): Flow<Int> {
        return alertHistoryDao.getActiveAlertCountByLevel(alertLevel)
    }

    override suspend fun getAlertStatistics(): Map<String, Int> {
        return alertHistoryDao.getAlertStatistics().associate { it.alertLevel to it.count }
    }

    override fun getUndismissedAlerts(): Flow<List<AlertHistory>> {
        return getActiveAlerts()
    }

    override fun getTopThreatAlerts(limit: Int): Flow<List<AlertHistory>> {
        return getAllAlerts().map { alerts ->
            alerts.sortedByDescending { it.threatScore }.take(limit)
        }
    }

    override fun getThreatScoreStatistics(): Flow<ThreatScoreStats> {
        return getAllAlerts().map { alerts ->
            if (alerts.isEmpty()) {
                ThreatScoreStats(0.0, 0.0, 0.0, 0)
            } else {
                val scores = alerts.map { it.threatScore }
                ThreatScoreStats(
                    average = scores.average(),
                    maximum = scores.maxOrNull() ?: 0.0,
                    minimum = scores.minOrNull() ?: 0.0,
                    count = scores.size
                )
            }
        }
    }

    override fun getAverageThreatScore(): Flow<Double> {
        return getAllAlerts().map { alerts ->
            if (alerts.isEmpty()) 0.0 else alerts.map { it.threatScore }.average()
        }
    }

    override fun getMaxThreatScore(): Flow<Double> {
        return getAllAlerts().map { alerts ->
            alerts.maxOfOrNull { it.threatScore } ?: 0.0
        }
    }

    override suspend fun dismissAlert(alertId: Long): Boolean {
        return try {
            val dismissedAt = System.currentTimeMillis()
            alertHistoryDao.dismissAlert(alertId, dismissedAt)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun dismissAlerts(alertIds: List<Long>): Boolean {
        return try {
            val dismissedAt = System.currentTimeMillis()
            alertHistoryDao.dismissAlerts(alertIds, dismissedAt)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun dismissAllAlerts(): Int {
        val dismissedAt = System.currentTimeMillis()
        return alertHistoryDao.dismissAllAlerts(dismissedAt)
    }

    override suspend fun updateAlert(alert: AlertHistory) {
        alertHistoryDao.update(alert)
    }

    override suspend fun deleteAlert(alert: AlertHistory) {
        alertHistoryDao.delete(alert)
    }

    override suspend fun deleteOldAlerts(beforeTimestamp: Long): Int {
        return alertHistoryDao.deleteOldAlerts(beforeTimestamp)
    }

    override suspend fun deleteOldDismissedAlerts(beforeTimestamp: Long): Int {
        return alertHistoryDao.deleteOldDismissedAlerts(beforeTimestamp)
    }

    override suspend fun deleteAllAlerts() {
        alertHistoryDao.deleteAll()
    }
}
