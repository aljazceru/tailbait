package com.tailbait.util

/**
 * Multi-level RSSI signal strength classification for BLE devices.
 *
 * Learned from Nordic nRF-Connect-Device-Manager which uses a 4-level system
 * for signal strength indication. This provides better proximity confidence
 * than a single threshold.
 *
 * ## Signal Levels (based on typical BLE characteristics)
 * - **VERY_WEAK** (-100 to -80 dBm): Device is far away or obstructed
 * - **WEAK** (-80 to -65 dBm): Moderate distance, possibly in another room
 * - **MEDIUM** (-65 to -50 dBm): Close proximity, same room
 * - **STRONG** (-50 to -35 dBm): Very close, within a few meters
 * - **VERY_STRONG** (> -35 dBm): Extremely close, likely in pocket/bag
 *
 * ## Threat Score Weights
 * A stronger signal indicates the device is physically closer, which increases
 * the likelihood of intentional tracking if the device follows across locations.
 *
 * ## Reference Values (typical BLE)
 * - TX Power at 1m: -59 dBm (typical)
 * - Free space path loss: ~2 dB per doubling of distance
 * - Wall attenuation: 3-15 dB depending on material
 *
 * @property minRssi Minimum RSSI value (inclusive) for this level
 * @property maxRssi Maximum RSSI value (exclusive) for this level
 * @property threatWeight Weight factor for threat score calculation (0.0-1.0)
 * @property proximityDescription Human-readable proximity description
 */
enum class SignalStrength(
    val minRssi: Int,
    val maxRssi: Int,
    val threatWeight: Float,
    val proximityDescription: String
) {
    /**
     * Very weak signal: Device is far away (10+ meters) or heavily obstructed.
     * Low confidence in proximity - could be a neighbor's device.
     */
    VERY_WEAK(
        minRssi = -100,
        maxRssi = -80,
        threatWeight = 0.1f,
        proximityDescription = "Far away (10+ meters)"
    ),

    /**
     * Weak signal: Device is at moderate distance (5-10 meters).
     * Possibly in an adjacent room or behind walls.
     */
    WEAK(
        minRssi = -80,
        maxRssi = -65,
        threatWeight = 0.25f,
        proximityDescription = "Moderate distance (5-10 meters)"
    ),

    /**
     * Medium signal: Device is in close proximity (2-5 meters).
     * Likely in the same room or nearby area.
     */
    MEDIUM(
        minRssi = -65,
        maxRssi = -50,
        threatWeight = 0.5f,
        proximityDescription = "Close proximity (2-5 meters)"
    ),

    /**
     * Strong signal: Device is very close (1-2 meters).
     * High confidence the device is physically near the user.
     */
    STRONG(
        minRssi = -50,
        maxRssi = -35,
        threatWeight = 0.75f,
        proximityDescription = "Very close (1-2 meters)"
    ),

    /**
     * Very strong signal: Device is extremely close (< 1 meter).
     * Highest confidence - device is likely in user's bag, pocket, or on their person.
     * This is the most suspicious proximity for an unknown tracker.
     */
    VERY_STRONG(
        minRssi = -35,
        maxRssi = 0,
        threatWeight = 1.0f,
        proximityDescription = "Extremely close (< 1 meter)"
    );

    /**
     * Check if a given RSSI falls within this signal strength range.
     */
    fun containsRssi(rssi: Int): Boolean = rssi >= minRssi && rssi < maxRssi

    /**
     * Get the signal strength level as an integer (0-4) for UI display.
     * Higher values indicate stronger signal.
     */
    val level: Int
        get() = ordinal

    /**
     * Get the maximum signal level (for progress indicators).
     */
    val maxLevel: Int
        get() = entries.size - 1

    companion object {
        /**
         * Get the SignalStrength level for a given RSSI value.
         *
         * @param rssi The RSSI value in dBm (typically -100 to 0)
         * @return The corresponding SignalStrength level
         */
        fun fromRssi(rssi: Int): SignalStrength {
            return when {
                rssi >= VERY_STRONG.minRssi -> VERY_STRONG
                rssi >= STRONG.minRssi -> STRONG
                rssi >= MEDIUM.minRssi -> MEDIUM
                rssi >= WEAK.minRssi -> WEAK
                else -> VERY_WEAK
            }
        }

        /**
         * Get threat weight for a given RSSI value.
         *
         * @param rssi The RSSI value in dBm
         * @return Threat weight (0.0-1.0) based on signal strength
         */
        fun getThreatWeight(rssi: Int): Float = fromRssi(rssi).threatWeight

        /**
         * Get proximity description for a given RSSI value.
         *
         * @param rssi The RSSI value in dBm
         * @return Human-readable proximity description
         */
        fun getProximityDescription(rssi: Int): String = fromRssi(rssi).proximityDescription

        /**
         * Calculate the signal level (0-4) for UI progress bars.
         *
         * @param rssi The RSSI value in dBm
         * @return Signal level from 0 (weakest) to 4 (strongest)
         */
        fun getSignalLevel(rssi: Int): Int = fromRssi(rssi).level

        /**
         * Estimate approximate distance in meters from RSSI.
         *
         * Uses the log-distance path loss model with typical BLE parameters.
         * This is an approximation and can vary significantly based on:
         * - TX power of the device
         * - Environmental obstacles
         * - Device antenna characteristics
         *
         * Formula: distance = 10 ^ ((txPower - rssi) / (10 * n))
         * Where n is the path loss exponent (typically 2.0 for free space, 2.5-4.0 indoors)
         *
         * @param rssi The RSSI value in dBm
         * @param txPower The reference TX power at 1 meter (default -59 dBm)
         * @param pathLossExponent The path loss exponent (default 2.5 for indoor)
         * @return Estimated distance in meters
         */
        fun estimateDistance(
            rssi: Int,
            txPower: Int = -59,
            pathLossExponent: Double = 2.5
        ): Double {
            if (rssi == 0) return -1.0  // Unknown

            return if (rssi >= txPower) {
                // Very close, less than 1 meter
                Math.pow(10.0, (txPower - rssi) / (10.0 * pathLossExponent))
            } else {
                Math.pow(10.0, (txPower - rssi) / (10.0 * pathLossExponent))
            }
        }

        /**
         * Get a human-readable signal strength description.
         *
         * @param rssi The RSSI value in dBm
         * @return String like "Strong (-45 dBm)"
         */
        fun getDescription(rssi: Int): String {
            val strength = fromRssi(rssi)
            return "${strength.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }} ($rssi dBm)"
        }
    }
}

/**
 * Extension function to get SignalStrength from an Int RSSI value.
 */
fun Int.toSignalStrength(): SignalStrength = SignalStrength.fromRssi(this)

/**
 * Extension function to get threat weight from an Int RSSI value.
 */
fun Int.toThreatWeight(): Float = SignalStrength.getThreatWeight(this)
