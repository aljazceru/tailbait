package com.tailbait.flows

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.AndroidJUnitRunner
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.algorithm.MacRotationDetector
import com.tailbait.algorithm.MovementCorrelationCalculator
import com.tailbait.algorithm.ShadowAnalyzer
import com.tailbait.algorithm.ShadowKeyGenerator
import com.tailbait.algorithm.ThreatScoreCalculator
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.AlertRepositoryImpl
import com.tailbait.data.repository.DeviceRepositoryImpl
import com.tailbait.data.repository.LinkStrength
import com.tailbait.data.repository.LocationRepositoryImpl
import com.tailbait.data.repository.SettingsRepositoryImpl
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.data.repository.WhitelistRepositoryImpl
import com.tailbait.service.AlertGenerator
import com.tailbait.service.NotificationHelper
import com.tailbait.util.Constants
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Do not start TailBaitApplication's production detection and cleanup workers during tests.
class TailBaitRegressionRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}

@RunWith(AndroidJUnit4::class)
class DetectionFlowIntegrationTest {
    private lateinit var fixture: DetectionFixture

    @Before
    fun setup() =
        runBlocking {
            fixture = DetectionFixture(ApplicationProvider.getApplicationContext())
            fixture.initialize()
        }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun closeUnknownFollowerAtEveryPlace_generatesPersistedAlert() =
        runBlocking {
            val path = fixture.addPath()
            val device = fixture.addDevice(path)

            val detections = fixture.algorithm.runDetection()

            assertEquals(listOf(device.id), detections.map { it.device.id })
            val detection = detections.single()
            assertEquals(path.map { it.id }.toSet(), detection.locations.map { it.id }.toSet())
            assertTrue(detection.threatScore >= 0.5)
            assertEquals(path.last().timestamp - path.first().timestamp, detection.timeSpanMs)

            val alertIds = fixture.alertGenerator.generateAlerts(detections)
            val alert = fixture.alertRepository.getAllAlerts().first().single()
            assertEquals(listOf(alert.id), alertIds)
            assertFalse(alert.isDismissed)
            assertEquals(device.address, JSONArray(alert.deviceAddresses).getString(0))
            assertEquals(path.size, JSONArray(alert.locationIds).length())
        }

    @Test
    fun unlinkedShadowWithNoOrdinaryCandidate_aggregatesEveryObservation() =
        runBlocking {
            val path = fixture.addPath()
            val devices =
                path.mapIndexed { index, location ->
                    fixture.addDevice(listOf(location), address(index), shadow = true)
                }
            assertTrue(devices.all { it.linkedDeviceId == null })
            assertTrue(fixture.deviceRepository.getSuspiciousDevicesWithLinked(3, fixture.cutoff).first().isEmpty())

            val detection = fixture.algorithm.runDetection().single()

            assertTrue(detection.device.id in devices.map { it.id })
            assertEquals(path.map { it.id }.toSet(), detection.locations.map { it.id }.toSet())
            assertEquals(path.last().timestamp - path.first().timestamp, detection.timeSpanMs)
            assertEquals(1, fixture.alertGenerator.generateAlerts(listOf(detection)).size)
            assertEquals(path.size, JSONArray(fixture.alertRepository.getAllAlerts().first().single().locationIds).length())
        }

    @Test
    fun shadowCutoffAndTrust_removeEvidenceWithoutTrustingSimilarDevices() =
        runBlocking {
            val path = fixture.addPath()
            val devices =
                path.mapIndexed { index, location ->
                    fixture.addDevice(listOf(location), address(index), shadow = true)
                }
            val trusted = fixture.addDevice(path, address(20), shadow = true)
            fixture.whitelistRepository.addToWhitelist(trusted.id, "Trusted tracker", WhitelistRepository.Category.OWN)
            val expiredPath = fixture.addPath(count = 3, start = fixture.now - 4 * DetectionFixture.DAY_MS)
            val expired = fixture.addDevice(expiredPath, address(21), shadow = true)
            // A recent packet must not make this device's old location evidence recent.
            fixture.database.scannedDeviceDao().update(expired.copy(lastSeen = fixture.now))

            val shadow =
                fixture.shadowAnalyzer.findSuspiciousShadows(
                    minLocationCount = 3,
                    whitelistedIds = fixture.whitelistRepository.getAllWhitelistedDeviceIds().first().toSet(),
                    sinceTimestamp = fixture.cutoff,
                ).single()

            assertEquals(devices.map { it.id }.toSet(), shadow.records.map { it.deviceId }.toSet())
            assertEquals(path.size, shadow.records.size)
            assertEquals(path.size, shadow.locationCount)
            assertEquals(path.map { it.id }.toSet(), shadow.locations.map { it.id }.toSet())
            val detection = fixture.algorithm.runDetection().single()
            assertEquals(path.map { it.id }.toSet(), detection.locations.map { it.id }.toSet())
            assertTrue(detection.device.id in devices.map { it.id })
        }

