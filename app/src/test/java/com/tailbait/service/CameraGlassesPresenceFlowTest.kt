package com.tailbait.service

import android.content.Context
import androidx.room.Room
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.model.DetectionResult
import com.tailbait.data.repository.AlertRepositoryImpl
import com.tailbait.data.repository.SettingsRepositoryImpl
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.data.repository.WhitelistRepositoryImpl
import com.tailbait.util.Constants
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.ManufacturerDataParser
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import android.os.ParcelUuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * End-to-end JVM test for the camera glasses presence alert feature.
 *
 * Runs the real chain over a real in-memory Room database:
 * advertisement -> DeviceIdentifier classification -> the exact
 * BleScannerManager gate (setting + type + whitelist) -> AlertGenerator
 * -> alert_history row + notification call, including 15-minute throttling.
 *
 * Only the Nordic BLE callback and NotificationHelper (Android notification
 * manager) are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraGlassesPresenceFlowTest {
    private lateinit var db: TailBaitDatabase
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var whitelistRepository: WhitelistRepositoryImpl
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var alertGenerator: AlertGenerator

    private val glassesAddress = "02:00:00:00:00:01"

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db =
            Room.inMemoryDatabaseBuilder(context, TailBaitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        settingsRepository = SettingsRepositoryImpl(db.appSettingsDao())
        whitelistRepository = WhitelistRepositoryImpl(db.whitelistEntryDao(), db.scannedDeviceDao())
        notificationHelper = mockk(relaxed = true)
        alertGenerator = AlertGenerator(AlertRepositoryImpl(db.alertHistoryDao()), notificationHelper)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Mirrors the BleScannerManager gate for one scan result. */
    private suspend fun processGlassesSighting(
        address: String,
        identification: DeviceIdentifier.IdentificationResult,
    ): Long? {
        // Mimic upsertDeviceWithFingerprint: reuse the existing device row for a
        // known address so the device ID (the throttle key) is stable.
        val deviceId =
            db.scannedDeviceDao().getByAddress(address)?.id
                ?: db.scannedDeviceDao().insert(
                    ScannedDevice(
                        address = address,
                        name = null,
                        firstSeen = System.currentTimeMillis(),
                        lastSeen = System.currentTimeMillis(),
                    ),
                )

        val settings = settingsRepository.getSettingsOnce()
        val shouldAlert =
            settings.cameraGlassesAlertsEnabled &&
                identification.deviceType == ManufacturerDataParser.DeviceType.CAMERA_GLASSES &&
                !whitelistRepository.isDeviceWhitelisted(deviceId)

        if (!shouldAlert) return null

        return alertGenerator.generateCameraGlassesPresenceAlert(
            deviceId = deviceId,
            address = address,
            deviceName = null,
            deviceModel = identification.deviceModel,
            manufacturerName = identification.manufacturerName,
        )
    }

    @Test
    fun `luxottica sighting with toggle on stores alert and fires notification`() =
        runTest {
            // getSettingsOnce creates the settings row; then flip the toggle
            settingsRepository.getSettingsOnce()
            settingsRepository.updateCameraGlassesAlertsEnabled(true)
            assertTrue(settingsRepository.getSettingsOnce().cameraGlassesAlertsEnabled)

            // Classification: exactly what BleScannerManager does per scan result
            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = 0x0D53,
                    manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                    serviceUuids = listOf(ParcelUuid(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))),
                    appearance = null,
                    deviceName = null,
                )
            assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, identification.deviceType)

            val alertId = processGlassesSighting(glassesAddress, identification)

            assertNotNull(alertId)
            val stored = db.alertHistoryDao().getById(alertId!!)
            assertNotNull(stored)
            assertEquals(Constants.ALERT_LEVEL_MEDIUM, stored!!.alertLevel)
            assertEquals(0.0, stored.threatScore, 0.0)
            assertTrue(stored.deviceAddresses.contains(glassesAddress))
            coVerify(exactly = 1) { notificationHelper.showAlertNotification(any()) }
        }

    @Test
    fun `presence alert never suppresses a tracking alert for the same device`() =
        runTest {
            settingsRepository.getSettingsOnce()
            settingsRepository.updateCameraGlassesAlertsEnabled(true)

            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = 0x0D53,
                    manufacturerData = byteArrayOf(0x01),
                    serviceUuids = null,
                    appearance = null,
                    deviceName = null,
                )

            // Presence alert fires first (single sighting)
            val presenceId = processGlassesSighting(glassesAddress, identification)
            assertNotNull(presenceId)

            // Then the device is detected FOLLOWING the user (multi-location,
            // high threat score). The tracking alert must NOT be suppressed by
            // the presence alert — regression test for the smart-throttle bug.
            val device =
                db.scannedDeviceDao().getByAddress(glassesAddress) ?: ScannedDevice(
                    address = glassesAddress,
                    name = null,
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                )
            val trackingResult =
                DetectionResult(
                    device = device,
                    locations =
                        listOf(
                            Location(id = 1, latitude = 40.7128, longitude = -74.0060, accuracy = 10.0f, timestamp = System.currentTimeMillis() - 7200000, provider = "GPS"),
                            Location(id = 2, latitude = 40.7228, longitude = -74.0160, accuracy = 10.0f, timestamp = System.currentTimeMillis() - 3600000, provider = "GPS"),
                            Location(id = 3, latitude = 40.7328, longitude = -74.0260, accuracy = 10.0f, timestamp = System.currentTimeMillis(), provider = "GPS"),
                        ),
                    threatScore = 0.9,
                    maxDistance = 3000.0,
                    avgDistance = 2000.0,
                    detectionReason = "Multi-location tracking",
                )

            val trackingAlertId = alertGenerator.generateAlert(trackingResult)

            assertNotNull(trackingAlertId)
            val alerts = db.alertHistoryDao().getAllAlerts().first()
            assertEquals(2, alerts.size)
            assertEquals(1, alerts.count { it.isCameraGlassesPresence })
            assertEquals(1, alerts.count { it.threatScore == 0.9 })
        }

    @Test
    fun `toggle off produces no presence alert even for glasses`() =
        runTest {
            // Default settings row, toggle left OFF
            settingsRepository.getSettingsOnce()
            assertFalse(settingsRepository.getSettingsOnce().cameraGlassesAlertsEnabled)

            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = 0x03C2, // Snap Spectacles
                    manufacturerData = byteArrayOf(0x01),
                    serviceUuids = null,
                    appearance = null,
                    deviceName = null,
                )
            assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, identification.deviceType)

            val alertId = processGlassesSighting(glassesAddress, identification)

            assertNull(alertId)
            assertTrue(db.alertHistoryDao().getAllAlerts().first().isEmpty())
            coVerify(exactly = 0) { notificationHelper.showAlertNotification(any()) }
        }

    @Test
    fun `second sighting within 15 minutes is throttled`() =
        runTest {
            settingsRepository.getSettingsOnce()
            settingsRepository.updateCameraGlassesAlertsEnabled(true)

            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = 0x0D53,
                    manufacturerData = byteArrayOf(0x01),
                    serviceUuids = null,
                    appearance = null,
                    deviceName = null,
                )

            val first = processGlassesSighting(glassesAddress, identification)
            assertNotNull(first)

            val second = processGlassesSighting(glassesAddress, identification)

            assertNull(second)
            assertEquals(1, db.alertHistoryDao().getAllAlerts().first().size)
            coVerify(exactly = 1) { notificationHelper.showAlertNotification(any()) }
        }

    @Test
    fun `whitelisted device does not alert`() =
        runTest {
            settingsRepository.getSettingsOnce()
            settingsRepository.updateCameraGlassesAlertsEnabled(true)

            val deviceId = db.scannedDeviceDao().insert(
                ScannedDevice(
                    address = glassesAddress,
                    name = "My Ray-Ban Meta",
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                ),
            )
            whitelistRepository.addToWhitelist(deviceId, "My glasses", WhitelistRepository.Category.OWN)

            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = 0x0D53,
                    manufacturerData = byteArrayOf(0x01),
                    serviceUuids = null,
                    appearance = null,
                    deviceName = "My Ray-Ban Meta",
                )
            assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, identification.deviceType)

            // The full gate: whitelisted -> no alert, no notification, nothing stored
            val settings = settingsRepository.getSettingsOnce()
            val shouldAlert =
                settings.cameraGlassesAlertsEnabled &&
                    identification.deviceType == ManufacturerDataParser.DeviceType.CAMERA_GLASSES &&
                    !whitelistRepository.isDeviceWhitelisted(deviceId)
            assertFalse(shouldAlert)

            coVerify(exactly = 0) { notificationHelper.showAlertNotification(any()) }
            assertTrue(db.alertHistoryDao().getAllAlerts().first().isEmpty())
        }

    @Test
    fun `fd5f service uuid alone classifies as camera glasses`() =
        runTest {
            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = null,
                    manufacturerData = null,
                    serviceUuids = listOf(
                        ParcelUuid(UUID.fromString("0000FD5F-0000-1000-8000-00805F9B34FB")),
                    ),
                    appearance = null,
                    deviceName = null,
                )
            assertEquals(ManufacturerDataParser.DeviceType.CAMERA_GLASSES, identification.deviceType)
        }

    @Test
    fun `non-glasses device never reaches the alert path`() =
        runTest {
            settingsRepository.getSettingsOnce()
            settingsRepository.updateCameraGlassesAlertsEnabled(true)

            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = 0x004C, // Apple
                    manufacturerData = byteArrayOf(0x10, 0x00, 0x00), // Nearby Info (iPhone)
                    serviceUuids = null,
                    appearance = null,
                    deviceName = "Someone's iPhone",
                )
            assertFalse(identification.deviceType == ManufacturerDataParser.DeviceType.CAMERA_GLASSES)
            assertTrue(db.alertHistoryDao().getAllAlerts().first().isEmpty())
        }
}
