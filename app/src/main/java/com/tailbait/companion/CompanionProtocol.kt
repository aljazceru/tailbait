package com.tailbait.companion

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol of the tailbait-companion ESP32 firmware (BLE GATT link).
 *
 * Service: a7f00001-e8a4-4b0e-a1c3-7461696c6261
 *  - DATA  (...0002, notify): TLV record stream, little-endian
 *  - CTRL  (...0003, write):  control commands
 *  - STAT  (...0004, read/notify): STATUS snapshots
 *
 * Records (see companion README for the authoritative spec):
 *  0x01 BLE_ADV     mac[6] rssi(i8) t(u32) advLen(u8) adv[]
 *  0x02 WIFI_PROBE  mac[6] rssi(i8) ch(u8) t(u32) ssidLen(u8) ssid[]
 *  0x03 WIFI_STA    mac[6] rssi(i8) ch(u8) t(u32)
 *  0x04 WIFI_BEACON mac[6] rssi(i8) ch(u8) t(u32) ssidLen(u8) ssid[] flags(u8)
 *  0x05 ALERT       score255(u8) places(u8) flags(u8) labelLen(u8) label[]
 *  0x10 STATUS      fwMaj fwMin mode subscribed tsync phase placeCnt devCnt
 *                   heap(u32) drops(u32) ble(u32) wifi(u32) cycle(u32)
 *                   dwell(u16) rsv(u16)
 *
 * `t` is epoch-ms once SET_TIME has been sent, otherwise device millis.
 */
object CompanionProtocol {
    const val SERVICE_UUID = "a7f00001-e8a4-4b0e-a1c3-7461696c6261"
    const val DATA_UUID = "a7f00002-e8a4-4b0e-a1c3-7461696c6261"
    const val CTRL_UUID = "a7f00003-e8a4-4b0e-a1c3-7461696c6261"
    const val STAT_UUID = "a7f00004-e8a4-4b0e-a1c3-7461696c6261"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val CTRL_SET_TIME = 0x01
    const val CTRL_SET_MODE = 0x02
    const val CTRL_FLUSH = 0x03
    const val CTRL_SET_CFG = 0x04
    const val CTRL_REQ_STATUS = 0x05

    const val MODE_SENTINEL = 0
    const val MODE_CARRY = 1

    /** Decoded companion records. */
    sealed class Record {
        abstract val timestamp: Long

        data class BleAdv(
            val mac: String,
            val rssi: Int,
            override val timestamp: Long,
            val adv: ByteArray,
        ) : Record()

        data class WifiProbe(
            val mac: String,
            val rssi: Int,
            val channel: Int,
            override val timestamp: Long,
            val ssid: String?,
        ) : Record()

        data class WifiSta(
            val mac: String,
            val rssi: Int,
            val channel: Int,
            override val timestamp: Long,
        ) : Record()

        /** Association/reassociation request: client joining `ssid`. */
        data class WifiAssoc(
            val mac: String,
            val rssi: Int,
            val channel: Int,
            override val timestamp: Long,
            val ssid: String?,
        ) : Record()

        data class WifiBeacon(
            val bssid: String,
            val rssi: Int,
            val channel: Int,
            override val timestamp: Long,
            val ssid: String?,
            val encrypted: Boolean,
        ) : Record()

        data class Alert(
            override val timestamp: Long,
            val label: String,
            val score: Float,
            val places: Int,
            val tracker: Boolean,
            val separated: Boolean,
            val randomizedMac: Boolean,
        ) : Record()

        data class Status(
            override val timestamp: Long,
            val firmware: String,
            val mode: Int,
            val phase: Int,
            val placeCount: Int,
            val deviceCount: Int,
            val heapFree: Long,
            val drops: Long,
            val bleCount: Long,
            val wifiCount: Long,
            val cycles: Long,
            val dwellMs: Int,
        ) : Record()
    }

    private fun mac(b: ByteBuffer): String {
        val m = ByteArray(6)
        b.get(m)
        return m.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Decode a DATA notification payload: one or more concatenated TLV records.
     * Malformed trailing bytes are ignored.
     */
    fun parseRecords(
        payload: ByteArray,
        receivedAt: Long = System.currentTimeMillis(),
    ): List<Record> {
        val out = ArrayList<Record>(2)
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= 2) {
            val type = b.get().toInt() and 0xFF
            val len = b.get().toInt() and 0xFF
            if (b.remaining() < len) break
            val start = b.position()
            try {
                decode(type, len, b, receivedAt)?.let { out.add(it) }
            } catch (_: Exception) {
                // malformed record — skip
            }
            b.position(start + len)
        }
        return out
    }

