package com.tailbait.util

import android.os.ParcelUuid
import timber.log.Timber

/**
 * Comprehensive BLE device identifier that combines multiple identification signals.
 *
 * Uses a multi-signal approach for device identification:
 * 1. Service UUIDs (highest priority for trackers like Samsung SmartTag)
 * 2. Manufacturer data (Apple Continuity, Tile, Chipolo, etc.)
 * 3. BLE Appearance value (standard Bluetooth SIG device categories)
 * 4. Device name patterns
 *
 * ## Key Detection Capabilities
 * - Samsung SmartTag via Service UUID 0xFD5A
 * - Tile via Service UUID 0xFEED
 * - Google Fast Pair via Service UUID 0xFE2C
 * - Standard BLE device types via Appearance values
 */
object DeviceIdentifier {

    // ============================================================================
    // SERVICE UUIDS FOR TRACKER DETECTION
    // ============================================================================

    object ServiceUuid {
        // Tracker-specific Service UUIDs (CRITICAL for detection)
        const val SAMSUNG_SMARTTAG = "0000FD5A-0000-1000-8000-00805F9B34FB"
        const val SAMSUNG_SMARTTAG_SHORT = "FD5A"
        const val TILE = "0000FEED-0000-1000-8000-00805F9B34FB"
        const val TILE_SHORT = "FEED"

        // Google Fast Pair (for detecting Google-compatible devices)
        const val GOOGLE_FAST_PAIR = "0000FE2C-0000-1000-8000-00805F9B34FB"
        const val GOOGLE_FAST_PAIR_SHORT = "FE2C"

        // Apple (various services)
        const val APPLE_NEARBY = "D0611E78-BBB4-4591-A5F8-487910AE4366"
        const val APPLE_CONTINUITY = "7DFC6000-7D1C-4951-86AA-8D9728F8D66C"

        // Common device services
        const val HEART_RATE = "0000180D-0000-1000-8000-00805F9B34FB"
        const val BATTERY_SERVICE = "0000180F-0000-1000-8000-00805F9B34FB"
        const val DEVICE_INFORMATION = "0000180A-0000-1000-8000-00805F9B34FB"
        const val GENERIC_ACCESS = "00001800-0000-1000-8000-00805F9B34FB"
        const val GENERIC_ATTRIBUTE = "00001801-0000-1000-8000-00805F9B34FB"

        // Fitness/Health
        const val RUNNING_SPEED = "00001814-0000-1000-8000-00805F9B34FB"
        const val CYCLING_POWER = "00001818-0000-1000-8000-00805F9B34FB"
        const val FITNESS_MACHINE = "00001826-0000-1000-8000-00805F9B34FB"

        // Audio
        const val AUDIO_STREAM = "0000184E-0000-1000-8000-00805F9B34FB"
    }

    /**
     * Known tracker service UUIDs for quick lookup.
     */
    private val trackerServiceUuids = setOf(
        ServiceUuid.SAMSUNG_SMARTTAG.lowercase(),
        ServiceUuid.TILE.lowercase(),
    )

    /**
     * BLE chipset/module manufacturer IDs that shouldn't be displayed to users.
     *
     * These are semiconductor companies that make BLE chips/modules. Their company ID
     * may appear in BLE advertisements from devices using their chips, but showing
     * these names to users isn't helpful (e.g., "Interplan" means nothing to users).
     *
     * When we detect these IDs, we should rely on other identification methods
     * (device name, service UUIDs, appearance) instead of showing the chipset vendor.
     */
    private val chipsetManufacturerIds = setOf(
        ManufacturerDataParser.ManufacturerId.NORDIC,           // 0x0059 - Nordic Semiconductor
        ManufacturerDataParser.ManufacturerId.INTERPLAN,        // 0x0212 - Interplan Co., Ltd (BLE modules)
        ManufacturerDataParser.ManufacturerId.CYPRESS,          // 0x0131 - Cypress Semiconductor
        ManufacturerDataParser.ManufacturerId.DIALOG,           // 0x00D2 - Dialog Semiconductor
        ManufacturerDataParser.ManufacturerId.SILABS,           // 0x02FF - Silicon Labs
        ManufacturerDataParser.ManufacturerId.REALTEK,          // 0x005D - Realtek Semiconductor
        ManufacturerDataParser.ManufacturerId.MEDIATEK,         // 0x00A5 - MediaTek
        ManufacturerDataParser.ManufacturerId.RENESAS,          // 0x00E3 - Renesas Electronics
        ManufacturerDataParser.ManufacturerId.BROADCOM,         // 0x000F - Broadcom
        ManufacturerDataParser.ManufacturerId.TEXAS_INSTRUMENTS,// 0x000D - Texas Instruments
    )

