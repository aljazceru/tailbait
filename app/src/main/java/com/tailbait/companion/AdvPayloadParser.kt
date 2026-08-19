package com.tailbait.companion

/**
 * Parses a raw BLE advertisement payload (sequence of AD structures) coming
 * from the companion device into the pieces the existing identification
 * pipeline consumes (manufacturer data, service UUIDs, names).
 *
 * The companion forwards the raw adv bytes (NimBLE payload order); the phone
 * stays the brain and runs DeviceIdentifier / ManufacturerDataParser on it —
 * identical treatment to the phone's own scanner results.
 */
object AdvPayloadParser {
    /** AD structure types we care about. */
    private const val AD_FLAGS = 0x01
    private const val AD_UUID16_COMPLETE = 0x03
    private const val AD_UUID16_MORE = 0x02
    private const val AD_UUID128_COMPLETE = 0x07
    private const val AD_UUID128_MORE = 0x06
    private const val AD_NAME_COMPLETE = 0x09
    private const val AD_NAME_SHORT = 0x08
    private const val AD_MFG_DATA = 0xFF
    private const val AD_TX_POWER = 0x0A
    private const val AD_APPEARANCE = 0x19

    data class Parsed(
        val manufacturerId: Int?,
        val manufacturerData: ByteArray?,
        val serviceUuids: List<String>?,
        val name: String?,
        val txPowerLevel: Int?,
        val appearance: Int?,
        val advertisingFlags: Int?,
    )

    fun parse(adv: ByteArray): Parsed {
        var manufacturerId: Int? = null
        var manufacturerData: ByteArray? = null
        val uuids = ArrayList<String>()
        var name: String? = null
        var txPower: Int? = null
        var appearance: Int? = null
        var flags: Int? = null

        var i = 0
        while (i + 1 < adv.size) {
            val len = adv[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > adv.size) break
            val type = adv[i + 1].toInt() and 0xFF
            val dataStart = i + 2
            val dataEnd = i + 1 + len
            val data = adv.copyOfRange(dataStart, dataEnd)

            when (type) {
                AD_MFG_DATA ->
                    if (data.size >= 2) {
                        manufacturerId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                        manufacturerData = data.copyOfRange(2, data.size)
                    }
                AD_UUID16_COMPLETE, AD_UUID16_MORE -> {
                    var j = 0
                    while (j + 1 < data.size) {
                        val u = (data[j].toInt() and 0xFF) or ((data[j + 1].toInt() and 0xFF) shl 8)
                        uuids.add(uuid16ToString(u))
                        j += 2
                    }
                }
                AD_UUID128_COMPLETE, AD_UUID128_MORE -> {
                    var j = 0
                    while (j + 16 <= data.size) {
                        val b = data.copyOfRange(j, j + 16)
                        // BLE UUIDs are little-endian on air
                        b.reverse()
                        val hex = b.joinToString("") { "%02x".format(it) }
                        uuids.add(
                            hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
                                hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20),
                        )
                        j += 16
                    }
                }
                AD_NAME_COMPLETE, AD_NAME_SHORT -> name = data.toString(Charsets.UTF_8)
                AD_TX_POWER -> if (data.isNotEmpty()) txPower = data[0].toInt()
                AD_APPEARANCE ->
                    if (data.size >= 2) {
                        appearance = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    }
                AD_FLAGS -> if (data.isNotEmpty()) flags = data[0].toInt()
            }
            i = dataEnd
        }

        return Parsed(
            manufacturerId = manufacturerId,
            manufacturerData = manufacturerData,
            serviceUuids = if (uuids.isEmpty()) null else uuids,
            name = name,
            txPowerLevel = txPower,
            appearance = appearance,
            advertisingFlags = flags,
        )
    }

    private fun uuid16ToString(u: Int): String {
        val hex = "%04x".format(u)
        return "0000$hex-0000-1000-8000-00805f9b34fb"
    }
}
