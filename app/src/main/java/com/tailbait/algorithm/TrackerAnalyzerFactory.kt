package com.tailbait.algorithm

import android.os.ParcelUuid
import com.tailbait.util.BeaconDetectionUtils
import com.tailbait.util.ManufacturerDataParser
import com.tailbait.util.SignalStrength
import timber.log.Timber

/**
 * Factory pattern for manufacturer-specific tracker analyzers.
 *
 * Learned from Nordic nRF-Toolbox's ServiceManagerFactory which maps service UUIDs
 * to specialized service managers. This pattern provides:
 * - Modular, extensible analyzer architecture
 * - Manufacturer-specific parsing logic
 * - Easy addition of new tracker types
 *
 * ## Architecture
 * Each manufacturer has a dedicated TrackerAnalyzer that understands:
 * - Manufacturer data format
 * - Service UUID patterns
 * - Device identification heuristics
 * - Threat scoring factors
 *
 * ## Supported Manufacturers
 * - Apple (AirTag, AirPods, Find My accessories)
 * - Samsung (SmartTag, SmartTag+)
 * - Tile (all Tile tracker models)
 * - Chipolo (ONE, CARD, etc.)
 * - Google (Find My Device Network participants)
 */
object TrackerAnalyzerFactory {

    // ============================================================================
    // TRACKER ANALYSIS RESULT
    // ============================================================================

    /**
     * Comprehensive result from tracker analysis.
     */
    data class TrackerAnalysis(
        val isTracker: Boolean,
        val trackerType: TrackerServiceDetector.TrackerType,
        val manufacturerName: String,
        val deviceModel: String?,
        val confidence: Float,
        val threatLevel: ThreatLevel,
        val signalStrength: SignalStrength,
        val beaconType: BeaconDetectionUtils.BeaconType?,
        val additionalInfo: Map<String, Any> = emptyMap()
    )

    /**
     * Threat level classification based on device characteristics.
     */
    enum class ThreatLevel {
        /** Not a tracker, no threat */
        NONE,
        /** Tracker detected but likely user's own device or known safe */
        LOW,
        /** Tracker detected, moderate suspicion */
        MEDIUM,
        /** Tracker detected with suspicious characteristics */
        HIGH,
        /** Tracker detected with strong indicators of stalking */
        CRITICAL
    }

    // ============================================================================
    // TRACKER ANALYZER INTERFACE
    // ============================================================================

    /**
     * Interface for manufacturer-specific tracker analyzers.
     */
    interface TrackerAnalyzer {
        /** Manufacturer ID this analyzer handles */
        val manufacturerId: Int

        /** Analyze scan data and return tracker analysis */
        fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis?
    }

    // ============================================================================
    // APPLE ANALYZER
    // ============================================================================

    /**
     * Analyzer for Apple devices (AirTag, AirPods, Find My accessories).
     *
     * Apple uses the Continuity protocol with different message types:
     * - 0x12 (Find My): AirTag and Find My accessories - HIGHEST THREAT
     * - 0x07 (Proximity Pairing): AirPods, Beats - Lower threat
     * - 0x10 (Nearby Info): iPhone, iPad - Low threat
     */
    private object AppleAnalyzer : TrackerAnalyzer {
        override val manufacturerId = ManufacturerDataParser.ManufacturerId.APPLE