    /**
     * Check if a manufacturer ID is a chipset/module vendor (not useful for display).
     */
    fun isChipsetManufacturer(manufacturerId: Int?): Boolean {
        return manufacturerId in chipsetManufacturerIds
    }

    /**
     * Short form of tracker service UUIDs (for matching 16-bit UUIDs).
     */
    private val trackerShortUuids = setOf(
        ServiceUuid.SAMSUNG_SMARTTAG_SHORT.lowercase(),
        ServiceUuid.TILE_SHORT.lowercase(),
    )

    // ============================================================================
    // BLE APPEARANCE VALUES (Bluetooth SIG Standard)
    // Source: https://www.bluetooth.com/specifications/assigned-numbers/
    // ============================================================================

    object Appearance {
        // Category ranges (upper 10 bits of 16-bit value)
        const val CATEGORY_UNKNOWN = 0x0000         // 0x0000 - 0x003F
        const val CATEGORY_PHONE = 0x0040           // 0x0040 - 0x007F
        const val CATEGORY_COMPUTER = 0x0080        // 0x0080 - 0x00BF
        const val CATEGORY_WATCH = 0x00C0           // 0x00C0 - 0x00FF
        const val CATEGORY_CLOCK = 0x0100           // 0x0100 - 0x013F
        const val CATEGORY_DISPLAY = 0x0140         // 0x0140 - 0x017F
        const val CATEGORY_REMOTE = 0x0180          // 0x0180 - 0x01BF
        const val CATEGORY_EYEGLASSES = 0x01C0      // 0x01C0 - 0x01FF
        const val CATEGORY_TAG = 0x0200             // 0x0200 - 0x023F (IMPORTANT!)
        const val CATEGORY_KEYRING = 0x0240         // 0x0240 - 0x027F
        const val CATEGORY_MEDIA_PLAYER = 0x0280    // 0x0280 - 0x02BF
        const val CATEGORY_BARCODE = 0x02C0         // 0x02C0 - 0x02FF
        const val CATEGORY_THERMOMETER = 0x0300     // 0x0300 - 0x033F
        const val CATEGORY_HEART_RATE = 0x0340      // 0x0340 - 0x037F
        const val CATEGORY_BLOOD_PRESSURE = 0x0380  // 0x0380 - 0x03BF
        const val CATEGORY_HID = 0x03C0             // 0x03C0 - 0x03FF
        const val CATEGORY_GLUCOSE = 0x0400         // 0x0400 - 0x043F
        const val CATEGORY_RUNNING = 0x0440         // 0x0440 - 0x047F
        const val CATEGORY_CYCLING = 0x0480         // 0x0480 - 0x04BF
        const val CATEGORY_PULSE_OX = 0x0C40        // 0x0C40 - 0x0C7F
        const val CATEGORY_OUTDOOR = 0x0540         // 0x0540 - 0x057F
        const val CATEGORY_WEARABLE = 0x0940        // 0x0940 - 0x097F
        const val CATEGORY_HEARING_AID = 0x0A40     // 0x0A40 - 0x0A7F

        // Specific appearance values
        const val GENERIC_PHONE = 0x0040
        const val GENERIC_COMPUTER = 0x0080
        const val GENERIC_WATCH = 0x00C0
        const val SPORTS_WATCH = 0x00C1
        const val GENERIC_TAG = 0x0200
        const val GENERIC_KEYRING = 0x0240
        const val GENERIC_MEDIA_PLAYER = 0x0280
        const val GENERIC_HEART_RATE = 0x0340
        const val HEART_RATE_BELT = 0x0341
        const val GENERIC_RUNNING = 0x0440
        const val IN_SHOE = 0x0441
        const val ON_SHOE = 0x0442
        const val ON_HIP = 0x0443
        const val GENERIC_CYCLING = 0x0480
        const val CYCLING_COMPUTER = 0x0481
        const val SPEED_SENSOR = 0x0482
        const val CADENCE_SENSOR = 0x0483
        const val POWER_SENSOR = 0x0484
        const val GENERIC_WEARABLE = 0x0940
        const val WRIST_WEARABLE = 0x0941
        const val GENERIC_HEARING_AID = 0x0A40
    }

