package com.tailbait.flows

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.algorithm.DetectionAlgorithm
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.*
import com.tailbait.data.repository.*
import com.tailbait.service.AlertGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for the complete detection flow.
 *
 * This test verifies the end-to-end detection process:
 * 1. Device scanning and storage
 * 2. Location tracking
 * 3. Device-location correlation
 * 4. Detection algorithm execution
 * 5. Alert generation
 *
 * This is a full integration test that uses a real in-memory database
 * and exercises all components together.
 */
@RunWith(AndroidJUnit4::class)
class DetectionFlowIntegrationTest {

    private lateinit var database: TailBaitDatabase
    private lateinit var context: Context

    // Repositories
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var whitelistRepository: WhitelistRepository
    private lateinit var settingsRepository: SettingsRepository

    // Services
    private lateinit var detectionAlgorithm: DetectionAlgorithm
    private lateinit var alertGenerator: AlertGenerator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            TailBaitDatabase::class.java
        ).allowMainThreadQueries().build()

        // Initialize repositories
        deviceRepository = DeviceRepositoryImpl(database.scannedDeviceDao())
        locationRepository = LocationRepositoryImpl(context, database.locationDao())
        alertRepository = AlertRepositoryImpl(database.alertHistoryDao())
        whitelistRepository = WhitelistRepositoryImpl(
            database.whitelistEntryDao(),
            database.scannedDeviceDao()
        )
        settingsRepository = SettingsRepositoryImpl(database.appSettingsDao())

        // Initialize services
        detectionAlgorithm = DetectionAlgorithm(
            deviceLocationRecordDao = database.deviceLocationRecordDao(),
            whitelistEntryDao = database.whitelistEntryDao(),
            scannedDeviceDao = database.scannedDeviceDao()
        )
        alertGenerator = AlertGenerator(
            alertRepository = alertRepository,
            settingsRepository = settingsRepository
        )

        // Initialize default settings
        runTest {
            database.appSettingsDao().insert(AppSettings())
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completeDetectionFlow_withSuspiciousDevice_generatesAlert() = runTest {
        // Step 1: Create locations
        val location1 = Location(
            latitude = 37.7749, longitude = -122.4194, accuracy = 10.0f,
            timestamp = 1000L, provider = "GPS"
        )
        val location2 = Location(
            latitude = 37.7850, longitude = -122.4294, accuracy = 10.0f,
            timestamp = 2000L, provider = "GPS"
        )
        val location3 = Location(
            latitude = 37.7950, longitude = -122.4394, accuracy = 10.0f,
            timestamp = 3000L, provider = "GPS"
        )

        val locationId1 = locationRepository.insertLocation(location1)
        val locationId2 = locationRepository.insertLocation(location2)
        val locationId3 = locationRepository.insertLocation(location3)

        // Step 2: Create a suspicious device (not whitelisted)
        val suspiciousDevice = ScannedDevice(
            address = "AA:BB:CC:DD:EE:FF", name = "Suspicious Device",
            firstSeen = 1000L, lastSeen = 3000L, detectionCount = 3
        )
        val deviceId = deviceRepository.insertDevice(suspiciousDevice)

        // Step 3: Create device-location correlation records
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId1, rssi = -70,
                timestamp = 1000L, scanTriggerType = "PERIODIC"
            )
        )
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId2, rssi = -75,
                timestamp = 2000L, scanTriggerType = "PERIODIC"
            )
        )
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId3, rssi = -72,
                timestamp = 3000L, scanTriggerType = "PERIODIC"
            )
        )

        // Step 4: Run detection algorithm
        val detections = detectionAlgorithm.runDetection(
            minLocationCount = 3,
            minThreatScore = 0.5
        )

        // Step 5: Verify detection found the suspicious device
        assertTrue("Should detect at least one suspicious device", detections.isNotEmpty())
        val detection = detections.find { it.deviceAddress == "AA:BB:CC:DD:EE:FF" }
        assertNotNull("Should detect the suspicious device", detection)

        // Step 6: Generate alert
        val alertIds = alertGenerator.generateAlerts(
            detectionResults = detections,
            throttleWindowMs = 60000L
        )

        // Step 7: Verify alert was created
        assertTrue("Should generate at least one alert", alertIds.isNotEmpty())
        val alerts = alertRepository.getAllAlerts().first()
        assertEquals("Should have exactly one alert", 1, alerts.size)

        val alert = alerts[0]
        assertEquals("Alert should be active", false, alert.isDismissed)
        assertTrue(
            "Alert should contain device address",
            alert.deviceAddresses.contains("AA:BB:CC:DD:EE:FF")
        )
    }

    @Test
    fun completeDetectionFlow_withWhitelistedDevice_doesNotGenerateAlert() = runTest {
        // Step 1: Create locations
        val location1 = Location(
            latitude = 37.7749, longitude = -122.4194, accuracy = 10.0f,
            timestamp = 1000L, provider = "GPS"
        )
        val location2 = Location(
            latitude = 37.7850, longitude = -122.4294, accuracy = 10.0f,
            timestamp = 2000L, provider = "GPS"
        )
        val location3 = Location(
            latitude = 37.7950, longitude = -122.4394, accuracy = 10.0f,
            timestamp = 3000L, provider = "GPS"
        )

        val locationId1 = locationRepository.insertLocation(location1)
        val locationId2 = locationRepository.insertLocation(location2)
        val locationId3 = locationRepository.insertLocation(location3)

        // Step 2: Create a device
        val trustedDevice = ScannedDevice(
            address = "11:22:33:44:55:66", name = "My Phone",
            firstSeen = 1000L, lastSeen = 3000L, detectionCount = 3
        )
        val deviceId = deviceRepository.insertDevice(trustedDevice)

        // Step 3: Add device to whitelist
        whitelistRepository.addToWhitelist(
            deviceId = deviceId,
            label = "My Phone",
            category = WhitelistRepository.Category.OWN,
            notes = "Personal device",
            addedViaLearnMode = false
        )

        // Step 4: Create device-location correlation records
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId1, rssi = -70,
                timestamp = 1000L, scanTriggerType = "PERIODIC"
            )
        )
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId2, rssi = -75,
                timestamp = 2000L, scanTriggerType = "PERIODIC"
            )
        )
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId3, rssi = -72,
                timestamp = 3000L, scanTriggerType = "PERIODIC"
            )
        )

        // Step 5: Run detection algorithm
        val detections = detectionAlgorithm.runDetection(
            minLocationCount = 3,
            minThreatScore = 0.5
        )

        // Step 6: Verify whitelisted device is not detected as suspicious
        val detection = detections.find { it.deviceAddress == "11:22:33:44:55:66" }
        assertNull("Whitelisted device should not be detected as suspicious", detection)

        // Step 7: Verify no alerts were generated
        val alertIds = alertGenerator.generateAlerts(
            detectionResults = detections,
            throttleWindowMs = 60000L
        )
        assertTrue("Should not generate alerts for whitelisted devices", alertIds.isEmpty())
    }

    @Test
    fun completeDetectionFlow_withInsufficientLocations_doesNotGenerateAlert() = runTest {
        // Step 1: Create only 2 locations (below threshold of 3)
        val location1 = Location(
            latitude = 37.7749, longitude = -122.4194, accuracy = 10.0f,
            timestamp = 1000L, provider = "GPS"
        )
        val location2 = Location(
            latitude = 37.7850, longitude = -122.4294, accuracy = 10.0f,
            timestamp = 2000L, provider = "GPS"
        )

        val locationId1 = locationRepository.insertLocation(location1)
        val locationId2 = locationRepository.insertLocation(location2)

        // Step 2: Create device
        val device = ScannedDevice(
            address = "FF:FF:FF:FF:FF:FF", name = "Test Device",
            firstSeen = 1000L, lastSeen = 2000L, detectionCount = 2
        )
        val deviceId = deviceRepository.insertDevice(device)

        // Step 3: Create only 2 correlation records
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId1, rssi = -70,
                timestamp = 1000L, scanTriggerType = "PERIODIC"
            )
        )
        database.deviceLocationRecordDao().insert(
            DeviceLocationRecord(
                deviceId = deviceId, locationId = locationId2, rssi = -75,
                timestamp = 2000L, scanTriggerType = "PERIODIC"
            )
        )

        // Step 4: Run detection algorithm with minLocationCount = 3
        val detections = detectionAlgorithm.runDetection(
            minLocationCount = 3,
            minThreatScore = 0.5
        )

        // Step 5: Verify device is not detected (insufficient locations)
        val detection = detections.find { it.deviceAddress == "FF:FF:FF:FF:FF:FF" }
        assertNull("Device with insufficient locations should not be detected", detection)

        // Step 6: Verify no alerts generated
        val alertIds = alertGenerator.generateAlerts(
            detectionResults = detections,
            throttleWindowMs = 60000L
        )
        assertTrue("Should not generate alerts for devices below threshold", alertIds.isEmpty())
    }

    @Test
    fun completeDetectionFlow_withMultipleSuspiciousDevices_generatesMultipleAlerts() = runTest {
        // Create 3 locations
        val location1 = Location(
            latitude = 37.7749, longitude = -122.4194,
            accuracy = 10.0f, timestamp = 1000L, provider = "GPS"
        )
        val location2 = Location(
            latitude = 37.7850, longitude = -122.4294,
            accuracy = 10.0f, timestamp = 2000L, provider = "GPS"
        )
        val location3 = Location(
            latitude = 37.7950, longitude = -122.4394,
            accuracy = 10.0f, timestamp = 3000L, provider = "GPS"
        )

        val locationId1 = locationRepository.insertLocation(location1)
        val locationId2 = locationRepository.insertLocation(location2)
        val locationId3 = locationRepository.insertLocation(location3)

        // Create 2 suspicious devices
        val device1 = ScannedDevice(
            address = "AA:AA:AA:AA:AA:AA", name = "Device 1",
            firstSeen = 1000L, lastSeen = 3000L, detectionCount = 3
        )
        val device2 = ScannedDevice(
            address = "BB:BB:BB:BB:BB:BB", name = "Device 2",
            firstSeen = 1000L, lastSeen = 3000L, detectionCount = 3
        )

        val deviceId1 = deviceRepository.insertDevice(device1)
        val deviceId2 = deviceRepository.insertDevice(device2)

        // Create correlation records for both devices
        for (deviceId in listOf(deviceId1, deviceId2)) {
            database.deviceLocationRecordDao().insert(
                DeviceLocationRecord(
                    deviceId = deviceId, locationId = locationId1, rssi = -70,
                    timestamp = 1000L, scanTriggerType = "PERIODIC"
                )
            )
            database.deviceLocationRecordDao().insert(
                DeviceLocationRecord(
                    deviceId = deviceId, locationId = locationId2, rssi = -75,
                    timestamp = 2000L, scanTriggerType = "PERIODIC"
                )
            )
            database.deviceLocationRecordDao().insert(
                DeviceLocationRecord(
                    deviceId = deviceId, locationId = locationId3, rssi = -72,
                    timestamp = 3000L, scanTriggerType = "PERIODIC"
                )
            )
        }

        // Run detection
        val detections = detectionAlgorithm.runDetection(minLocationCount = 3, minThreatScore = 0.5)

        // Verify multiple detections
        assertEquals("Should detect 2 suspicious devices", 2, detections.size)

        // Generate alerts
        val alertIds = alertGenerator.generateAlerts(
            detectionResults = detections,
            throttleWindowMs = 60000L
        )

        // Verify multiple alerts created
        assertEquals("Should generate 2 alerts", 2, alertIds.size)
    }
}