        override fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis? {
            if (manufacturerData == null || manufacturerData.isEmpty()) return null

            val messageType = manufacturerData[0].toInt() and 0xFF
            val signalStrength = SignalStrength.fromRssi(rssi)

            return when (messageType) {
                ManufacturerDataParser.AppleContinuityType.FIND_MY -> {
                    // Parse Find My payload for additional info
                    val findMyInfo = ManufacturerDataParser.parseFindMyPayload(manufacturerData)
                    val isSeparated = findMyInfo?.separatedFromOwner ?: false

                    // Separated AirTag = CRITICAL threat
                    val threatLevel = when {
                        isSeparated && signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.CRITICAL
                        isSeparated -> ThreatLevel.HIGH
                        signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                        else -> ThreatLevel.MEDIUM
                    }

                    TrackerAnalysis(
                        isTracker = true,
                        trackerType = TrackerServiceDetector.TrackerType.APPLE_AIRTAG,
                        manufacturerName = "Apple",
                        deviceModel = "AirTag",
                        confidence = 0.98f,
                        threatLevel = threatLevel,
                        signalStrength = signalStrength,
                        beaconType = BeaconDetectionUtils.BeaconType.FIND_MY,
                        additionalInfo = buildMap {
                            put("separatedFromOwner", isSeparated)
                            findMyInfo?.let {
                                put("batteryLevel", it.batteryLevel.name)
                                put("fingerprint", it.payloadFingerprint)
                            }
                        }
                    )
                }

                ManufacturerDataParser.AppleContinuityType.PROXIMITY_PAIRING -> {
                    // AirPods/Beats - lower threat but still trackable
                    val model = parseAirPodsModel(manufacturerData)

                    TrackerAnalysis(
                        isTracker = false,  // AirPods aren't trackers per se
                        trackerType = TrackerServiceDetector.TrackerType.NOT_A_TRACKER,
                        manufacturerName = "Apple",
                        deviceModel = model ?: "AirPods",
                        confidence = 0.90f,
                        threatLevel = ThreatLevel.LOW,
                        signalStrength = signalStrength,
                        beaconType = BeaconDetectionUtils.BeaconType.PROXIMITY_PAIRING
                    )
                }

                else -> {
                    // Other Apple devices (iPhone, iPad, etc.)
                    TrackerAnalysis(
                        isTracker = false,
                        trackerType = TrackerServiceDetector.TrackerType.NOT_A_TRACKER,
                        manufacturerName = "Apple",
                        deviceModel = inferAppleDeviceModel(messageType),
                        confidence = 0.70f,
                        threatLevel = ThreatLevel.NONE,
                        signalStrength = signalStrength,
                        beaconType = null
                    )
                }
            }
        }

        private fun parseAirPodsModel(data: ByteArray): String? {
            if (data.size < 3) return null
            val modelId = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return when (modelId) {
                0x0220 -> "AirPods (1st gen)"
                0x0F20 -> "AirPods (2nd gen)"
                0x1420 -> "AirPods (3rd gen)"
                0x0E20 -> "AirPods Pro"
                0x0A20 -> "AirPods Max"
                0x0B20 -> "Powerbeats Pro"
                0x1120 -> "Beats Studio Buds"
                0x1220 -> "Beats Fit Pro"
                else -> null
            }
        }

        private fun inferAppleDeviceModel(messageType: Int): String {
            return when (messageType) {
                0x10 -> "iPhone/iPad"
                0x0C -> "iPhone/iPad/Mac (Handoff)"
                0x0B -> "Apple Watch"
                0x05 -> "iPhone/iPad (AirDrop)"
                0x0D, 0x0E -> "iPhone (Hotspot)"
                else -> "Apple Device"
            }
        }
    }

    // ============================================================================
    // SAMSUNG ANALYZER
    // ============================================================================

    /**
     * Analyzer for Samsung devices (SmartTag, SmartTag+).
     *
     * Samsung SmartTags are best detected via service UUID 0xFD5A.
     * Manufacturer data provides additional device info.
     */
    private object SamsungAnalyzer : TrackerAnalyzer {
        override val manufacturerId = ManufacturerDataParser.ManufacturerId.SAMSUNG

        override fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis {
            val signalStrength = SignalStrength.fromRssi(rssi)
            val isSmartTag = TrackerServiceDetector.isSamsungSmartTag(serviceUuids)

            if (isSmartTag) {
                val threatLevel = when {
                    signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                    signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.MEDIUM
                    else -> ThreatLevel.LOW
                }

                return TrackerAnalysis(
                    isTracker = true,
                    trackerType = TrackerServiceDetector.TrackerType.SAMSUNG_SMARTTAG,
                    manufacturerName = "Samsung",
                    deviceModel = "SmartTag",
                    confidence = 0.95f,
                    threatLevel = threatLevel,
                    signalStrength = signalStrength,
                    beaconType = null
                )
            }

            // Not a SmartTag, likely a Samsung phone/tablet
            return TrackerAnalysis(
                isTracker = false,
                trackerType = TrackerServiceDetector.TrackerType.NOT_A_TRACKER,
                manufacturerName = "Samsung",
                deviceModel = deviceName ?: "Samsung Galaxy",
                confidence = 0.65f,
                threatLevel = ThreatLevel.NONE,
                signalStrength = signalStrength,
                beaconType = null
            )
        }
    }