    /**
     * Get device type category from BLE appearance value.
     */
    fun getAppearanceCategory(appearance: Int): Int {
        // Appearance is 16 bits: upper 10 bits = category, lower 6 bits = subcategory
        return appearance and 0xFFC0  // Mask to get category (bits 6-15)
    }

    /**
     * Get device type from BLE appearance value.
     */
    fun getDeviceTypeFromAppearance(appearance: Int?): ManufacturerDataParser.DeviceType {
        if (appearance == null) return ManufacturerDataParser.DeviceType.UNKNOWN

        val category = getAppearanceCategory(appearance)

        return when {
            category == Appearance.CATEGORY_PHONE -> ManufacturerDataParser.DeviceType.PHONE
            category == Appearance.CATEGORY_COMPUTER -> ManufacturerDataParser.DeviceType.COMPUTER
            category == Appearance.CATEGORY_WATCH -> ManufacturerDataParser.DeviceType.WATCH
            category == Appearance.CATEGORY_TAG -> ManufacturerDataParser.DeviceType.TRACKER
            category == Appearance.CATEGORY_KEYRING -> ManufacturerDataParser.DeviceType.TRACKER
            category == Appearance.CATEGORY_MEDIA_PLAYER -> ManufacturerDataParser.DeviceType.SPEAKER
            category == Appearance.CATEGORY_HEART_RATE -> ManufacturerDataParser.DeviceType.FITNESS_BAND
            category == Appearance.CATEGORY_RUNNING -> ManufacturerDataParser.DeviceType.FITNESS_BAND
            category == Appearance.CATEGORY_CYCLING -> ManufacturerDataParser.DeviceType.FITNESS_BAND
            category == Appearance.CATEGORY_WEARABLE -> ManufacturerDataParser.DeviceType.FITNESS_BAND
            category == Appearance.CATEGORY_HEARING_AID -> ManufacturerDataParser.DeviceType.HEADPHONES
            else -> ManufacturerDataParser.DeviceType.UNKNOWN
        }
    }

    /**
     * Get human-readable appearance name.
     */
    fun getAppearanceName(appearance: Int?): String {
        if (appearance == null) return "Unknown"

        return when (appearance) {
            Appearance.GENERIC_PHONE -> "Phone"
            Appearance.GENERIC_COMPUTER -> "Computer"
            Appearance.GENERIC_WATCH -> "Watch"
            Appearance.SPORTS_WATCH -> "Sports Watch"
            Appearance.GENERIC_TAG -> "Tag"
            Appearance.GENERIC_KEYRING -> "Keyring"
            Appearance.GENERIC_MEDIA_PLAYER -> "Media Player"
            Appearance.GENERIC_HEART_RATE -> "Heart Rate Monitor"
            Appearance.HEART_RATE_BELT -> "Heart Rate Belt"
            Appearance.GENERIC_RUNNING -> "Running Sensor"
            Appearance.IN_SHOE -> "In-Shoe Sensor"
            Appearance.ON_SHOE -> "On-Shoe Sensor"
            Appearance.ON_HIP -> "Hip Sensor"
            Appearance.GENERIC_CYCLING -> "Cycling Sensor"
            Appearance.CYCLING_COMPUTER -> "Cycling Computer"
            Appearance.SPEED_SENSOR -> "Speed Sensor"
            Appearance.CADENCE_SENSOR -> "Cadence Sensor"
            Appearance.POWER_SENSOR -> "Power Sensor"
            Appearance.GENERIC_WEARABLE -> "Wearable"
            Appearance.WRIST_WEARABLE -> "Wrist Wearable"
            Appearance.GENERIC_HEARING_AID -> "Hearing Aid"
            else -> {
                val category = getAppearanceCategory(appearance)
                when (category) {
                    Appearance.CATEGORY_PHONE -> "Phone"
                    Appearance.CATEGORY_COMPUTER -> "Computer"
                    Appearance.CATEGORY_WATCH -> "Watch"
                    Appearance.CATEGORY_TAG -> "Tag/Tracker"
                    Appearance.CATEGORY_KEYRING -> "Keyring"
                    Appearance.CATEGORY_WEARABLE -> "Wearable"
                    else -> "Unknown (0x${appearance.toString(16).uppercase()})"
                }
            }
        }
    }

    // ============================================================================
    // SERVICE UUID ANALYSIS
    // ============================================================================