    @Test
    fun linkedEvidenceUsesCutoff_andExplicitAliasTrustExcludesFamily() =
        runBlocking {
            val path = fixture.addPath()
            val root = fixture.addDevice(path)
            val expiredPath = fixture.addPath(count = 3, start = fixture.now - 4 * DetectionFixture.DAY_MS)
            val alias = fixture.addDevice(expiredPath, address(30))
            val fingerprint = "FM:" + "AB".repeat(22)
            fixture.database.scannedDeviceDao().update(root.copy(payloadFingerprint = fingerprint))
            fixture.database.scannedDeviceDao().update(
                alias.copy(
                    lastSeen = fixture.now,
                    linkedDeviceId = root.id,
                    linkStrength = LinkStrength.STRONG.name,
                    payloadFingerprint = fingerprint,
                ),
            )

            val detection = fixture.algorithm.runDetection().single()
            assertEquals(root.id, detection.device.id)
            assertEquals(path.map { it.id }.toSet(), detection.locations.map { it.id }.toSet())
            assertEquals(path.last().timestamp - path.first().timestamp, detection.timeSpanMs)

            fixture.whitelistRepository.addToWhitelist(alias.id, "My tracker", WhitelistRepository.Category.OWN)

            assertTrue(fixture.whitelistRepository.isDeviceWhitelisted(root.id))
            assertTrue(fixture.algorithm.runDetection().isEmpty())
            assertTrue(fixture.alertRepository.getAllAlerts().first().isEmpty())
        }

    @Test
    fun overlappingGpsUncertainty_doesNotManufactureMovement() =
        runBlocking {
            val path = fixture.addPath(accuracy = 2_000f, latitudeStep = 0.002)
            val device = fixture.addDevice(path)
            assertEquals(
                listOf(device.id),
                fixture.deviceRepository.getSuspiciousDevicesWithLinked(3, fixture.cutoff).first().map { it.id },
            )

            assertTrue(fixture.algorithm.runDetection().isEmpty())
        }

    @Test
    fun invalidGpsAccuracy_doesNotManufactureMovement() =
        runBlocking {
            val path = fixture.addPath(accuracy = -1f)
            val device = fixture.addDevice(path)
            assertEquals(
                listOf(device.id),
                fixture.deviceRepository.getSuspiciousDevicesWithLinked(3, fixture.cutoff).first().map { it.id },
            )

            assertTrue(fixture.algorithm.runDetection().isEmpty())
        }

    @Test
    fun concurrentCriticalEscalationAfterTwoHours_persistsExactlyOneNewAlert() =
        runBlocking {
            fixture.addDevice(fixture.addPath())
            val detection = fixture.algorithm.runDetection().single()
            val initial = detection.copy(threatScore = 0.51, timestamp = fixture.now - 2 * DetectionFixture.HOUR_MS)
            assertNotNull(fixture.alertGenerator.generateAlert(initial))
            val critical = detection.copy(threatScore = 0.99, timestamp = fixture.now)
            val secondGenerator =
                AlertGenerator(
                    AlertRepositoryImpl(fixture.database.alertHistoryDao()),
                    fixture.notificationHelper,
                )

            val inserted =
                coroutineScope {
                    listOf(fixture.alertGenerator, secondGenerator).map { generator ->
                        async(Dispatchers.IO) {
                            generator.generateAlert(critical, throttleWindowMs = 6 * DetectionFixture.HOUR_MS)
                        }
                    }.awaitAll().filterNotNull()
                }

            assertEquals(1, inserted.size)
            val alerts = fixture.alertRepository.getAllAlerts().first()
            assertEquals(2, alerts.size)
            val criticalAlert = alerts.single { it.alertLevel == Constants.ALERT_LEVEL_CRITICAL }
            assertEquals(inserted.single(), criticalAlert.id)
            assertEquals(0.99, criticalAlert.threatScore, 0.0)
            assertNull(fixture.alertGenerator.generateAlert(critical))
            assertNull(fixture.alertGenerator.generateAlert(critical.copy(threatScore = 1.0)))
            assertEquals(2, fixture.alertRepository.getAllAlerts().first().size)
        }

