package com.tailbait.algorithm

import android.os.ParcelUuid
import com.tailbait.algorithm.TrackerServiceDetector.TrackerType
import com.tailbait.companion.AdvPayloadParser
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.ManufacturerDataParser
import no.nordicsemi.android.kotlin.ble.core.mapper.ScanRecordSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackerClassificationTest {
    private val findHubService = parcel("0000FEAA-0000-1000-8000-00805F9B34FB")
    private val eid160 = ByteArray(20) { it.toByte() }
    private val eid256 = ByteArray(32) { (0x80 + it).toByte() }
    private val fingerprint160 = "FHN:000102030405060708090A0B0C0D0E0F10111213"
    private val fingerprint256 = "FHN:808182838485868788898A8B8C8D8E8F909192939495969798999A9B9C9D9E9F"
    private val supportedServices =
        listOf(
            parcel("0000FD5A-0000-1000-8000-00805F9B34FB") to TrackerType.SAMSUNG_SMARTTAG,
            parcel("0000FEED-0000-1000-8000-00805F9B34FB") to TrackerType.TILE,
            parcel("0000FEEC-0000-1000-8000-00805F9B34FB") to TrackerType.TILE,
            parcel("FEED0001-C497-4476-A7ED-727DE7648AB1") to TrackerType.TILE,
        )

    @Test
    fun `find hub accepts both complete eid lengths and excludes hashed flags from identity`() {
        for ((eid, expected) in listOf(eid160 to fingerprint160, eid256 to fingerprint256)) {
            for ((frame, flags) in listOf(0x40 to null, 0x40 to 0x00, 0x40 to 0xFF, 0x41 to 0x00, 0x41 to 0xFF)) {
                val payload = byteArrayOf(frame.toByte()) + eid + (flags?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf())
                val serviceData = mapOf(findHubService to payload)
                assertEquals(expected, TrackerServiceDetector.findHubFingerprint(serviceData))
                val detected = TrackerServiceDetector.detectTracker(null, serviceData)
                assertTrue(detected.isTracker)
                assertEquals(TrackerType.GOOGLE_FIND_MY_DEVICE, detected.trackerType)
                assertEquals(findHubService.uuid, detected.detectedServiceUuid)
                assertTrue(identify(null, serviceData).isTracker)
            }
        }
        assertNull(TrackerServiceDetector.getTrackerType(findHubService.uuid))
    }

    @Test
    fun `find hub rejects truncated trailing and unknown frame data`() {
        for (frame in listOf(0x40, 0x41)) {
            val permittedLengths = if (frame == 0x40) setOf(21, 22, 33, 34) else setOf(22, 34)
            for (size in 0..36) {
                if (size in permittedLengths) continue
                val payload = ByteArray(size).also { if (it.isNotEmpty()) it[0] = frame.toByte() }
                assertNotTracker(findHubService, payload)
            }
        }
        // Eddystone UID, URL, TLM and EID frames share FEAA but are not Find Hub.
        for (frame in listOf(0x00, 0x10, 0x20, 0x30, 0x42, 0xFF)) {
            for (size in listOf(21, 22, 33, 34)) {
                assertNotTracker(findHubService, ByteArray(size).also { it[0] = frame.toByte() })
            }
        }
    }

    @Test
    fun `unrelated assigned services and fast pair are not tracker evidence`() {
        for (shortUuid in listOf("FD6F", "FE2C", "FEAA", "FE8C", "FE8D", "FE8E", "FE9F", "FEA0")) {
            val service = parcel("0000$shortUuid-0000-1000-8000-00805F9B34FB")
            assertFalse(TrackerServiceDetector.isTracker(listOf(service)))
            assertFalse(TrackerServiceDetector.detectTracker(listOf(service)).isTracker)
            assertFalse(identify(listOf(service)).isTracker)
            assertNull(TrackerServiceDetector.getTrackerType(service.uuid))
            if (service != findHubService) {
                assertNotTracker(service, byteArrayOf(0x40) + eid160)
            }
        }
    }

    @Test
    fun `uuid lookalikes do not match a tracker class or find hub identity`() {
        for (uuid in listOf(
            "1234FD5A-0000-1000-8000-00805F9B34FB",
            "1234FEED-0000-1000-8000-00805F9B34FB",
            "1234FEEC-0000-1000-8000-00805F9B34FB",
            "1234FEAA-0000-1000-8000-00805F9B34FB",
            "0000FD5A-1111-1000-8000-00805F9B34FB",
            "0000FEAA-1111-1000-8000-00805F9B34FB",
            "FEED0001-C497-4476-A7ED-727DE7648AB2",
        )) {
            val service = parcel(uuid)
            assertNotTracker(service, byteArrayOf(0x40) + eid160)
            assertNull(DeviceIdentifier.getDeviceModelFromServiceUuid(listOf(service)))
            assertNull(DeviceIdentifier.getDeviceTypeFromServiceUuids(listOf(service)))
            assertEquals(uuid, DeviceIdentifier.serviceUuidsToString(listOf(service)))
        }
    }

    @Test
    fun `supported tracker services remain detectable without manufacturer or uuid list`() {
        val genericService = parcel("0000180D-0000-1000-8000-00805F9B34FB")
        for ((service, expectedType) in supportedServices) {
            val services = listOf(genericService, service)
            val expectedModel = if (expectedType == TrackerType.TILE) "Tile" else "Samsung SmartTag"
            assertEquals(expectedType, TrackerServiceDetector.detectTracker(services).trackerType)
            assertEquals(expectedType, TrackerServiceDetector.detectTracker(null, mapOf(service to byteArrayOf())).trackerType)
            assertTrue(DeviceIdentifier.isTrackerByServiceUuid(services))
            assertEquals(expectedModel, DeviceIdentifier.getDeviceModelFromServiceUuid(services))
            assertEquals(ManufacturerDataParser.DeviceType.TRACKER, DeviceIdentifier.getDeviceTypeFromServiceUuids(services))
            assertEquals(expectedModel, identify(null, mapOf(service to byteArrayOf())).deviceModel)
            if (expectedType == TrackerType.TILE) assertTrue(TrackerServiceDetector.isTile(services))
        }
    }

    @Test
    fun `specific service classification beats missing and negative manufacturer results`() {
        val manufacturers =
            listOf(
                null to null,
                ManufacturerDataParser.ManufacturerId.APPLE to null,
                ManufacturerDataParser.ManufacturerId.APPLE to byteArrayOf(0x10, 0x00, 0x00),
                ManufacturerDataParser.ManufacturerId.SAMSUNG to byteArrayOf(0x01),
                ManufacturerDataParser.ManufacturerId.GOOGLE to byteArrayOf(0x01),
                ManufacturerDataParser.ManufacturerId.TILE to byteArrayOf(0x01),
                0xFFFF to byteArrayOf(0x01),
            )
        for ((service, expectedType) in supportedServices + (findHubService to TrackerType.GOOGLE_FIND_MY_DEVICE)) {
            val serviceData = if (service == findHubService) mapOf(service to (byteArrayOf(0x40) + eid160)) else emptyMap()
            val expected = identify(listOf(service), serviceData)
            for ((manufacturerId, manufacturerData) in manufacturers) {
                val actual = identify(listOf(service), serviceData, manufacturerId, manufacturerData)
                assertTrue(actual.isTracker)
                assertEquals(ManufacturerDataParser.DeviceType.TRACKER, actual.deviceType)
                assertEquals(expected.deviceModel, actual.deviceModel)
                assertEquals(expected.manufacturerName, actual.manufacturerName)
                assertEquals(expected.identificationMethod, actual.identificationMethod)
                val analysis =
                    TrackerAnalyzerFactory.analyze(
                        manufacturerId = manufacturerId,
                        manufacturerData = manufacturerData,
                        serviceUuids = listOf(service),
                        rssi = -60,
                        deviceName = null,
                        serviceData = serviceData,
                    )
                assertNotNull(analysis)
                assertTrue(analysis!!.isTracker)
                assertEquals(expectedType, analysis.trackerType)
            }
        }
    }

    @Test
    fun `apple analyzer requires the shared validated find my frame`() {
        val malformed =
            listOf(
                byteArrayOf(0x12),
                byteArrayOf(0x12, 0x19, 0x04),
                byteArrayOf(0x12, 0x19, 0x04, 0x00),
                byteArrayOf(0x12, 0x02, 0x04) + ByteArray(24),
            )
        for (payload in malformed) {
            assertNull(TrackerAnalyzerFactory.analyze(ManufacturerDataParser.ManufacturerId.APPLE, payload, null, -60, null))
            assertFalse(identify(null, manufacturerId = ManufacturerDataParser.ManufacturerId.APPLE, manufacturerData = payload).isTracker)
        }
        val shortStatus = byteArrayOf(0x12, 0x02, 0x04, 0x00)
        val fullFrame = byteArrayOf(0x12, 0x19, 0x04) + ByteArray(24) { it.toByte() }
        for (payload in listOf(shortStatus, fullFrame)) {
            val analysis = TrackerAnalyzerFactory.analyze(ManufacturerDataParser.ManufacturerId.APPLE, payload, null, -60, null)
            assertTrue(analysis!!.isTracker)
            assertTrue(identify(null, manufacturerId = ManufacturerDataParser.ManufacturerId.APPLE, manufacturerData = payload).isTracker)
            if (payload === shortStatus) assertFalse(analysis.additionalInfo.containsKey("fingerprint"))
        }
    }

    @Test
    fun `phone and companion advertisement representations produce identical classification and identity`() {
        val fixtures =
            listOf(
                Triple(serviceData16(0xFEAA, byteArrayOf(0x40) + eid160), true, fingerprint160),
                Triple(serviceData16(0xFEAA, byteArrayOf(0x41) + eid256 + byteArrayOf(0xFF.toByte())), true, fingerprint256),
                Triple(serviceData16(0xFEAA, byteArrayOf(0x41) + eid160), false, null),
                Triple(serviceData16(0xFEAA, byteArrayOf(0x40) + eid160.copyOf(19)), false, null),
                Triple(serviceData16(0xFEAA, byteArrayOf(0x00) + ByteArray(19)), false, null),
                Triple(serviceData16(0xFE2C, byteArrayOf(0x40) + eid160), false, null),
                Triple(ad(0x03, byteArrayOf(0x6F, 0xFD.toByte())), false, null),
                Triple(ad(0x03, byteArrayOf(0x5A, 0xFD.toByte())), true, null),
                Triple(ad(0x03, byteArrayOf(0xEC.toByte(), 0xFE.toByte())), true, null),
                Triple(ad(0x20, byteArrayOf(0xAA.toByte(), 0xFE.toByte(), 0x34, 0x12, 0x40) + eid160), false, null),
            )
        for ((data, isTracker, fingerprint) in fixtures) {
            for (manufacturer in listOf(byteArrayOf(), ad(0xFF, byteArrayOf(0x4C, 0x00, 0x10, 0x00, 0x00)))) {
                val raw = ad(0x01, byteArrayOf(0x06)) + data + manufacturer
                val phone = requireNotNull(ScanRecordSerializer.parseFromBytes(raw))
                val phoneManufacturer = phone.manufacturerSpecificData.takeIf { it.size() > 0 }
                val phoneServiceData = phone.serviceData.mapValues { it.value.value }
                val phoneIdentification =
                    identify(
                        serviceUuids = phone.serviceUuids,
                        serviceData = phoneServiceData,
                        manufacturerId = phoneManufacturer?.keyAt(0),
                        manufacturerData = phoneManufacturer?.valueAt(0)?.value,
                    )
                val companion = AdvPayloadParser.parse(raw)
                val companionIdentification =
                    identify(
                        serviceUuids = companion.serviceUuids?.map { parcel(it) },
                        serviceData = companion.serviceData,
                        manufacturerId = companion.manufacturerId,
                        manufacturerData = companion.manufacturerData,
                    )
                assertEquals(isTracker, phoneIdentification.isTracker)
                assertEquals(phoneIdentification, companionIdentification)
                assertEquals(fingerprint, TrackerServiceDetector.findHubFingerprint(phoneServiceData))
                assertEquals(fingerprint, TrackerServiceDetector.findHubFingerprint(companion.serviceData))
            }
        }
    }

    private fun assertNotTracker(
        service: ParcelUuid,
        payload: ByteArray,
    ) {
        val data = mapOf(service to payload)
        assertNull(TrackerServiceDetector.findHubFingerprint(data))
        assertFalse(TrackerServiceDetector.detectTracker(listOf(service), data).isTracker)
        assertFalse(identify(listOf(service), data).isTracker)
        assertNull(TrackerAnalyzerFactory.analyze(null, null, listOf(service), -60, null, data))
    }

    private fun identify(
        serviceUuids: List<ParcelUuid>?,
        serviceData: Map<ParcelUuid, ByteArray> = emptyMap(),
        manufacturerId: Int? = null,
        manufacturerData: ByteArray? = null,
    ) = DeviceIdentifier.identifyDevice(manufacturerId, manufacturerData, serviceUuids, null, null, serviceData)

    private fun parcel(uuid: String) = ParcelUuid(UUID.fromString(uuid))

    private fun serviceData16(
        uuid: Int,
        payload: ByteArray,
    ) = ad(0x16, byteArrayOf(uuid.toByte(), (uuid ushr 8).toByte()) + payload)

    private fun ad(
        type: Int,
        data: ByteArray,
    ) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data
}
