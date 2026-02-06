package com.tailbait.util

import timber.log.Timber

/**
 * Comprehensive BLE manufacturer data parser for device identification.
 *
 * Parses BLE advertisement manufacturer-specific data to extract:
 * - Manufacturer identity (from Bluetooth SIG assigned company identifiers)
 * - Device type classification (phone, watch, tracker, etc.)
 * - Device model identification (AirTag, AirPods Pro, SmartTag, etc.)
 * - Apple Continuity protocol message types
 *
 * ## Key Detection Capabilities
 * - **AirTag Detection**: Identifies Find My network broadcasts (type 0x12)
 * - **AirPods Detection**: Identifies Proximity Pairing messages (type 0x07)
 * - **SmartTag Detection**: Via Samsung manufacturer ID patterns
 * - **Tile/Chipolo**: Direct manufacturer ID matching
 *
 * ## References
 * - Bluetooth SIG Assigned Numbers: https://www.bluetooth.com/specifications/assigned-numbers/
 * - Apple Continuity Protocol: https://github.com/furiousMAC/continuity
 * - Samsung SmartTag: https://arxiv.org/pdf/2210.14702
 */
object ManufacturerDataParser {

    // ============================================================================
    // MANUFACTURER IDS (Bluetooth SIG Company Identifiers)
    // Source: https://www.bluetooth.com/specifications/assigned-numbers/
    // ============================================================================

    object ManufacturerId {
        // Major Tech Companies
        const val APPLE = 0x004C
        const val SAMSUNG = 0x0075
        const val GOOGLE = 0x00E0
        const val MICROSOFT = 0x0006
        const val SONY = 0x012D
        const val LG = 0x00C7
        const val HUAWEI = 0x0270
        const val XIAOMI = 0x038F
        const val OPPO = 0x05A7
        const val ONEPLUS = 0x05E1
        const val REALME = 0x05F7
        const val VIVO = 0x05D6
        const val MOTOROLA = 0x00DB
        const val NOKIA = 0x0001
        const val HTC = 0x000A

        // Tracker Manufacturers (CRITICAL for stalking detection)
        const val TILE = 0x0099
        const val CHIPOLO = 0x02E5
        const val PEBBLEBEE = 0x0636
        const val CUBE = 0x05B8

        // Wearables & Fitness
        const val FITBIT = 0x0224
        const val GARMIN = 0x0087
        const val POLAR = 0x006B
        const val SUUNTO = 0x004F
        const val WHOOP = 0x0614
        const val OURA = 0x04C4

        // Audio Manufacturers
        const val BOSE = 0x009E
        const val JABRA = 0x0067  // GN Audio
        const val SENNHEISER = 0x0082
        const val BANG_OLUFSEN = 0x0057
        const val SKULLCANDY = 0x03B4
        const val JBL = 0x0057    // Harman
        const val BEATS = APPLE   // Beats uses Apple ID
        const val SONY_AUDIO = SONY

        // Smart Home
        const val PHILIPS = 0x000C
        const val IKEA = 0x0550
        const val AMAZON = 0x0171

        // Automotive
        const val TESLA = 0x05F7
        const val BMW = 0x04E1
        const val MERCEDES = 0x0495

        // Other
        const val NORDIC = 0x0059
        const val ESPRESSIF = 0x02E5
        const val QUALCOMM = 0x000A
        const val BROADCOM = 0x000F
        const val TEXAS_INSTRUMENTS = 0x000D
        const val INTERPLAN = 0x0212  // Interplan Co., Ltd (Japan)
        const val RENESAS = 0x00E3    // Renesas Electronics
        const val CYPRESS = 0x0131    // Cypress Semiconductor
        const val DIALOG = 0x00D2     // Dialog Semiconductor
        const val SILABS = 0x02FF     // Silicon Labs
        const val REALTEK = 0x005D    // Realtek Semiconductor
        const val MEDIATEK = 0x00A5   // MediaTek
        const val LENOVO = 0x0640     // Lenovo
        const val ASUS = 0x04C5       // ASUSTek Computer
        const val ACER = 0x0491       // Acer
        const val DELL = 0x0089       // Dell
        const val HP = 0x000B         // HP
    }

    /**
     * Map of manufacturer IDs to human-readable names.
     */
    private val manufacturerNames = mapOf(
        ManufacturerId.APPLE to "Apple",
        ManufacturerId.SAMSUNG to "Samsung",
        ManufacturerId.GOOGLE to "Google",
        ManufacturerId.MICROSOFT to "Microsoft",
        ManufacturerId.SONY to "Sony",
        ManufacturerId.LG to "LG",
        ManufacturerId.HUAWEI to "Huawei",
        ManufacturerId.XIAOMI to "Xiaomi",
        ManufacturerId.OPPO to "OPPO",
        ManufacturerId.ONEPLUS to "OnePlus",
        ManufacturerId.REALME to "Realme",
        ManufacturerId.VIVO to "Vivo",
        ManufacturerId.MOTOROLA to "Motorola",
        ManufacturerId.NOKIA to "Nokia",
        ManufacturerId.HTC to "HTC",
        ManufacturerId.TILE to "Tile",
        ManufacturerId.CHIPOLO to "Chipolo",
        ManufacturerId.PEBBLEBEE to "Pebblebee",
        ManufacturerId.CUBE to "Cube",
        ManufacturerId.FITBIT to "Fitbit",
        ManufacturerId.GARMIN to "Garmin",
        ManufacturerId.POLAR to "Polar",
        ManufacturerId.SUUNTO to "Suunto",
        ManufacturerId.WHOOP to "Whoop",
        ManufacturerId.OURA to "Oura",
        ManufacturerId.BOSE to "Bose",
        ManufacturerId.JABRA to "Jabra",
        ManufacturerId.SENNHEISER to "Sennheiser",
        ManufacturerId.BANG_OLUFSEN to "Bang & Olufsen",
        ManufacturerId.SKULLCANDY to "Skullcandy",
        ManufacturerId.PHILIPS to "Philips",
        ManufacturerId.IKEA to "IKEA",
        ManufacturerId.AMAZON to "Amazon",
        ManufacturerId.NORDIC to "Nordic Semiconductor",
        ManufacturerId.INTERPLAN to "Interplan",
        ManufacturerId.RENESAS to "Renesas",
        ManufacturerId.CYPRESS to "Cypress",
        ManufacturerId.DIALOG to "Dialog",
        ManufacturerId.SILABS to "Silicon Labs",
        ManufacturerId.REALTEK to "Realtek",
        ManufacturerId.MEDIATEK to "MediaTek",
        ManufacturerId.LENOVO to "Lenovo",
        ManufacturerId.ASUS to "ASUS",
        ManufacturerId.ACER to "Acer",
        ManufacturerId.DELL to "Dell",
        ManufacturerId.HP to "HP",
    )

