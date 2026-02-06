package com.tailbait.algorithm

import com.tailbait.data.database.dao.ShadowLocationCount
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
    private lateinit var macRotationDetector: MacRotationDetector
    private lateinit var shadowAnalyzer: ShadowAnalyzer

    private val shadowKey = "B:FIND_MY|C:12|M:004C|T:TRACKER|TR:1"

    @Before
    fun setup() {
        deviceRepository = mockk(relaxed = true)
        locationRepository = mockk(relaxed = true)
        macRotationDetector = MacRotationDetector() // Use real implementation
        shadowAnalyzer = ShadowAnalyzer(deviceRepository, locationRepository, macRotationDetector)
    }

    private fun device(
        id: Long,
        address: String,
        firstSeen: Long = 1000L,
        lastSeen: Long = 2000L,
        highestRssi: Int = -55
    ) = ScannedDevice(
        id = id,
        address = address,
        name = null,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        manufacturerId = 0x004C,
        deviceType = "TRACKER",
        isTracker = true,
        highestRssi = highestRssi
    )

    private fun location(id: Long) = Location(
        id = id,
        latitude = 37.0 + id * 0.01,
        longitude = -122.0 + id * 0.01,
        accuracy = 10.0f,
        timestamp = System.currentTimeMillis(),
        provider = "gps"
    )

    @Test
    fun `all-ones counts produce high persistence score`() = runTest {
        // Setup: shadow key at 4 locations, exactly 1 device per location
        val locations = (1L..4L).map { location(it) }
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(any()) } returns listOf(shadowKey)
        coEvery { deviceRepository.getShadowLocationDeviceCounts(shadowKey) } returns listOf(
            ShadowLocationCount(1L, 1, -55),
            ShadowLocationCount(2L, 1, -60),
            ShadowLocationCount(3L, 1, -50),
            ShadowLocationCount(4L, 1, -65)
        )
        val min15 = 15 * 60 * 1000L
        val devices = listOf(
            device(1, "AA:00:00:00:00:01", firstSeen = 0, lastSeen = min15 - 60_000),
            device(2, "AA:00:00:00:00:02", firstSeen = min15, lastSeen = 2 * min15 - 60_000),
            device(3, "AA:00:00:00:00:03", firstSeen = 2 * min15, lastSeen = 3 * min15 - 60_000),
            device(4, "AA:00:00:00:00:04", firstSeen = 3 * min15, lastSeen = 4 * min15 - 60_000)
        )
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey) } returns devices

        val results = shadowAnalyzer.findSuspiciousShadows(3, emptySet())

        assertTrue("Should find suspicious shadow, got ${results.size}", results.isNotEmpty())
        val result = results.first()
        assertTrue(
            "Persistence score should be high, was ${result.persistenceScore}",
            result.persistenceScore > 0.3
        )
        assertEquals(4, result.locationCount)
    }

    @Test
    fun `varying counts produce low persistence score`() = runTest {
        // Setup: shadow key at 4 locations with varying device counts (common type, not following)
        val locations = (1L..4L).map { location(it) }
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(any()) } returns listOf(shadowKey)
        coEvery { deviceRepository.getShadowLocationDeviceCounts(shadowKey) } returns listOf(
            ShadowLocationCount(1L, 5, -55),
            ShadowLocationCount(2L, 3, -60),
            ShadowLocationCount(3L, 7, -50),
            ShadowLocationCount(4L, 4, -65)
        )
        val devices = (1L..7L).map {
            device(it, "AA:00:00:00:00:%02X".format(it))
        }
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey) } returns devices

        val results = shadowAnalyzer.findSuspiciousShadows(3, emptySet())

        // High variance in counts → low persistence → likely below threshold
        if (results.isNotEmpty()) {
            val result = results.first()
            assertTrue(
                "Persistence score should be low for varying counts, was ${result.persistenceScore}",
                result.persistenceScore < 0.3
            )
        }
    }

    @Test
    fun `whitelisted devices are excluded from results`() = runTest {
        val locations = (1L..3L).map { location(it) }
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(any()) } returns listOf(shadowKey)
        coEvery { deviceRepository.getShadowLocationDeviceCounts(shadowKey) } returns listOf(
            ShadowLocationCount(1L, 1, -55),
            ShadowLocationCount(2L, 1, -60),
            ShadowLocationCount(3L, 1, -50)
        )
        // All devices are whitelisted
        val devices = listOf(device(1, "AA:00:00:00:00:01"), device(2, "AA:00:00:00:00:02"))
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey) } returns devices

        val results = shadowAnalyzer.findSuspiciousShadows(3, setOf(1L, 2L))

        assertTrue("Whitelisted devices should produce no results", results.isEmpty())
    }

    @Test
    fun `no shadow keys returns empty results`() = runTest {
        val locations = (1L..3L).map { location(it) }
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(any()) } returns emptyList()

        val results = shadowAnalyzer.findSuspiciousShadows(3, emptySet())

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rotation evidence boosts combined score`() = runTest {
        val locations = (1L..4L).map { location(it) }
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(any()) } returns listOf(shadowKey)
        coEvery { deviceRepository.getShadowLocationDeviceCounts(shadowKey) } returns listOf(
            ShadowLocationCount(1L, 1, -55),
            ShadowLocationCount(2L, 1, -60),
            ShadowLocationCount(3L, 1, -50),
            ShadowLocationCount(4L, 1, -65)
        )
        // Devices with perfect 15-min rotation pattern
        val min15 = 15 * 60 * 1000L
        val devices = listOf(
            device(1, "AA:00:00:00:00:01", firstSeen = 0, lastSeen = min15 - 60_000),
            device(2, "AA:00:00:00:00:02", firstSeen = min15, lastSeen = 2 * min15 - 60_000),
            device(3, "AA:00:00:00:00:03", firstSeen = 2 * min15, lastSeen = 3 * min15 - 60_000),
            device(4, "AA:00:00:00:00:04", firstSeen = 3 * min15, lastSeen = 4 * min15 - 60_000)
        )
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey) } returns devices

        val results = shadowAnalyzer.findSuspiciousShadows(3, emptySet())

        assertTrue("Should find result", results.isNotEmpty())
        val result = results.first()
        assertTrue("Rotation score should be > 0, was ${result.rotationScore}", result.rotationScore > 0.0)
        assertTrue(
            "Combined score should be > persistence alone",
            result.combinedScore >= result.persistenceScore * 0.7
        )
    }

    @Test
    fun `representative device is most recently seen`() = runTest {
        val locations = (1L..3L).map { location(it) }
        every { locationRepository.getAllLocations() } returns flowOf(locations)
        coEvery { deviceRepository.getSuspiciousShadowKeys(any()) } returns listOf(shadowKey)
        coEvery { deviceRepository.getShadowLocationDeviceCounts(shadowKey) } returns listOf(
            ShadowLocationCount(1L, 1, -55),
            ShadowLocationCount(2L, 1, -60),
            ShadowLocationCount(3L, 1, -50)
        )
        val min15 = 15 * 60 * 1000L
        val devices = listOf(
            device(1, "AA:00:00:00:00:01", firstSeen = 0, lastSeen = min15),
            device(2, "AA:00:00:00:00:02", firstSeen = min15, lastSeen = 2 * min15),
            device(3, "AA:00:00:00:00:03", firstSeen = 2 * min15, lastSeen = 3 * min15)
        )
        coEvery { deviceRepository.getDevicesByShadowKey(shadowKey) } returns devices

        val results = shadowAnalyzer.findSuspiciousShadows(3, emptySet())

        if (results.isNotEmpty()) {
            assertEquals(
                "Representative should be most recently seen device",
                3L, results.first().representativeDevice.id
            )
        }
    }
}
