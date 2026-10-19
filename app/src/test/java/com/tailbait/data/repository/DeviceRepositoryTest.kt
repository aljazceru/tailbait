package com.tailbait.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.room.withTransaction
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for DeviceRepository and DeviceRepositoryImpl.
 *
 * Tests device data management, upsert logic, and device-location correlation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // Mock database and DAOs
    private lateinit var database: TailBaitDatabase
    private lateinit var scannedDeviceDao: ScannedDeviceDao
    private lateinit var deviceLocationRecordDao: DeviceLocationRecordDao

    private lateinit var repository: DeviceRepository

    // Test data
    private val testAddress = "AA:BB:CC:DD:EE:01"
    private val testDevice =
        ScannedDevice(
            id = 1L,
            address = testAddress,
            name = "Test Device",
            firstSeen = 1000L,
            lastSeen = 2000L,
            detectionCount = 1,
            manufacturerData = "0102030405",
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Initialize mocks
        database = mockk(relaxed = true)
        scannedDeviceDao = mockk(relaxed = true)
        deviceLocationRecordDao = mockk(relaxed = true)

        // Mock withTransaction to just execute the block directly
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Any?>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = args[1] as (suspend () -> Any?)
            block()
        }

        // Create repository
        repository = DeviceRepositoryImpl(database, scannedDeviceDao, deviceLocationRecordDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // ==================== Upsert Device Tests ====================

    @Test
    fun `upsertDevice inserts new device when not exists`() =
        runTest {
            // Setup mock - device doesn't exist
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
            coEvery { scannedDeviceDao.insert(any()) } returns 1L

            // Execute
            val result =
                repository.upsertDevice(
                    address = testAddress,
                    name = "New Device",
                    lastSeen = 1000L,
                    manufacturerData = null,
                )

            // Verify
            assertEquals(1L, result)
            coVerify {
                scannedDeviceDao.insert(
                    match {
                        it.address == testAddress &&
                            it.name == "New Device" &&
                            it.firstSeen == 1000L &&
                            it.lastSeen == 1000L &&
                            it.detectionCount == 1
                    },
                )
            }
        }

    @Test
    fun `upsertDevice updates existing device when exists`() =
        runTest {
            // Setup mock - device exists
            val existingDevice = testDevice.copy(detectionCount = 5)
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns existingDevice
            coEvery { scannedDeviceDao.update(any()) } just Runs

            // Execute - update with new lastSeen and increment count
            val result =
                repository.upsertDevice(
                    address = testAddress,
                    name = "Updated Device",
                    lastSeen = 3000L,
                    manufacturerData = null,
                )

            // Verify
            assertEquals(existingDevice.id, result)
            coVerify {
                scannedDeviceDao.update(
                    match {
                        it.id == existingDevice.id &&
                            it.address == testAddress &&
                            it.lastSeen == 3000L &&
                            it.detectionCount == 6 // Incremented from 5 to 6
                    },
                )
            }
        }

    @Test
    fun `upsertDevice preserves original name when new name is null`() =
        runTest {
            // Setup mock - device exists with a name
            val existingDevice = testDevice.copy(name = "Original Name")
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns existingDevice
            coEvery { scannedDeviceDao.update(any()) } just Runs

            // Execute - update without providing new name
            repository.upsertDevice(
                address = testAddress,
                name = null,
                lastSeen = 3000L,
                manufacturerData = null,
            )

            // Verify original name is preserved
            coVerify {
                scannedDeviceDao.update(
                    match { it.name == "Original Name" },
                )
            }
        }

    @Test
    fun `upsertDevice replaces name when new name is provided`() =
        runTest {
            // Setup mock - device exists
            val existingDevice = testDevice.copy(name = "Old Name")
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns existingDevice
            coEvery { scannedDeviceDao.update(any()) } just Runs

            // Execute - update with new name
            repository.upsertDevice(
                address = testAddress,
                name = "New Name",
                lastSeen = 3000L,
                manufacturerData = null,
            )

            // Verify name is replaced
            coVerify {
                scannedDeviceDao.update(
                    match { it.name == "New Name" },
                )
            }
        }

    @Test
    fun `upsertDevice converts manufacturer data to hex string`() =
        runTest {
            // Setup mock - device doesn't exist
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
            coEvery { scannedDeviceDao.insert(any()) } returns 1L

            // Execute with byte array manufacturer data
            val manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
            repository.upsertDevice(
                address = testAddress,
                name = "Test",
                lastSeen = 1000L,
                manufacturerData = manufacturerData,
            )

            // Verify manufacturer data is converted to hex string
            coVerify {
                scannedDeviceDao.insert(
                    match { it.manufacturerData == "010203ff" },
                )
            }
        }

    // ==================== Insert Device Location Record Tests ====================

    @Test
    fun `insertDeviceLocationRecord creates record successfully`() =
        runTest {
            // Setup mock
            coEvery { deviceLocationRecordDao.insert(any()) } returns 1L

            // Execute
            val result =
                repository.insertDeviceLocationRecord(
                    deviceId = 1L,
                    locationId = 2L,
                    rssi = -65,
                    timestamp = 1000L,
                    locationChanged = true,
                    distanceFromLast = 150.0,
                    scanTriggerType = "PERIODIC",
                )

            // Verify
            assertEquals(1L, result)
            coVerify {
                deviceLocationRecordDao.insert(
                    match {
                        it.deviceId == 1L &&
                            it.locationId == 2L &&
                            it.rssi == -65 &&
                            it.timestamp == 1000L &&
                            it.locationChanged &&
                            it.distanceFromLast == 150.0 &&
                            it.scanTriggerType == "PERIODIC"
                    },
                )
            }
        }

    @Test
    fun `insertDeviceLocationRecord handles null distanceFromLast`() =
        runTest {
            // Setup mock
            coEvery { deviceLocationRecordDao.insert(any()) } returns 1L

            // Execute with null distance
            repository.insertDeviceLocationRecord(
                deviceId = 1L,
                locationId = 2L,
                rssi = -70,
                timestamp = 1000L,
                locationChanged = false,
                distanceFromLast = null,
                scanTriggerType = "MANUAL",
            )

            // Verify
            coVerify {
                deviceLocationRecordDao.insert(
                    match {
                        it.distanceFromLast == null &&
                            !it.locationChanged
                    },
                )
            }
        }

    // ==================== Get Device Tests ====================

    @Test
    fun `getDeviceByAddress returns device when exists`() =
        runTest {
            // Setup mock
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns testDevice

            // Execute
            val result = repository.getDeviceByAddress(testAddress)

            // Verify
            assertNotNull(result)
            assertEquals(testDevice.address, result!!.address)
            assertEquals(testDevice.name, result.name)
        }

    @Test
    fun `getDeviceByAddress returns null when not exists`() =
        runTest {
            // Setup mock
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null

            // Execute
            val result = repository.getDeviceByAddress(testAddress)

            // Verify
            assertNull(result)
        }

    // ==================== Get All Devices Tests ====================

    @Test
    fun `getAllDevices returns all devices`() =
        runTest {
            // Setup mock
            val devices =
                listOf(
                    testDevice,
                    testDevice.copy(id = 2L, address = "AA:BB:CC:DD:EE:02", name = "Device 2"),
                )
            every { scannedDeviceDao.getAllDevices() } returns flowOf(devices)

            // Execute
            val result = repository.getAllDevices().first()

            // Verify
            assertEquals(2, result.size)
            assertEquals("Test Device", result[0].name)
            assertEquals("Device 2", result[1].name)
        }

    @Test
    fun `getAllDevices returns empty list when no devices`() =
        runTest {
            // Setup mock
            every { scannedDeviceDao.getAllDevices() } returns flowOf(emptyList())

            // Execute
            val result = repository.getAllDevices().first()

            // Verify
            assertEquals(0, result.size)
        }

    // ==================== Get Suspicious Devices Tests ====================

    @Test
    fun `getSuspiciousDevices filters by location count`() =
        runTest {
            // Setup mock
            val suspiciousDevices =
                listOf(
                    testDevice.copy(id = 1L, name = "Suspicious Device 1"),
                    testDevice.copy(id = 2L, name = "Suspicious Device 2"),
                )
            every { scannedDeviceDao.getSuspiciousDevices(3) } returns flowOf(suspiciousDevices)

            // Execute
            val result = repository.getSuspiciousDevices(3).first()

            // Verify
            assertEquals(2, result.size)
            coVerify { scannedDeviceDao.getSuspiciousDevices(3) }
        }

    @Test
    fun `getSuspiciousDevices returns empty list when none found`() =
        runTest {
            // Setup mock
            every { scannedDeviceDao.getSuspiciousDevices(5) } returns flowOf(emptyList())

            // Execute
            val result = repository.getSuspiciousDevices(5).first()

            // Verify
            assertEquals(0, result.size)
        }

    // ==================== Get Distinct Location Count Tests ====================

    @Test
    fun `getDistinctLocationCountForDevice returns correct count`() =
        runTest {
            // Setup mock
            every { deviceLocationRecordDao.getDistinctLocationCountForDevice(1L) } returns flowOf(5)

            // Execute
            val result = repository.getDistinctLocationCountForDevice(1L).first()

            // Verify
            assertEquals(5, result)
        }

    @Test
    fun `getDistinctLocationCountForDevice returns zero when no locations`() =
        runTest {
            // Setup mock
            every { deviceLocationRecordDao.getDistinctLocationCountForDevice(99L) } returns flowOf(0)

            // Execute
            val result = repository.getDistinctLocationCountForDevice(99L).first()

            // Verify
            assertEquals(0, result)
        }

    // ==================== Delete Old Devices Tests ====================

    @Test
    fun `deleteOldDevices removes devices before timestamp`() =
        runTest {
            // Setup mock
            coEvery { scannedDeviceDao.deleteOldDevices(5000L) } returns 3

            // Execute
            val result = repository.deleteOldDevices(5000L)

            // Verify
            assertEquals(3, result)
            coVerify { scannedDeviceDao.deleteOldDevices(5000L) }
        }

    @Test
    fun `deleteOldDevices returns zero when no devices to delete`() =
        runTest {
            // Setup mock
            coEvery { scannedDeviceDao.deleteOldDevices(1000L) } returns 0

            // Execute
            val result = repository.deleteOldDevices(1000L)

            // Verify
            assertEquals(0, result)
        }

    // ==================== Delete All Devices Tests ====================

    @Test
    fun `deleteAllDevices removes all devices and location records`() =
        runTest {
            // Setup mocks
            coEvery { scannedDeviceDao.deleteAll() } just Runs
            coEvery { deviceLocationRecordDao.deleteAll() } just Runs

            // Execute
            repository.deleteAllDevices()

            // Verify both DAOs were called
            coVerify { scannedDeviceDao.deleteAll() }
            coVerify { deviceLocationRecordDao.deleteAll() }
        }

    // ==================== Edge Case Tests ====================

    @Test
    fun `upsertDevice handles empty address`() =
        runTest {
            // Setup mock
            coEvery { scannedDeviceDao.getByAddress("") } returns null
            coEvery { scannedDeviceDao.insert(any()) } returns 1L

            // Execute
            val result =
                repository.upsertDevice(
                    address = "",
                    name = "Test",
                    lastSeen = 1000L,
                    manufacturerData = null,
                )

            // Verify
            assertEquals(1L, result)
            coVerify { scannedDeviceDao.insert(match { it.address == "" }) }
        }

    @Test
    fun `upsertDevice handles very long device name`() =
        runTest {
            // Setup mock
            val longName = "A".repeat(1000)
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
            coEvery { scannedDeviceDao.insert(any()) } returns 1L

            // Execute
            repository.upsertDevice(
                address = testAddress,
                name = longName,
                lastSeen = 1000L,
                manufacturerData = null,
            )

            // Verify
            coVerify { scannedDeviceDao.insert(match { it.name == longName }) }
        }

    @Test
    fun `insertDeviceLocationRecord handles negative RSSI values`() =
        runTest {
            // Setup mock
            coEvery { deviceLocationRecordDao.insert(any()) } returns 1L

            // Execute with very weak signal
            repository.insertDeviceLocationRecord(
                deviceId = 1L,
                locationId = 2L,
                // Very weak signal
                rssi = -120,
                timestamp = 1000L,
                locationChanged = true,
                distanceFromLast = 50.0,
                scanTriggerType = "CONTINUOUS",
            )

            // Verify
            coVerify {
                deviceLocationRecordDao.insert(
                    match { it.rssi == -120 },
                )
            }
        }

    @Test
    fun `upsertDevice handles empty manufacturer data byte array`() =
        runTest {
            // Setup mock
            coEvery { scannedDeviceDao.getByAddress(testAddress) } returns null
            coEvery { scannedDeviceDao.insert(any()) } returns 1L

            // Execute with empty byte array
            repository.upsertDevice(
                address = testAddress,
                name = "Test",
                lastSeen = 1000L,
                // Empty array
                manufacturerData = byteArrayOf(),
            )

            // Verify manufacturer data is empty string
            coVerify {
                scannedDeviceDao.insert(
                    match { it.manufacturerData == "" },
                )
            }
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceRepositoryIdentityTest {
    private lateinit var database: TailBaitDatabase
    private lateinit var repository: DeviceRepository
    private val fullIdentities = listOf("FM:" + "AB".repeat(22), "FHN:" + "CD".repeat(20), "FHN:" + "EF".repeat(32))

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TailBaitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = DeviceRepositoryImpl(database, database.scannedDeviceDao(), database.deviceLocationRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `co-present devices with identical names class and RSSI remain distinct`() =
        runTest {
            val firstId = observe(address(1), 1_000)
            val secondId = observe(address(2), 1_000)
            val secondAgain = observe(address(2), 2_000)

            assertNotEquals(firstId, secondId)
            assertEquals(secondId, secondAgain)
            val first = repository.getDeviceById(firstId)!!
            val second = repository.getDeviceById(secondId)!!
            assertNull(first.linkedDeviceId)
            assertNull(second.linkedDeviceId)
            assertEquals(first.shadowKey, second.shadowKey)
            assertEquals(1_000L, first.firstSeen)
            assertEquals(1_000L, first.lastSeen)
            assertEquals(1, first.detectionCount)
            assertEquals(2, second.detectionCount)
        }

    @Test
    fun `full identities return child IDs consistently and link through a fresh child to the canonical root`() =
        runTest {
            for ((index, fingerprint) in fullIdentities.withIndex()) {
                val rootId = observe(address(index * 3 + 10), 1_000_000, fingerprint)
                val rootBefore = repository.getDeviceById(rootId)!!
                val childAddress = address(index * 3 + 11)
                val childId = observe(childAddress, 1_900_000, fingerprint)
                record(childId, 1_900_000)
                val secondChildId = observe(childAddress, 1_960_000, fingerprint)
                record(secondChildId, 1_960_000)
                val thirdId = observe(address(index * 3 + 12), 2_800_000, fingerprint)

                assertNotEquals(rootId, childId)
                assertEquals(childId, secondChildId)
                assertNotEquals(childId, thirdId)
                assertEquals(rootBefore, repository.getDeviceById(rootId))
                val child = repository.getDeviceById(childId)!!
                assertEquals(rootId, child.linkedDeviceId)
                assertEquals("STRONG", child.linkStrength)
                assertEquals("fingerprint_match:$fingerprint", child.linkReason)
                assertEquals(1_900_000L, child.firstSeen)
                assertEquals(1_960_000L, child.lastSeen)
                assertEquals(rootId, repository.getDeviceById(thirdId)!!.linkedDeviceId)
                assertEquals(
                    listOf(1_900_000L, 1_960_000L),
                    repository.getDeviceLocationRecordsForDevice(childId).map { it.timestamp }.sorted(),
                )
                assertEquals(2, repository.getDeviceLocationRecordsForDeviceWithLinked(rootId).size)
                assertEquals(1, repository.getDeviceLocationRecordsForDeviceWithLinked(childId, 1_950_000).size)
            }
        }

    @Test
    fun `stale full identities in either packet order never create weak links`() =
        runTest {
            for ((index, fingerprint) in fullIdentities.withIndex()) {
                val rootId = observe(address(index * 3 + 30), 5_000_000, fingerprint)
                val newerId = observe(address(index * 3 + 31), 6_200_001, fingerprint)
                val olderId = observe(address(index * 3 + 32), 3_799_999, fingerprint)

                for (id in listOf(newerId, olderId)) {
                    assertNotEquals(rootId, id)
                    val device = repository.getDeviceById(id)!!
                    assertNull(device.linkedDeviceId)
                    assertNull(device.linkStrength)
                    assertNull(device.linkReason)
                    assertEquals(fingerprint, device.payloadFingerprint)
                }
                assertEquals(5_000_000L, repository.getDeviceById(rootId)!!.lastSeen)
            }
        }

    @Test
    fun `class composite short lowercase and malformed keys cannot establish identity`() =
        runTest {
            val invalidKeys =
                listOf(
                    "PP:0E200050AA",
                    "COMP:123456ABCDEF",
                    "ST:FD5A",
                    "GF:FEAA",
                    "FM:AABBCCDD1122",
                    "FM:" + "AB".repeat(21),
                    "FM:" + "AB".repeat(23),
                    "FM:" + "ab".repeat(22),
                    "FM:" + "AB".repeat(21) + "G0",
                    "FHN:" + "CD".repeat(8),
                    "FHN:" + "CD".repeat(21),
                    "FHN:" + "EF".repeat(32) + "00",
                )
            for ((index, fingerprint) in invalidKeys.withIndex()) {
                val legacyId =
                    database.scannedDeviceDao().insert(
                        ScannedDevice(
                            address = address(index * 2 + 50),
                            name = "Shared name",
                            firstSeen = 1_000,
                            lastSeen = 1_000,
                            payloadFingerprint = fingerprint,
                        ),
                    )
                val observedId = observe(address(index * 2 + 51), 2_000, fingerprint)
                val observed = repository.getDeviceById(observedId)!!
                assertNotEquals(legacyId, observedId)
                assertNull(fingerprint, observed.payloadFingerprint)
                assertNull(fingerprint, observed.linkedDeviceId)
                assertNull(fingerprint, observed.linkStrength)
            }
        }

    @Test
    fun `weak or orphan fingerprint matches do not attach a new observation to a false root`() =
        runTest {
            for ((index, strength) in listOf("WEAK", "STRONG").withIndex()) {
                database.scannedDeviceDao().insert(
                    ScannedDevice(
                        address = address(90 + index * 2),
                        name = null,
                        firstSeen = 1_000,
                        lastSeen = 1_000,
                        payloadFingerprint = fullIdentities[index],
                        linkedDeviceId = 999,
                        linkStrength = strength,
                    ),
                )
                val observedId = observe(address(91 + index * 2), 2_000, fullIdentities[index])
                assertNull(repository.getDeviceById(observedId)!!.linkedDeviceId)
                assertEquals(1, repository.getDeviceById(observedId)!!.detectionCount)
            }
        }

    @Test
    fun `latest valid Find My status replaces true with false and older packets cannot regress it`() =
        runTest {
            val deviceId = observe(address(100), 1_000, status = 0, separated = true)
            assertTrue(repository.getDeviceById(deviceId)!!.findMySeparated)
            observe(address(100), 2_000, status = 4, separated = false)
            observe(address(100), 1_500, status = 0, separated = true)
            observe(address(100), 3_000, status = null, separated = true)
            observe(address(100), 4_000, status = 256, separated = true)

            val device = repository.getDeviceById(deviceId)!!
            assertEquals(4, device.findMyStatus)
            assertEquals(2_000L, device.findMyStatusTimestamp)
            assertFalse(device.findMySeparated)
            assertFalse(device.shadowKey!!.contains("SEP:1"))
            assertEquals(1_000L, device.firstSeen)
            assertEquals(4_000L, device.lastSeen)
            assertEquals(5, device.detectionCount)
        }

    @Test
    fun `newer observations without status do not discard a delayed latest valid status`() =
        runTest {
            val deviceId = observe(address(101), 1_000, status = 0, separated = true)
            observe(address(101), 3_000)
            observe(address(101), 2_000, status = 4, separated = false)
            observe(address(101), 1_500, status = 0, separated = true)

            val device = repository.getDeviceById(deviceId)!!
            assertEquals(4, device.findMyStatus)
            assertEquals(2_000L, device.findMyStatusTimestamp)
            assertFalse(device.findMySeparated)
            assertEquals(3_000L, device.lastSeen)
            assertEquals(4, device.detectionCount)
        }

    @Test
    fun `equal confidence corrected classification refreshes same sized shadow key`() =
        runTest {
            val id = observe(address(102), 1_000, type = "PHONE")
            val previousKey = repository.getDeviceById(id)!!.shadowKey!!
            observe(address(102), 2_000, type = "WATCH")

            val updated = repository.getDeviceById(id)!!
            val updatedKey = updated.shadowKey!!
            assertEquals("WATCH", updated.deviceType)
            assertTrue(updatedKey.contains("T:WATCH"))
            assertFalse(updatedKey.contains("T:PHONE"))
            assertEquals(previousKey.split("|").size, updatedKey.split("|").size)
            assertNotEquals(previousKey, updatedKey)
        }

    @Test
    fun `validated service tracker overrides stronger generic classification and survives generic packets`() =
        runTest {
            val id = observe(address(103), 1_000, confidence = 1.0f)
            observe(address(103), 2_000, type = "TRACKER", tracker = true, method = "SERVICE_DATA")
            observe(address(103), 3_000, confidence = 1.0f)

            val updated = repository.getDeviceById(id)!!
            assertTrue(updated.isTracker)
            assertEquals("TRACKER", updated.deviceType)
            assertEquals("SERVICE_DATA", updated.identificationMethod)
            assertEquals(0.95f, updated.identificationConfidence, 0.0f)
            assertTrue(updated.shadowKey!!.contains("TR:1"))
        }

    @Test
    fun `shadow repository APIs return only evidence and members inside the requested observation window`() =
        runTest {
            val oldId = observe(address(104), 1_000)
            val currentId = observe(address(105), 2_000)
            record(oldId, 1_000)
            record(currentId, 2_000)
            val shadowKey = repository.getDeviceById(currentId)!!.shadowKey!!

            assertEquals(listOf(shadowKey), repository.getSuspiciousShadowKeys(1, 1_500))
            assertTrue(repository.getSuspiciousShadowKeys(2, 1_500).isEmpty())
            assertEquals(listOf(currentId), repository.getDevicesByShadowKey(shadowKey, 1_500).map { it.id })
            assertEquals(1, repository.getShadowLocationDeviceCounts(shadowKey, 1_500).size)
            assertEquals(listOf(currentId), repository.getDeviceLocationRecordsForShadow(shadowKey, 1_500).map { it.deviceId })
            assertTrue(repository.getDeviceLocationRecordsForShadow(shadowKey, 2_500).isEmpty())
            assertTrue(repository.getDeviceLocationRecordsForDeviceWithLinked(oldId, 1_500).isEmpty())
            assertEquals(1, repository.getDeviceLocationRecordsForDeviceWithLinked(oldId).size)
        }

    private fun address(index: Int): String = "02:00:00:00:00:%02X".format(index)

    private suspend fun observe(
        address: String,
        at: Long,
        fingerprint: String? = null,
        status: Int? = null,
        separated: Boolean = false,
        type: String = "PHONE",
        confidence: Float = 0.95f,
        tracker: Boolean = false,
        method: String = "MANUFACTURER_DATA",
    ): Long =
        repository.upsertDeviceWithFingerprint(
            address = address,
            name = "Shared name",
            advertisedName = "Shared name",
            lastSeen = at,
            manufacturerData = null,
            manufacturerId = 0x004C,
            manufacturerName = "Apple",
            deviceType = type,
            deviceModel = type,
            isTracker = tracker,
            serviceUuids = null,
            appearance = 0x0200,
            txPowerLevel = -7,
            advertisingFlags = null,
            appleContinuityType = if (status != null) 0x12 else null,
            identificationConfidence = confidence,
            identificationMethod = method,
            payloadFingerprint = fingerprint,
            findMyStatus = status,
            findMySeparated = separated,
            highestRssi = -55,
        )

    private suspend fun record(
        deviceId: Long,
        at: Long,
    ) {
        val locationId =
            database.locationDao().insert(
                Location(latitude = 1.0, longitude = 2.0, accuracy = 5f, timestamp = at, provider = "GPS"),
            )
        repository.insertDeviceLocationRecord(deviceId, locationId, -55, at, false, null, "CONTINUOUS")
    }
}
