package com.tailbait.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AlertStatistic
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for AlertHistory entity operations.
 *
 * This DAO provides methods for managing stalking detection alerts in the database,
 * including CRUD operations, alert filtering by severity and status, and alert
 * statistics queries.
 */
@Dao
interface AlertHistoryDao {

    /**
     * Insert a new alert into the database.
     *
     * @param alert The alert to insert
     * @return The row ID of the newly inserted alert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertHistory): Long

    /**
     * Insert multiple alerts in a single transaction.
     *
     * @param alerts List of alerts to insert
     * @return List of row IDs for the inserted alerts
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alerts: List<AlertHistory>): List<Long>

    /**
     * Update an existing alert record.
     * Typically used to update dismissal status.
     *
     * @param alert The alert to update
     */
    @Update
    suspend fun update(alert: AlertHistory)

    /**
     * Delete an alert from the database.
     *
     * @param alert The alert to delete
     */
    @Delete
    suspend fun delete(alert: AlertHistory)

    /**
     * Get an alert by its unique ID.
     *
     * @param id The alert ID
     * @return The alert, or null if not found
     */
    @Query("SELECT * FROM alert_history WHERE id = :id")
    suspend fun getById(id: Long): AlertHistory?

    /**
     * Get all alerts as a Flow for reactive updates.
     *
     * @return Flow emitting list of all alerts ordered by timestamp descending
     */
    @Query("SELECT * FROM alert_history ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<AlertHistory>>

    /**
     * Get active (non-dismissed) alerts.
     *
     * @return Flow emitting list of active alerts
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE is_dismissed = 0
        ORDER BY timestamp DESC, threat_score DESC
    """)
    fun getActiveAlerts(): Flow<List<AlertHistory>>

    /**
     * Get dismissed alerts.
     *
     * @return Flow emitting list of dismissed alerts
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE is_dismissed = 1
        ORDER BY dismissed_at DESC
    """)
    fun getDismissedAlerts(): Flow<List<AlertHistory>>

    /**
     * Get recent alerts within a time window.
     *
     * @param sinceTimestamp Timestamp threshold (alerts after this time)
     * @return Flow emitting list of recent alerts
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE timestamp >= :sinceTimestamp
        ORDER BY timestamp DESC, threat_score DESC
    """)
    fun getRecentAlerts(sinceTimestamp: Long): Flow<List<AlertHistory>>

    /**
     * Get recent active (non-dismissed) alerts within a time window.
     *
     * @param sinceTimestamp Timestamp threshold (alerts after this time)
     * @return Flow emitting list of recent active alerts
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE is_dismissed = 0
        AND timestamp >= :sinceTimestamp
        ORDER BY timestamp DESC, threat_score DESC
    """)
    fun getRecentActiveAlerts(sinceTimestamp: Long): Flow<List<AlertHistory>>

    /**
     * Get alerts by severity level.
     *
     * @param alertLevel The severity level (e.g., "LOW", "MEDIUM", "HIGH", "CRITICAL")
     * @return Flow emitting list of alerts at the specified level
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE alert_level = :alertLevel
        ORDER BY timestamp DESC
    """)
    fun getAlertsByLevel(alertLevel: String): Flow<List<AlertHistory>>

    /**
     * Get active alerts by severity level.
     *
     * @param alertLevel The severity level
     * @return Flow emitting list of active alerts at the specified level
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE alert_level = :alertLevel
        AND is_dismissed = 0
        ORDER BY timestamp DESC
    """)
    fun getActiveAlertsByLevel(alertLevel: String): Flow<List<AlertHistory>>

    /**
     * Get alerts with threat score above a threshold.
     *
     * @param minThreatScore Minimum threat score (0.0 to 1.0)
     * @return Flow emitting list of high-threat alerts
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE threat_score >= :minThreatScore
        ORDER BY threat_score DESC, timestamp DESC
    """)
    fun getHighThreatAlerts(minThreatScore: Double): Flow<List<AlertHistory>>

    /**
     * Get alerts within a time range.
     *
     * @param startTimestamp Start of time range (inclusive)
     * @param endTimestamp End of time range (inclusive)
     * @return Flow emitting list of alerts within the time range
     */
    @Query("""
        SELECT * FROM alert_history
        WHERE timestamp >= :startTimestamp AND timestamp <= :endTimestamp
        ORDER BY timestamp DESC
    """)
    fun getAlertsByTimeRange(startTimestamp: Long, endTimestamp: Long): Flow<List<AlertHistory>>

    /**
     * Get the most recent alert.
     *
     * @return The most recent alert, or null if no alerts exist
     */
    @Query("SELECT * FROM alert_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAlert(): AlertHistory?

    /**
     * Get count of all alerts.
     *
     * @return Flow emitting total alert count
     */
    @Query("SELECT COUNT(*) FROM alert_history")
    fun getAlertCount(): Flow<Int>

    /**
     * Get count of active (non-dismissed) alerts.
     *
     * @return Flow emitting active alert count
     */
    @Query("""
        SELECT COUNT(*) FROM alert_history
        WHERE is_dismissed = 0
    """)
    fun getActiveAlertCount(): Flow<Int>

    /**
     * Get count of alerts by severity level.
     *
     * @param alertLevel The severity level
     * @return Flow emitting count of alerts at the specified level
     */
    @Query("""
        SELECT COUNT(*) FROM alert_history
        WHERE alert_level = :alertLevel
    """)
    fun getAlertCountByLevel(alertLevel: String): Flow<Int>

    /**
     * Get count of active alerts by severity level.
     *
     * @param alertLevel The severity level
     * @return Flow emitting count of active alerts at the specified level
     */
    @Query("""
        SELECT COUNT(*) FROM alert_history
        WHERE alert_level = :alertLevel
        AND is_dismissed = 0
    """)
    fun getActiveAlertCountByLevel(alertLevel: String): Flow<Int>

    /**
     * Get alert statistics grouped by severity level.
     * Returns counts for each alert level.
     *
     * @return List of alert statistics (alert level and count pairs)
     */
    @Query("""
        SELECT alert_level, COUNT(*) as count
        FROM alert_history
        GROUP BY alert_level
    """)
    suspend fun getAlertStatistics(): List<AlertStatistic>

    /**
     * Mark an alert as dismissed.
     *
     * @param alertId The alert ID to dismiss
     * @param dismissedAt Timestamp when the alert was dismissed
     */
    @Query("""
        UPDATE alert_history
        SET is_dismissed = 1, dismissed_at = :dismissedAt
        WHERE id = :alertId
    """)
    suspend fun dismissAlert(alertId: Long, dismissedAt: Long)

    /**
     * Mark multiple alerts as dismissed.
     *
     * @param alertIds List of alert IDs to dismiss
     * @param dismissedAt Timestamp when the alerts were dismissed
     */
    @Query("""
        UPDATE alert_history
        SET is_dismissed = 1, dismissed_at = :dismissedAt
        WHERE id IN (:alertIds)
    """)
    suspend fun dismissAlerts(alertIds: List<Long>, dismissedAt: Long)

    /**
     * Mark all active alerts as dismissed.
     *
     * @param dismissedAt Timestamp when the alerts were dismissed
     * @return Number of alerts dismissed
     */
    @Query("""
        UPDATE alert_history
        SET is_dismissed = 1, dismissed_at = :dismissedAt
        WHERE is_dismissed = 0
    """)
    suspend fun dismissAllAlerts(dismissedAt: Long): Int

    /**
     * Delete alerts older than the specified timestamp.
     * Used for data retention cleanup.
     *
     * @param beforeTimestamp Delete alerts before this timestamp
     * @return Number of alerts deleted
     */
    @Query("DELETE FROM alert_history WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldAlerts(beforeTimestamp: Long): Int

    /**
     * Delete dismissed alerts older than the specified timestamp.
     *
     * @param beforeTimestamp Delete dismissed alerts before this timestamp
     * @return Number of alerts deleted
     */
    @Query("""
        DELETE FROM alert_history
        WHERE is_dismissed = 1 AND dismissed_at < :beforeTimestamp
    """)
    suspend fun deleteOldDismissedAlerts(beforeTimestamp: Long): Int

    /**
     * Delete all alerts.
     * WARNING: This removes all alert history.
     */
    @Query("DELETE FROM alert_history")
    suspend fun deleteAll()
}
