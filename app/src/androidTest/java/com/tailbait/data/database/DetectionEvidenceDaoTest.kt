package com.tailbait.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.data.database.dao.DeviceLocationRecordDao
import com.tailbait.data.database.dao.ScannedDeviceDao
import com.tailbait.data.database.dao.ShadowLocationCount
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectionEvidenceDaoTest {
    private lateinit var database: TailBaitDatabase
    private lateinit var devices: ScannedDeviceDao
    private lateinit var records: DeviceLocationRecordDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                TailBaitDatabase::class.java,
            ).build()
        devices = database.scannedDeviceDao()
        records = database.deviceLocationRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observationCutoffControlsEveryShadowQueryIncludingBoundary() =
        runBlocking {
            locations(3)
            device(1)
            device(2)
            device(3, shadowKey = "stale")
            device(4)
            record(1, 1, CUTOFF - 1, -5)
            val boundary = record(1, 2, CUTOFF, -70)
            val recent = record(1, 3, CUTOFF + 1, -60)
            record(2, 2, CUTOFF - 1, -1)
            record(3, 1, CUTOFF - 2)
            record(3, 3, CUTOFF - 1)

            assertEquals(listOf(SHADOW), devices.getSuspiciousShadowKeys(2, CUTOFF))
            assertTrue(devices.getSuspiciousShadowKeys(3, CUTOFF).isEmpty())
            assertEquals(listOf(1L), devices.getDevicesByShadowKey(SHADOW, CUTOFF).map { it.id })
            assertEquals(
                listOf(ShadowLocationCount(2, 1, -70), ShadowLocationCount(3, 1, -60)),
                devices.getShadowLocationDeviceCounts(SHADOW, CUTOFF).sortedBy { it.locationId },
            )
            assertEquals(listOf(recent, boundary), records.getRecordsForShadow(SHADOW, CUTOFF).map { it.id })
            assertTrue(devices.getDevicesByShadowKey("stale", CUTOFF).isEmpty())
            assertTrue(devices.getShadowLocationDeviceCounts("stale", CUTOFF).isEmpty())
            assertTrue(records.getRecordsForShadow("stale", CUTOFF).isEmpty())
        }

    @Test
    fun linkedEvidenceContainsOnlyStrongFamilyWithinWindowAndDefaultsToHistory() =
        runBlocking {
            locations(4)
            device(1, lastSeen = CUTOFF)
            device(2, linkedDeviceId = 1, linkStrength = "STRONG")
            device(3, linkedDeviceId = 1, linkStrength = "WEAK")
            val old = record(1, 1, CUTOFF - 1)
            val boundary = record(1, 2, CUTOFF)
            val strong = record(2, 3, CUTOFF + 1)
            val weak = record(3, 4, CUTOFF + 2)

            assertEquals(listOf(1L), devices.getSuspiciousDevicesWithLinked(2, CUTOFF).first().map { it.id })
            assertTrue(devices.getSuspiciousDevicesWithLinked(3, CUTOFF).first().isEmpty())
            assertEquals(
                setOf(1L, 3L),
                devices.getSuspiciousDevicesWithLinked(1, CUTOFF).first().map { it.id }.toSet(),
            )
            assertEquals(listOf(strong, boundary), records.getRecordsForDeviceWithLinked(1, CUTOFF).map { it.id })
            assertEquals(listOf(strong, boundary), records.getRecordsForDeviceWithLinked(2, CUTOFF).map { it.id })
            assertEquals(listOf(strong, boundary, old), records.getRecordsForDeviceWithLinked(1).map { it.id })
            assertEquals(listOf(weak), records.getRecordsForDeviceWithLinked(3, CUTOFF).map { it.id })
        }

    @Test
    fun trustOnAnyStrongMemberExcludesFamilyWithoutTrustingShadowPeers() =
        runBlocking {
            locations(6)
            device(1)
            device(2, linkedDeviceId = 1, linkStrength = "STRONG", shadowKey = "trusted-child-profile")
            device(3, linkedDeviceId = 1, linkStrength = "STRONG")
            device(4, linkedDeviceId = 1, linkStrength = "WEAK")
            device(5)
            record(1, 1, CUTOFF, -1)
            record(2, 2, CUTOFF, -1)
            record(3, 3, CUTOFF, -1)
            val eligibleRecords =
                setOf(
                    record(4, 4, CUTOFF, -80),
                    record(4, 5, CUTOFF, -80),
                    record(5, 5, CUTOFF, -65),
                    record(5, 6, CUTOFF, -65),
                )

            for (trustedId in listOf(1L, 2L)) {
                val entry = trust(trustedId)
                assertEquals(
                    setOf(4L, 5L),
                    devices.getSuspiciousDevicesWithLinked(2, CUTOFF).first().map { it.id }.toSet(),
                )
                for (memberId in 1L..3L) {
                    assertTrue(records.getRecordsForDeviceWithLinked(memberId, CUTOFF).isEmpty())
                }
                assertEquals(listOf(SHADOW), devices.getSuspiciousShadowKeys(3, CUTOFF))
                assertTrue(devices.getSuspiciousShadowKeys(4, CUTOFF).isEmpty())
                assertEquals(
                    setOf(4L, 5L),
                    devices.getDevicesByShadowKey(SHADOW, CUTOFF).map { it.id }.toSet(),
                )
                assertEquals(
                    listOf(
                        ShadowLocationCount(4, 1, -80),
                        ShadowLocationCount(5, 2, -65),
                        ShadowLocationCount(6, 1, -65),
                    ),
                    devices.getShadowLocationDeviceCounts(SHADOW, CUTOFF).sortedBy { it.locationId },
                )
                assertEquals(eligibleRecords, records.getRecordsForShadow(SHADOW, CUTOFF).map { it.id }.toSet())
                assertTrue(records.getRecordsForShadow("trusted-child-profile", CUTOFF).isEmpty())
                database.whitelistEntryDao().delete(entry)
            }
        }

    @Test
    fun trustedWeakChildDoesNotSuppressPrimaryFamily() =
        runBlocking {
            locations(4)
            device(1)
            device(2, linkedDeviceId = 1, linkStrength = "STRONG")
            device(3, linkedDeviceId = 1, linkStrength = "WEAK")
            val root = record(1, 1, CUTOFF)
            val strong = record(2, 2, CUTOFF + 1)
            record(3, 3, CUTOFF)
            record(3, 4, CUTOFF)
            trust(3)

            assertEquals(listOf(1L), devices.getSuspiciousDevicesWithLinked(2, CUTOFF).first().map { it.id })
            assertEquals(listOf(strong, root), records.getRecordsForDeviceWithLinked(1, CUTOFF).map { it.id })
            assertTrue(records.getRecordsForDeviceWithLinked(3).isEmpty())
            assertEquals(listOf(SHADOW), devices.getSuspiciousShadowKeys(2, CUTOFF))
            assertTrue(devices.getSuspiciousShadowKeys(3, CUTOFF).isEmpty())
            assertEquals(setOf(1L, 2L), devices.getDevicesByShadowKey(SHADOW, CUTOFF).map { it.id }.toSet())
            assertEquals(listOf(strong, root), records.getRecordsForShadow(SHADOW, CUTOFF).map { it.id })
        }

    @Test
    fun malformedStrongLinksRemainIndependentFromTrustedRoot() =
        runBlocking {
            locations(7)
            device(1)
            device(2, linkedDeviceId = 1, linkStrength = "STRONG")
            device(3, linkedDeviceId = 2, linkStrength = "STRONG")
            device(4, linkedDeviceId = 999, linkStrength = "STRONG")
            device(5, linkedDeviceId = 5, linkStrength = "STRONG")
            device(6, linkedDeviceId = 1, linkStrength = "WEAK")
            device(7, linkedDeviceId = 6, linkStrength = "STRONG")
            for (id in 1L..7L) record(id, id, CUTOFF)
            trust(1)

            val independentIds = setOf(3L, 4L, 5L, 6L, 7L)
            assertEquals(
                independentIds,
                devices.getSuspiciousDevicesWithLinked(1, CUTOFF).first().map { it.id }.toSet(),
            )
            assertEquals(independentIds, devices.getDevicesByShadowKey(SHADOW, CUTOFF).map { it.id }.toSet())
            for (id in independentIds) {
                assertEquals(listOf(id), records.getRecordsForDeviceWithLinked(id, CUTOFF).map { it.deviceId })
            }
        }

    @Test
    fun pruningPreservesLiveRootsAndExplicitWhitelistWhileDeletingExpiredHistory() =
        runBlocking {
            locations(2)
            device(1, lastSeen = CUTOFF - 1)
            device(2, lastSeen = CUTOFF, linkedDeviceId = 1, linkStrength = "STRONG")
            device(3, lastSeen = CUTOFF - 1)
            device(4, lastSeen = CUTOFF - 1, linkedDeviceId = 3, linkStrength = "STRONG")
            device(5, lastSeen = CUTOFF - 1)
            device(6, lastSeen = CUTOFF - 1)
            device(7, lastSeen = CUTOFF - 1, linkedDeviceId = 6, linkStrength = "STRONG")
            device(8, lastSeen = CUTOFF - 1)
            device(9)
            device(10, lastSeen = CUTOFF - 1, linkedDeviceId = 1, linkStrength = "STRONG")
            val liveRecord = record(2, 1, CUTOFF)
            val trustedRecord = record(4, 2, CUTOFF - 1)
            val trustedChild = trust(4)
            val explicitlyTrusted = trust(5)

            assertEquals(4, devices.deleteOldDevices(CUTOFF))
            assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 9L), devices.getAllDevices().first().map { it.id }.toSet())
            assertEquals(1L, devices.getById(2)?.linkedDeviceId)
            assertEquals(3L, devices.getById(4)?.linkedDeviceId)
            assertNotNull(records.getById(liveRecord))
            assertNotNull(records.getById(trustedRecord))
            assertNull(devices.getById(6))
            assertNull(devices.getById(7))
            assertEquals(trustedChild, database.whitelistEntryDao().getById(trustedChild.id))
            assertEquals(explicitlyTrusted, database.whitelistEntryDao().getById(explicitlyTrusted.id))
            assertEquals(0, devices.deleteOldDevices(CUTOFF))
        }

    private suspend fun device(
        id: Long,
        lastSeen: Long = CUTOFF + 10,
        linkedDeviceId: Long? = null,
        linkStrength: String? = null,
        shadowKey: String? = SHADOW,
    ): Long =
        devices.insert(
            ScannedDevice(
                id = id,
                address = "02:00:00:00:00:${id.toString(16).padStart(2, '0')}",
                name = null,
                firstSeen = 0,
                lastSeen = lastSeen,
                createdAt = 0,
                linkedDeviceId = linkedDeviceId,
                linkStrength = linkStrength,
                shadowKey = shadowKey,
            ),
        )

    private suspend fun locations(count: Int) {
        for (id in 1L..count.toLong()) {
            database.locationDao().insert(
                Location(
                    id = id,
                    latitude = 37.0 + id / 10000.0,
                    longitude = -122.0,
                    accuracy = 5f,
                    timestamp = CUTOFF,
                    provider = "GPS",
                    createdAt = 0,
                ),
            )
        }
    }

    private suspend fun record(
        deviceId: Long,
        locationId: Long,
        timestamp: Long,
        rssi: Int = -60,
    ): Long =
        records.insert(
            DeviceLocationRecord(
                deviceId = deviceId,
                locationId = locationId,
                rssi = rssi,
                timestamp = timestamp,
                scanTriggerType = "MANUAL",
                createdAt = 0,
            ),
        )

    private suspend fun trust(deviceId: Long): WhitelistEntry {
        val entry =
            WhitelistEntry(
                deviceId = deviceId,
                label = "Trusted $deviceId",
                category = "OWN",
                notes = "Preserve user trust",
                createdAt = 0,
            )
        return entry.copy(id = database.whitelistEntryDao().insert(entry))
    }

    private companion object {
        const val CUTOFF = 1000L
        const val SHADOW = "shared-profile"
    }
}
