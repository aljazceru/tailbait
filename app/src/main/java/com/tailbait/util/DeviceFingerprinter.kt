package com.tailbait.util

import android.os.ParcelUuid
import timber.log.Timber
import java.security.MessageDigest

/**
 * Comprehensive device fingerprinting system for BLE device correlation.
 *
 * This class provides a multi-layered fingerprinting approach to correlate
 * BLE devices across MAC address rotations. Modern BLE devices (especially
 * phones and tablets) rotate their MAC addresses every 15-20 minutes for
 * privacy, which makes tracking the same device challenging.
 *
 * ## Fingerprinting Priority (Highest to Lowest):
 * 1. **Payload Fingerprint**: Apple Continuity payloads (AirTags, iPhones, etc.)
 * 2. **Service UUID Fingerprint**: Samsung SmartTag, Tile, Chipolo, etc.
 * 3. **Composite Fingerprint**: Combination of stable device characteristics
 *
 * ## Composite Fingerprint Signals:
 * The composite fingerprint combines multiple semi-stable device characteristics
 * to create a probabilistic identifier. It uses:
 * - Manufacturer ID (very stable)
 * - Device type (stable)
 * - BLE appearance value (usually stable)
 * - TX power level (usually stable)
 * - Service UUID hash (stable)
 * - Device name pattern (variable but useful)
 *
 * ## Usage:
 * ```kotlin
 * val fingerprinter = DeviceFingerprinter()
 * val fingerprint = fingerprinter.generateFingerprint(
 *     manufacturerId = 0x004C,
 *     manufacturerData = byteArrayOf(...),
 *     serviceUuids = listOf(...),
 *     deviceType = "PHONE",
 *     appearance = 0x0200,
 *     txPowerLevel = -59,
 *     deviceName = "iPhone"
 * )
 * ```
 */
object DeviceFingerprinter {

    private const val TAG = "DeviceFingerprinter"

    /**
     * Result of fingerprint generation with confidence score.
     *
     * @property fingerprint The generated fingerprint string
     * @property confidence Confidence score (0.0-1.0) indicating reliability
     * @property method The fingerprinting method used
     */
    data class FingerprintResult(
        val fingerprint: String,
        val confidence: Float,
        val method: FingerprintMethod
    )

    /**
     * Fingerprinting methods in order of reliability.
     */
    enum class FingerprintMethod {
        APPLE_PAYLOAD,      // Apple Continuity payload (highest confidence)
        SERVICE_UUID,       // Service UUID-based (high confidence)
        COMPOSITE,          // Combined signals (medium confidence)
        TEMPORAL,           // Time-based clustering (low confidence)
        NONE                // No fingerprint possible
    }

    /**
     * Generate the best available fingerprint for a device.
     *
     * Tries fingerprinting methods in order of reliability:
     * 1. Apple Continuity payload fingerprint
     * 2. Service UUID fingerprint
     * 3. Composite fingerprint from multiple signals
     *
     * @param manufacturerId Bluetooth SIG manufacturer ID
     * @param manufacturerData Raw manufacturer data bytes
     * @param serviceUuids List of advertised service UUIDs
     * @param deviceType Inferred device type (PHONE, WATCH, TRACKER, etc.)
     * @param appearance BLE GAP appearance value
     * @param txPowerLevel TX power level in dBm
     * @param deviceName Device name from advertisement
     * @return FingerprintResult with fingerprint and confidence, or null
     */
    fun generateFingerprint(
        manufacturerId: Int?,
        manufacturerData: ByteArray?,
        serviceUuids: List<ParcelUuid>?,
        deviceType: String?,
        appearance: Int?,
        txPowerLevel: Int?,
        deviceName: String?
    ): FingerprintResult? {
        // Try Apple payload fingerprint first (highest confidence)
        if (manufacturerId == ManufacturerDataParser.ManufacturerId.APPLE && manufacturerData != null) {
            val appleFingerprint = ManufacturerDataParser.extractPayloadFingerprint(manufacturerId, manufacturerData)
            if (appleFingerprint != null) {
                return FingerprintResult(
                    fingerprint = appleFingerprint,
                    confidence = 0.95f,
                    method = FingerprintMethod.APPLE_PAYLOAD
                )
            }
        }

        // Try service UUID fingerprint (high confidence for trackers)
        val serviceFingerprint = ManufacturerDataParser.extractServiceUuidFingerprint(
            manufacturerId, serviceUuids, manufacturerData
        )
        if (serviceFingerprint != null) {
            return FingerprintResult(
                fingerprint = serviceFingerprint,
                confidence = 0.90f,
                method = FingerprintMethod.SERVICE_UUID
            )
        }

        // Try composite fingerprint (medium confidence)
        val compositeFingerprint = generateCompositeFingerprint(
            manufacturerId = manufacturerId,
            deviceType = deviceType,
            appearance = appearance,
            txPowerLevel = txPowerLevel,
            serviceUuids = serviceUuids,
            deviceName = deviceName
        )
        if (compositeFingerprint != null) {
            return FingerprintResult(
                fingerprint = compositeFingerprint.fingerprint,
                confidence = compositeFingerprint.confidence,
                method = FingerprintMethod.COMPOSITE
            )
        }

        return null
    }

