package com.tailbait.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `tile FEEC gets tracker fingerprint for mac rotation correlation`() {
        val fingerprint =
            ManufacturerDataParser.extractServiceUuidFingerprint(
                manufacturerId = null,
                serviceUuids = listOf(parcel("0000FEEC-0000-1000-8000-00805F9B34FB")),
                manufacturerData = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            )
        assertTrue(fingerprint!!.startsWith("TL:"))
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
        assertEquals("Drone (Remote ID)", DeviceIdentifier.getDeviceModelFromServiceUuid(listOf(parcel("0000FFFA-0000-1000-8000-00805F9B34FB"))))
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
}