    /**
     * Check if any service UUID indicates a tracking device.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return True if device is identified as a tracker via service UUID
     */
    fun isTrackerByServiceUuid(serviceUuids: List<ParcelUuid>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false

        return serviceUuids.any { uuid ->
            val uuidString = uuid.uuid.toString().lowercase()
            val shortUuid = extractShortUuid(uuidString)

            // Check full UUID
            if (uuidString in trackerServiceUuids) {
                Timber.d("Tracker detected via service UUID: $uuidString")
                return true
            }

            // Check short UUID
            if (shortUuid != null && shortUuid in trackerShortUuids) {
                Timber.d("Tracker detected via short service UUID: $shortUuid")
                return true
            }

            false
        }
    }

    /**
     * Identify device model from service UUIDs.
     *
     * @param serviceUuids List of service UUIDs from BLE advertisement
     * @return Device model name or null if not identifiable
     */
    fun getDeviceModelFromServiceUuid(serviceUuids: List<ParcelUuid>?): String? {
        if (serviceUuids.isNullOrEmpty()) return null

        for (uuid in serviceUuids) {
            val uuidString = uuid.uuid.toString().lowercase()
            val shortUuid = extractShortUuid(uuidString)

            when {
                uuidString == ServiceUuid.SAMSUNG_SMARTTAG.lowercase() ||
                shortUuid == ServiceUuid.SAMSUNG_SMARTTAG_SHORT.lowercase() -> {
                    return "Samsung SmartTag"
                }
                uuidString == ServiceUuid.TILE.lowercase() ||
                shortUuid == ServiceUuid.TILE_SHORT.lowercase() -> {
                    return "Tile"
                }
                uuidString == ServiceUuid.GOOGLE_FAST_PAIR.lowercase() ||
                shortUuid == ServiceUuid.GOOGLE_FAST_PAIR_SHORT.lowercase() -> {
                    return "Google Fast Pair Device"
                }
            }
        }

        return null
    }

    /**
     * Get device type from service UUIDs.
     */
    fun getDeviceTypeFromServiceUuids(serviceUuids: List<ParcelUuid>?): ManufacturerDataParser.DeviceType? {
        if (serviceUuids.isNullOrEmpty()) return null

        for (uuid in serviceUuids) {
            val uuidString = uuid.uuid.toString().lowercase()
            val shortUuid = extractShortUuid(uuidString)

            // Tracker detection
            if (isTrackerByServiceUuid(listOf(uuid))) {
                return ManufacturerDataParser.DeviceType.TRACKER
            }

            // Fitness device detection
            when {
                shortUuid == "180d" || shortUuid == "1814" ||
                shortUuid == "1818" || shortUuid == "1826" -> {
                    return ManufacturerDataParser.DeviceType.FITNESS_BAND
                }
            }
        }

        return null
    }

    /**
     * Extract 16-bit short UUID from full 128-bit UUID.
     * Standard Bluetooth Base UUID: XXXXXXXX-0000-1000-8000-00805F9B34FB
     */
    private fun extractShortUuid(fullUuid: String): String? {
        // Check if it's a standard Bluetooth UUID
        if (fullUuid.endsWith("-0000-1000-8000-00805f9b34fb")) {
            val shortPart = fullUuid.substring(4, 8)
            return shortPart.lowercase()
        }
        return null
    }

    /**
     * Convert service UUIDs to a comma-separated string for storage.
     */
    fun serviceUuidsToString(serviceUuids: List<ParcelUuid>?): String? {
        if (serviceUuids.isNullOrEmpty()) return null
        return serviceUuids.joinToString(",") { uuid ->
            extractShortUuid(uuid.uuid.toString().lowercase())
                ?: uuid.uuid.toString().uppercase()
        }
    }

    /**
     * Parse service UUIDs from comma-separated string.
     */
    fun stringToServiceUuids(uuidsString: String?): List<String> {
        if (uuidsString.isNullOrBlank()) return emptyList()
        return uuidsString.split(",").map { it.trim() }
    }

    // ============================================================================
    // COMPREHENSIVE DEVICE IDENTIFICATION
    // ============================================================================

    /**
     * Result of comprehensive device identification.
     */
    data class IdentificationResult(
        val deviceType: ManufacturerDataParser.DeviceType,
        val deviceModel: String?,
        val isTracker: Boolean,
        val manufacturerId: Int?,
        val manufacturerName: String?,
        val appleContinuityType: Int?,
        val confidence: Float,
        val identificationMethod: String
    )