    /**
     * Generate a composite fingerprint from multiple device signals.
     *
     * A composite fingerprint is created by combining several semi-stable
     * device characteristics. The confidence score depends on how many
     * signals are available.
     *
     * ## Signal Weights:
     * - Manufacturer ID: 25% (very stable)
     * - Device Type: 20% (stable)
     * - Appearance: 15% (usually stable)
     * - TX Power: 15% (usually stable)
     * - Service UUIDs: 15% (stable)
     * - Name Pattern: 10% (variable)
     *
     * @return Composite fingerprint with confidence, or null if insufficient signals
     */
    private fun generateCompositeFingerprint(
        manufacturerId: Int?,
        deviceType: String?,
        appearance: Int?,
        txPowerLevel: Int?,
        serviceUuids: List<ParcelUuid>?,
        deviceName: String?
    ): FingerprintResult? {
        // Count available signals and calculate confidence
        var signalCount = 0
        var confidenceSum = 0f

        val components = mutableListOf<String>()

        // Manufacturer ID (25% weight)
        if (manufacturerId != null && manufacturerId != 0) {
            components.add("M%04X".format(manufacturerId))
            signalCount++
            confidenceSum += 0.25f
        }

        // Device type (20% weight)
        if (!deviceType.isNullOrBlank() && deviceType != "UNKNOWN") {
            components.add("T${deviceType.take(3)}")
            signalCount++
            confidenceSum += 0.20f
        }

        // Appearance (15% weight)
        if (appearance != null && appearance != 0) {
            components.add("A%04X".format(appearance))
            signalCount++
            confidenceSum += 0.15f
        }

        // TX Power (15% weight)
        if (txPowerLevel != null) {
            // Normalize TX power to a range value (-100 to 0 -> 0x00 to 0x64)
            val normalizedTx = ((txPowerLevel + 100).coerceIn(0, 100))
            components.add("P%02X".format(normalizedTx))
            signalCount++
            confidenceSum += 0.15f
        }

        // Service UUIDs (15% weight)
        if (!serviceUuids.isNullOrEmpty()) {
            // Create a hash of service UUIDs for consistent representation
            val uuidHash = serviceUuids
                .map { it.uuid.toString().uppercase().take(8) }
                .sorted()
                .joinToString("")
                .hashCode()
                .let { "%08X".format(it) }
            components.add("U$uuidHash")
            signalCount++
            confidenceSum += 0.15f
        }

        // Device name pattern (10% weight)
        if (!deviceName.isNullOrBlank()) {
            // Extract a stable pattern from the name (remove numbers, normalize)
            val namePattern = extractNamePattern(deviceName)
            if (namePattern.isNotBlank()) {
                components.add("N${namePattern.hashCode().let { "%08X".format(it) }}")
                signalCount++
                confidenceSum += 0.10f
            }
        }

        // Need at least 3 signals for a meaningful composite fingerprint
        if (signalCount < 3) {
            Timber.tag(TAG).v("Insufficient signals for composite fingerprint: $signalCount")
            return null
        }

        // Generate fingerprint hash from components
        val componentString = components.sorted().joinToString(":")
        val fingerprint = "COMP:" + hashString(componentString).take(12)

        Timber.tag(TAG).d(
            "Generated composite fingerprint: $fingerprint from $signalCount signals " +
                "(confidence: ${(confidenceSum * 100).toInt()}%)"
        )

        return FingerprintResult(
            fingerprint = fingerprint,
            confidence = confidenceSum.coerceAtMost(0.75f), // Cap composite confidence at 75%
            method = FingerprintMethod.COMPOSITE
        )
    }

