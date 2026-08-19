package com.tailbait.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM tests for the companion TLV protocol parser and the raw BLE adv
 * payload parser — mirrors the firmware record formats byte-for-byte.
 */
class CompanionProtocolTest {
    private fun tlv(
        type: Int,
        vararg fields: ByteArray,
    ): ByteArray {
        val payload = fields.fold(ByteArray(0)) { acc, f -> acc + f }
        return byteArrayOf(type.toByte(), payload.size.toByte()) + payload
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte())

    private fun u32(v: Long) =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )

    private fun mac(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test
    fun `wifi beacon with privacy flag parses`() {
        val rec =
            tlv(
                0x04,
                mac(0xC4, 0xE9, 0x84, 0x0A, 0x35, 0x4C),
                byteArrayOf((-30).toByte()),
                byteArrayOf(6),
                u32(291531888L),
                byteArrayOf(14),
                "TB-HOTSPOT-E2E".toByteArray(),
                byteArrayOf(1), // privacy bit set
            )
        val out = CompanionProtocol.parseRecords(rec)
        assertEquals(1, out.size)
        val beacon = out[0] as CompanionProtocol.Record.WifiBeacon
        assertEquals("C4:E9:84:0A:35:4C", beacon.bssid)
        assertEquals(-30, beacon.rssi)
        assertEquals(6, beacon.channel)
        assertEquals(291531888L, beacon.timestamp)
        assertEquals("TB-HOTSPOT-E2E", beacon.ssid)
        assertTrue(beacon.encrypted)
    }

    @Test
    fun `wifi assoc record parses with ssid`() {
        val rec =
            tlv(
                0x06,
                mac(0x1A, 0x90, 0xB6, 0xE8, 0x68, 0x09),
                byteArrayOf((-46).toByte()),
                byteArrayOf(6),
                u32(292312984L),
                byteArrayOf(14),
                "TB-HOTSPOT-E2E".toByteArray(),
            )
        val out = CompanionProtocol.parseRecords(rec)
        val assoc = out[0] as CompanionProtocol.Record.WifiAssoc
        assertEquals("1A:90:B6:E8:68:09", assoc.mac)
        assertEquals("TB-HOTSPOT-E2E", assoc.ssid)
        assertEquals(6, assoc.channel)
    }

    @Test
    fun `multiple records in one notification parse`() {
        val sta = tlv(0x03, mac(1, 2, 3, 4, 5, 6), byteArrayOf((-80).toByte()), byteArrayOf(11), u32(1000))
        val probe = tlv(0x02, mac(7, 8, 9, 10, 11, 12), byteArrayOf((-70).toByte()), byteArrayOf(3), u32(2000), byteArrayOf(0))
        val out = CompanionProtocol.parseRecords(sta + probe)
        assertEquals(2, out.size)
        assertEquals(1000L, (out[0] as CompanionProtocol.Record.WifiSta).timestamp)
        assertEquals(2000L, (out[1] as CompanionProtocol.Record.WifiProbe).timestamp)
        assertNull((out[1] as CompanionProtocol.Record.WifiProbe).ssid)
    }

    @Test
    fun `truncated trailing record is ignored`() {
        val good = tlv(0x03, mac(1, 2, 3, 4, 5, 6), byteArrayOf((-80).toByte()), byteArrayOf(11), u32(1))
        val bad = byteArrayOf(0x02, 20, 1, 2, 3) // claims 20 bytes, has 3
        val out = CompanionProtocol.parseRecords(good + bad)
        assertEquals(1, out.size)
    }

    @Test
    fun `control payloads encode`() {
        val t = CompanionProtocol.setTimePayload(1786984077639L)
        assertEquals(9, t.size)
        assertEquals(0x01, t[0].toInt())
        val ms = ByteBuffer.wrap(t, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(1786984077639L, ms)
        assertArrayEquals(byteArrayOf(2.toByte(), 1.toByte()), CompanionProtocol.setModePayload(CompanionProtocol.MODE_CARRY))
    }

    @Test
    fun `ble adv record keeps raw payload`() {
        val adv = byteArrayOf(0x02, 0x01, 0x06, 0x03, 0x03, 0x5A.toByte(), 0xFD.toByte())
        val rec =
            tlv(
                0x01,
                mac(0x87, 0xC1, 0xF5, 0xB3, 0x58, 0xE2),
                byteArrayOf((-35).toByte()),
                u32(286597979L),
                byteArrayOf(adv.size.toByte()),
                adv,
            )
        val out = CompanionProtocol.parseRecords(rec)
        val ble = out[0] as CompanionProtocol.Record.BleAdv
        assertArrayEquals(adv, ble.adv)
    }

    @Test
    fun `adv payload parser extracts find-my style manufacturer data`() {
        // ADV: flags + manufacturer 0x004C with Find My payload
        val md =
            byteArrayOf(
                0x4C, 0x00, 0x12, 0x19, 0x04, 0x31, 0x16, 0x7B, 0x4C,
            ) + byteArrayOf(0xB6.toByte(), 0xC8.toByte())
        val adv =
            byteArrayOf(
                0x02, 0x01, 0x06,
                (md.size + 1).toByte(), 0xFF.toByte(),
            ) + md
        val parsed = AdvPayloadParser.parse(adv)
        assertEquals(0x004C, parsed.manufacturerId)
        assertEquals(9, parsed.manufacturerData?.size)
        val fm =
            com.tailbait.util.ManufacturerDataParser.parseManufacturerData(
                parsed.manufacturerId!!,
                parsed.manufacturerData!!,
            )
        assertTrue(fm?.findMyInfo?.separatedFromOwner == true)
    }

    @Test
    fun `adv payload parser extracts name and 16-bit uuids`() {
        val adv =
            byteArrayOf(
                0x02, 0x01, 0x06,
                0x03, 0x03, 0x5A.toByte(), 0xFD.toByte(), // SmartTag UUID FD5A
                0x05, 0x09, 0x54, 0x54, 0x47, 0x4F, // "TTGO"
            )
        val parsed = AdvPayloadParser.parse(adv)
        assertEquals("TTGO", parsed.name)
        assertEquals(1, parsed.serviceUuids?.size)
        assertTrue(parsed.serviceUuids!!.any { it.endsWith("fd5a-0000-1000-8000-00805f9b34fb") })
    }

    private fun assertArrayEquals(
        a: ByteArray,
        b: ByteArray,
    ) {
        assertTrue(a.contentEquals(b))
    }

    private fun assertFalse(v: Boolean) = org.junit.Assert.assertFalse(v)
}
