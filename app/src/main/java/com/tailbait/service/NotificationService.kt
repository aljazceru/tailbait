package com.tailbait.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.tailbait.MainActivity
import com.tailbait.R

/**
 * Base notification service class that provides common notification functionality
 * for foreground services.
 *
 * This class handles:
 * - Notification channel creation and management
 * - Foreground service notification building
 * - Notification action PendingIntent creation
 * - Channel priority and importance settings
 *
 * Extend this class to create foreground services that need to display
 * persistent notifications while running in the background.
 *
 * @see TailBaitService
 */
abstract class NotificationService : LifecycleService() {

    /**
     * CRITICAL FIX: Removed lateinit var notificationManager to prevent memory leak.
     *
     * Previously, NotificationManager was stored as a lateinit var which could:
     * 1. Leak the Service context if the NotificationManager holds a reference
     * 2. Keep the Service in memory even after it should be destroyed
     * 3. Cause subtle memory leaks in long-running foreground services
     *
     * Now we get NotificationManager from context each time it's needed.
     * This is efficient as getSystemService() is fast and typically cached.
     */
    private fun getNotificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Abstract property that defines the notification channel ID.
     * Override this in subclasses to provide a unique channel ID.
     */
    protected abstract val notificationChannelId: String

    /**
     * Abstract property that defines the notification channel name.
     * Override this in subclasses to provide a user-friendly channel name.
     */
    protected abstract val notificationChannelName: String

    /**
     * Optional property that defines the notification channel description.
     * Override this in subclasses to provide additional context about the channel.
     */
    protected open val notificationChannelDescription: String? = null

    /**
     * Property that defines the notification channel importance level.
     * Default is IMPORTANCE_LOW for non-intrusive notifications.
     *
     * Available importance levels:
     * - IMPORTANCE_MIN: No sound, no popup, no status bar icon
     * - IMPORTANCE_LOW: No sound, no popup, shows status bar icon
     * - IMPORTANCE_DEFAULT: Sound, no popup, shows status bar icon
     * - IMPORTANCE_HIGH: Sound, popup, shows status bar icon
     */
    protected open val channelImportance: Int = NotificationManager.IMPORTANCE_LOW

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Creates the notification channel for Android O (API 26) and above.
     * On older Android versions, this is a no-op.
     *
     * Notification channels allow users to control notification behavior
     * (sound, vibration, importance) on a per-channel basis.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                notificationChannelName,
                channelImportance
            ).apply {
                description = notificationChannelDescription
                // Don't show badge for low importance channels
                setShowBadge(channelImportance >= NotificationManager.IMPORTANCE_DEFAULT)
            }
            getNotificationManager().createNotificationChannel(channel)
        }
    }

    /**
     * Creates a basic foreground service notification with customizable content.
     *
     * This notification:
     * - Opens the main activity when tapped
     * - Shows the provided title and message
     * - Uses the app icon as the small icon
     * - Supports custom actions via the builder parameter
     *
     * @param title The notification title
     * @param message The notification message/content text
     * @param priority The notification priority (default: PRIORITY_LOW for non-intrusive)
     * @param onGoing Whether the notification is ongoing (default: true for foreground services)
     * @param builder Optional lambda to customize the notification builder further
     * @return A built Notification object ready to be displayed
     */
    protected fun createNotification(
        title: String,
        message: String,
        priority: Int = NotificationCompat.PRIORITY_LOW,
        onGoing: Boolean = true,
        builder: (NotificationCompat.Builder.() -> Unit)? = null
    ): Notification {
        // Intent to open main activity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification) // Note: Create this icon
            .setContentIntent(pendingIntent)
            .setPriority(priority)
            .setOngoing(onGoing)
            .setAutoCancel(false)
            .apply {
                // Apply custom builder modifications if provided
                builder?.invoke(this)
            }
            .build()
    }

    /**
     * Creates a PendingIntent for notification actions.
     *
     * @param action The intent action string
     * @param requestCode A unique request code for this action
     * @return A PendingIntent that can be attached to notification actions
     */
    protected fun createActionPendingIntent(
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(this, this::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Updates an existing notification.
     * Use this to update the notification content while the service is running.
     *
     * @param notificationId The ID of the notification to update
     * @param notification The new notification to display
     */
    protected fun updateNotification(notificationId: Int, notification: Notification) {
        getNotificationManager().notify(notificationId, notification)
    }

    /**
     * Cancels a notification by ID.
     *
     * @param notificationId The ID of the notification to cancel
     */
    protected fun cancelNotification(notificationId: Int) {
        getNotificationManager().cancel(notificationId)
    }

    /**
     * Creates a notification action that can be added to a notification.
     *
     * @param iconResId The icon resource ID for the action
     * @param title The action title
     * @param pendingIntent The PendingIntent to execute when the action is tapped
     * @return A NotificationCompat.Action object
     */
    protected fun createNotificationAction(
        iconResId: Int,
        title: String,
        pendingIntent: PendingIntent
    ): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(iconResId, title, pendingIntent).build()
    }
}