    // ============================================================================
    // TILE ANALYZER
    // ============================================================================

    /**
     * Analyzer for Tile trackers.
     *
     * Tile devices advertise a custom service UUID and manufacturer ID 0x0099.
     */
    private object TileAnalyzer : TrackerAnalyzer {
        override val manufacturerId = ManufacturerDataParser.ManufacturerId.TILE

        override fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis {
            val signalStrength = SignalStrength.fromRssi(rssi)

            val threatLevel = when {
                signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.MEDIUM
                else -> ThreatLevel.LOW
            }

            // Try to determine Tile model from name or data
            val model = when {
                deviceName?.contains("Pro", ignoreCase = true) == true -> "Tile Pro"
                deviceName?.contains("Mate", ignoreCase = true) == true -> "Tile Mate"
                deviceName?.contains("Slim", ignoreCase = true) == true -> "Tile Slim"
                deviceName?.contains("Sticker", ignoreCase = true) == true -> "Tile Sticker"
                else -> "Tile"
            }

            return TrackerAnalysis(
                isTracker = true,
                trackerType = TrackerServiceDetector.TrackerType.TILE,
                manufacturerName = "Tile",
                deviceModel = model,
                confidence = 0.95f,
                threatLevel = threatLevel,
                signalStrength = signalStrength,
                beaconType = null
            )
        }
    }

    // ============================================================================
    // CHIPOLO ANALYZER
    // ============================================================================

    /**
     * Analyzer for Chipolo trackers.
     */
    private object ChipoloAnalyzer : TrackerAnalyzer {
        override val manufacturerId = ManufacturerDataParser.ManufacturerId.CHIPOLO

        override fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis {
            val signalStrength = SignalStrength.fromRssi(rssi)

            val threatLevel = when {
                signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.MEDIUM
                else -> ThreatLevel.LOW
            }

            val model = when {
                deviceName?.contains("ONE", ignoreCase = true) == true -> "Chipolo ONE"
                deviceName?.contains("CARD", ignoreCase = true) == true -> "Chipolo CARD"
                else -> "Chipolo"
            }

            return TrackerAnalysis(
                isTracker = true,
                trackerType = TrackerServiceDetector.TrackerType.CHIPOLO,
                manufacturerName = "Chipolo",
                deviceModel = model,
                confidence = 0.95f,
                threatLevel = threatLevel,
                signalStrength = signalStrength,
                beaconType = null
            )
        }
    }

    // ============================================================================
    // GOOGLE ANALYZER
    // ============================================================================

    /**
     * Analyzer for Google devices (Pixel, Find My Device Network).
     */
    private object GoogleAnalyzer : TrackerAnalyzer {
        override val manufacturerId = ManufacturerDataParser.ManufacturerId.GOOGLE

        override fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis {
            val signalStrength = SignalStrength.fromRssi(rssi)
            val isFindMy = TrackerServiceDetector.isGoogleFindMy(serviceUuids)

            if (isFindMy) {
                val threatLevel = when {
                    signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                    signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.MEDIUM
                    else -> ThreatLevel.LOW
                }

                return TrackerAnalysis(
                    isTracker = true,
                    trackerType = TrackerServiceDetector.TrackerType.GOOGLE_FIND_MY_DEVICE,
                    manufacturerName = "Google",
                    deviceModel = "Find My Device Tracker",
                    confidence = 0.90f,
                    threatLevel = threatLevel,
                    signalStrength = signalStrength,
                    beaconType = null
                )
            }

            // Not a tracker, likely a Pixel phone
            return TrackerAnalysis(
                isTracker = false,
                trackerType = TrackerServiceDetector.TrackerType.NOT_A_TRACKER,
                manufacturerName = "Google",
                deviceModel = deviceName ?: "Pixel",
                confidence = 0.70f,
                threatLevel = ThreatLevel.NONE,
                signalStrength = signalStrength,
                beaconType = null
            )
        }
    }

    // ============================================================================
    // GENERIC TRACKER ANALYZER
    // ============================================================================

