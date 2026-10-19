package com.tailbait.algorithm

import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShadowAnalyzerTest {
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var shadowAnalyzer: ShadowAnalyzer

    private val shadowKey = "B:FIND_MY|C:12|M:004C|T:TRACKER|TR:1"
    private val sinceTimestamp = 1_000_000L
    private val rotationInterval = 15 * 60 * 1000L

    @Before
    fun setup() {
        deviceRepository = mockk()
        locationRepository = mockk()
        coEvery { locationRepository.getUserPathSince(sinceTimestamp) } returns emptyList()
        shadowAnalyzer = ShadowAnalyzer(deviceRepository, locationRepository, MacRotationDetector())
    }

    private fun device(id: Long) =
        ScannedDevice(
            id = id,
            address = "AA:00:00:00:00:%02X".format(id),
            name = null,
            firstSeen = 0,
            lastSeen = sinceTimestamp + 100 * rotationInterval,
            manufacturerId = 0x004C,
            deviceType = "TRACKER",
            isTracker = true,
            highestRssi = -10,
            shadowKey = shadowKey,
        )

    private fun location(id: Long) =
        Location(
            id = id,
            latitude = 37.0 + id * 0.01,
            longitude = -122.0 + id * 0.01,
            accuracy = 10f,
            timestamp = sinceTimestamp + id * rotationInterval,
            provider = "gps",
        )

    private fun record(
        deviceId: Long,
        locationId: Long,
        timestamp: Long,
    ) = DeviceLocationRecord(
        deviceId = deviceId,
        locationId = locationId,
        timestamp = timestamp,
        rssi = -55,
        scanTriggerType = "PERIODIC",
    )

    private fun rotatingRecords(count: Int): List<DeviceLocationRecord> =
        (1L..count.toLong()).flatMap { id ->
            val start = sinceTimestamp + (id - 1) * rotationInterval
            listOf(record(id, id, start), record(id, id, start + rotationInterval - 60_000))
        }

    private fun configureEvidence(
        devices: List<ScannedDevice>,
        records: List<DeviceLocationRecord>,
        locations: List<Location>,
    ) {
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(3, sinceTimestamp) } returns listOf(shadowKey)
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey, sinceTimestamp) } returns devices
        coEvery { deviceRepository.getDeviceLocationRecordsForShadow(shadowKey, sinceTimestamp) } returns records
    }

    @Test
    fun `one MAC per place carries actual group records and locations`() =
        runTest {
            val records = rotatingRecords(4)
            val locations = (1L..4L).map { location(it) }
            configureEvidence((1L..4L).map { device(it) }, records, locations)

            val result = shadowAnalyzer.findSuspiciousShadows(3, emptySet(), sinceTimestamp).single()

            assertEquals(records, result.records)
            assertEquals(locations, result.locations)
            assertEquals(4, result.locationCount)
            assertEquals(0.625, result.persistenceScore, 0.0001)
            assertEquals(1.0, result.rotationScore, 0.0001)
            assertEquals(0.7375, result.combinedScore, 0.0001)
            assertEquals(4L, result.representativeDevice.id)
            assertEquals(records.last().timestamp, result.representativeDevice.lastSeen)
            assertEquals(-55, result.representativeDevice.highestRssi)
        }

    @Test
    fun `varying crowd counts lower persistence below reporting threshold`() =
        runTest {
            val counts = listOf(5, 3, 7, 4)
            val records =
                counts.flatMapIndexed { index, count ->
                    (1L..count.toLong()).map { deviceId ->
                        record(deviceId, index + 1L, sinceTimestamp + 60_000)
                    }
                }
            configureEvidence((1L..7L).map { device(it) }, records, (1L..4L).map { location(it) })

            assertTrue(shadowAnalyzer.findSuspiciousShadows(3, emptySet(), sinceTimestamp).isEmpty())
        }

    @Test
    fun `explicit trust removes all shadow evidence`() =
        runTest {
            configureEvidence((1L..3L).map { device(it) }, rotatingRecords(3), (1L..3L).map { location(it) })

            assertTrue(shadowAnalyzer.findSuspiciousShadows(3, setOf(1L, 2L, 3L), sinceTimestamp).isEmpty())
        }

    @Test
    fun `no shadow keys returns no detections`() =
        runTest {
            coEvery { deviceRepository.getSuspiciousShadowKeys(3, sinceTimestamp) } returns emptyList()

            assertTrue(shadowAnalyzer.findSuspiciousShadows(3, emptySet(), sinceTimestamp).isEmpty())
        }

    @Test
    fun `stale and trusted members cannot inflate counts rotation or representative state`() =
        runTest {
            val eligibleDevices = (1L..3L).map { device(it) }
            val eligibleRecords = rotatingRecords(3)
            val locations = (1L..4L).map { location(it) } + location(9).copy(timestamp = sinceTimestamp - 1)
            configureEvidence(eligibleDevices, eligibleRecords, locations)
            val baseline = shadowAnalyzer.findSuspiciousShadows(3, setOf(8L), sinceTimestamp).single()

            val staleAndTrustedRecords =
                listOf(
                    record(1, 9, sinceTimestamp - rotationInterval),
                    record(9, 9, sinceTimestamp - 1),
                    record(8, 1, sinceTimestamp + 4 * rotationInterval),
                    record(8, 4, sinceTimestamp + 5 * rotationInterval),
                )
            configureEvidence(
                eligibleDevices + device(8).copy(findMySeparated = true) + device(9),
                eligibleRecords + staleAndTrustedRecords,
                locations,
            )

            val result = shadowAnalyzer.findSuspiciousShadows(3, setOf(8L), sinceTimestamp).single()

            assertEquals(eligibleRecords, result.records)
            assertEquals(locations.take(3), result.locations)
            assertEquals(3, result.locationCount)
            assertEquals(baseline.persistenceScore, result.persistenceScore, 0.0)
            assertEquals(baseline.rotationScore, result.rotationScore, 0.0)
            assertEquals(baseline.combinedScore, result.combinedScore, 0.0)
            assertEquals(baseline.representativeDevice, result.representativeDevice)
        }

    @Test
    fun `stale and trusted places cannot satisfy the minimum location count`() =
        runTest {
            configureEvidence(
                listOf(device(1), device(2), device(3)),
                listOf(
                    record(1, 1, sinceTimestamp + 1),
                    record(2, 2, sinceTimestamp + 2),
                    record(3, 3, sinceTimestamp - 1),
                ),
                (1L..3L).map { location(it) },
            )

            assertTrue(shadowAnalyzer.findSuspiciousShadows(3, setOf(2L), sinceTimestamp).isEmpty())
        }

    @Test
    fun `recent MAC observations do not refresh stale separated status`() =
        runTest {
            val locations = (1L..3L).map { location(it) }
            val staleState =
                device(1).copy(
                    findMyStatus = 0,
                    findMyStatusTimestamp = sinceTimestamp - 1,
                    findMySeparated = true,
                )
            val records = locations.map { record(staleState.id, it.id, it.timestamp) }
            configureEvidence(listOf(staleState), records, locations)

            val result = shadowAnalyzer.findSuspiciousShadows(3, emptySet(), sinceTimestamp).single()

            assertEquals(records, result.records)
            assertFalse(result.representativeDevice.findMySeparated)
            assertNull(result.representativeDevice.findMyStatus)
            assertNull(result.representativeDevice.findMyStatusTimestamp)
        }

    @Test
    fun `historical unrelated places do not dilute current shadow coverage`() =
        runTest {
            val currentLocations = (1L..3L).map { location(it) }
            val historicalLocations = (4L..30L).map { location(it).copy(timestamp = sinceTimestamp - 1) }
            configureEvidence((1L..3L).map { device(it) }, rotatingRecords(3), currentLocations + historicalLocations)

            val result = shadowAnalyzer.findSuspiciousShadows(3, emptySet(), sinceTimestamp).single()

            assertEquals(0.625, result.persistenceScore, 0.0001)
            assertEquals(currentLocations, result.locations)
        }
}
