package com.tailbait.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailbait.TailBaitApplication
import com.tailbait.MainActivity
import com.tailbait.R
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for managing alert notifications with Hilt injection.
 *
 * This class handles:
 * - Building and showing alert notifications with different severity levels
 * - Notification actions (View Alert, Dismiss, Add to Whitelist)
 * - Sound/vibration patterns based on alert level
 * - Notification grouping for multiple alerts
 * - Do-not-disturb (DND) status respect
 * - User preference checks for notification settings
 * - Notification history logging
 *
 * Notification channels are created in TailBaitApplication:
 * - CHANNEL_ID_ALERT_LOW (Low priority)
 * - CHANNEL_ID_ALERT_MEDIUM (Medium priority)
 * - CHANNEL_ID_ALERT_HIGH (High priority)
 * - CHANNEL_ID_ALERT_CRITICAL (Critical priority with vibration)
 *
 * @property context Application context
 * @property alertRepository Repository for alert data operations
 * @property settingsRepository Repository for user preferences
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertRepository: AlertRepository,
    private val settingsRepository: SettingsRepository
) {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val systemNotificationManager = context.getSystemService(
        Context.NOTIFICATION_SERVICE
    ) as NotificationManager

    /**
     * Show notification for a new alert.
     * Respects user preferences and DND status.
     *
     * @param alert The alert to notify about
     */
    suspend fun showAlertNotification(alert: AlertHistory) {
        // Check user preferences
        val settings = settingsRepository.getSettingsOnce()
        if (!settings.alertNotificationEnabled) {
            Timber.d("Notifications disabled in settings, skipping notification for alert ${alert.id}")
            return
        }

        // Check DND status if configured to respect it
        if (isDoNotDisturbActive() && shouldRespectDoNotDisturb()) {
            Timber.d("Do Not Disturb is active, skipping notification for alert ${alert.id}")
            return
        }

        try {
            val notification = buildAlertNotification(alert, settings)
            val notificationId = getNotificationId(alert)

            // Show notification
            notificationManager.notify(notificationId, notification)
            Timber.d("Notification shown for alert ${alert.id} with ID $notificationId")

            // Show summary notification if there are multiple active alerts
            showSummaryNotificationIfNeeded()

        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException: Notification permission not granted")
        } catch (e: Exception) {
            Timber.e(e, "Error showing notification for alert ${alert.id}")
        }
    }

    /**
     * Build notification for an alert with appropriate channel, actions, and styling.
     *
     * @param alert The alert to build notification for
     * @param settings App settings for sound/vibration preferences
     * @return Built notification
     */
    private fun buildAlertNotification(
        alert: AlertHistory,
        settings: com.tailbait.data.database.entities.AppSettings
    ): android.app.Notification {
        val channelId = getChannelIdForAlertLevel(alert.alertLevel)
        val priority = getPriorityForAlertLevel(alert.alertLevel)

        // Build base notification
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(priority)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(Constants.NOTIFICATION_GROUP_ALERTS)
            .setWhen(alert.timestamp)
            .setShowWhen(true)

        // Add content intent (tap to view alert details)
        val contentIntent = createViewAlertIntent(alert.id)
        builder.setContentIntent(contentIntent)

        // Add notification actions
        builder.addAction(createViewAlertAction(alert.id))
        builder.addAction(createDismissAlertAction(alert.id))

        // Add whitelist action if there are device addresses in the alert
        if (alert.deviceAddresses.isNotEmpty()) {
            builder.addAction(createAddToWhitelistAction(alert.id, alert.deviceAddresses))
        }

        // Add sound if enabled
        if (settings.alertSoundEnabled) {
            val soundUri = getSoundForAlertLevel(alert.alertLevel)
            builder.setSound(soundUri)
        }

        // Add vibration if enabled
        if (settings.alertVibrationEnabled) {
            val vibrationPattern = getVibrationPatternForAlertLevel(alert.alertLevel)
            builder.setVibrate(vibrationPattern)
        }

        // Set color based on alert level
        builder.setColor(getColorForAlertLevel(alert.alertLevel))

        // For high and critical alerts, make them heads-up notifications
        if (alert.alertLevel == Constants.ALERT_LEVEL_HIGH ||
            alert.alertLevel == Constants.ALERT_LEVEL_CRITICAL) {
            builder.priority = NotificationCompat.PRIORITY_HIGH
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        return builder.build()
    }

    /**
     * Create PendingIntent to open alert detail screen.
     *
     * @param alertId Alert ID to view
     * @return PendingIntent for viewing alert
     */
    private fun createViewAlertIntent(alertId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Constants.ACTION_VIEW_ALERT
            putExtra(Constants.EXTRA_ALERT_ID, alertId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            (Constants.REQUEST_CODE_VIEW_ALERT + alertId.toInt()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Create "View Alert" notification action.
     *
     * @param alertId Alert ID
     * @return NotificationCompat.Action
     */
    private fun createViewAlertAction(alertId: Long): NotificationCompat.Action {
        val intent = createViewAlertIntent(alertId)
        return NotificationCompat.Action.Builder(
            R.drawable.ic_visibility,
            "View",
            intent
        ).build()
    }

    /**
     * Create "Dismiss" notification action.
     *
     * @param alertId Alert ID
     * @return NotificationCompat.Action
     */
    private fun createDismissAlertAction(alertId: Long): NotificationCompat.Action {
        val intent = Intent(context, AlertNotificationReceiver::class.java).apply {
            action = Constants.ACTION_DISMISS_ALERT
            putExtra(Constants.EXTRA_ALERT_ID, alertId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (Constants.REQUEST_CODE_DISMISS_ALERT + alertId.toInt()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            "Dismiss",
            pendingIntent
        ).build()
    }

    /**
     * Create "Add to Whitelist" notification action.
     *
     * @param alertId Alert ID
     * @param deviceAddresses JSON string of device addresses
     * @return NotificationCompat.Action
     */
    private fun createAddToWhitelistAction(alertId: Long, deviceAddresses: String): NotificationCompat.Action {
        val intent = Intent(context, AlertNotificationReceiver::class.java).apply {
            action = Constants.ACTION_ADD_TO_WHITELIST
            putExtra(Constants.EXTRA_ALERT_ID, alertId)
            putExtra(Constants.EXTRA_DEVICE_IDS, deviceAddresses)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (Constants.REQUEST_CODE_ADD_TO_WHITELIST + alertId.toInt()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_playlist_add,
            "Whitelist",
            pendingIntent
        ).build()
    }

    /**
     * Show summary notification when there are multiple active alerts.
     * Groups all alert notifications under a single summary.
     */
    private suspend fun showSummaryNotificationIfNeeded() {
        try {
            val activeAlerts = alertRepository.getActiveAlerts().first()

            // Only show summary if there are 2 or more active alerts
            if (activeAlerts.size < 2) {
                return
            }

            val summaryText = when {
                activeAlerts.size == 2 -> "2 active alerts"
                else -> "${activeAlerts.size} active alerts"
            }

            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(summaryText)
                .setSummaryText("Tap to view all alerts")

            // Add up to 5 alerts to the summary
            activeAlerts.take(5).forEach { alert ->
                inboxStyle.addLine("${alert.alertLevel}: ${alert.title}")
            }

            if (activeAlerts.size > 5) {
                inboxStyle.addLine("...and ${activeAlerts.size - 5} more")
            }

            val summaryNotification = NotificationCompat.Builder(
                context,
                TailBaitApplication.CHANNEL_ID_ALERT_MEDIUM
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(summaryText)
                .setContentText("You have multiple active security alerts")
                .setStyle(inboxStyle)
                .setGroup(Constants.NOTIFICATION_GROUP_ALERTS)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            notificationManager.notify(Constants.ALERT_NOTIFICATION_SUMMARY_ID, summaryNotification)
            Timber.d("Summary notification shown for ${activeAlerts.size} alerts")

        } catch (e: Exception) {
            Timber.e(e, "Error showing summary notification")
        }
    }

    /**
     * Dismiss a notification and mark the alert as dismissed in the database.
     *
     * @param alertId Alert ID to dismiss
     */
    suspend fun dismissNotification(alertId: Long) {
        try {
            // Cancel the notification
            val notificationId = getNotificationId(alertId)
            notificationManager.cancel(notificationId)
            Timber.d("Notification cancelled for alert $alertId")

            // Mark alert as dismissed in database
            alertRepository.dismissAlert(alertId)

            // Update summary notification
            updateSummaryNotification()

        } catch (e: Exception) {
            Timber.e(e, "Error dismissing notification for alert $alertId")
        }
    }

    /**
     * Update summary notification based on current active alerts.
     */
    private suspend fun updateSummaryNotification() {
        try {
            val activeAlerts = alertRepository.getActiveAlerts().first()

            if (activeAlerts.isEmpty()) {
                // Cancel summary if no active alerts
                notificationManager.cancel(Constants.ALERT_NOTIFICATION_SUMMARY_ID)
                Timber.d("Summary notification cancelled - no active alerts")
            } else if (activeAlerts.size >= 2) {
                // Update summary
                showSummaryNotificationIfNeeded()
            } else {
                // Cancel summary if only 1 active alert
                notificationManager.cancel(Constants.ALERT_NOTIFICATION_SUMMARY_ID)
                Timber.d("Summary notification cancelled - only 1 active alert")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating summary notification")
        }
    }

    /**
     * Cancel all alert notifications.
     */
    fun cancelAllNotifications() {
        try {
            notificationManager.cancelAll()
            Timber.d("All notifications cancelled")
        } catch (e: Exception) {
            Timber.e(e, "Error cancelling all notifications")
        }
    }

    /**
     * Get notification channel ID based on alert level.
     *
     * @param alertLevel Alert level (LOW, MEDIUM, HIGH, CRITICAL)
     * @return Channel ID
     */
    private fun getChannelIdForAlertLevel(alertLevel: String): String {
        return when (alertLevel) {
            Constants.ALERT_LEVEL_LOW -> TailBaitApplication.CHANNEL_ID_ALERT_LOW
            Constants.ALERT_LEVEL_MEDIUM -> TailBaitApplication.CHANNEL_ID_ALERT_MEDIUM
            Constants.ALERT_LEVEL_HIGH -> TailBaitApplication.CHANNEL_ID_ALERT_HIGH
            Constants.ALERT_LEVEL_CRITICAL -> TailBaitApplication.CHANNEL_ID_ALERT_CRITICAL
            else -> TailBaitApplication.CHANNEL_ID_ALERT_MEDIUM // Default
        }
    }

    /**
     * Get notification priority based on alert level.
     *
     * @param alertLevel Alert level
     * @return NotificationCompat priority constant
     */
    private fun getPriorityForAlertLevel(alertLevel: String): Int {
        return when (alertLevel) {
            Constants.ALERT_LEVEL_LOW -> NotificationCompat.PRIORITY_LOW
            Constants.ALERT_LEVEL_MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            Constants.ALERT_LEVEL_HIGH -> NotificationCompat.PRIORITY_HIGH
            Constants.ALERT_LEVEL_CRITICAL -> NotificationCompat.PRIORITY_MAX
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
    }

    /**
     * Get sound URI for alert level.
     *
     * @param alertLevel Alert level
     * @return Sound URI
     */
    private fun getSoundForAlertLevel(alertLevel: String): Uri {
        return when (alertLevel) {
            Constants.ALERT_LEVEL_CRITICAL, Constants.ALERT_LEVEL_HIGH -> {
                // Use default alarm sound for critical/high alerts
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            else -> {
                // Use default notification sound for low/medium alerts
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
        }
    }

    /**
     * Get vibration pattern for alert level.
     * Pattern format: [delay, vibrate, sleep, vibrate, sleep, ...]
     *
     * @param alertLevel Alert level
     * @return Vibration pattern array
     */
    private fun getVibrationPatternForAlertLevel(alertLevel: String): LongArray {
        return when (alertLevel) {
            Constants.ALERT_LEVEL_CRITICAL -> {
                // Aggressive pattern: long vibrations with short pauses
                longArrayOf(0, 500, 200, 500, 200, 500)
            }
            Constants.ALERT_LEVEL_HIGH -> {
                // Strong pattern: medium vibrations
                longArrayOf(0, 400, 300, 400)
            }
            Constants.ALERT_LEVEL_MEDIUM -> {
                // Moderate pattern: two short vibrations
                longArrayOf(0, 300, 200, 300)
            }
            Constants.ALERT_LEVEL_LOW -> {
                // Gentle pattern: single short vibration
                longArrayOf(0, 200)
            }
            else -> longArrayOf(0, 300, 200, 300) // Default
        }
    }

    /**
     * Get color for alert level.
     *
     * @param alertLevel Alert level
     * @return Color int
     */
    private fun getColorForAlertLevel(alertLevel: String): Int {
        return when (alertLevel) {
            Constants.ALERT_LEVEL_CRITICAL -> 0xFFD32F2F.toInt() // Red
            Constants.ALERT_LEVEL_HIGH -> 0xFFF57C00.toInt() // Orange
            Constants.ALERT_LEVEL_MEDIUM -> 0xFFFFA000.toInt() // Amber
            Constants.ALERT_LEVEL_LOW -> 0xFF1976D2.toInt() // Blue
            else -> 0xFF1976D2.toInt() // Default blue
        }
    }

    /**
     * Get unique notification ID for an alert.
     * Uses base ID + alert ID to ensure uniqueness.
     *
     * @param alert Alert or alert ID
     * @return Notification ID
     */
    private fun getNotificationId(alert: AlertHistory): Int {
        return Constants.ALERT_NOTIFICATION_BASE_ID + alert.id.toInt()
    }

    private fun getNotificationId(alertId: Long): Int {
        return Constants.ALERT_NOTIFICATION_BASE_ID + alertId.toInt()
    }

    /**
     * Check if Do Not Disturb (DND) is currently active.
     *
     * @return True if DND is active
     */
    private fun isDoNotDisturbActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val filter = systemNotificationManager.currentInterruptionFilter
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }
    }

    /**
     * Check if we should respect Do Not Disturb mode.
     * For critical alerts, we may choose to override DND.
     *
     * @return True if should respect DND
     */
    private fun shouldRespectDoNotDisturb(): Boolean {
        // For now, always respect DND
        // In the future, this could be a user setting
        return true
    }

    /**
     * Check if notification permission is granted.
     * Required for Android 13+ (API 33+)
     *
     * @return True if permission is granted
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationManager.areNotificationsEnabled()
        } else {
            true // Permission not required on older versions
        }
    }

    /**
     * Test notification system by showing a test notification.
     * Useful for debugging and user verification.
     */
    suspend fun showTestNotification() {
        if (!hasNotificationPermission()) {
            Timber.w("Cannot show test notification: permission not granted")
            return
        }

        val message = "This is a test notification from BLE Tracker. " +
                "If you see this, notifications are working correctly."

        val testAlert = AlertHistory(
            id = 0,
            alertLevel = Constants.ALERT_LEVEL_MEDIUM,
            title = "Test Alert",
            message = message,
            timestamp = System.currentTimeMillis(),
            deviceAddresses = "[]",
            locationIds = "[]",
            threatScore = 0.5,
            detectionDetails = "{}"
        )

        showAlertNotification(testAlert)
    }
}
