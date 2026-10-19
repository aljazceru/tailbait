package com.tailbait.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Device signatures verified against the official Bluetooth SIG registry
 * (bitbucket.org/bluetooth-SIG/public):
 * Tile 0x067C + second UUID FEEC, Chipolo 0x08C3, Flipper Zero
 * (0x0E29 + 3081/3082/3083), OpenDroneID FFFA, Xuntong/Flock 0x09C8,
 * Raven 0x3100-0x3500.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BleSignatureVerificationTest {
    private fun parcel(uuid: String) = android.os.ParcelUuid(UUID.fromString(uuid))

    @Test
    fun `tile manufacturer id 0x067C classifies as tracker`() {
        val info = ManufacturerDataParser.parseManufacturerData(0x067C, byteArrayOf(0x01))!!
        assertEquals(ManufacturerDataParser.DeviceType.TRACKER, info.deviceType)
        assertEquals("Tile", info.deviceModel)
        assertTrue(info.isTracker)
    }

    @Test
    fun `old tile id 0x0099 is i-Tech not a tracker`() {
        val info = ManufacturerDataParser.parseManufacturerData(0x0099, byteArrayOf(0x01))!!
        // 0x0099 belongs to i.Tech Dynamic per the SIG registry — must NOT be Tile
        assertFalse(info.isTracker)
        assertFalse(info.deviceModel == "Tile")
    }

    @Test
    fun `chipolo manufacturer id 0x08C3 classifies as tracker`() {
        val info = ManufacturerDataParser.parseManufacturerData(0x08C3, byteArrayOf(0x01))!!
        assertEquals(ManufacturerDataParser.DeviceType.TRACKER, info.deviceType)
        assertTrue(info.isTracker)
    }

    @Test
    fun `espressif id 0x02E5 is not a tracker`() {
        // Regression: 0x02E5 was misattributed to Chipolo, marking every
        // Espressif-based device (dev boards, smart home gear) as a tracker
        val info = ManufacturerDataParser.parseManufacturerData(0x02E5, byteArrayOf(0x01))!!
        assertFalse(info.isTracker)
    }

    @Test
    fun `tile second service uuid FEEC detected as tracker via service uuid`() {
        assertTrue(DeviceIdentifier.isTrackerByServiceUuid(listOf(parcel("0000FEEC-0000-1000-8000-00805F9B34FB"))))
        assertEquals("Tile", DeviceIdentifier.getDeviceModelFromServiceUuid(listOf(parcel("0000FEEC-0000-1000-8000-00805F9B34FB"))))
        assertEquals(
            ManufacturerDataParser.DeviceType.TRACKER,
            DeviceIdentifier.getDeviceTypeFromServiceUuids(listOf(parcel("0000FEEC-0000-1000-8000-00805F9B34FB"))),
        )
    }

    @Test
    fun `service and manufacturer class profiles do not identify a physical device`() {
        val serviceUuids =
            listOf(
                "FD5A",
                "FEED",
                "FEEC",
                "FE8C",
                "FE8D",
                "FE8E",
                "FE9F",
                "FEA0",
                "FD6F",
                "FE2C",
                "FEAA",
                "3081",
                "FFFA",
            ).map { parcel("0000$it-0000-1000-8000-00805F9B34FB") } +
                parcel("FEED0001-C497-4476-A7ED-727DE7648AB1")
        for (manufacturerId in listOf(null, 0x0075, 0x067C, 0x08C3, 0x0E29)) {
            for (serviceUuid in serviceUuids) {
                assertNull(
                    ManufacturerDataParser.extractBestFingerprint(
                        manufacturerId = manufacturerId,
                        manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                        serviceUuids = listOf(serviceUuid),
                    ),
                )
            }
        }
    }

    @Test
    fun `flipper zero manufacturer id 0x0E29 classifies as pentest device`() {
        val info = ManufacturerDataParser.parseManufacturerData(0x0E29, byteArrayOf(0x01))!!
        assertEquals(ManufacturerDataParser.DeviceType.PENTEST_DEVICE, info.deviceType)
        assertEquals("Flipper Zero", info.deviceModel)
        assertFalse(info.isTracker)
    }

    @Test
    fun `flipper service uuids classify as pentest device`() {
        for (uuid in listOf("00003081-", "00003082-", "00003083-")) {
            assertEquals(
                ManufacturerDataParser.DeviceType.PENTEST_DEVICE,
                DeviceIdentifier.getDeviceTypeFromServiceUuids(listOf(parcel(uuid + "0000-1000-8000-00805F9B34FB"))),
            )
        }
        assertEquals("Flipper Zero", DeviceIdentifier.getDeviceModelFromServiceUuid(listOf(parcel("00003081-0000-1000-8000-00805F9B34FB"))))
    }

    @Test
    fun `flipper device name classifies as pentest device`() {
        val identification =
            DeviceIdentifier.identifyDevice(
                manufacturerId = null,
                manufacturerData = null,
                serviceUuids = null,
                appearance = null,
                deviceName = "Flipper vibes",
            )
        assertEquals(ManufacturerDataParser.DeviceType.PENTEST_DEVICE, identification.deviceType)
    }

    @Test
    fun `opendroneid service uuid FFFA classifies as drone`() {
        assertEquals(
            ManufacturerDataParser.DeviceType.DRONE,
            DeviceIdentifier.getDeviceTypeFromServiceUuids(listOf(parcel("0000FFFA-0000-1000-8000-00805F9B34FB"))),
        )
        assertEquals(
            "Drone (Remote ID)",
            DeviceIdentifier.getDeviceModelFromServiceUuid(listOf(parcel("0000FFFA-0000-1000-8000-00805F9B34FB"))),
        )
    }

    @Test
    fun `xuntong manufacturer id 0x09C8 classifies as flock surveillance`() {
        val info = ManufacturerDataParser.parseManufacturerData(0x09C8, byteArrayOf(0x01))!!
        assertEquals(ManufacturerDataParser.DeviceType.SURVEILLANCE, info.deviceType)
        assertTrue(info.manufacturerName.contains("Flock"))
    }

    @Test
    fun `raven uuid range 3100-3500 classifies as surveillance`() {
        assertEquals(
            ManufacturerDataParser.DeviceType.SURVEILLANCE,
            DeviceIdentifier.getDeviceTypeFromServiceUuids(listOf(parcel("00003100-0000-1000-8000-00805F9B34FB"))),
        )
        assertEquals(
            ManufacturerDataParser.DeviceType.SURVEILLANCE,
            DeviceIdentifier.getDeviceTypeFromServiceUuids(listOf(parcel("00003500-0000-1000-8000-00805F9B34FB"))),
        )
    }

    @Test
    fun `full Find My identity uses all 22 key bytes and excludes status and hints`() {
        val payload = fullFindMyFrame()
        val expected = "FM:808182838485868788898A8B8C8D8E8F909192939495"

        assertEquals(expected, ManufacturerDataParser.parseFindMyPayload(payload)!!.payloadFingerprint)
        val manufacturerInfo = ManufacturerDataParser.parseManufacturerData(0x004C, payload)!!
        assertTrue(manufacturerInfo.isTracker)
        assertEquals(expected, manufacturerInfo.payloadFingerprint)
        assertEquals(expected, ManufacturerDataParser.extractBestFingerprint(0x004C, payload, null))
        assertTrue(BeaconDetectionUtils.isFindMy(0x004C, payload))
        assertEquals(
            BeaconDetectionUtils.BeaconType.FIND_MY,
            BeaconDetectionUtils.detectAppleContinuity(0x004C, payload)!!.beaconType,
        )

        val changedLastKeyByte = payload.copyOf().apply { this[24] = 0xAA.toByte() }
        assertEquals(
            "FM:808182838485868788898A8B8C8D8E8F9091929394AA",
            ManufacturerDataParser.extractPayloadFingerprint(0x004C, changedLastKeyByte),
        )
        val changedMetadata =
            payload.copyOf().apply {
                this[2] = 0xC4.toByte()
                this[25] = 0x01
                this[26] = 0x7F
            }
        assertEquals(expected, ManufacturerDataParser.extractPayloadFingerprint(0x004C, changedMetadata))
        assertNull(ManufacturerDataParser.extractPayloadFingerprint(0x0075, payload))
        assertFalse(BeaconDetectionUtils.isFindMy(0x0075, payload))
    }

    @Test
    fun `short Find My frames expose status without synthesizing an identity`() {
        for (status in listOf(0x00, 0x04)) {
            val payload = byteArrayOf(0x12, 0x02, status.toByte(), 0x42)
            val findMy = ManufacturerDataParser.parseFindMyPayload(payload)!!
            assertEquals(status, findMy.statusByte)
            assertNull(findMy.payloadFingerprint)
            val manufacturerInfo = ManufacturerDataParser.parseManufacturerData(0x004C, payload)!!
            assertTrue(manufacturerInfo.isTracker)
            assertEquals(findMy, manufacturerInfo.findMyInfo)
            assertNull(manufacturerInfo.payloadFingerprint)
            assertNull(ManufacturerDataParser.extractPayloadFingerprint(0x004C, payload))
            assertNull(ManufacturerDataParser.extractBestFingerprint(0x004C, payload, null))
            assertTrue(BeaconDetectionUtils.isFindMy(0x004C, payload))
            assertEquals(
                BeaconDetectionUtils.BeaconType.FIND_MY,
                BeaconDetectionUtils.detectAppleContinuity(0x004C, payload)!!.beaconType,
            )
        }
    }

    @Test
    fun `Find My rejects every truncation and declared length mismatch`() {
        val full = fullFindMyFrame()
        val short = byteArrayOf(0x12, 0x02, 0x04, 0x00)
        for (frame in listOf(full, short)) {
            for (size in 0 until frame.size) {
                assertRejectedFindMy(frame.copyOf(size))
            }
            for (declaredLength in 0..255) {
                if (declaredLength == frame.size - 2) continue
                assertRejectedFindMy(frame.copyOf().apply { this[1] = declaredLength.toByte() })
            }
            assertRejectedFindMy(frame + byteArrayOf(0x00))
            assertRejectedFindMy(frame.copyOf().apply { this[0] = 0x13 })
        }
        for (declaredLength in 0..255) {
            if (declaredLength == 0x02 || declaredLength == 0x19) continue
            assertRejectedFindMy(
                ByteArray(declaredLength + 2).apply {
                    this[0] = 0x12
                    this[1] = declaredLength.toByte()
                },
            )
        }
    }

    @Test
    fun `maintained owner connection controls legacy separation field and battery meaning`() {
        val connectedStatuses =
            mapOf(
                0x04 to ManufacturerDataParser.FindMyInfo.BatteryLevel.FULL,
                0x44 to ManufacturerDataParser.FindMyInfo.BatteryLevel.MEDIUM,
                0x84 to ManufacturerDataParser.FindMyInfo.BatteryLevel.LOW,
                0xC4 to ManufacturerDataParser.FindMyInfo.BatteryLevel.CRITICAL,
            )
        for ((status, batteryLevel) in connectedStatuses) {
            for (payload in listOf(fullFindMyFrame(status), byteArrayOf(0x12, 0x02, status.toByte(), 0x00))) {
                val findMy = ManufacturerDataParser.parseFindMyPayload(payload)!!
                assertFalse(findMy.separatedFromOwner)
                assertEquals(batteryLevel, findMy.batteryLevel)
            }
        }
        for (status in listOf(0x00, 0x40, 0x80, 0xC0)) {
            for (payload in listOf(fullFindMyFrame(status), byteArrayOf(0x12, 0x02, status.toByte(), 0x00))) {
                val findMy = ManufacturerDataParser.parseFindMyPayload(payload)!!
                assertTrue(findMy.separatedFromOwner)
                assertEquals(ManufacturerDataParser.FindMyInfo.BatteryLevel.UNKNOWN, findMy.batteryLevel)
            }
        }
    }

    @Test
    fun `proximity pairing remains classified without a physical fingerprint`() {
        val payload = byteArrayOf(0x07, 0x19, 0x01, 0x0E, 0x20, 0x02, 0x03)
        val info = ManufacturerDataParser.parseManufacturerData(0x004C, payload)!!
        assertEquals(ManufacturerDataParser.DeviceType.EARBUDS, info.deviceType)
        assertFalse(info.isTracker)
        assertNull(info.payloadFingerprint)
        assertNull(ManufacturerDataParser.extractPayloadFingerprint(0x004C, payload))
        assertNull(ManufacturerDataParser.extractBestFingerprint(0x004C, payload, null))
    }

    @Test
    fun `Find Hub service data supplies only its full validated ephemeral identity`() {
        val findHubUuid = parcel("0000FEAA-0000-1000-8000-00805F9B34FB")
        for (keyLength in listOf(20, 32)) {
            val key = ByteArray(keyLength) { it.toByte() }
            val expected =
                if (keyLength == 20) {
                    "FHN:000102030405060708090A0B0C0D0E0F10111213"
                } else {
                    "FHN:000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"
                }
            val frame = byteArrayOf(0x41) + key + byteArrayOf(0xA5.toByte())
            assertEquals(
                expected,
                ManufacturerDataParser.extractBestFingerprint(
                    manufacturerId = null,
                    manufacturerData = null,
                    serviceUuids = null,
                    serviceData = mapOf(findHubUuid to frame),
                ),
            )
            assertEquals(
                expected,
                ManufacturerDataParser.extractBestFingerprint(
                    manufacturerId = 0x004C,
                    manufacturerData = byteArrayOf(0x12, 0x02, 0x04, 0x00),
                    serviceUuids = listOf(findHubUuid),
                    serviceData = mapOf(findHubUuid to frame),
                ),
            )
            assertNull(
                ManufacturerDataParser.extractBestFingerprint(
                    manufacturerId = null,
                    manufacturerData = null,
                    serviceUuids = listOf(findHubUuid),
                    serviceData = mapOf(findHubUuid to frame.copyOf(frame.size - 1)),
                ),
            )
        }
        assertNull(ManufacturerDataParser.extractBestFingerprint(null, null, listOf(findHubUuid)))
    }

    private fun fullFindMyFrame(status: Int = 0x04): ByteArray =
        byteArrayOf(0x12, 0x19, status.toByte()) +
            ByteArray(22) { (0x80 + it).toByte() } +
            byteArrayOf(0x03, 0x55)

    private fun assertRejectedFindMy(payload: ByteArray) {
        assertNull(ManufacturerDataParser.parseFindMyPayload(payload))
        val info = ManufacturerDataParser.parseManufacturerData(0x004C, payload)!!
        assertFalse(info.isTracker)
        assertNull(info.findMyInfo)
        assertNull(info.payloadFingerprint)
        assertNull(ManufacturerDataParser.extractPayloadFingerprint(0x004C, payload))
        assertNull(ManufacturerDataParser.extractBestFingerprint(0x004C, payload, null))
        assertFalse(BeaconDetectionUtils.isFindMy(0x004C, payload))
        assertNull(BeaconDetectionUtils.detectAppleContinuity(0x004C, payload))
    }
}