    /**
     * Perform comprehensive device identification using all available signals.
     *
     * Priority order:
     * 1. Service UUIDs (highest - most reliable for trackers)
     * 2. Manufacturer data (Apple Continuity, known trackers)
     * 3. BLE Appearance (standard category)
     * 4. Device name patterns (lowest priority)
     *
     * @param manufacturerId Bluetooth SIG company identifier (from sparse array key)
     * @param manufacturerData Raw manufacturer data bytes (payload only, without ID)
     * @param serviceUuids List of advertised service UUIDs
     * @param appearance BLE appearance value
     * @param deviceName Advertised device name
     * @return Comprehensive identification result
     */
    fun identifyDevice(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<ParcelUuid>?,
        appearance: Int?,
        deviceName: String?
    ): IdentificationResult {
        // Track the best identification
        var bestType = ManufacturerDataParser.DeviceType.UNKNOWN
        var bestModel: String? = null
        var isTracker = false
        var resolvedManufacturerId: Int? = manufacturerId

        // Get manufacturer name, but filter out chipset/module vendors
        // (their names aren't useful to users - e.g., "Interplan" means nothing)
        var manufacturerName: String? = manufacturerId?.let { id ->
            // Don't show chipset manufacturer names to users
            if (isChipsetManufacturer(id)) {
                Timber.d("Filtering out chipset manufacturer: ${ManufacturerDataParser.getManufacturerName(id)} (0x${id.toString(16).uppercase()})")
                null
            } else {
                ManufacturerDataParser.getManufacturerName(id)
                    .takeIf { !it.startsWith("Unknown") }
            }
        }
        var appleContinuityType: Int? = null
        var confidence = 0.0f
        var method = "NONE"

        // 1. SERVICE UUID ANALYSIS (highest priority for trackers)
        if (!serviceUuids.isNullOrEmpty()) {
            if (isTrackerByServiceUuid(serviceUuids)) {
                isTracker = true
                bestType = ManufacturerDataParser.DeviceType.TRACKER
                bestModel = getDeviceModelFromServiceUuid(serviceUuids)
                confidence = 0.95f
                method = "SERVICE_UUID"

                // Set manufacturer based on detected model (override if not already known)
                when (bestModel) {
                    "Samsung SmartTag" -> {
                        if (resolvedManufacturerId == null) resolvedManufacturerId = ManufacturerDataParser.ManufacturerId.SAMSUNG
                        if (manufacturerName == null) manufacturerName = "Samsung"
                    }
                    "Tile" -> {
                        if (resolvedManufacturerId == null) resolvedManufacturerId = ManufacturerDataParser.ManufacturerId.TILE
                        if (manufacturerName == null) manufacturerName = "Tile"
                    }
                }
            } else {
                // Check for other device types via service UUID
                getDeviceTypeFromServiceUuids(serviceUuids)?.let { type ->
                    if (type != ManufacturerDataParser.DeviceType.UNKNOWN) {
                        bestType = type
                        confidence = 0.75f
                        method = "SERVICE_UUID"
                    }
                }
            }
        }

        // 2. MANUFACTURER DATA ANALYSIS
        // Use the overload that accepts manufacturer ID separately (from sparse array key)
        if (resolvedManufacturerId != null) {
            ManufacturerDataParser.parseManufacturerData(resolvedManufacturerId, manufacturerData)?.let { info ->
                // Update manufacturer name if we got a better one
                if (manufacturerName == null && info.manufacturerName != null &&
                    !info.manufacturerName.startsWith("Unknown")) {
                    manufacturerName = info.manufacturerName
                }
                appleContinuityType = info.appleContinuityType

                // Update if manufacturer data gives higher confidence
                if (info.identificationConfidence > confidence) {
                    bestType = info.deviceType
                    bestModel = info.deviceModel
                    isTracker = isTracker || info.isTracker
                    confidence = info.identificationConfidence
                    method = "MANUFACTURER_DATA"
                } else if (info.isTracker) {
                    // Always mark as tracker if manufacturer data says so
                    isTracker = true
                }
            }
        }

        // 3. BLE APPEARANCE ANALYSIS
        appearance?.let { app ->
            val appearanceType = getDeviceTypeFromAppearance(app)

            // Appearance says tracker - trust it
            if (appearanceType == ManufacturerDataParser.DeviceType.TRACKER) {
                isTracker = true
                if (confidence < 0.80f) {
                    bestType = appearanceType
                    bestModel = getAppearanceName(app)
                    confidence = 0.80f
                    method = "APPEARANCE"
                }
            }

            // Use appearance if no better identification
            if (bestType == ManufacturerDataParser.DeviceType.UNKNOWN &&
                appearanceType != ManufacturerDataParser.DeviceType.UNKNOWN) {
                bestType = appearanceType
                bestModel = getAppearanceName(app)
                confidence = 0.70f
                method = "APPEARANCE"
            }
        }

        // 4. DEVICE NAME PATTERN ANALYSIS (lowest priority)
        if (bestType == ManufacturerDataParser.DeviceType.UNKNOWN && !deviceName.isNullOrBlank()) {
            val nameLower = deviceName.lowercase()
            val (nameType, nameModel, nameIsTracker) = inferFromDeviceName(nameLower)

            if (nameType != ManufacturerDataParser.DeviceType.UNKNOWN) {
                bestType = nameType
                bestModel = nameModel ?: deviceName
                isTracker = isTracker || nameIsTracker
                confidence = 0.50f
                method = "DEVICE_NAME"
            }
        }

        return IdentificationResult(
            deviceType = bestType,
            deviceModel = bestModel,
            isTracker = isTracker,
            manufacturerId = resolvedManufacturerId,
            manufacturerName = manufacturerName,
            appleContinuityType = appleContinuityType,
            confidence = confidence,
            identificationMethod = method
        )
    }