    /**
     * Analyzer for other known tracker manufacturers (Pebblebee, Cube, etc.).
     */
    private class GenericTrackerAnalyzer(
        override val manufacturerId: Int,
        private val name: String,
        private val trackerType: TrackerServiceDetector.TrackerType
    ) : TrackerAnalyzer {

        override fun analyze(
            manufacturerData: ByteArray?,
            serviceUuids: List<ParcelUuid>?,
            rssi: Int,
            deviceName: String?
        ): TrackerAnalysis {
            val signalStrength = SignalStrength.fromRssi(rssi)

            val threatLevel = when {
                signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.MEDIUM
                else -> ThreatLevel.LOW
            }

            return TrackerAnalysis(
                isTracker = true,
                trackerType = trackerType,
                manufacturerName = name,
                deviceModel = deviceName ?: name,
                confidence = 0.90f,
                threatLevel = threatLevel,
                signalStrength = signalStrength,
                beaconType = null
            )
        }
    }

    // ============================================================================
    // FACTORY REGISTRY
    // ============================================================================

    /**
     * Registry of manufacturer ID to analyzer mappings.
     */
    private val analyzers: Map<Int, TrackerAnalyzer> = mapOf(
        ManufacturerDataParser.ManufacturerId.APPLE to AppleAnalyzer,
        ManufacturerDataParser.ManufacturerId.SAMSUNG to SamsungAnalyzer,
        ManufacturerDataParser.ManufacturerId.TILE to TileAnalyzer,
        ManufacturerDataParser.ManufacturerId.CHIPOLO to ChipoloAnalyzer,
        ManufacturerDataParser.ManufacturerId.GOOGLE to GoogleAnalyzer,
        ManufacturerDataParser.ManufacturerId.PEBBLEBEE to GenericTrackerAnalyzer(
            ManufacturerDataParser.ManufacturerId.PEBBLEBEE,
            "Pebblebee",
            TrackerServiceDetector.TrackerType.PEBBLEBEE
        ),
        ManufacturerDataParser.ManufacturerId.CUBE to GenericTrackerAnalyzer(
            ManufacturerDataParser.ManufacturerId.CUBE,
            "Cube",
            TrackerServiceDetector.TrackerType.CUBE
        )
    )

    // ============================================================================
    // FACTORY METHODS
    // ============================================================================

    /**
     * Get the appropriate analyzer for a manufacturer ID.
     *
     * @param manufacturerId Bluetooth SIG company identifier
     * @return TrackerAnalyzer if one exists for this manufacturer, null otherwise
     */
    fun getAnalyzer(manufacturerId: Int): TrackerAnalyzer? {
        return analyzers[manufacturerId]
    }

    /**
     * Analyze a device using the appropriate manufacturer-specific analyzer.
     *
     * @param manufacturerId Bluetooth SIG company identifier
     * @param manufacturerData Manufacturer-specific data payload
     * @param serviceUuids List of advertised service UUIDs
     * @param rssi Signal strength in dBm
     * @param deviceName Device name from advertisement
     * @return TrackerAnalysis result or null if no analyzer available
     */
    fun analyze(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<ParcelUuid>?,
        rssi: Int,
        deviceName: String?
    ): TrackerAnalysis? {
        if (manufacturerId == null) return null

        val analyzer = getAnalyzer(manufacturerId)
        if (analyzer != null) {
            return analyzer.analyze(manufacturerData, serviceUuids, rssi, deviceName)
        }

        // Check service UUIDs for trackers from unknown manufacturers
        val serviceDetection = TrackerServiceDetector.detectTracker(serviceUuids)
        if (serviceDetection.isTracker) {
            val signalStrength = SignalStrength.fromRssi(rssi)
            val threatLevel = when {
                signalStrength >= SignalStrength.STRONG -> ThreatLevel.HIGH
                signalStrength >= SignalStrength.MEDIUM -> ThreatLevel.MEDIUM
                else -> ThreatLevel.LOW
            }

            return TrackerAnalysis(
                isTracker = true,
                trackerType = serviceDetection.trackerType,
                manufacturerName = serviceDetection.manufacturerName ?: "Unknown",
                deviceModel = null,
                confidence = serviceDetection.confidence,
                threatLevel = threatLevel,
                signalStrength = signalStrength,
                beaconType = null
            )
        }

        return null
    }

    /**
     * Check if we have an analyzer for a given manufacturer.
     */
    fun hasAnalyzer(manufacturerId: Int): Boolean {
        return analyzers.containsKey(manufacturerId)
    }

    /**
     * Get all supported manufacturer IDs.
     */
    fun getSupportedManufacturers(): Set<Int> {
        return analyzers.keys
    }
}
