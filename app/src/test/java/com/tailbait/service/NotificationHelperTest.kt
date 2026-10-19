package com.tailbait.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.tailbait.TailBaitApplication
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.AlertRepositoryImpl
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.SettingsRepositoryImpl
import com.tailbait.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [NotificationHelperTest.PermissionRevokingNotificationManager::class])
class NotificationHelperTest {
    private lateinit var context: Application
    private lateinit var database: TailBaitDatabase
    private lateinit var alertRepository: AlertRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var systemNotificationManager: NotificationManager
    private lateinit var notifications: PermissionRevokingNotificationManager
    private val errors = mutableListOf<Throwable>()
    private val logTree =
        object : Timber.Tree() {
            override fun log(
                priority: Int,
                tag: String?,
                message: String,
                t: Throwable?,
            ) {
                if (t != null) errors.add(t)
            }
        }

    private val sampleAlert =
        AlertHistory(
            id = 1L,
            alertLevel = Constants.ALERT_LEVEL_MEDIUM,
            title = "Test Alert",
            message = "This is a test alert message",
            timestamp = 1_700_000_000_000L,
            deviceAddresses = """["AA:BB:CC:DD:EE:FF"]""",
            locationIds = "[1, 2, 3]",
            threatScore = 0.6,
            detectionDetails = "{}",
        )

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        systemNotificationManager = context.getSystemService(NotificationManager::class.java)
        notifications = shadowOf(systemNotificationManager) as PermissionRevokingNotificationManager
        notifications.setNotificationPolicyAccessGranted(true)
        systemNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        notifications.setNotificationsEnabled(true)
        database =
            Room.inMemoryDatabaseBuilder(context, TailBaitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        alertRepository = AlertRepositoryImpl(database.alertHistoryDao())
        settingsRepository = SettingsRepositoryImpl(database.appSettingsDao())
        notificationHelper = NotificationHelper(context, alertRepository, settingsRepository)
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
        database.close()
    }

