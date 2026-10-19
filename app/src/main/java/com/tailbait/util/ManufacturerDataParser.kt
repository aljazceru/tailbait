package com.tailbait.util

import android.os.ParcelUuid
import com.tailbait.algorithm.TrackerServiceDetector
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
        // Values verified against the official Bluetooth SIG company identifier
        // registry (bitbucket.org/bluetooth-SIG/public, commit 3267e25):
        //   Tile, Inc. = 0x067C (0x0099 is i.Tech Dynamic — NOT Tile)
        //   Chipolo d.o.o. = 0x08C3 (0x02E5 is Espressif — NOT Chipolo)
        const val TILE = 0x067C
        const val CHIPOLO = 0x08C3
        // Pebblebee and Cube have no verified SIG company IDs here.

        // Pentest / surveillance hardware (IDs verified in the SIG registry)
        const val FLIPPER = 0x0E29 // Flipper Devices Inc.
        const val XUNTONG = 0x09C8 // Flock Safety camera BLE radio supplier

        // Camera glasses (lens-in-the-room detection, signals from zuckoff.app)
        const val LUXOTTICA = 0x0D53 // Ray-Ban Meta, Oakley Meta
        const val META_PLATFORMS = 0x058E // Meta Platforms Technologies wearable
        const val META = 0x01AB // Meta Platforms, Inc. (glasses/headsets)
        const val SNAP = 0x03C2 // Snap Spectacles

        // Wearables & Fitness
        const val FITBIT = 0x0224
        const val GARMIN = 0x0087
        const val POLAR = 0x006B
        const val SUUNTO = 0x004F
        const val WHOOP = 0x0614
        const val OURA = 0x04C4

        // Audio Manufacturers
        const val BOSE = 0x009E
        const val JABRA = 0x0067 // GN Audio
        const val SENNHEISER = 0x0082
        const val BANG_OLUFSEN = 0x0057
        const val SKULLCANDY = 0x03B4
        const val JBL = 0x0057 // Harman
        const val BEATS = APPLE // Beats uses Apple ID
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
        const val INTERPLAN = 0x0212 // Interplan Co., Ltd (Japan)
        const val RENESAS = 0x00E3 // Renesas Electronics
        const val CYPRESS = 0x0131 // Cypress Semiconductor
        const val DIALOG = 0x00D2 // Dialog Semiconductor
        const val SILABS = 0x02FF // Silicon Labs
        const val REALTEK = 0x005D // Realtek Semiconductor
        const val MEDIATEK = 0x00A5 // MediaTek
        const val LENOVO = 0x0640 // Lenovo
        const val ASUS = 0x04C5 // ASUSTek Computer
        const val ACER = 0x0491 // Acer
        const val DELL = 0x0089 // Dell
        const val HP = 0x000B // HP
    }

    /**
     * Map of manufacturer IDs to human-readable names.
     */
    private val manufacturerNames =
        mapOf(
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
            ManufacturerId.FLIPPER to "Flipper Devices",
            ManufacturerId.XUNTONG to "Xuntong (Flock Safety)",
            ManufacturerId.LUXOTTICA to "Luxottica (Ray-Ban/Oakley Meta)",
            ManufacturerId.META_PLATFORMS to "Meta Platforms",
            ManufacturerId.META to "Meta Platforms",
            ManufacturerId.SNAP to "Snap",
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
        const val PROXIMITY_PAIRING = 0x07 // AirPods, Beats
        const val HEY_SIRI = 0x08
        const val AIRPLAY_TARGET = 0x09
        const val AIRPLAY_SOURCE = 0x0A
        const val MAGIC_SWITCH = 0x0B // Apple Watch disconnection
        const val HANDOFF = 0x0C // Cross-device handoff
        const val TETHERING_TARGET = 0x0D
        const val TETHERING_SOURCE = 0x0E
        const val NEARBY_ACTION = 0x0F
        const val NEARBY_INFO = 0x10 // iPhone broadcasting status
        const val FIND_MY = 0x12 // AirTag, Find My network! CRITICAL!

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
        TRACKER, // AirTag, Tile, SmartTag, Chipolo, etc.
        CAMERA_GLASSES, // Ray-Ban Meta, Oakley Meta, Snap Spectacles
        PENTEST_DEVICE, // Flipper Zero and similar wireless testing hardware
        DRONE, // OpenDroneID / ASTM F3411 Remote ID broadcasts
        SURVEILLANCE, // Flock ALPR cameras, Raven gunshot detectors, etc.
        BEACON,
        FITNESS_BAND,
        SMART_HOME,
        AUTOMOTIVE,
        GAMING,
        MEDICAL,
        UNKNOWN,
        ;

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

    private val airpodsModelNames =
        mapOf(
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
     * Parse manufacturer data when the ID is already extracted (e.g., from sparse array key).
     *
     * Use this version when using Nordic BLE library where the manufacturer ID
     * is the sparse array key and the data bytes are the payload only.
     *
     * @param manufacturerId The Bluetooth SIG company identifier
     * @param payload The manufacturer-specific data (WITHOUT the 2-byte ID prefix)
     * @return Parsed manufacturer info or null
     */
    fun parseManufacturerData(
        manufacturerId: Int,
        payload: ByteArray?,
    ): ManufacturerInfo? {
        return try {
            val safePayload = payload ?: byteArrayOf()
            val manufacturerName = getManufacturerName(manufacturerId)
            val (deviceType, deviceModel, appleContinuityType, confidence) =
                inferDeviceInfo(manufacturerId, safePayload)

            // Extract Find My info for Apple devices with type 0x12
            val findMyInfo =
                if (manufacturerId == ManufacturerId.APPLE && safePayload.isNotEmpty()) {
                    parseFindMyPayload(safePayload)
                } else {
                    null
                }

            val payloadFingerprint = findMyInfo?.payloadFingerprint

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
                payloadFingerprint = payloadFingerprint,
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
    @OptIn(ExperimentalStdlibApi::class)
    fun parseManufacturerData(manufacturerData: ByteArray?): ManufacturerInfo? {
        if (manufacturerData == null || manufacturerData.size < 2) {
            return null
        }

        return try {
            // First 2 bytes are manufacturer ID (little-endian)
            val manufacturerId =
                ((manufacturerData[1].toInt() and 0xFF) shl 8) or
                    (manufacturerData[0].toInt() and 0xFF)

            val payload =
                if (manufacturerData.size > 2) {
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
        payload: ByteArray,
    ): DeviceInference {
        return when (manufacturerId) {
            ManufacturerId.APPLE -> inferAppleDevice(payload)
            ManufacturerId.SAMSUNG -> inferSamsungDevice(payload)
            ManufacturerId.TILE -> DeviceInference(DeviceType.TRACKER, "Tile", null, 0.95f)
            ManufacturerId.CHIPOLO -> DeviceInference(DeviceType.TRACKER, "Chipolo", null, 0.95f)
            ManufacturerId.FLIPPER -> DeviceInference(DeviceType.PENTEST_DEVICE, "Flipper Zero", null, 0.95f)
            ManufacturerId.XUNTONG -> DeviceInference(DeviceType.SURVEILLANCE, "Flock Safety camera (ALPR)", null, 0.85f)
            ManufacturerId.LUXOTTICA -> DeviceInference(DeviceType.CAMERA_GLASSES, "Ray-Ban Meta / Oakley Meta", null, 0.95f)
            ManufacturerId.META_PLATFORMS -> DeviceInference(DeviceType.CAMERA_GLASSES, "Meta camera wearable (glasses/Quest)", null, 0.90f)
            ManufacturerId.META -> DeviceInference(DeviceType.CAMERA_GLASSES, "Meta camera wearable (glasses/Quest)", null, 0.85f)
            ManufacturerId.SNAP -> DeviceInference(DeviceType.CAMERA_GLASSES, "Snap Spectacles", null, 0.95f)
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
                if (!isValidFindMyPayload(payload)) {
                    return DeviceInference(DeviceType.UNKNOWN, null, null, 0.30f)
                }
                Timber.d("Detected Apple Find My device (AirTag/Find My accessory)")
                DeviceInference(
                    deviceType = DeviceType.TRACKER,
                    deviceModel = "Find My device",
                    appleContinuityType = AppleContinuityType.FIND_MY,
                    confidence = 0.95f,
                )
            }

            // =====================================================
            // PROXIMITY PAIRING (Type 0x07) - AIRPODS DETECTION
            // =====================================================
            AppleContinuityType.PROXIMITY_PAIRING -> {
                val model =
                    if (payload.size >= 3) {
                        val modelId =
                            ((payload[2].toInt() and 0xFF) shl 8) or
                                (payload[1].toInt() and 0xFF)
                        airpodsModelNames[modelId]
                    } else {
                        null
                    }

                DeviceInference(
                    deviceType = if (model?.contains("Max") == true) DeviceType.HEADPHONES else DeviceType.EARBUDS,
                    deviceModel = model ?: "AirPods",
                    appleContinuityType = AppleContinuityType.PROXIMITY_PAIRING,
                    confidence = 0.90f,
                )
            }

            // =====================================================
            // NEARBY INFO (Type 0x10) - IPHONE/IPAD STATUS
            // =====================================================
            AppleContinuityType.NEARBY_INFO -> {
                val actionCode = if (payload.size >= 3) payload[2].toInt() and 0xFF else 0
                val deviceModel =
                    when (actionCode) {
                        0x0A -> "Apple Watch" // Watch on wrist
                        0x0D -> "iPhone (Driving)"
                        0x0E -> "iPhone (On Call)"
                        else -> "iPhone/iPad"
                    }
                DeviceInference(
                    deviceType = if (actionCode == 0x0A) DeviceType.WATCH else DeviceType.PHONE,
                    deviceModel = deviceModel,
                    appleContinuityType = AppleContinuityType.NEARBY_INFO,
                    confidence = 0.85f,
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
                    confidence = 0.75f,
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
                    confidence = 0.85f,
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
                        confidence = 0.95f,
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
                    confidence = 0.80f,
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
                    confidence = 0.75f,
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
                    confidence = 0.80f,
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
                    confidence = 0.70f,
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
                    confidence = 0.50f,
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
    private fun inferGoogleDevice(_payload: ByteArray): DeviceInference {
        // Google Fast Pair uses specific patterns
        return DeviceInference(DeviceType.PHONE, "Pixel", null, 0.65f)
    }

    /**
     * Infer Garmin device type.
     */
    private fun inferGarminDevice(_payload: ByteArray): DeviceInference {
        // Garmin makes both watches and fitness bands
        return DeviceInference(DeviceType.WATCH, "Garmin", null, 0.80f)
    }

    /**
     * Infer Xiaomi device type.
     */
    private fun inferXiaomiDevice(_payload: ByteArray): DeviceInference {
        // Xiaomi makes phones, bands, and earbuds
        return DeviceInference(DeviceType.PHONE, "Xiaomi", null, 0.60f)
    }

    /**
     * Infer Huawei device type.
     */
    private fun inferHuaweiDevice(_payload: ByteArray): DeviceInference {
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
        appleContinuityType: Int?,
    ): Boolean {
        // Direct tracker manufacturers
        if (manufacturerId in
            listOf(
                ManufacturerId.TILE,
                ManufacturerId.CHIPOLO,
            )
        ) {
            return true
        }

        // Apple Find My (AirTag)
        if (manufacturerId == ManufacturerId.APPLE &&
            appleContinuityType == AppleContinuityType.FIND_MY
        ) {
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
     * Find My manufacturer payload, excluding the company identifier:
     * - Full frame: type 0x12, length 0x19, status, 22 key bytes, key bits, hint.
     * - Short frame: type 0x12, length 0x02, status, hint; no public key.
     *
     * Status bit 2 means the owner connected during the current key rotation period.
     * Battery bits 6-7 are meaningful only when that maintained-connection bit is set.
     * Source: https://raw.githubusercontent.com/furiousMAC/continuity/master/messages/findmy.md
     */
    object FindMyPayload {
        const val SHORT_PAYLOAD_LENGTH = 4
        const val FULL_PAYLOAD_LENGTH = 27
        const val MAINTAINED_OWNER_CONNECTION_MASK = 0x04

        const val BATTERY_MASK = 0xC0
        const val BATTERY_FULL = 0x00
        const val BATTERY_MEDIUM = 0x40
        const val BATTERY_LOW = 0x80
        const val BATTERY_CRITICAL = 0xC0

        const val FINGERPRINT_START = 3
        const val FINGERPRINT_LENGTH = 22
    }

    /**
     * Parsed Find My network payload information.
     *
     * [FindMyInfo.separatedFromOwner] retains its storage-compatible name, but means
     * no maintained/recent owner connection was advertised, not physical separation.
     */
    data class FindMyInfo(
        val statusByte: Int,
        val separatedFromOwner: Boolean,
        val batteryLevel: BatteryLevel,
        val payloadFingerprint: String?,
        val rawPayload: ByteArray,
    ) {
        enum class BatteryLevel {
            FULL,
            MEDIUM,
            LOW,
            CRITICAL,
            UNKNOWN,
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
            result = 31 * result + (payloadFingerprint?.hashCode() ?: 0)
            result = 31 * result + rawPayload.contentHashCode()
            return result
        }
    }

    internal fun isValidFindMyPayload(payload: ByteArray): Boolean {
        if (payload.size != FindMyPayload.SHORT_PAYLOAD_LENGTH &&
            payload.size != FindMyPayload.FULL_PAYLOAD_LENGTH
        ) {
            return false
        }
        return payload[0].toInt() and 0xFF == AppleContinuityType.FIND_MY &&
            payload[1].toInt() and 0xFF == payload.size - 2
    }

    /**
     * Parse a complete Find My frame. Only the full frame carries an identity;
     * its advertised key changes with key rotation and is not a permanent device ID.
     */
    fun parseFindMyPayload(payload: ByteArray): FindMyInfo? {
        if (!isValidFindMyPayload(payload)) return null

        val statusByte = payload[2].toInt() and 0xFF
        val ownerRecentlyConnected = (statusByte and FindMyPayload.MAINTAINED_OWNER_CONNECTION_MASK) != 0
        val batteryLevel =
            if (!ownerRecentlyConnected) {
                FindMyInfo.BatteryLevel.UNKNOWN
            } else {
                when (statusByte and FindMyPayload.BATTERY_MASK) {
                    FindMyPayload.BATTERY_FULL -> FindMyInfo.BatteryLevel.FULL
                    FindMyPayload.BATTERY_MEDIUM -> FindMyInfo.BatteryLevel.MEDIUM
                    FindMyPayload.BATTERY_LOW -> FindMyInfo.BatteryLevel.LOW
                    FindMyPayload.BATTERY_CRITICAL -> FindMyInfo.BatteryLevel.CRITICAL
                    else -> FindMyInfo.BatteryLevel.UNKNOWN
                }
            }
        val fingerprint =
            if (payload.size == FindMyPayload.FULL_PAYLOAD_LENGTH) {
                buildString(3 + FindMyPayload.FINGERPRINT_LENGTH * 2) {
                    append("FM:")
                    for (
                    index in FindMyPayload.FINGERPRINT_START until
                        FindMyPayload.FINGERPRINT_START + FindMyPayload.FINGERPRINT_LENGTH
                    ) {
                        val value = payload[index].toInt() and 0xFF
                        append("0123456789ABCDEF"[value ushr 4])
                        append("0123456789ABCDEF"[value and 0x0F])
                    }
                }
            } else {
                null
            }

        return FindMyInfo(
            statusByte = statusByte,
            separatedFromOwner = !ownerRecentlyConnected,
            batteryLevel = batteryLevel,
            payloadFingerprint = fingerprint,
            rawPayload = payload,
        )
    }

    /**
     * Extract a full advertised Find My key, not a shared model/status profile.
     */
    fun extractPayloadFingerprint(
        manufacturerId: Int,
        payload: ByteArray?,
    ): String? {
        if (manufacturerId != ManufacturerId.APPLE || payload == null) return null
        return parseFindMyPayload(payload)?.payloadFingerprint
    }

    /**
     * Extract only validated Find My or Find Hub identity bytes.
     * Service UUIDs, manufacturer prefixes, and device profiles are not physical IDs.
     */
    fun extractBestFingerprint(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<ParcelUuid>?,
        serviceData: Map<ParcelUuid, ByteArray> = emptyMap(),
    ): String? {
        // Try Apple payload fingerprint first (most specific for Apple devices)
        if (manufacturerId == ManufacturerId.APPLE) {
            val appleFingerprint = extractPayloadFingerprint(manufacturerId, manufacturerData)
            if (appleFingerprint != null) {
                return appleFingerprint
            }
        }

        return TrackerServiceDetector.findHubFingerprint(serviceData)
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
        val confidence: Float,
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
        val payloadFingerprint: String? = null,
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
}