    // ============================================================================
    // APPLE CONTINUITY PROTOCOL TYPES
    // Source: https://github.com/furiousMAC/continuity
    // ============================================================================

    object AppleContinuityType {
        const val AIRPRINT = 0x03
        const val AIRDROP = 0x05
        const val HOMEKIT = 0x06
        const val PROXIMITY_PAIRING = 0x07  // AirPods, Beats
        const val HEY_SIRI = 0x08
        const val AIRPLAY_TARGET = 0x09
        const val AIRPLAY_SOURCE = 0x0A
        const val MAGIC_SWITCH = 0x0B       // Apple Watch disconnection
        const val HANDOFF = 0x0C            // Cross-device handoff
        const val TETHERING_TARGET = 0x0D
        const val TETHERING_SOURCE = 0x0E
        const val NEARBY_ACTION = 0x0F
        const val NEARBY_INFO = 0x10        // iPhone broadcasting status
        const val FIND_MY = 0x12            // AirTag, Find My network! CRITICAL!

        // iBeacon uses different format (0x02 0x15 prefix)
        const val IBEACON_TYPE = 0x02
        const val IBEACON_LENGTH = 0x15
    }

    // ============================================================================
    // DEVICE TYPE CLASSIFICATION
    // ============================================================================

    /**
     * Device type categories for BLE devices.
     */
    enum class DeviceType {
        PHONE,
        TABLET,
        COMPUTER,
        WATCH,
        HEADPHONES,
        EARBUDS,
        SPEAKER,
        TRACKER,       // AirTag, Tile, SmartTag, Chipolo, etc.
        BEACON,
        FITNESS_BAND,
        SMART_HOME,
        AUTOMOTIVE,
        GAMING,
        MEDICAL,
        UNKNOWN;

        companion object {
            fun fromString(value: String?): DeviceType {
                return values().find { it.name == value } ?: UNKNOWN
            }
        }
    }

    // ============================================================================
    // APPLE DEVICE MODELS
    // Source: Reverse engineering and https://github.com/furiousMAC/continuity
    // ============================================================================

    object AppleDeviceModel {
        // AirPods models (from Proximity Pairing payload)
        const val AIRPODS_1 = 0x0220
        const val AIRPODS_2 = 0x0F20
        const val AIRPODS_3 = 0x1420
        const val AIRPODS_PRO = 0x0E20
        const val AIRPODS_PRO_2 = 0x1420
        const val AIRPODS_MAX = 0x0A20
        const val POWERBEATS_PRO = 0x0B20
        const val BEATS_SOLO_PRO = 0x0C20
        const val BEATS_STUDIO_BUDS = 0x1120
        const val BEATS_FIT_PRO = 0x1220
    }

    private val airpodsModelNames = mapOf(
        AppleDeviceModel.AIRPODS_1 to "AirPods (1st gen)",
        AppleDeviceModel.AIRPODS_2 to "AirPods (2nd gen)",
        AppleDeviceModel.AIRPODS_3 to "AirPods (3rd gen)",
        AppleDeviceModel.AIRPODS_PRO to "AirPods Pro",
        AppleDeviceModel.AIRPODS_PRO_2 to "AirPods Pro (2nd gen)",
        AppleDeviceModel.AIRPODS_MAX to "AirPods Max",
        AppleDeviceModel.POWERBEATS_PRO to "Powerbeats Pro",
        AppleDeviceModel.BEATS_SOLO_PRO to "Beats Solo Pro",
        AppleDeviceModel.BEATS_STUDIO_BUDS to "Beats Studio Buds",
        AppleDeviceModel.BEATS_FIT_PRO to "Beats Fit Pro",
    )

    // ============================================================================
    // MAIN PARSING FUNCTIONS
    // ============================================================================