    private fun decode(
        type: Int,
        len: Int,
        b: ByteBuffer,
        now: Long,
    ): Record? {
        return when (type) {
            0x01 -> { // BLE_ADV
                if (len < 12) return null
                val macStr = mac(b)
                val rssi = b.get().toInt()
                val t = b.int.toLong() and 0xFFFFFFFFL
                val advLen = b.get().toInt() and 0xFF
                val adv = ByteArray(advLen)
                b.get(adv)
                Record.BleAdv(macStr, rssi, t, adv)
            }
            0x02 -> { // WIFI_PROBE
                if (len < 12) return null
                val macStr = mac(b)
                val rssi = b.get().toInt()
                val ch = b.get().toInt() and 0xFF
                val t = b.int.toLong() and 0xFFFFFFFFL
                val sl = b.get().toInt() and 0xFF
                val ssid = if (sl > 0) readString(b, sl) else null
                Record.WifiProbe(macStr, rssi, ch, t, ssid)
            }
            0x03 -> { // WIFI_STA
                if (len < 12) return null
                val macStr = mac(b)
                val rssi = b.get().toInt()
                val ch = b.get().toInt() and 0xFF
                val t = b.int.toLong() and 0xFFFFFFFFL
                Record.WifiSta(macStr, rssi, ch, t)
            }
            0x06 -> { // WIFI_ASSOC
                if (len < 12) return null
                val macStr = mac(b)
                val rssi = b.get().toInt()
                val ch = b.get().toInt() and 0xFF
                val t = b.int.toLong() and 0xFFFFFFFFL
                val sl = b.get().toInt() and 0xFF
                val ssid = if (sl > 0) readString(b, sl) else null
                Record.WifiAssoc(macStr, rssi, ch, t, ssid)
            }
            0x04 -> { // WIFI_BEACON
                if (len < 13) return null
                val macStr = mac(b)
                val rssi = b.get().toInt()
                val ch = b.get().toInt() and 0xFF
                val t = b.int.toLong() and 0xFFFFFFFFL
                val sl = b.get().toInt() and 0xFF
                val ssid = if (sl > 0) readString(b, sl) else null
                val flags = if (b.remaining() >= 1) b.get().toInt() and 0xFF else 0
                Record.WifiBeacon(macStr, rssi, ch, t, ssid, flags and 0x01 != 0)
            }
            0x05 -> { // ALERT
                if (len < 4) return null
                val score = (b.get().toInt() and 0xFF) / 255f
                val places = b.get().toInt() and 0xFF
                val flags = b.get().toInt() and 0xFF
                val ll = b.get().toInt() and 0xFF
                val label = if (ll > 0) readString(b, ll) else "?"
                Record.Alert(
                    now,
                    label,
                    score,
                    places,
                    flags and 0x01 != 0,
                    flags and 0x02 != 0,
                    flags and 0x04 != 0,
                )
            }
            0x10 -> { // STATUS
                if (len < 32) return null
                val fwMaj = b.get().toInt() and 0xFF
                val fwMin = b.get().toInt() and 0xFF
                val mode = b.get().toInt() and 0xFF
                b.get() // subscribed
                b.get() // tsync
                val phase = b.get().toInt() and 0xFF
                val placeCount = b.get().toInt() and 0xFF
                val deviceCount = b.get().toInt() and 0xFF
                val heap = b.int.toLong() and 0xFFFFFFFFL
                val drops = b.int.toLong() and 0xFFFFFFFFL
                val bleCount = b.int.toLong() and 0xFFFFFFFFL
                val wifiCount = b.int.toLong() and 0xFFFFFFFFL
                val cycles = b.int.toLong() and 0xFFFFFFFFL
                val dwell = b.short.toInt() and 0xFFFF
                Record.Status(
                    now, "$fwMaj.$fwMin", mode, phase, placeCount, deviceCount,
                    heap, drops, bleCount, wifiCount, cycles, dwell,
                )
            }
            else -> null
        }
    }

    private fun readString(
        b: ByteBuffer,
        len: Int,
    ): String {
        val bytes = ByteArray(len)
        b.get(bytes)
        // SSIDs are arbitrary bytes; replace non-printable for display safety
        return bytes.toString(Charsets.UTF_8).map { if (it.code in 32..126) it else '?' }.joinToString("")
    }

    fun setTimePayload(epochMs: Long): ByteArray {
        val p = ByteArray(9)
        p[0] = CTRL_SET_TIME.toByte()
        for (i in 0 until 8) p[i + 1] = (epochMs ushr (8 * i)).toByte()
        return p
    }

    fun setModePayload(mode: Int): ByteArray = byteArrayOf(CTRL_SET_MODE.toByte(), mode.toByte())

    fun setDwellPayload(ms: Int): ByteArray = byteArrayOf(CTRL_SET_CFG.toByte(), (ms and 0xFF).toByte(), ((ms shr 8) and 0xFF).toByte())

    fun reqStatusPayload(): ByteArray = byteArrayOf(CTRL_REQ_STATUS.toByte())
}