    private fun address(index: Int): String = "C0:00:00:00:00:${index.toString(16).padStart(2, '0')}"
}

internal class DetectionFixture(context: Context) : AutoCloseable {
    val now = System.currentTimeMillis()
    val cutoff = now - 3 * DAY_MS
    val database = Room.inMemoryDatabaseBuilder(context, TailBaitDatabase::class.java).build()
    val deviceRepository = DeviceRepositoryImpl(database, database.scannedDeviceDao(), database.deviceLocationRecordDao())
    val locationRepository = LocationRepositoryImpl(context, database.locationDao(), database.userPathDao())
    val settingsRepository = SettingsRepositoryImpl(database.appSettingsDao())
    val whitelistRepository = WhitelistRepositoryImpl(database.whitelistEntryDao(), database.scannedDeviceDao())
    val alertRepository = AlertRepositoryImpl(database.alertHistoryDao())
    val notificationHelper = NotificationHelper(context, alertRepository, settingsRepository)
    val alertGenerator = AlertGenerator(alertRepository, notificationHelper)
    val shadowAnalyzer = ShadowAnalyzer(deviceRepository, locationRepository, MacRotationDetector())
    val algorithm =
        DetectionAlgorithm(
            deviceRepository,
            locationRepository,
            whitelistRepository,
            settingsRepository,
            ThreatScoreCalculator(MovementCorrelationCalculator()),
            shadowAnalyzer,
        )

    suspend fun initialize() {
        database.appSettingsDao().insert(
            AppSettings(
                isTrackingEnabled = false,
                alertThresholdCount = 3,
                minDetectionDistanceMeters = 100.0,
                alertNotificationEnabled = false,
                alertSoundEnabled = false,
                alertVibrationEnabled = false,
            ),
        )
    }

    suspend fun addPath(
        count: Int = 10,
        start: Long = now - 6 * HOUR_MS,
        accuracy: Float = 5f,
        latitudeStep: Double = 0.012,
    ): List<Location> =
        List(count) { index ->
            val location =
                Location(
                    latitude = 37.0 + index * latitudeStep,
                    longitude = -122.0,
                    accuracy = accuracy,
                    timestamp = start + index * 20 * 60_000L,
                    provider = "GPS",
                )
            val stored = location.copy(id = locationRepository.insertLocation(location))
            locationRepository.insertUserPath(stored.id, stored)
            stored
        }

    suspend fun addDevice(
        locations: List<Location>,
        address: String = "A0:00:00:00:00:01",
        shadow: Boolean = false,
    ): ScannedDevice {
        val device =
            ScannedDevice(
                address = address,
                name = null,
                firstSeen = locations.minOf { it.timestamp },
                lastSeen = locations.maxOf { it.timestamp },
                detectionCount = locations.size,
                highestRssi = -35,
                manufacturerId = if (shadow) 0x004C else null,
                deviceType = if (shadow) "TRACKER" else null,
                isTracker = shadow,
                appleContinuityType = if (shadow) 0x12 else null,
                findMyStatus = if (shadow) 0 else null,
                findMySeparated = shadow,
                findMyStatusTimestamp = if (shadow) locations.maxOf { it.timestamp } else null,
                beaconType = if (shadow) "FIND_MY" else null,
                txPowerLevel = if (shadow) -7 else null,
            )
        val profiled = if (shadow) device.copy(shadowKey = ShadowKeyGenerator.generate(device)) else device
        val stored = profiled.copy(id = database.scannedDeviceDao().insert(profiled))
        locations.forEachIndexed { index, location ->
            deviceRepository.insertDeviceLocationRecord(
                deviceId = stored.id,
                locationId = location.id,
                rssi = -35,
                timestamp = location.timestamp,
                locationChanged = index > 0,
                distanceFromLast = null,
                scanTriggerType = "PERIODIC",
            )
        }
        return stored
    }

    override fun close() {
        database.close()
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