    /**
     * Parse manufacturer data to extract comprehensive device information.
     *
     * @param manufacturerData Raw manufacturer data as byte array
     * @return Parsed manufacturer info or null if data is invalid
     */
    /**
     * Parse manufacturer data when the ID is already extracted (e.g., from sparse array key).
     *
     * Use this version when using Nordic BLE library where the manufacturer ID
     * is the sparse array key and the data bytes are the payload only.
     *
     * @param manufacturerId The Bluetooth SIG company identifier
     * @param payload The manufacturer-specific data (WITHOUT the 2-byte ID prefix)
     * @return Parsed manufacturer info or null
     */
    fun parseManufacturerData(manufacturerId: Int, payload: ByteArray?): ManufacturerInfo? {
        return try {
            val safePayload = payload ?: byteArrayOf()
            val manufacturerName = getManufacturerName(manufacturerId)
            val (deviceType, deviceModel, appleContinuityType, confidence) =
                inferDeviceInfo(manufacturerId, safePayload)

            // Extract Find My info for Apple devices with type 0x12
            val findMyInfo = if (manufacturerId == ManufacturerId.APPLE && safePayload.isNotEmpty()) {
                parseFindMyPayload(safePayload)
            } else null

            // Extract payload fingerprint for device correlation across MAC rotations
            val payloadFingerprint = extractPayloadFingerprint(manufacturerId, safePayload)

            ManufacturerInfo(
                manufacturerId = manufacturerId,
                manufacturerName = manufacturerName,
                deviceType = deviceType,
                deviceModel = deviceModel,
                appleContinuityType = appleContinuityType,
                isTracker = isTrackerDevice(manufacturerId, deviceType, appleContinuityType),
                payload = safePayload,
                identificationConfidence = confidence,
                findMyInfo = findMyInfo,
                payloadFingerprint = payloadFingerprint
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing manufacturer data for ID 0x${manufacturerId.toString(16)}")
            null
        }
    }

    /**
     * Parse manufacturer data from raw bytes (legacy format with ID prefix).
     *
     * Use this version when the manufacturer data includes the 2-byte ID prefix
     * (standard BLE advertisement format).
     */
    fun parseManufacturerData(manufacturerData: ByteArray?): ManufacturerInfo? {
        if (manufacturerData == null || manufacturerData.size < 2) {
            return null
        }

        return try {
            // First 2 bytes are manufacturer ID (little-endian)
            val manufacturerId = ((manufacturerData[1].toInt() and 0xFF) shl 8) or
                    (manufacturerData[0].toInt() and 0xFF)

            val payload = if (manufacturerData.size > 2) {
                manufacturerData.copyOfRange(2, manufacturerData.size)
            } else {
                byteArrayOf()
            }

            // Delegate to the ID+payload version
            parseManufacturerData(manufacturerId, payload)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing manufacturer data: ${manufacturerData.toHexString()}")
            null
        }
    }

    /**
     * Get manufacturer name from ID.
     */
    fun getManufacturerName(manufacturerId: Int): String {
        return manufacturerNames[manufacturerId]
            ?: "Unknown (0x${manufacturerId.toString(16).uppercase().padStart(4, '0')})"
    }

    /**
     * Infer device information from manufacturer ID and payload.
     *
     * @return Quadruple of (DeviceType, DeviceModel?, AppleContinuityType?, Confidence)
     */
    private fun inferDeviceInfo(
        manufacturerId: Int,
        payload: ByteArray
    ): DeviceInference {
        return when (manufacturerId) {
            ManufacturerId.APPLE -> inferAppleDevice(payload)
            ManufacturerId.SAMSUNG -> inferSamsungDevice(payload)
            ManufacturerId.TILE -> DeviceInference(DeviceType.TRACKER, "Tile", null, 0.95f)
            ManufacturerId.CHIPOLO -> DeviceInference(DeviceType.TRACKER, "Chipolo", null, 0.95f)
            ManufacturerId.PEBBLEBEE -> DeviceInference(DeviceType.TRACKER, "Pebblebee", null, 0.95f)
            ManufacturerId.CUBE -> DeviceInference(DeviceType.TRACKER, "Cube", null, 0.95f)
            ManufacturerId.FITBIT -> DeviceInference(DeviceType.FITNESS_BAND, "Fitbit", null, 0.85f)
            ManufacturerId.GARMIN -> inferGarminDevice(payload)
            ManufacturerId.POLAR -> DeviceInference(DeviceType.FITNESS_BAND, "Polar", null, 0.85f)
            ManufacturerId.WHOOP -> DeviceInference(DeviceType.FITNESS_BAND, "Whoop", null, 0.85f)
            ManufacturerId.OURA -> DeviceInference(DeviceType.FITNESS_BAND, "Oura Ring", null, 0.85f)
            ManufacturerId.BOSE -> DeviceInference(DeviceType.HEADPHONES, "Bose", null, 0.80f)
            ManufacturerId.JABRA -> DeviceInference(DeviceType.HEADPHONES, "Jabra", null, 0.80f)
            ManufacturerId.SENNHEISER -> DeviceInference(DeviceType.HEADPHONES, "Sennheiser", null, 0.80f)
            ManufacturerId.GOOGLE -> inferGoogleDevice(payload)
            ManufacturerId.MICROSOFT -> DeviceInference(DeviceType.COMPUTER, null, null, 0.60f)
            ManufacturerId.XIAOMI -> inferXiaomiDevice(payload)
            ManufacturerId.HUAWEI -> inferHuaweiDevice(payload)
            ManufacturerId.AMAZON -> DeviceInference(DeviceType.SMART_HOME, "Amazon Echo", null, 0.70f)
            ManufacturerId.PHILIPS -> DeviceInference(DeviceType.SMART_HOME, "Philips Hue", null, 0.70f)
            ManufacturerId.IKEA -> DeviceInference(DeviceType.SMART_HOME, "IKEA Tradfri", null, 0.70f)
            else -> DeviceInference(DeviceType.UNKNOWN, null, null, 0.30f)
        }
    }

    /**
     * Infer Apple device type from Continuity protocol payload.
     * CRITICAL: This is where AirTag detection happens!
     */
    private fun inferAppleDevice(payload: ByteArray): DeviceInference {
        if (payload.isEmpty()) {
            return DeviceInference(DeviceType.PHONE, null, null, 0.40f)
        }

        val messageType = payload[0].toInt() and 0xFF

        return when (messageType) {
            // =====================================================
            // FIND MY NETWORK (Type 0x12) - AIRTAG DETECTION!
            // =====================================================
            AppleContinuityType.FIND_MY -> {
                Timber.d("Detected Apple Find My device (AirTag/Find My accessory)")
                DeviceInference(
                    deviceType = DeviceType.TRACKER,
                    deviceModel = "AirTag",
                    appleContinuityType = AppleContinuityType.FIND_MY,
                    confidence = 0.95f
                )
            }

            // =====================================================
            // PROXIMITY PAIRING (Type 0x07) - AIRPODS DETECTION
            // =====================================================
            AppleContinuityType.PROXIMITY_PAIRING -> {
                val model = if (payload.size >= 3) {
                    val modelId = ((payload[2].toInt() and 0xFF) shl 8) or
                                  (payload[1].toInt() and 0xFF)
                    airpodsModelNames[modelId]
                } else null

                DeviceInference(
                    deviceType = if (model?.contains("Max") == true) DeviceType.HEADPHONES else DeviceType.EARBUDS,
                    deviceModel = model ?: "AirPods",
                    appleContinuityType = AppleContinuityType.PROXIMITY_PAIRING,
                    confidence = 0.90f
                )
            }

            // =====================================================
            // NEARBY INFO (Type 0x10) - IPHONE/IPAD STATUS
            // =====================================================
            AppleContinuityType.NEARBY_INFO -> {
                val actionCode = if (payload.size >= 3) payload[2].toInt() and 0xFF else 0
                val deviceModel = when (actionCode) {
                    0x0A -> "Apple Watch"  // Watch on wrist
                    0x0D -> "iPhone (Driving)"
                    0x0E -> "iPhone (On Call)"
                    else -> "iPhone/iPad"
                }
                DeviceInference(
                    deviceType = if (actionCode == 0x0A) DeviceType.WATCH else DeviceType.PHONE,
                    deviceModel = deviceModel,
                    appleContinuityType = AppleContinuityType.NEARBY_INFO,
                    confidence = 0.85f
                )
            }

            // =====================================================
            // HANDOFF (Type 0x0C) - CROSS-DEVICE SYNC
            // =====================================================
            AppleContinuityType.HANDOFF -> {
                DeviceInference(
                    deviceType = DeviceType.PHONE,
                    deviceModel = "iPhone/iPad/Mac",
                    appleContinuityType = AppleContinuityType.HANDOFF,
                    confidence = 0.75f
                )
            }

            // =====================================================
            // MAGIC SWITCH (Type 0x0B) - APPLE WATCH
            // =====================================================
            AppleContinuityType.MAGIC_SWITCH -> {
                DeviceInference(
                    deviceType = DeviceType.WATCH,
                    deviceModel = "Apple Watch",
                    appleContinuityType = AppleContinuityType.MAGIC_SWITCH,
                    confidence = 0.85f
                )
            }

            // =====================================================
            // IBEACON (Type 0x02 with length 0x15)
            // =====================================================
            AppleContinuityType.IBEACON_TYPE -> {
                if (payload.size >= 2 && payload[1].toInt() and 0xFF == AppleContinuityType.IBEACON_LENGTH) {
                    DeviceInference(
                        deviceType = DeviceType.BEACON,
                        deviceModel = "iBeacon",
                        appleContinuityType = null,
                        confidence = 0.95f
                    )
                } else {
                    DeviceInference(DeviceType.UNKNOWN, null, messageType, 0.50f)
                }
            }

            // =====================================================
            // HEY SIRI (Type 0x08)
            // =====================================================
            AppleContinuityType.HEY_SIRI -> {
                DeviceInference(
                    deviceType = DeviceType.PHONE,
                    deviceModel = "iPhone (Siri Active)",
                    appleContinuityType = AppleContinuityType.HEY_SIRI,
                    confidence = 0.80f
                )
            }

            // =====================================================
            // AIRDROP (Type 0x05)
            // =====================================================
            AppleContinuityType.AIRDROP -> {
                DeviceInference(
                    deviceType = DeviceType.PHONE,
                    deviceModel = "iPhone/iPad/Mac (AirDrop)",
                    appleContinuityType = AppleContinuityType.AIRDROP,
                    confidence = 0.75f
                )
            }

            // =====================================================
            // TETHERING (Type 0x0D/0x0E)
            // =====================================================
            AppleContinuityType.TETHERING_SOURCE, AppleContinuityType.TETHERING_TARGET -> {
                DeviceInference(
                    deviceType = DeviceType.PHONE,
                    deviceModel = "iPhone (Hotspot)",
                    appleContinuityType = messageType,
                    confidence = 0.80f
                )
            }

            // =====================================================
            // AIRPLAY (Type 0x09/0x0A)
            // =====================================================
            AppleContinuityType.AIRPLAY_SOURCE, AppleContinuityType.AIRPLAY_TARGET -> {
                DeviceInference(
                    deviceType = DeviceType.SPEAKER,
                    deviceModel = "AirPlay Device",
                    appleContinuityType = messageType,
                    confidence = 0.70f
                )
            }

            // =====================================================
            // DEFAULT - Unknown Apple device
            // =====================================================
            else -> {
                Timber.d("Unknown Apple Continuity type: 0x${messageType.toString(16)}")
                DeviceInference(
                    deviceType = DeviceType.PHONE,
                    deviceModel = null,
                    appleContinuityType = messageType,
                    confidence = 0.50f
                )
            }
        }
    }

    /**
     * Infer Samsung device type.
     */
    private fun inferSamsungDevice(payload: ByteArray): DeviceInference {
        // Samsung uses various payload patterns
        // SmartTag detection is better done via Service UUID (0xFD5A)
        // Here we use heuristics based on payload patterns

        if (payload.isEmpty()) {
            return DeviceInference(DeviceType.PHONE, "Samsung Galaxy", null, 0.60f)
        }

        // Samsung SmartTag has specific patterns in manufacturer data
        // However, the most reliable detection is via Service UUID FD5A

        return DeviceInference(DeviceType.PHONE, "Samsung Galaxy", null, 0.65f)
    }

    /**
     * Infer Google device type.
     */
    private fun inferGoogleDevice(payload: ByteArray): DeviceInference {
        // Google Fast Pair uses specific patterns
        return DeviceInference(DeviceType.PHONE, "Pixel", null, 0.65f)
    }

    /**
     * Infer Garmin device type.
     */
    private fun inferGarminDevice(payload: ByteArray): DeviceInference {
        // Garmin makes both watches and fitness bands
        return DeviceInference(DeviceType.WATCH, "Garmin", null, 0.80f)
    }

    /**
     * Infer Xiaomi device type.
     */
    private fun inferXiaomiDevice(payload: ByteArray): DeviceInference {
        // Xiaomi makes phones, bands, and earbuds
        return DeviceInference(DeviceType.PHONE, "Xiaomi", null, 0.60f)
    }

    /**
     * Infer Huawei device type.
     */
    private fun inferHuaweiDevice(payload: ByteArray): DeviceInference {
        return DeviceInference(DeviceType.PHONE, "Huawei", null, 0.60f)
    }

    // ============================================================================
    // TRACKER DETECTION
    // ============================================================================

    /**
     * Determine if device is a tracking device.
     *
     * CRITICAL for stalking detection!
     */
    private fun isTrackerDevice(
        manufacturerId: Int,
        deviceType: DeviceType,
        appleContinuityType: Int?
    ): Boolean {
        // Direct tracker manufacturers
        if (manufacturerId in listOf(
            ManufacturerId.TILE,
            ManufacturerId.CHIPOLO,
            ManufacturerId.PEBBLEBEE,
            ManufacturerId.CUBE
        )) {
            return true
        }

        // Apple Find My (AirTag)
        if (manufacturerId == ManufacturerId.APPLE &&
            appleContinuityType == AppleContinuityType.FIND_MY) {
            return true
        }

        // Device type is tracker
        if (deviceType == DeviceType.TRACKER) {
            return true
        }

        return false
    }

    /**
     * Check if manufacturer data indicates a tracking device.
     */
    fun isTrackingDevice(manufacturerData: ByteArray?): Boolean {
        val info = parseManufacturerData(manufacturerData) ?: return false
        return info.isTracker
    }

    /**
     * Extract device type string from manufacturer data.
     */
    fun extractDeviceType(manufacturerData: ByteArray?): String? {
        val info = parseManufacturerData(manufacturerData) ?: return null
        return info.deviceType.name
    }

    // ============================================================================
    // FIND MY NETWORK PAYLOAD ANALYSIS
    // ============================================================================

    /**
     * Find My advertisement payload structure (Type 0x12):
     *
     * Byte 0: Type (0x12)
     * Byte 1: Length (typically 0x19 = 25 bytes)
     * Byte 2: Status byte (contains separated-from-owner flag)
     * Bytes 3-24: Public key data (rotates but has semi-stable portions)
     *
     * Status byte (Byte 2) bit flags:
     * - Bit 2 (0x04): Separated from owner
     * - Bits 6-7: Battery level hint (00=full, 01=medium, 10=low, 11=critical)
     */
    object FindMyPayload {
        const val MIN_PAYLOAD_LENGTH = 3  // Minimum for type + length + status
        const val FULL_PAYLOAD_LENGTH = 27  // Type + length + 25 bytes of data

        // Status byte bit masks
        const val SEPARATED_FROM_OWNER_MASK = 0x04  // Bit 2

        // Battery level bits (bits 6-7)
        const val BATTERY_MASK = 0xC0
        const val BATTERY_FULL = 0x00
        const val BATTERY_MEDIUM = 0x40
        const val BATTERY_LOW = 0x80
        const val BATTERY_CRITICAL = 0xC0

        // Fingerprint extraction: We use bytes 3-8 (first 6 bytes of public key)
        // These rotate less frequently than the MAC and provide device correlation
        const val FINGERPRINT_START = 2  // After type and length
        const val FINGERPRINT_LENGTH = 6  // 6 bytes = 12 hex chars
    }

    /**
     * Parsed Find My network payload information.
     */
    data class FindMyInfo(
        val statusByte: Int,
        val separatedFromOwner: Boolean,
        val batteryLevel: BatteryLevel,
        val payloadFingerprint: String,
        val rawPayload: ByteArray
    ) {
        enum class BatteryLevel {
            FULL, MEDIUM, LOW, CRITICAL, UNKNOWN
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as FindMyInfo

            if (statusByte != other.statusByte) return false
            if (separatedFromOwner != other.separatedFromOwner) return false
            if (batteryLevel != other.batteryLevel) return false
            if (payloadFingerprint != other.payloadFingerprint) return false
            if (!rawPayload.contentEquals(other.rawPayload)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = statusByte
            result = 31 * result + separatedFromOwner.hashCode()
            result = 31 * result + batteryLevel.hashCode()
            result = 31 * result + payloadFingerprint.hashCode()
            result = 31 * result + rawPayload.contentHashCode()
            return result
        }
    }

    /**
     * Parse Find My network payload to extract fingerprint and status.
     *
     * This is CRITICAL for AirTag tracking across MAC rotations.
     * AirTags rotate their MAC address every ~15 minutes, but the payload
     * contains semi-stable portions that can be used as a fingerprint.
     *
     * @param payload The manufacturer data payload (after type byte)
     * @return FindMyInfo if parsing succeeds, null otherwise
     */
    fun parseFindMyPayload(payload: ByteArray): FindMyInfo? {
        if (payload.isEmpty()) {
            Timber.d("Find My payload is empty")
            return null
        }

        // Check if this is a Find My advertisement (type 0x12)
        val messageType = payload[0].toInt() and 0xFF
        if (messageType != AppleContinuityType.FIND_MY) {
            return null
        }

        // Need at least type + length + status
        if (payload.size < FindMyPayload.MIN_PAYLOAD_LENGTH) {
            Timber.d("Find My payload too short: ${payload.size} bytes")
            return null
        }

        try {
            // Extract length byte (byte 1)
            val length = if (payload.size > 1) payload[1].toInt() and 0xFF else 0

            // Extract status byte (byte 2)
            val statusByte = if (payload.size > 2) payload[2].toInt() and 0xFF else 0

            // Check if separated from owner (bit 2)
            val separatedFromOwner = (statusByte and FindMyPayload.SEPARATED_FROM_OWNER_MASK) != 0

            // Extract battery level
            val batteryLevel = when (statusByte and FindMyPayload.BATTERY_MASK) {
                FindMyPayload.BATTERY_FULL -> FindMyInfo.BatteryLevel.FULL
                FindMyPayload.BATTERY_MEDIUM -> FindMyInfo.BatteryLevel.MEDIUM
                FindMyPayload.BATTERY_LOW -> FindMyInfo.BatteryLevel.LOW
                FindMyPayload.BATTERY_CRITICAL -> FindMyInfo.BatteryLevel.CRITICAL
                else -> FindMyInfo.BatteryLevel.UNKNOWN
            }

            // Extract fingerprint from bytes 3-8 (or as many as available)
            // This is the semi-stable portion of the public key
            val fingerprintStart = FindMyPayload.FINGERPRINT_START
            val fingerprintEnd = minOf(
                fingerprintStart + FindMyPayload.FINGERPRINT_LENGTH,
                payload.size
            )

            val fingerprint = if (fingerprintEnd > fingerprintStart) {
                payload.copyOfRange(fingerprintStart, fingerprintEnd).toHexString()
            } else {
                // Fallback: use status byte + any available bytes
                payload.copyOfRange(0, minOf(4, payload.size)).toHexString()
            }

            Timber.d(
                "Find My payload parsed: separated=$separatedFromOwner, " +
                    "battery=$batteryLevel, fingerprint=$fingerprint"
            )

            return FindMyInfo(
                statusByte = statusByte,
                separatedFromOwner = separatedFromOwner,
                batteryLevel = batteryLevel,
                payloadFingerprint = fingerprint,
                rawPayload = payload
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing Find My payload")
            return null
        }
    }

    /**
     * Extract fingerprint from Apple Continuity payloads for device correlation.
     *
     * This is CRITICAL for tracking devices across MAC address rotations.
     * Apple devices rotate their BLE MAC address every ~15 minutes for privacy,
     * but their Continuity payloads contain semi-stable data that can be used
     * to correlate the same physical device across rotations.
     *
     * ## Supported Continuity Types:
     * - **Find My (0x12)**: AirTags - uses public key bytes 3-8 (highest stability)
     * - **Proximity Pairing (0x07)**: AirPods/Beats - uses model ID + status bytes
     * - **Nearby Info (0x10)**: iPhones/iPads - uses action code + status flags
     * - **Magic Switch (0x0B)**: Apple Watch - uses device state bytes
     * - **Handoff (0x0C)**: Mac/iPad - uses activity hash bytes
     * - **AirDrop (0x05)**: Any Apple - uses truncated contact hash
     *
     * ## Fingerprint Stability:
     * - Find My: Changes with each key rotation (~15 min), but correlates MAC rotations
     * - Proximity Pairing: Stable per device (model ID doesn't change)
     * - Nearby Info: Semi-stable (status flags change with device state)
     * - Others: Variable stability, but useful for short-term correlation
     *
     * @param manufacturerId The manufacturer ID
     * @param payload The manufacturer data payload
     * @return Fingerprint string prefixed with type (e.g., "FM:AABBCCDD" for Find My)
     *         or null if fingerprint cannot be extracted
     */
    fun extractPayloadFingerprint(manufacturerId: Int, payload: ByteArray?): String? {
        if (payload == null || payload.isEmpty()) return null

        // Only process Apple devices for now (Samsung/Tile handled separately via service UUIDs)
        if (manufacturerId != ManufacturerId.APPLE) return null

        val messageType = payload[0].toInt() and 0xFF

        return when (messageType) {
            // =====================================================
            // FIND MY (0x12) - AirTags
            // Payload: Type(1) + Length(1) + Status(1) + PublicKey(25)
            // Fingerprint: Bytes 2-7 (first 6 bytes of rotating public key)
            //
            // WARNING: These bytes are part of the rotating public key prefix
            // and change every ~15 minutes when the MAC address rotates.
            // This fingerprint is only valid within a single rotation window.
            // Cross-rotation correlation requires the shadow detection path.
            // =====================================================
            AppleContinuityType.FIND_MY -> {
                val findMyFingerprint = parseFindMyPayload(payload)?.payloadFingerprint
                findMyFingerprint?.let { "FM:$it" }
            }

            // =====================================================
            // PROXIMITY PAIRING (0x07) - AirPods/Beats
            // Payload: Type(1) + Length(1) + ModelID(2 LE) + Status(1) + BatteryL(1) + BatteryR(1) + Case(1) + Lid(1)
            // Fingerprint: Model ID (stable) + color byte if available
            // =====================================================
            AppleContinuityType.PROXIMITY_PAIRING -> {
                if (payload.size >= 4) {
                    // Extract model ID (bytes 2-3, little-endian) and status byte
                    val modelId = ((payload[2].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    val statusByte = if (payload.size >= 5) payload[4].toInt() and 0xFF else 0
                    // Include color byte (byte 6) if available for better differentiation
                    val colorByte = if (payload.size >= 7) payload[6].toInt() and 0xFF else 0
                    "PP:%04X%02X%02X".format(modelId, statusByte and 0xF0, colorByte)
                } else null
            }

            // =====================================================
            // NEARBY INFO (0x10) - iPhones/iPads - MOST COMMON
            // =====================================================
            // IMPORTANT: These devices CANNOT be reliably fingerprinted!
            // The auth tag (bytes 4+) rotates WITH the MAC address every ~15 min.
            // Only bytes 2-3 (status + action code) are semi-stable, but they're
            // NOT unique - many iPhones will have identical values.
            //
            // Attempting to fingerprint these leads to:
            // - False correlation (different iPhones with same status+action)
            // - OR no correlation (same iPhone with different auth tags)
            //
            // SOLUTION: Return null - these are NOT tracker threats anyway.
            // The detection algorithm should focus on isTracker=true devices.
            // =====================================================
            AppleContinuityType.NEARBY_INFO -> {
                // Do NOT fingerprint regular iPhones - they rotate too frequently
                // and are not stalking threats. Let them be "noise" in the database.
                null
            }

            // =====================================================
            // MAGIC SWITCH (0x0B) - Apple Watch
            // =====================================================
            // Similar problem: the payload bytes rotate with MAC address.
            // Apple Watches are not stalking threats - don't fingerprint.
            // =====================================================
            AppleContinuityType.MAGIC_SWITCH -> {
                null  // Not a tracker, not fingerprintable
            }

            // =====================================================
            // HANDOFF (0x0C) - Mac/iPad cross-device sync
            // =====================================================
            // Auth tag rotates with MAC. Not a tracker threat.
            // =====================================================
            AppleContinuityType.HANDOFF -> {
                null  // Not a tracker, not fingerprintable
            }

            // =====================================================
            // NON-TRACKER APPLE CONTINUITY TYPES
            // =====================================================
            // All of these are regular Apple devices (phones, tablets, Macs)
            // that rotate their BLE data with MAC address. They are NOT
            // stalking threats and CANNOT be reliably fingerprinted.
            //
            // Return null for all of them - the detection algorithm will
            // filter them out via isTracker=false.
            // =====================================================
            AppleContinuityType.AIRDROP,
            AppleContinuityType.HEY_SIRI,
            AppleContinuityType.TETHERING_SOURCE,
            AppleContinuityType.TETHERING_TARGET,
            AppleContinuityType.AIRPLAY_SOURCE,
            AppleContinuityType.AIRPLAY_TARGET -> {
                null  // Not trackers, not fingerprintable
            }

            // Unknown Apple Continuity type
            else -> {
                // Don't try to fingerprint unknown types - likely not trackers
                Timber.d("Unknown Apple Continuity type 0x${messageType.toString(16)}, not fingerprinting")
                null
            }
        }
    }

    // ============================================================================
    // SERVICE UUID-BASED FINGERPRINTING (Non-Apple Trackers)
    // ============================================================================

    /**
     * Known tracker service UUIDs for fingerprinting.
     * These are stable identifiers that don't rotate with MAC address.
     */
    object TrackerServiceUuid {
        // Samsung SmartTag - most reliable identifier
        const val SAMSUNG_SMARTTAG = "0000FD5A-0000-1000-8000-00805F9B34FB"
        const val SAMSUNG_SMARTTAG_SHORT = "FD5A"

        // Tile trackers
        const val TILE = "0000FEED-0000-1000-8000-00805F9B34FB"
        const val TILE_SHORT = "FEED"
        const val TILE_FULL = "FEED0001-C497-4476-A7ED-727DE7648AB1"

        // Chipolo trackers
        const val CHIPOLO = "0000FE8C-0000-1000-8000-00805F9B34FB"
        const val CHIPOLO_SHORT = "FE8C"

        // Google Find My Device Network
        const val GOOGLE_FIND_MY = "0000FE2C-0000-1000-8000-00805F9B34FB"
        const val GOOGLE_FIND_MY_SHORT = "FE2C"

        // Other trackers
        const val PEBBLEBEE = "0000FE8D-0000-1000-8000-00805F9B34FB"
        const val PEBBLEBEE_SHORT = "FE8D"
        const val CUBE = "0000FE8E-0000-1000-8000-00805F9B34FB"
        const val CUBE_SHORT = "FE8E"
        const val EUFY = "0000FE9F-0000-1000-8000-00805F9B34FB"
        const val EUFY_SHORT = "FE9F"
        const val JIO_TAG = "0000FEA0-0000-1000-8000-00805F9B34FB"
        const val JIO_TAG_SHORT = "FEA0"

        // Apple Find My Network (for third-party Find My accessories)
        const val APPLE_FIND_MY = "0000FD6F-0000-1000-8000-00805F9B34FB"
        const val APPLE_FIND_MY_SHORT = "FD6F"
    }

    /**
     * Extract fingerprint from service UUIDs for non-Apple trackers.
     *
     * Service UUIDs are the MOST STABLE identifier for BLE trackers.
     * Unlike MAC addresses, they don't rotate and uniquely identify
     * the tracker type/manufacturer.
     *
     * For additional correlation, we combine the service UUID with
     * manufacturer data bytes when available to differentiate between
     * multiple devices of the same type.
     *
     * ## Fingerprint Format: "{TYPE}:{UUID}:{PAYLOAD_HASH}"
     * Examples:
     * - "ST:FD5A:A1B2C3" for Samsung SmartTag
     * - "TL:FEED:D4E5F6" for Tile
     * - "CH:FE8C:789ABC" for Chipolo
     *
     * @param manufacturerId Bluetooth SIG manufacturer ID
     * @param serviceUuids List of advertised service UUIDs
     * @param manufacturerData Raw manufacturer data bytes
     * @return Fingerprint string or null if no tracker UUID found
     */
    fun extractServiceUuidFingerprint(
        manufacturerId: Int?,
        serviceUuids: List<android.os.ParcelUuid>?,
        manufacturerData: ByteArray?
    ): String? {
        if (serviceUuids.isNullOrEmpty()) return null

        // Convert service UUIDs to uppercase strings for comparison
        val uuidStrings = serviceUuids.map { it.uuid.toString().uppercase() }
        val shortUuids = uuidStrings.map { uuid ->
            // Extract short UUID from full UUID (e.g., "0000FD5A-0000-1000-8000-00805F9B34FB" -> "FD5A")
            if (uuid.startsWith("0000") && uuid.contains("-0000-1000-8000-00805F9B34FB")) {
                uuid.substring(4, 8)
            } else uuid
        }

        // Extract payload hash for differentiation between multiple devices of same type
        val payloadHash = manufacturerData?.let {
            if (it.size >= 4) {
                it.copyOfRange(0, minOf(4, it.size)).joinToString("") { b -> "%02X".format(b) }
            } else if (it.isNotEmpty()) {
                it.joinToString("") { b -> "%02X".format(b) }
            } else null
        } ?: ""

        // Check for Samsung SmartTag
        if (shortUuids.contains(TrackerServiceUuid.SAMSUNG_SMARTTAG_SHORT) ||
            uuidStrings.any { it.contains("FD5A") }) {
            return "ST:FD5A:$payloadHash"
        }

        // Check for Tile
        if (shortUuids.contains(TrackerServiceUuid.TILE_SHORT) ||
            uuidStrings.any { it.contains("FEED") }) {
            return "TL:FEED:$payloadHash"
        }

        // Check for Chipolo
        if (shortUuids.contains(TrackerServiceUuid.CHIPOLO_SHORT) ||
            uuidStrings.any { it.contains("FE8C") }) {
            return "CH:FE8C:$payloadHash"
        }

        // Check for Google Find My Device
        if (shortUuids.contains(TrackerServiceUuid.GOOGLE_FIND_MY_SHORT) ||
            uuidStrings.any { it.contains("FE2C") }) {
            return "GF:FE2C:$payloadHash"
        }

        // Check for Pebblebee
        if (shortUuids.contains(TrackerServiceUuid.PEBBLEBEE_SHORT) ||
            uuidStrings.any { it.contains("FE8D") }) {
            return "PB:FE8D:$payloadHash"
        }

        // Check for Cube
        if (shortUuids.contains(TrackerServiceUuid.CUBE_SHORT) ||
            uuidStrings.any { it.contains("FE8E") }) {
            return "CB:FE8E:$payloadHash"
        }

        // Check for eufy
        if (shortUuids.contains(TrackerServiceUuid.EUFY_SHORT) ||
            uuidStrings.any { it.contains("FE9F") }) {
            return "EF:FE9F:$payloadHash"
        }

        // Check for Jio Tag
        if (shortUuids.contains(TrackerServiceUuid.JIO_TAG_SHORT) ||
            uuidStrings.any { it.contains("FEA0") }) {
            return "JT:FEA0:$payloadHash"
        }

        // Check for Apple Find My Network (third-party accessories)
        if (shortUuids.contains(TrackerServiceUuid.APPLE_FIND_MY_SHORT) ||
            uuidStrings.any { it.contains("FD6F") }) {
            return "AF:FD6F:$payloadHash"
        }

        // Also check by manufacturer ID for known tracker manufacturers
        return when (manufacturerId) {
            ManufacturerId.TILE -> "TL:MFR:$payloadHash"
            ManufacturerId.CHIPOLO -> "CH:MFR:$payloadHash"
            ManufacturerId.PEBBLEBEE -> "PB:MFR:$payloadHash"
            ManufacturerId.CUBE -> "CB:MFR:$payloadHash"
            else -> null
        }
    }

    /**
     * Combined fingerprint extraction that tries all methods.
     *
     * Priority order:
     * 1. Apple Continuity payload fingerprint (for Apple devices)
     * 2. Service UUID fingerprint (for Samsung, Tile, Chipolo, etc.)
     * 3. Returns null if no fingerprint can be extracted
     *
     * @param manufacturerId Bluetooth SIG manufacturer ID
     * @param manufacturerData Raw manufacturer data bytes
     * @param serviceUuids List of advertised service UUIDs
     * @return Best available fingerprint or null
     */
    fun extractBestFingerprint(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<android.os.ParcelUuid>?
    ): String? {
        // Try Apple payload fingerprint first (most specific for Apple devices)
        if (manufacturerId == ManufacturerId.APPLE) {
            val appleFingerprint = extractPayloadFingerprint(manufacturerId, manufacturerData)
            if (appleFingerprint != null) {
                return appleFingerprint
            }
        }

        // Try service UUID fingerprint (works for Samsung, Tile, Chipolo, etc.)
        val serviceFingerprint = extractServiceUuidFingerprint(manufacturerId, serviceUuids, manufacturerData)
        if (serviceFingerprint != null) {
            return serviceFingerprint
        }

        // No fingerprint available
        return null
    }

    // ============================================================================
    // HELPER CLASSES
    // ============================================================================

    /**
     * Internal class for device inference results.
     */
    private data class DeviceInference(
        val deviceType: DeviceType,
        val deviceModel: String?,
        val appleContinuityType: Int?,
        val confidence: Float
    )

    /**
     * Parsed manufacturer information with comprehensive device details.
     */
    data class ManufacturerInfo(
        val manufacturerId: Int,
        val manufacturerName: String,
        val deviceType: DeviceType,
        val deviceModel: String?,
        val appleContinuityType: Int?,
        val isTracker: Boolean,
        val payload: ByteArray,
        val identificationConfidence: Float,

        // Find My network specific fields (for AirTag detection)
        val findMyInfo: FindMyInfo? = null,
        val payloadFingerprint: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ManufacturerInfo

            if (manufacturerId != other.manufacturerId) return false
            if (manufacturerName != other.manufacturerName) return false
            if (deviceType != other.deviceType) return false
            if (deviceModel != other.deviceModel) return false
            if (appleContinuityType != other.appleContinuityType) return false
            if (isTracker != other.isTracker) return false
            if (!payload.contentEquals(other.payload)) return false
            if (identificationConfidence != other.identificationConfidence) return false
            if (findMyInfo != other.findMyInfo) return false
            if (payloadFingerprint != other.payloadFingerprint) return false

            return true
        }

        override fun hashCode(): Int {
            var result = manufacturerId
            result = 31 * result + manufacturerName.hashCode()
            result = 31 * result + deviceType.hashCode()
            result = 31 * result + (deviceModel?.hashCode() ?: 0)
            result = 31 * result + (appleContinuityType ?: 0)
            result = 31 * result + isTracker.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + identificationConfidence.hashCode()
            result = 31 * result + (findMyInfo?.hashCode() ?: 0)
            result = 31 * result + (payloadFingerprint?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String {
            return "ManufacturerInfo(" +
                "manufacturer=$manufacturerName (0x${manufacturerId.toString(16).uppercase()}), " +
                "type=$deviceType, " +
                "model=$deviceModel, " +
                "isTracker=$isTracker, " +
                "confidence=${(identificationConfidence * 100).toInt()}%)"
        }
    }

    // ============================================================================
    // UTILITY EXTENSIONS
    // ============================================================================

    /**
     * Convert ByteArray to hex string for logging/storage.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
