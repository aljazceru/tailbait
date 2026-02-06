package com.tailbait.algorithm

import com.tailbait.data.database.entities.ScannedDevice
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects MAC address rotation patterns from a group of BLE devices
 * that share the same shadow key (coarse device profile).
 *
 * A device rotating MACs every ~15 minutes creates a distinctive
 * birth-death pattern: at each rotation boundary, one MAC "dies"
 * (lastSeen) and another "is born" (firstSeen). Regular hand-off
 * intervals are strong evidence of a single physical device.
 *
 * ## Algorithm
 * 1. Sort devices by firstSeen ascending
 * 2. Find hand-offs: device N's lastSeen within [MAX_HANDOFF_GAP_MS] of device N+1's firstSeen
 * 3. Compute hand-off intervals (time between consecutive firstSeen values)
 * 4. Calculate regularity via coefficient of variation
 * 5. Score = (handOffCount / (deviceCount - 1)) * regularity
 * 6. Minimum 2 hand-offs required for any score
 */
@Singleton
class MacRotationDetector @Inject constructor() {

    companion object {
        /** Maximum gap between one device's lastSeen and the next device's firstSeen
         *  to consider it a hand-off (MAC rotation event). */
        const val MAX_HANDOFF_GAP_MS = 5 * 60 * 1000L // 5 minutes
    }

    /**
     * Result of MAC rotation detection analysis.
     *
     * @property score Rotation confidence score (0.0-1.0). Higher = more likely single device rotating.
     * @property handOffCount Number of detected hand-off events between consecutive devices.
     * @property averageIntervalMs Average time between consecutive firstSeen timestamps in milliseconds.
     * @property isRegular True if hand-off intervals show low variance (CV < 0.5).
     */
    data class RotationResult(
        val score: Double,
        val handOffCount: Int,
        val averageIntervalMs: Long,
        val isRegular: Boolean
    )

    /**
     * Analyze a list of devices sharing the same shadow key for MAC rotation patterns.
     *
     * @param devices All devices with the same shadow key, in any order.
     * @return RotationResult with score and pattern metrics.
     */
    fun detectRotation(devices: List<ScannedDevice>): RotationResult {
        if (devices.size < 2) {
            return RotationResult(score = 0.0, handOffCount = 0, averageIntervalMs = 0, isRegular = false)
        }

        // Sort by firstSeen ascending to establish temporal order
        val sorted = devices.sortedBy { it.firstSeen }

        // Find hand-offs: device N's lastSeen is close to device N+1's firstSeen
        val handOffIndices = mutableListOf<Int>()
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].firstSeen - sorted[i].lastSeen
            // Gap should be small (device died, new one born shortly after)
            // and non-negative (they shouldn't overlap significantly)
            if (gap in -MAX_HANDOFF_GAP_MS..MAX_HANDOFF_GAP_MS) {
                handOffIndices.add(i)
            }
        }

        val handOffCount = handOffIndices.size

        if (handOffCount < 2) {
            return RotationResult(
                score = 0.0,
                handOffCount = handOffCount,
                averageIntervalMs = 0,
                isRegular = false
            )
        }

        // Compute intervals between consecutive firstSeen values at hand-off points
        val intervals = handOffIndices.map { i ->
            sorted[i + 1].firstSeen - sorted[i].firstSeen
        }

        val avgInterval = intervals.average().toLong()

        // Calculate regularity using coefficient of variation (stddev / mean)
        val mean = intervals.average()
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) stdDev / mean else 1.0
        val regularity = (1.0 - cv.coerceAtMost(1.0)).coerceAtLeast(0.0)
        val isRegular = cv < 0.5

        // Score: coverage of hand-offs * regularity
        val coverage = handOffCount.toDouble() / (sorted.size - 1)
        val score = (coverage * regularity).coerceIn(0.0, 1.0)

        return RotationResult(
            score = score,
            handOffCount = handOffCount,
            averageIntervalMs = avgInterval,
            isRegular = isRegular
        )
    }
}
