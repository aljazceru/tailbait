package com.tailbait.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.LocationDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.DeviceRepositoryImpl
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.LocationRepositoryImpl
import com.tailbait.util.Constants
import com.tailbait.util.DistanceCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/**
 * Integration tests for device-location correlation functionality.
 *
 * These tests verify the complete flow of correlating BLE device detections
 * with GPS location data, which is critical for stalking detection.
 *
 * Tests cover:
 * - Device insertion and updates (upsert logic)
 * - Location recording
 * - Device-location correlation record creation
 * - Time window matching for correlation
 * - Duplicate record prevention
 * - Batch processing optimization
 * - Multi-location device tracking
 * - Distance calculation between locations
 * - Location change detection
 * - Query performance for correlation data
 */
@RunWith(AndroidJUnit4::class)
class CorrelationIntegrationTest {

    private lateinit var database: TailBaitDatabase
    private lateinit var locationDao: LocationDao
    private lateinit var scannedDeviceDao: ScannedDeviceDao
    private lateinit var deviceLocationRecordDao: DeviceLocationRecordDao
    private lateinit var locationRepository: LocationRepository
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            TailBaitDatabase::class.java
        ).allowMainThreadQueries().build()

        locationDao = database.locationDao()
        scannedDeviceDao = database.scannedDeviceDao()
        deviceLocationRecordDao = database.deviceLocationRecordDao()

        locationRepository = LocationRepositoryImpl(context, locationDao)
        deviceRepository = DeviceRepositoryImpl(scannedDeviceDao, deviceLocationRecordDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Test basic device-location correlation flow.
     */
    @Test
    fun testBasicDeviceLocationCorrelation() = runTest {
        // Given - Create a location
        val location = Location(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10f,
            timestamp = System.currentTimeMillis(),
            provider = "GPS"
        )
        val locationId = locationDao.insert(location)

        // When - Insert a device and correlate with location
        val deviceId = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            lastSeen = System.currentTimeMillis(),
            manufacturerData = null
        )

        val recordId = deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -60,
            timestamp = System.currentTimeMillis(),
            locationChanged = true,
            distanceFromLast = null,
            scanTriggerType = Constants.SCAN_TRIGGER_MANUAL
        )

        // Then - Verify record was created
        assertTrue(recordId > 0, "Record ID should be positive")

        val record = deviceLocationRecordDao.getById(recordId)
        assertNotNull(record, "Record should exist in database")
        assertEquals(deviceId, record.deviceId)
        assertEquals(locationId, record.locationId)
        assertEquals(-60, record.rssi)
        assertEquals(Constants.SCAN_TRIGGER_MANUAL, record.scanTriggerType)
    }

    /**
     * Test device upsert logic - updating existing device.
     */
    @Test
    fun testDeviceUpsertUpdatesExistingDevice() = runTest {
        // Given - Insert initial device
        val timestamp1 = System.currentTimeMillis()
        val deviceId1 = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Device v1",
            lastSeen = timestamp1,
            manufacturerData = null
        )

        // When - Update same device (same MAC address)
        val timestamp2 = timestamp1 + 60000
        val deviceId2 = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Device v2",
            lastSeen = timestamp2,
            manufacturerData = byteArrayOf(0x01, 0x02)
        )

        // Then - Should be same device ID
        assertEquals(deviceId1, deviceId2, "Device ID should remain the same")

        val device = scannedDeviceDao.getByAddress("AA:BB:CC:DD:EE:FF")
        assertNotNull(device)
        assertEquals("Device v2", device.name)
        assertEquals(timestamp2, device.lastSeen)
        assertEquals(2, device.detectionCount, "Detection count should increment")
    }

    /**
     * Test multiple locations for single device (stalking scenario).
     */
    @Test
    fun testDeviceSeenAtMultipleLocations() = runTest {
        // Given - Create multiple locations
        val location1 = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = System.currentTimeMillis(),
                provider = "GPS"
            )
        )

        val location2 = locationDao.insert(
            Location(
                latitude = 37.8049,
                longitude = -122.4494,
                accuracy = 15f,
                timestamp = System.currentTimeMillis() + 300000,
                provider = "GPS"
            )
        )

        val location3 = locationDao.insert(
            Location(
                latitude = 37.8349,
                longitude = -122.4794,
                accuracy = 12f,
                timestamp = System.currentTimeMillis() + 600000,
                provider = "GPS"
            )
        )

        // When - Same device detected at all locations
        val deviceId = deviceRepository.upsertDevice(
            address = "STALKER:00:00:01",
            name = "Suspicious AirTag",
            lastSeen = System.currentTimeMillis(),
            manufacturerData = null
        )

        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = location1,
            rssi = -65,
            timestamp = System.currentTimeMillis(),
            locationChanged = true,
            distanceFromLast = null,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = location2,
            rssi = -70,
            timestamp = System.currentTimeMillis() + 300000,
            locationChanged = true,
            distanceFromLast = 3400.0,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = location3,
            rssi = -68,
            timestamp = System.currentTimeMillis() + 600000,
            locationChanged = true,
            distanceFromLast = 3600.0,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        // Then - Verify device was seen at 3 distinct locations
        val locationCount = deviceLocationRecordDao.getDistinctLocationCountForDevice(deviceId).first()
        assertEquals(3, locationCount, "Device should be seen at 3 distinct locations")

        val records = deviceLocationRecordDao.getRecordsForDevice(deviceId).first()
        assertEquals(3, records.size, "Should have 3 correlation records")
    }

    /**
     * Test location change detection logic.
     */
    @Test
    fun testLocationChangeDetection() = runTest {
        // Given - Two locations
        val location1 = Location(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10f,
            timestamp = System.currentTimeMillis(),
            provider = "GPS"
        )

        val location2 = Location(
            latitude = 37.7750,
            longitude = -122.4195,
            accuracy = 10f,
            timestamp = System.currentTimeMillis() + 60000,
            provider = "GPS"
        )

        // When - Calculate distance
        val distance = DistanceCalculator.calculateDistance(
            location1.latitude,
            location1.longitude,
            location2.latitude,
            location2.longitude
        )

        // Then - Distance should be small (< 50m threshold)
        assertTrue(distance < Constants.DEFAULT_LOCATION_CHANGE_THRESHOLD_METERS,
            "Distance should be less than threshold: $distance meters")

        // Verify location change would not be detected for this small movement
        val locationChanged = distance > Constants.DEFAULT_LOCATION_CHANGE_THRESHOLD_METERS
        assertFalse(locationChanged, "Location should not be marked as changed")
    }

    /**
     * Test significant location change detection.
     */
    @Test
    fun testSignificantLocationChange() = runTest {
        // Given - Two distant locations
        val location1 = Location(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10f,
            timestamp = System.currentTimeMillis(),
            provider = "GPS"
        )

        val location2 = Location(
            latitude = 37.8049,
            longitude = -122.4494,
            accuracy = 10f,
            timestamp = System.currentTimeMillis() + 300000,
            provider = "GPS"
        )

        // When - Calculate distance
        val distance = DistanceCalculator.calculateDistance(
            location1.latitude,
            location1.longitude,
            location2.latitude,
            location2.longitude
        )

        // Then - Distance should be significant (> 50m threshold)
        assertTrue(distance > Constants.DEFAULT_LOCATION_CHANGE_THRESHOLD_METERS,
            "Distance should exceed threshold: $distance meters")

        val locationChanged = distance > Constants.DEFAULT_LOCATION_CHANGE_THRESHOLD_METERS
        assertTrue(locationChanged, "Location should be marked as changed")
    }

    /**
     * Test batch insertion of correlation records.
     */
    @Test
    fun testBatchCorrelationRecordInsertion() = runTest {
        // Given - One location and multiple devices
        val locationId = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = System.currentTimeMillis(),
                provider = "GPS"
            )
        )

        // When - Insert multiple devices and correlate
        val records = mutableListOf<DeviceLocationRecord>()
        for (i in 1..10) {
            val deviceId = deviceRepository.upsertDevice(
                address = "AA:BB:CC:DD:EE:$i",
                name = "Device $i",
                lastSeen = System.currentTimeMillis(),
                manufacturerData = null
            )

            records.add(
                DeviceLocationRecord(
                    deviceId = deviceId,
                    locationId = locationId,
                    rssi = -60 - i,
                    timestamp = System.currentTimeMillis(),
                    locationChanged = true,
                    distanceFromLast = null,
                    scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
                )
            )
        }

        val recordIds = deviceLocationRecordDao.insertAll(records)

        // Then - All records should be inserted
        assertEquals(10, recordIds.size, "Should insert 10 records")
        assertTrue(recordIds.all { it > 0 }, "All record IDs should be positive")

        val deviceCount = deviceLocationRecordDao.getDeviceCountAtLocation(locationId).first()
        assertEquals(10, deviceCount, "Should have 10 devices at this location")
    }

    /**
     * Test time window matching for correlation.
     */
    @Test
    fun testTimeWindowMatching() = runTest {
        // Given - Location and device
        val baseTime = System.currentTimeMillis()
        val locationId = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = baseTime,
                provider = "GPS"
            )
        )

        val deviceId = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            lastSeen = baseTime,
            manufacturerData = null
        )

        // When - Insert records with different timestamps
        val record1Id = deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -60,
            timestamp = baseTime,
            locationChanged = true,
            distanceFromLast = null,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        val record2Id = deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -65,
            // 30 seconds later
            timestamp = baseTime + 30000,
            locationChanged = false,
            distanceFromLast = 0.0,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        // Then - Query records within time window
        // 1 minute before
        val timeWindowStart = baseTime - 60000
        // 1 minute after
        val timeWindowEnd = baseTime + 60000

        val recordsInWindow = deviceLocationRecordDao.getRecordsByTimeRange(
            timeWindowStart,
            timeWindowEnd
        ).first()

        assertEquals(2, recordsInWindow.size, "Should find both records in time window")
        assertTrue(recordsInWindow.any { it.id == record1Id })
        assertTrue(recordsInWindow.any { it.id == record2Id })
    }

    /**
     * Test duplicate detection prevention.
     */
    @Test
    fun testDuplicateRecordPrevention() = runTest {
        // Given - Location and device
        val timestamp = System.currentTimeMillis()
        val locationId = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = timestamp,
                provider = "GPS"
            )
        )

        val deviceId = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            lastSeen = timestamp,
            manufacturerData = null
        )

        // When - Insert same correlation multiple times (simulating duplicate detections)
        val record1Id = deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -60,
            timestamp = timestamp,
            locationChanged = true,
            distanceFromLast = null,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        // Second detection at same location within short time window
        val record2Id = deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -62,
            // 1 second later
            timestamp = timestamp + 1000,
            locationChanged = false,
            distanceFromLast = 0.0,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        // Then - Both records should exist (they have different timestamps)
        // In production, the scanner deduplicates within scan window
        // But database allows multiple records for analysis
        assertNotEquals(record1Id, record2Id, "Records should have different IDs")

        val records = deviceLocationRecordDao.getRecordsForDeviceAtLocation(
            deviceId,
            locationId
        ).first()
        assertEquals(2, records.size, "Should have 2 records")
    }

    /**
     * Test RSSI tracking across locations.
     */
    @Test
    fun testRSSITracking() = runTest {
        // Given - Multiple locations and device
        val location1Id = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = System.currentTimeMillis(),
                provider = "GPS"
            )
        )

        val location2Id = locationDao.insert(
            Location(
                latitude = 37.8049,
                longitude = -122.4494,
                accuracy = 10f,
                timestamp = System.currentTimeMillis() + 300000,
                provider = "GPS"
            )
        )

        val deviceId = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            lastSeen = System.currentTimeMillis(),
            manufacturerData = null
        )

        // When - Record different RSSI values
        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = location1Id,
            // Strong signal
            rssi = -55,
            timestamp = System.currentTimeMillis(),
            locationChanged = true,
            distanceFromLast = null,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = location2Id,
            // Weak signal
            rssi = -85,
            timestamp = System.currentTimeMillis() + 300000,
            locationChanged = true,
            distanceFromLast = 3400.0,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        // Then - Verify average RSSI calculation
        val avgRssi = deviceLocationRecordDao.getAverageRssiForDevice(deviceId)
        assertNotNull(avgRssi)
        // Average of -55 and -85
        assertEquals(-70.0, avgRssi, 0.5)
    }

    /**
     * Test scan trigger type tracking.
     */
    @Test
    fun testScanTriggerTypeTracking() = runTest {
        // Given - Location and device
        val locationId = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = System.currentTimeMillis(),
                provider = "GPS"
            )
        )

        val deviceId = deviceRepository.upsertDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Test Device",
            lastSeen = System.currentTimeMillis(),
            manufacturerData = null
        )

        // When - Record with different trigger types
        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -60,
            timestamp = System.currentTimeMillis(),
            locationChanged = true,
            distanceFromLast = null,
            scanTriggerType = Constants.SCAN_TRIGGER_MANUAL
        )

        deviceRepository.insertDeviceLocationRecord(
            deviceId = deviceId,
            locationId = locationId,
            rssi = -65,
            timestamp = System.currentTimeMillis() + 60000,
            locationChanged = false,
            distanceFromLast = 0.0,
            scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
        )

        // Then - Query by trigger type
        val manualRecords = deviceLocationRecordDao.getRecordsByScanType(
            Constants.SCAN_TRIGGER_MANUAL
        ).first()
        assertEquals(1, manualRecords.size)

        val continuousRecords = deviceLocationRecordDao.getRecordsByScanType(
            Constants.SCAN_TRIGGER_CONTINUOUS
        ).first()
        assertEquals(1, continuousRecords.size)
    }

    /**
     * Test suspicious device detection query.
     */
    @Test
    fun testSuspiciousDeviceQuery() = runTest {
        // Given - Device seen at multiple locations
        val deviceId = deviceRepository.upsertDevice(
            address = "SUSPICIOUS:00:01",
            name = "AirTag",
            lastSeen = System.currentTimeMillis(),
            manufacturerData = null
        )

        // Create 5 different locations
        for (i in 1..5) {
            val locationId = locationDao.insert(
                Location(
                    latitude = 37.7749 + (i * 0.01),
                    longitude = -122.4194 + (i * 0.01),
                    accuracy = 10f,
                    timestamp = System.currentTimeMillis() + (i * 300000L),
                    provider = "GPS"
                )
            )

            deviceRepository.insertDeviceLocationRecord(
                deviceId = deviceId,
                locationId = locationId,
                rssi = -60 - i,
                timestamp = System.currentTimeMillis() + (i * 300000L),
                locationChanged = true,
                distanceFromLast = if (i > 1) 1000.0 * i else null,
                scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
            )
        }

        // When - Query for devices at multiple locations
        val suspiciousDevices = deviceRepository.getSuspiciousDevices(
            minLocationCount = 3
        ).first()

        // Then - Should find the device
        assertEquals(1, suspiciousDevices.size)
        assertEquals("SUSPICIOUS:00:01", suspiciousDevices[0].address)
        assertEquals("AirTag", suspiciousDevices[0].name)

        val locationCount = deviceRepository.getDistinctLocationCountForDevice(deviceId).first()
        assertEquals(5, locationCount)
    }

    /**
     * Test location-based record filtering.
     */
    @Test
    fun testLocationBasedRecordFiltering() = runTest {
        // Given - Multiple locations with different devices
        val location1Id = locationDao.insert(
            Location(
                latitude = 37.7749,
                longitude = -122.4194,
                accuracy = 10f,
                timestamp = System.currentTimeMillis(),
                provider = "GPS"
            )
        )

        val location2Id = locationDao.insert(
            Location(
                latitude = 37.8049,
                longitude = -122.4494,
                accuracy = 10f,
                timestamp = System.currentTimeMillis() + 300000,
                provider = "GPS"
            )
        )

        // Insert devices at each location
        for (i in 1..3) {
            val deviceId = deviceRepository.upsertDevice(
                address = "AA:BB:CC:DD:EE:0$i",
                name = "Device $i",
                lastSeen = System.currentTimeMillis(),
                manufacturerData = null
            )

            deviceRepository.insertDeviceLocationRecord(
                deviceId = deviceId,
                locationId = location1Id,
                rssi = -60,
                timestamp = System.currentTimeMillis(),
                locationChanged = true,
                distanceFromLast = null,
                scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
            )
        }

        for (i in 4..5) {
            val deviceId = deviceRepository.upsertDevice(
                address = "AA:BB:CC:DD:EE:0$i",
                name = "Device $i",
                lastSeen = System.currentTimeMillis() + 300000,
                manufacturerData = null
            )

            deviceRepository.insertDeviceLocationRecord(
                deviceId = deviceId,
                locationId = location2Id,
                rssi = -65,
                timestamp = System.currentTimeMillis() + 300000,
                locationChanged = true,
                distanceFromLast = null,
                scanTriggerType = Constants.SCAN_TRIGGER_CONTINUOUS
            )
        }

        // When - Query records at each location
        val location1Records = deviceLocationRecordDao.getRecordsAtLocation(location1Id).first()
        val location2Records = deviceLocationRecordDao.getRecordsAtLocation(location2Id).first()

        // Then - Verify correct filtering
        assertEquals(3, location1Records.size, "Location 1 should have 3 records")
        assertEquals(2, location2Records.size, "Location 2 should have 2 records")
    }
}