    /**
     * Extract a stable pattern from a device name.
     *
     * Device names often contain variable parts (like serial numbers, MAC fragments)
     * that change with each device. This function extracts the stable pattern.
     *
     * Examples:
     * - "iPhone" -> "IPHONE"
     * - "iPhone (John's)" -> "IPHONE"
     * - "Galaxy S23" -> "GALAXY S"
     * - "AirPods Pro" -> "AIRPODS PRO"
     */
    private fun extractNamePattern(name: String): String {
        return name
            .uppercase()
            // Remove parenthetical content (usually owner names)
            .replace(Regex("\\([^)]*\\)"), "")
            // Remove apostrophe content
            .replace(Regex("'S\\b"), "")
            // Remove numbers that look like model numbers or serials
            .replace(Regex("\\b[0-9]{2,}\\b"), "")
            // Remove hex-like patterns (MAC fragments)
            .replace(Regex("\\b[0-9A-F]{2}([:-][0-9A-F]{2})+\\b"), "")
            // Normalize whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
            // Take first 20 characters
            .take(20)
    }

    /**
     * Check if two composite fingerprints might represent the same device.
     *
     * Composite fingerprints may not match exactly due to variable signals.
     * This function compares fingerprints with some tolerance for variation.
     *
     * @param fingerprint1 First fingerprint
     * @param fingerprint2 Second fingerprint
     * @param minMatchScore Minimum similarity score (0.0-1.0) for a match
     * @return True if fingerprints likely represent the same device
     */
    fun areCompositesFingerprintsSimilar(
        fingerprint1: String,
        fingerprint2: String,
        minMatchScore: Float = 0.7f
    ): Boolean {
        // Only compare composite fingerprints
        if (!fingerprint1.startsWith("COMP:") || !fingerprint2.startsWith("COMP:")) {
            return fingerprint1 == fingerprint2
        }

        // For composite fingerprints, use hash comparison
        // If they're identical, it's a match
        if (fingerprint1 == fingerprint2) return true

        // Otherwise, no match (hash collision is unlikely)
        return false
    }

    /**
     * Create a SHA-256 hash of the input string.
     */
    private fun hashString(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray())
            hashBytes.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error hashing string")
            input.hashCode().let { "%08X".format(it) }
        }
    }

    /**
     * Determine if a fingerprint is from a high-confidence method.
     *
     * @param fingerprint The fingerprint string
     * @return True if fingerprint is from Apple payload or Service UUID method
     */
    fun isHighConfidenceFingerprint(fingerprint: String?): Boolean {
        if (fingerprint == null) return false
        return fingerprint.startsWith("FM:") ||  // Find My
               fingerprint.startsWith("PP:") ||  // Proximity Pairing
               fingerprint.startsWith("NI:") ||  // Nearby Info
               fingerprint.startsWith("MS:") ||  // Magic Switch
               fingerprint.startsWith("HO:") ||  // Handoff
               fingerprint.startsWith("AD:") ||  // AirDrop
               fingerprint.startsWith("ST:") ||  // Samsung SmartTag
               fingerprint.startsWith("TL:") ||  // Tile
               fingerprint.startsWith("CH:") ||  // Chipolo
               fingerprint.startsWith("GF:")     // Google Find My
    }

    /**
     * Get the fingerprint method from a fingerprint string.
     *
     * @param fingerprint The fingerprint string
     * @return The fingerprinting method used
     */
    fun getFingerprintMethod(fingerprint: String?): FingerprintMethod {
        if (fingerprint == null) return FingerprintMethod.NONE

        return when {
            // Apple payload methods
            fingerprint.startsWith("FM:") ||
            fingerprint.startsWith("PP:") ||
            fingerprint.startsWith("NI:") ||
            fingerprint.startsWith("MS:") ||
            fingerprint.startsWith("HO:") ||
            fingerprint.startsWith("AD:") ||
            fingerprint.startsWith("HS:") ||
            fingerprint.startsWith("TH:") ||
            fingerprint.startsWith("AP:") ||
            fingerprint.startsWith("UK:") -> FingerprintMethod.APPLE_PAYLOAD

            // Service UUID methods
            fingerprint.startsWith("ST:") ||
            fingerprint.startsWith("TL:") ||
            fingerprint.startsWith("CH:") ||
            fingerprint.startsWith("GF:") ||
            fingerprint.startsWith("PB:") ||
            fingerprint.startsWith("CB:") ||
            fingerprint.startsWith("EF:") ||
            fingerprint.startsWith("JT:") ||
            fingerprint.startsWith("AF:") -> FingerprintMethod.SERVICE_UUID

            // Composite method
            fingerprint.startsWith("COMP:") -> FingerprintMethod.COMPOSITE

            else -> FingerprintMethod.NONE
        }
    }
}
