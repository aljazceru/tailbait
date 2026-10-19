package com.tailbait.flows

import android.os.ParcelUuid
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailbait.companion.AdvPayloadParser
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.repository.DeviceRepositoryImpl
import com.tailbait.data.repository.LinkStrength
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.ManufacturerDataParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolIdentityIntegrationTest {
    private lateinit var database: TailBaitDatabase
    private lateinit var repository: DeviceRepositoryImpl
    private val findHubUuid = uuid16("FEAA")

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                TailBaitDatabase::class.java,
            ).build()
        repository = DeviceRepositoryImpl(database, database.scannedDeviceDao(), database.deviceLocationRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun validFindHubFrames_phoneAndCompanionPersistTheSameClassificationAndFullEid() =
        runBlocking {
            data class Case(
                val label: String,
                val frame: ByteArray,
                val fingerprint: String,
                val manufacturerData: ByteArray? = null,
            )

            val eid20 = ByteArray(20) { 0xA5.toByte() }
            val eid32 = ByteArray(32) { 0xC3.toByte() }
            val cases =
                listOf(
                    Case("40/20 without flags", byteArrayOf(0x40) + eid20, "FHN:" + "A5".repeat(20)),
                    Case("41/20 with flags", byteArrayOf(0x41) + eid20 + byteArrayOf(0x96.toByte()), "FHN:" + "A5".repeat(20)),
                    Case("40/32 with flags", byteArrayOf(0x40) + eid32 + byteArrayOf(0xE7.toByte()), "FHN:" + "C3".repeat(32)),
                    Case(
                        "41/32 overrides generic Apple classification",
                        byteArrayOf(0x41) + eid32 + byteArrayOf(0x80.toByte()),
                        "FHN:" + "C3".repeat(32),
                        manufacturerData = byteArrayOf(0x07, 0x19, 0x20, 0x02),
                    ),
                )

            cases.forEachIndexed { index, case ->
                val phoneInput =
                    Advertisement(
                        manufacturerId = case.manufacturerData?.let { 0x004C },
                        manufacturerData = case.manufacturerData,
                        serviceData = mapOf(findHubUuid to case.frame),
                    )
                val rawAdvertisement =
                    ad(0x16, byteArrayOf(0xAA.toByte(), 0xFE.toByte()) + case.frame) +
                        (case.manufacturerData?.let { manufacturerAd(it) } ?: byteArrayOf())
                val phone = stored(observe(index * 2, 1_000, phoneInput))
                val companion = stored(observe(index * 2 + 1, 1_000, fromCompanion(rawAdvertisement)))

                for (device in listOf(phone, companion)) {
                    assertTrue(case.label, device.isTracker)
                    assertEquals(case.label, "TRACKER", device.deviceType)
                    assertEquals(case.label, "Find Hub Tracker", device.deviceModel)
                    assertEquals(case.label, "Google", device.manufacturerName)
                    assertEquals(case.label, "SERVICE_DATA", device.identificationMethod)
                    assertEquals(case.label, case.fingerprint, device.payloadFingerprint)
                }
                assertEquals(case.label, phone.identificationConfidence, companion.identificationConfidence, 0.0f)
                assertEquals(case.label, phone.manufacturerData, companion.manufacturerData)
            }
        }

    @Test
    fun unrelatedServicesAndMalformedFindHubFrames_doNotPersistTrackerIdentity() =
        runBlocking {
            val eid = ByteArray(20) { 0xA5.toByte() }
            val validFrame = byteArrayOf(0x40) + eid
            val wrongFindHubUuid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fc")
            val wrongSmartTagUuid = ParcelUuid.fromString("0000fd5a-1111-2222-3333-00805f9b34fb")
            val cases =
                listOf(
                    "FEAA UUID without service data" to Advertisement(serviceUuids = listOf(findHubUuid)),
                    "Fast Pair is not Find Hub" to serviceAdvertisement(uuid16("FE2C"), validFrame),
                    "Exposure Notification is not Find Hub" to serviceAdvertisement(uuid16("FD6F"), validFrame),
                    "FEAA substring in a different UUID" to serviceAdvertisement(wrongFindHubUuid, validFrame),
                    "FD5A substring in a different UUID" to Advertisement(serviceUuids = listOf(wrongSmartTagUuid)),
                    "Eddystone frame on FEAA" to serviceAdvertisement(findHubUuid, byteArrayOf(0x00) + eid),
                    "Truncated 20-byte EID" to serviceAdvertisement(findHubUuid, byteArrayOf(0x40) + eid.copyOf(19)),
                    "41 frame missing required flags" to serviceAdvertisement(findHubUuid, byteArrayOf(0x41) + eid),
                )

            cases.forEachIndexed { index, (label, input) ->
                val device = stored(observe(index, 1_000, input))

                assertFalse(label, device.isTracker)
                assertNotEquals(label, "TRACKER", device.deviceType)
                assertNull(label, device.payloadFingerprint)
                assertNull(label, device.linkedDeviceId)
            }
        }

    @Test
    fun findMyFullShortAndTruncatedFrames_persistOnlyValidatedIdentityAndCurrentOwnerState() =
        runBlocking {
            data class Case(
                val label: String,
                val payload: ByteArray,
                val status: Int?,
                val separated: Boolean,
                val fingerprint: String?,
            )

            val full = fullFindMy(status = 0)
            val cases =
                listOf(
                    Case("Full key without owner connection", full, 0, true, "FM:" + "A5".repeat(22)),
                    Case("Short current-owner status has no identity", byteArrayOf(0x12, 0x02, 0x04, 0x00), 4, false, null),
                    Case("Full frame missing final byte", full.copyOf(26), null, false, null),
                    Case("Short frame missing final byte", byteArrayOf(0x12, 0x02, 0x00), null, false, null),
                    Case("Full frame with short declared length", full.copyOf().apply { this[1] = 0x02 }, null, false, null),
                )

            cases.forEachIndexed { index, case ->
                val phone = stored(observe(index * 2, 1_000, apple(case.payload)))
                val companion = stored(observe(index * 2 + 1, 1_000, fromCompanion(manufacturerAd(case.payload))))

                for (device in listOf(phone, companion)) {
                    assertEquals(case.label, case.status != null, device.isTracker)
                    assertEquals(case.label, case.status, device.findMyStatus)
                    assertEquals(case.label, if (case.status != null) 1_000L else null, device.findMyStatusTimestamp)
                    assertEquals(case.label, case.separated, device.findMySeparated)
                    assertEquals(case.label, case.fingerprint, device.payloadFingerprint)
                    assertEquals(case.label, if (case.status != null) 0x12 else null, device.appleContinuityType)
                    if (case.fingerprint == null) assertNull(case.label, device.linkedDeviceId)
                }
                assertEquals(case.label, phone.deviceType, companion.deviceType)
                assertEquals(case.label, phone.identificationMethod, companion.identificationMethod)
            }
        }

    @Test
    fun coPresentClassAndSignaturePeers_remainSeparateDespiteMatchingNameProfileAndRssi() =
        runBlocking {
            val cases =
                listOf(
                    "Shared proximity-pairing signature" to apple(byteArrayOf(0x07, 0x19, 0x20, 0x02, 0x50, 0x55)),
                    "Shared SmartTag service class" to Advertisement(serviceUuids = listOf(uuid16("FD5A"))),
                    "Shared short Find My status" to apple(byteArrayOf(0x12, 0x02, 0x00, 0x00)),
                )

            cases.forEachIndexed { index, (label, input) ->
                val firstId = observe(index * 2, 1_000, input)
                val firstBefore = stored(firstId)
                val secondId = observe(index * 2 + 1, 1_000, input)
                val secondAgain = observe(index * 2 + 1, 2_000, input)
                val second = stored(secondId)

                assertNotEquals(label, firstId, secondId)
                assertEquals(label, secondId, secondAgain)
                assertEquals(label, address(index * 2 + 1), second.address)
                assertNull(label, firstBefore.payloadFingerprint)
                assertNull(label, second.payloadFingerprint)
                assertNull(label, firstBefore.linkedDeviceId)
                assertNull(label, second.linkedDeviceId)
                assertNull(label, second.linkStrength)
                assertNotNull(label, firstBefore.shadowKey)
                assertEquals(label, firstBefore.shadowKey, second.shadowKey)
                assertEquals(label, firstBefore, stored(firstId))
                assertEquals(label, 2, second.detectionCount)
                assertEquals(label, listOf(firstId), repository.getLinkedDevices(firstId).map { it.id })
                assertEquals(
                    label,
                    listOf(1_000L, 2_000L),
                    repository.getDeviceLocationRecordsForDevice(secondId).map { it.timestamp }.sorted(),
                )
            }
        }

    @Test
    fun fullFindMyKeysDifferingOnlyInTheirLastByte_doNotMergeOnTheSharedPrefix() =
        runBlocking {
            val firstId = observe(1, 1_000, apple(fullFindMy(status = 0)))
            val differentKey = fullFindMy(status = 0).apply { this[24] = 0xB6.toByte() }
            val secondId = observe(2, 1_000, apple(differentKey))
            val first = stored(firstId)
            val second = stored(secondId)

            assertNotEquals(firstId, secondId)
            assertEquals("FM:" + "A5".repeat(22), first.payloadFingerprint)
            assertEquals("FM:" + "A5".repeat(21) + "B6", second.payloadFingerprint)
            assertNull(first.linkedDeviceId)
            assertNull(second.linkedDeviceId)
            assertEquals(listOf(firstId), repository.getLinkedDevices(firstId).map { it.id })
        }

    @Test
    fun fullIdentityRotations_returnObservedChildIdsAndKeepCanonicalRootLifetimes() =
        runBlocking {
            val inputs =
                listOf(
                    apple(fullFindMy(status = 0)),
                    serviceAdvertisement(findHubUuid, byteArrayOf(0x40) + ByteArray(20) { 0xC3.toByte() }),
                    serviceAdvertisement(findHubUuid, byteArrayOf(0x41) + ByteArray(32) { 0xD7.toByte() } + byteArrayOf(0x80.toByte())),
                )

            inputs.forEachIndexed { index, input ->
                val rootId = observe(index * 4, 1_000_000, input)
                val rootBefore = stored(rootId)
                val childIndex = index * 4 + 1
                val childId = observe(childIndex, 1_900_000, input)
                val childAgain = observe(childIndex, 1_960_000, input)
                // The root is now stale; the fresh child must resolve back to that same root.
                val thirdId = observe(index * 4 + 2, 2_800_000, input)
                val child = stored(childId)
                val third = stored(thirdId)

                assertNotNull(rootBefore.payloadFingerprint)
                assertNotEquals(rootId, childId)
                assertEquals(childId, childAgain)
                assertEquals(childId, repository.getDeviceByAddress(address(childIndex))!!.id)
                assertEquals(address(childIndex), child.address)
                assertEquals(rootBefore, stored(rootId))
                assertEquals(rootId, child.linkedDeviceId)
                assertEquals(LinkStrength.STRONG.name, child.linkStrength)
                assertEquals("fingerprint_match:${rootBefore.payloadFingerprint}", child.linkReason)
                assertEquals(1_900_000L, child.firstSeen)
                assertEquals(1_960_000L, child.lastSeen)
                assertEquals(2, child.detectionCount)
                assertNotEquals(childId, thirdId)
                assertEquals(rootId, third.linkedDeviceId)
                assertEquals(LinkStrength.STRONG.name, third.linkStrength)
                assertEquals(
                    listOf(1_900_000L, 1_960_000L),
                    repository.getDeviceLocationRecordsForDevice(childId).map { it.timestamp }.sorted(),
                )
                assertEquals(listOf(1_000_000L), repository.getDeviceLocationRecordsForDevice(rootId).map { it.timestamp })
                assertEquals(
                    setOf(rootId, childId, thirdId),
                    repository.getDeviceLocationRecordsForDeviceWithLinked(rootId).map { it.deviceId }.toSet(),
                )
                assertEquals(
                    listOf(1_960_000L, 2_800_000L),
                    repository.getDeviceLocationRecordsForDeviceWithLinked(childId, 1_950_000).map { it.timestamp }.sorted(),
                )

                val staleMatch = stored(observe(index * 4 + 3, 4_000_001, input))
                assertNull(staleMatch.linkedDeviceId)
                assertNull(staleMatch.linkStrength)
                assertEquals(rootBefore.payloadFingerprint, staleMatch.payloadFingerprint)
                assertEquals(rootBefore, stored(rootId))
            }
        }

    @Test
    fun concurrentFirstSightingsOfOneAlias_keepOneChildAndAllObservedRecords() =
        runBlocking {
            val input = apple(fullFindMy(status = 0))
            val rootId = observe(1, 1_000, input)
            val rootBefore = stored(rootId)
            val childIds =
                listOf(2_000L, 3_000L).map { timestamp ->
                    async(Dispatchers.IO) { observe(2, timestamp, input) }
                }.awaitAll()
            val child = repository.getDeviceByAddress(address(2))!!

            assertNotEquals(rootId, child.id)
            assertEquals(listOf(child.id, child.id), childIds)
            assertEquals(rootId, child.linkedDeviceId)
            assertEquals(LinkStrength.STRONG.name, child.linkStrength)
            assertEquals(2, child.detectionCount)
            assertEquals(2_000L, child.firstSeen)
            assertEquals(3_000L, child.lastSeen)
            assertEquals(rootBefore, stored(rootId))
            assertEquals(setOf(rootId, child.id), repository.getLinkedDevices(rootId).map { it.id }.toSet())
            assertEquals(
                listOf(2_000L, 3_000L),
                repository.getDeviceLocationRecordsForDevice(child.id).map { it.timestamp }.sorted(),
            )
        }

    @Test
    fun latestValidOwnerStatus_clearsSeparationDespiteNewerNonStatusAndOlderSeparatedPackets() =
        runBlocking {
            val id = observe(1, 1_000, apple(fullFindMy(status = 0)))
            assertTrue(stored(id).findMySeparated)
            assertTrue(stored(id).shadowKey!!.contains("SEP:1"))

            observe(1, 3_000, apple(byteArrayOf(0x10, 0x02, 0x00, 0x00)))
            observe(1, 2_000, apple(byteArrayOf(0x12, 0x02, 0x04, 0x00)))
            val connected = stored(id)
            assertEquals(4, connected.findMyStatus)
            assertEquals(2_000L, connected.findMyStatusTimestamp)
            assertFalse(connected.findMySeparated)
            assertFalse(connected.shadowKey!!.contains("SEP:1"))

            observe(1, 1_500, apple(fullFindMy(status = 0)))
            observe(1, 4_000, apple(fullFindMy(status = 0).copyOf(26)))
            val latest = stored(id)

            assertEquals(4, latest.findMyStatus)
            assertEquals(2_000L, latest.findMyStatusTimestamp)
            assertFalse(latest.findMySeparated)
            assertFalse(latest.shadowKey!!.contains("SEP:1"))
            assertEquals("FM:" + "A5".repeat(22), latest.payloadFingerprint)
            assertEquals(1_000L, latest.firstSeen)
            assertEquals(4_000L, latest.lastSeen)
            assertEquals(5, latest.detectionCount)
            assertEquals(
                listOf(1_000L, 1_500L, 2_000L, 3_000L, 4_000L),
                repository.getDeviceLocationRecordsForDevice(id).map { it.timestamp }.sorted(),
            )
        }

    private data class Advertisement(
        val manufacturerId: Int? = null,
        val manufacturerData: ByteArray? = null,
        val serviceUuids: List<ParcelUuid>? = null,
        val serviceData: Map<ParcelUuid, ByteArray> = emptyMap(),
    )

    private fun uuid16(value: String): ParcelUuid = ParcelUuid.fromString("0000$value-0000-1000-8000-00805f9b34fb")

    private fun address(index: Int): String = "02:00:00:00:00:%02X".format(index)

    private fun apple(payload: ByteArray) = Advertisement(manufacturerId = 0x004C, manufacturerData = payload)

    private fun serviceAdvertisement(
        uuid: ParcelUuid,
        data: ByteArray,
    ) = Advertisement(serviceUuids = listOf(uuid), serviceData = mapOf(uuid to data))

    private fun fullFindMy(status: Int): ByteArray =
        byteArrayOf(0x12, 0x19, status.toByte()) + ByteArray(22) { 0xA5.toByte() } + byteArrayOf(0x00, 0x00)

    private fun ad(
        type: Int,
        data: ByteArray,
    ): ByteArray = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data

    private fun manufacturerAd(payload: ByteArray): ByteArray = ad(0xFF, byteArrayOf(0x4C, 0x00) + payload)

    private fun fromCompanion(rawAdvertisement: ByteArray): Advertisement {
        val parsed = AdvPayloadParser.parse(rawAdvertisement)
        return Advertisement(
            manufacturerId = parsed.manufacturerId,
            manufacturerData = parsed.manufacturerData,
            serviceUuids = parsed.serviceUuids?.map { ParcelUuid.fromString(it) },
            serviceData = parsed.serviceData,
        )
    }

    private suspend fun stored(id: Long): ScannedDevice = requireNotNull(repository.getDeviceById(id))

    private suspend fun observe(
        index: Int,
        timestamp: Long,
        input: Advertisement,
    ): Long {
        val identification =
            DeviceIdentifier.identifyDevice(
                manufacturerId = input.manufacturerId,
                manufacturerData = input.manufacturerData,
                serviceUuids = input.serviceUuids,
                appearance = null,
                deviceName = "Shared device",
                serviceData = input.serviceData,
            )
        val manufacturerInfo =
            input.manufacturerId?.let { ManufacturerDataParser.parseManufacturerData(it, input.manufacturerData) }
        val fingerprint =
            ManufacturerDataParser.extractBestFingerprint(
                manufacturerId = input.manufacturerId,
                manufacturerData = input.manufacturerData,
                serviceUuids = input.serviceUuids,
                serviceData = input.serviceData,
            )
        val id =
            repository.upsertDeviceWithFingerprint(
                address = address(index),
                name = "Shared device",
                advertisedName = "Shared device",
                lastSeen = timestamp,
                manufacturerData = input.manufacturerData,
                manufacturerId = identification.manufacturerId,
                manufacturerName = identification.manufacturerName,
                deviceType = identification.deviceType.name,
                deviceModel = identification.deviceModel,
                isTracker = identification.isTracker,
                serviceUuids = DeviceIdentifier.serviceUuidsToString(input.serviceUuids),
                appearance = null,
                txPowerLevel = -7,
                advertisingFlags = null,
                appleContinuityType = identification.appleContinuityType,
                identificationConfidence = identification.confidence,
                identificationMethod = identification.identificationMethod,
                payloadFingerprint = fingerprint,
                findMyStatus = manufacturerInfo?.findMyInfo?.statusByte,
                findMySeparated = manufacturerInfo?.findMyInfo?.separatedFromOwner ?: false,
                highestRssi = -55,
            )
        val locationId =
            database.locationDao().insert(
                Location(latitude = 37.0, longitude = -122.0, accuracy = 5f, timestamp = timestamp, provider = "TEST"),
            )
        repository.insertDeviceLocationRecord(id, locationId, -55, timestamp, false, null, "PERIODIC")
        return id
    }
}