    @Test
    fun `granted permission posts alert content actions and grouping`() =
        runTest {
            notificationHelper.showAlertNotification(sampleAlert)

            val notification = alertNotification()
            assertEquals(sampleAlert.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(sampleAlert.message, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(TailBaitApplication.CHANNEL_ID_ALERT_MEDIUM, notification.channelId)
            assertEquals(Constants.NOTIFICATION_GROUP_ALERTS, notification.group)
            assertEquals(listOf("View", "Dismiss", "Whitelist"), notification.actions.map { it.title.toString() })
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(Constants.ACTION_VIEW_ALERT, intent.action)
            assertEquals(sampleAlert.id, intent.getLongExtra(Constants.EXTRA_ALERT_ID, -1L))
            assertEquals(1, notifications.size())
            assertNull(notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID))
        }

    @Test
    fun `notifications disabled in settings do not post`() =
        runTest {
            settingsRepository.updateSettings(settingsRepository.getSettingsOnce().copy(alertNotificationEnabled = false))

            notificationHelper.showAlertNotification(sampleAlert)

            assertEquals(0, notifications.size())
        }

    @Test
    fun `do not disturb still prevents alerts`() =
        runTest {
            systemNotificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

            notificationHelper.showAlertNotification(sampleAlert)

            assertEquals(0, notifications.size())
        }

    @Test
    fun `low high and critical alerts retain their channels and priorities`() =
        runTest {
            val levels =
                listOf(
                    Triple(Constants.ALERT_LEVEL_LOW, TailBaitApplication.CHANNEL_ID_ALERT_LOW, NotificationCompat.PRIORITY_LOW),
                    Triple(Constants.ALERT_LEVEL_HIGH, TailBaitApplication.CHANNEL_ID_ALERT_HIGH, NotificationCompat.PRIORITY_HIGH),
                    Triple(Constants.ALERT_LEVEL_CRITICAL, TailBaitApplication.CHANNEL_ID_ALERT_CRITICAL, NotificationCompat.PRIORITY_HIGH),
                )
            for ((level, channel, priority) in levels) {
                notificationHelper.showAlertNotification(sampleAlert.copy(alertLevel = level))

                val notification = alertNotification()
                assertEquals(channel, notification.channelId)
                assertEquals(priority, notification.priority)
            }
        }

    @Test
    fun `granted camera presence and critical alerts both post with a summary`() =
        runTest {
            val generator = AlertGenerator(alertRepository, notificationHelper)
            val cameraId = generator.generateCameraGlassesPresenceAlert(1L, "AA:BB:CC:DD:EE:FF", null, "Ray-Ban Meta", null)
            assertNotNull(cameraId)
            val criticalAlert = sampleAlert.copy(id = 2L, alertLevel = Constants.ALERT_LEVEL_CRITICAL)
            alertRepository.insertAlert(criticalAlert)

            notificationHelper.showAlertNotification(criticalAlert)

            assertEquals("Camera glasses nearby", alertNotification(cameraId!!).extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(TailBaitApplication.CHANNEL_ID_ALERT_MEDIUM, alertNotification(cameraId).channelId)
            assertEquals(TailBaitApplication.CHANNEL_ID_ALERT_CRITICAL, alertNotification(criticalAlert.id).channelId)
            val summary = notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID)!!
            assertEquals("2 active alerts", summary.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(Constants.NOTIFICATION_GROUP_ALERTS, summary.group)
            assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            assertEquals(3, notifications.size())
        }

    @Test
    fun `summary contains active alert count and titles`() =
        runTest {
            seedAlerts(3)

            notificationHelper.showAlertNotification(sampleAlert)

            val summary = notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID)!!
            assertEquals("3 active alerts", summary.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(3, summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
            assertEquals(2, notifications.size())
        }

    @Test
    fun `denied permission prevents alert and summary posts`() =
        runTest {
            seedAlerts(3)
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

            notificationHelper.showAlertNotification(sampleAlert)

            assertFalse(notificationHelper.hasNotificationPermission())
            assertEquals(0, notifications.size())
            assertEquals(3, alertRepository.getActiveAlerts().first().size)
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `disabled system notifications prevent posts despite runtime permission`() =
        runTest {
            seedAlerts(2)
            notifications.setNotificationsEnabled(false)

            notificationHelper.showAlertNotification(sampleAlert)

            assertFalse(notificationHelper.hasNotificationPermission())
            assertEquals(0, notifications.size())
        }

    @Test
    fun `revocation after alert posting prevents unauthorized summary`() =
        runTest {
            seedAlerts(2)
            notifications.revokeAfterNotificationId = Constants.ALERT_NOTIFICATION_BASE_ID + sampleAlert.id.toInt()

            notificationHelper.showAlertNotification(sampleAlert)

            assertNotNull(alertNotification())
            assertFalse(notificationHelper.hasNotificationPermission())
            assertNull(notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID))
            assertEquals(1, notifications.size())
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `revocation during alert posting is logged without crashing`() =
        runTest {
            seedAlerts(2)
            notifications.revokeOnNotificationId = Constants.ALERT_NOTIFICATION_BASE_ID + sampleAlert.id.toInt()

            notificationHelper.showAlertNotification(sampleAlert)

            assertFalse(notificationHelper.hasNotificationPermission())
            assertEquals(0, notifications.size())
            assertTrue(errors.single() is SecurityException)
            assertEquals(2, alertRepository.getActiveAlerts().first().size)
        }

    @Test
    fun `revocation during summary posting preserves the alert and logs failure`() =
        runTest {
            seedAlerts(2)
            notifications.revokeOnNotificationId = Constants.ALERT_NOTIFICATION_SUMMARY_ID

            notificationHelper.showAlertNotification(sampleAlert)

            assertNotNull(alertNotification())
            assertFalse(notificationHelper.hasNotificationPermission())
            assertNull(notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID))
            assertEquals(1, notifications.size())
            assertTrue(errors.single() is SecurityException)
            assertEquals(2, alertRepository.getActiveAlerts().first().size)
        }

    @Test
    fun `denied permission does not prevent dismissal or post a replacement summary`() =
        runTest {
            seedAlerts(3)
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

            notificationHelper.dismissNotification(sampleAlert.id)

            assertTrue(alertRepository.getAlertById(sampleAlert.id)!!.isDismissed)
            assertEquals(2, alertRepository.getActiveAlerts().first().size)
            assertEquals(0, notifications.size())
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `revocation during dismissal summary posting preserves the dismissal`() =
        runTest {
            seedAlerts(3)
            notifications.revokeOnNotificationId = Constants.ALERT_NOTIFICATION_SUMMARY_ID

            notificationHelper.dismissNotification(sampleAlert.id)

            assertTrue(alertRepository.getAlertById(sampleAlert.id)!!.isDismissed)
            assertEquals(2, alertRepository.getActiveAlerts().first().size)
            assertFalse(notificationHelper.hasNotificationPermission())
            assertEquals(0, notifications.size())
            assertTrue(errors.single() is SecurityException)
        }

    @Test
    fun `denied camera notification still stores the alert and preserves throttling`() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val generator = AlertGenerator(alertRepository, notificationHelper)

            val alertId = generator.generateCameraGlassesPresenceAlert(1L, "AA:BB:CC:DD:EE:FF", null, "Ray-Ban Meta", null)
            val duplicateId = generator.generateCameraGlassesPresenceAlert(1L, "AA:BB:CC:DD:EE:FF", null, "Ray-Ban Meta", null)

            assertNotNull(alertId)
            assertNull(duplicateId)
            assertEquals("Camera glasses nearby", alertRepository.getAlertById(alertId!!)!!.title)
            assertEquals(1, alertRepository.getActiveAlerts().first().size)
            assertEquals(0, notifications.size())
        }

    @Test
    fun `dismissal cancels child updates summary and removes summary below two alerts`() =
        runTest {
            seedAlerts(3)
            notificationHelper.showAlertNotification(sampleAlert)

            notificationHelper.dismissNotification(sampleAlert.id)

            assertNull(notifications.getNotification(Constants.ALERT_NOTIFICATION_BASE_ID + sampleAlert.id.toInt()))
            assertTrue(alertRepository.getAlertById(sampleAlert.id)!!.isDismissed)
            val summary = notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID)!!
            assertEquals("2 active alerts", summary.extras.getCharSequence(Notification.EXTRA_TITLE))

            notificationHelper.dismissNotification(2L)

            assertNull(notifications.getNotification(Constants.ALERT_NOTIFICATION_SUMMARY_ID))
            assertEquals(1, alertRepository.getActiveAlerts().first().size)
        }

    @Test
    fun `dismissal removes summary when no active alerts remain`() =
        runTest {
            seedAlerts(2)
            notificationHelper.showAlertNotification(sampleAlert)
            alertRepository.dismissAlert(2L)

            notificationHelper.dismissNotification(sampleAlert.id)

            assertEquals(0, notifications.size())
            assertTrue(alertRepository.getActiveAlerts().first().isEmpty())
        }

    @Test
    fun `cancel all removes notifications without dismissing stored alerts`() =
        runTest {
            seedAlerts(2)
            notificationHelper.showAlertNotification(sampleAlert)
            assertEquals(2, notifications.size())

            notificationHelper.cancelAllNotifications()

            assertEquals(0, notifications.size())
            assertEquals(2, alertRepository.getActiveAlerts().first().size)
        }

    @Test
    fun `disabled sound and vibration still allow an alert`() =
        runTest {
            settingsRepository.updateSettings(
                settingsRepository.getSettingsOnce().copy(alertSoundEnabled = false, alertVibrationEnabled = false),
            )

            notificationHelper.showAlertNotification(sampleAlert)

            val notification = alertNotification()
            assertNull(notification.sound)
            assertNull(notification.vibrate)
        }

    @Test
    @Config(sdk = [28])
    fun `pre Android 13 does not require notification runtime permission`() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

            notificationHelper.showAlertNotification(sampleAlert)

            assertTrue(notificationHelper.hasNotificationPermission())
            assertNotNull(alertNotification())
        }

    @Test
    fun `test notification requires permission and posts after permission is granted`() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            notificationHelper.showTestNotification()
            assertEquals(0, notifications.size())

            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            notificationHelper.showTestNotification()

            assertEquals("Test Alert", alertNotification(0L).extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(1, notifications.size())
        }

    @Test
    fun `summary collection cancellation propagates through posting and dismissal`() =
        runTest {
            seedAlerts(2)
            val cancellation = CancellationException("Summary collection cancelled")
            val cancellingRepository =
                object : AlertRepository by alertRepository {
                    override fun getActiveAlerts(): Flow<List<AlertHistory>> = flow { throw cancellation }
                }
            notificationHelper = NotificationHelper(context, cancellingRepository, settingsRepository)

            assertSame(cancellation, runCatching { notificationHelper.showAlertNotification(sampleAlert) }.exceptionOrNull())
            assertNotNull(alertNotification())
            assertSame(cancellation, runCatching { notificationHelper.dismissNotification(sampleAlert.id) }.exceptionOrNull())
            assertTrue(alertRepository.getAlertById(sampleAlert.id)!!.isDismissed)
            assertTrue(errors.isEmpty())
        }

    private suspend fun seedAlerts(count: Int) {
        alertRepository.insertAlerts((1..count).map { sampleAlert.copy(id = it.toLong()) })
    }

    private fun alertNotification(id: Long = sampleAlert.id): Notification =
        requireNotNull(notifications.getNotification(Constants.ALERT_NOTIFICATION_BASE_ID + id.toInt()))

    @Implements(NotificationManager::class)
    class PermissionRevokingNotificationManager : ShadowNotificationManager() {
        var revokeOnNotificationId: Int? = null
        var revokeAfterNotificationId: Int? = null

        @Implementation
        override fun notify(
            tag: String?,
            id: Int,
            notification: Notification,
        ) {
            if (id == revokeOnNotificationId) {
                shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                throw SecurityException("Notification permission revoked before posting")
            }
            super.notify(tag, id, notification)
            if (id == revokeAfterNotificationId) {
                shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