    /**
     * Infer device type from device name patterns.
     */
    private fun inferFromDeviceName(nameLower: String): Triple<ManufacturerDataParser.DeviceType, String?, Boolean> {
        return when {
            // Trackers
            nameLower.contains("airtag") -> Triple(ManufacturerDataParser.DeviceType.TRACKER, "AirTag", true)
            nameLower.contains("smarttag") -> Triple(ManufacturerDataParser.DeviceType.TRACKER, "Samsung SmartTag", true)
            nameLower.contains("tile") && !nameLower.contains("reptile") ->
                Triple(ManufacturerDataParser.DeviceType.TRACKER, "Tile", true)
            nameLower.contains("chipolo") -> Triple(ManufacturerDataParser.DeviceType.TRACKER, "Chipolo", true)
            nameLower.contains("tracker") -> Triple(ManufacturerDataParser.DeviceType.TRACKER, null, true)

            // Audio devices
            nameLower.contains("airpods") -> Triple(ManufacturerDataParser.DeviceType.EARBUDS, "AirPods", false)
            nameLower.contains("buds") -> Triple(ManufacturerDataParser.DeviceType.EARBUDS, null, false)
            nameLower.contains("headphone") -> Triple(ManufacturerDataParser.DeviceType.HEADPHONES, null, false)
            nameLower.contains("speaker") -> Triple(ManufacturerDataParser.DeviceType.SPEAKER, null, false)

            // Wearables
            nameLower.contains("watch") -> Triple(ManufacturerDataParser.DeviceType.WATCH, null, false)
            nameLower.contains("band") || nameLower.contains("fit") ->
                Triple(ManufacturerDataParser.DeviceType.FITNESS_BAND, null, false)
            nameLower.contains("garmin") -> Triple(ManufacturerDataParser.DeviceType.WATCH, "Garmin", false)
            nameLower.contains("fitbit") -> Triple(ManufacturerDataParser.DeviceType.FITNESS_BAND, "Fitbit", false)

            // Phones
            nameLower.contains("iphone") -> Triple(ManufacturerDataParser.DeviceType.PHONE, "iPhone", false)
            nameLower.contains("galaxy") || nameLower.contains("samsung") ->
                Triple(ManufacturerDataParser.DeviceType.PHONE, "Samsung Galaxy", false)
            nameLower.contains("pixel") -> Triple(ManufacturerDataParser.DeviceType.PHONE, "Google Pixel", false)

            // Computers
            nameLower.contains("macbook") || nameLower.contains("imac") ->
                Triple(ManufacturerDataParser.DeviceType.COMPUTER, "Mac", false)
            nameLower.contains("laptop") || nameLower.contains("pc") ->
                Triple(ManufacturerDataParser.DeviceType.COMPUTER, null, false)

            else -> Triple(ManufacturerDataParser.DeviceType.UNKNOWN, null, false)
        }
    }
}
