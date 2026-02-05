package com.tailbait.service

import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.model.DetectionResult
import com.tailbait.data.model.ThreatLevel
import com.tailbait.data.repository.AlertRepository
import com.tailbait.util.Constants
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for generating alerts from detection results.
 *
 * The AlertGenerator is responsible for:
 * - Converting DetectionResults into AlertHistory entries
 * - Determining alert severity levels
 * - Creating user-friendly alert messages
 * - Preventing duplicate alerts
 * - Implementing alert throttling to avoid spamming users
 *
 * Alert Levels:
 * - CRITICAL: Threat score >= 0.9 (Very high confidence of stalking)
 * - HIGH: Threat score >= 0.75 (High confidence of stalking)
 * - MEDIUM: Threat score >= 0.6 (Moderate confidence of stalking)
 * - LOW: Threat score >= 0.5 (Low confidence, but worth flagging)
 *
 * Duplicate Prevention:
 * - Checks for similar recent alerts (same devices) within throttle window
 * - Default throttle window: 1 hour (configurable)
 * - Prevents notification spam for the same device
 *
 * Thread Safety:
 * This class is thread-safe and can be safely used as a singleton.
 */
@Singleton
class AlertGenerator @Inject constructor(
    private val alertRepository: AlertRepository
) {

    companion object {
        // Default throttle window: 1 hour
        private const val DEFAULT_THROTTLE_WINDOW_MS = 60 * 60 * 1000L

        // Alert message templates
        private const val ALERT_TITLE_CRITICAL = "🚨 Critical Tracking Alert"
        private const val ALERT_TITLE_HIGH = "⚠️ High Priority Alert"
        private const val ALERT_TITLE_MEDIUM = "⚡ Medium Priority Alert"
        private const val ALERT_TITLE_LOW = "ℹ️ Low Priority Alert"
    }

    /**
     * Generate and store an alert from a detection result.
     *
     * This method will:
     * 1. Check for duplicate/similar alerts within throttle window
     * 2. Determine alert level based on threat score
     * 3. Create user-friendly alert message
     * 4. Store alert in database
     *
     * @param detectionResult The detection result to convert to an alert
     * @param throttleWindowMs Time window in milliseconds to check for duplicates (default: 1 hour)
     * @return The alert ID if created, null if throttled
     */
    suspend fun generateAlert(
        detectionResult: DetectionResult,
        throttleWindowMs: Long = DEFAULT_THROTTLE_WINDOW_MS
    ): Long? {
        Timber.d("Generating alert for device ${detectionResult.device.address}")

        try {
            // Determine alert level
            val alertLevel = determineAlertLevel(detectionResult.threatScore)

            // Create alert message
            val title = createAlertTitle(alertLevel)
            val message = createAlertMessage(detectionResult)

            // Prepare device addresses and location IDs as JSON
            val deviceAddresses = createDeviceAddressesJson(detectionResult)
            val locationIds = createLocationIdsJson(detectionResult)

            // Create detection details JSON
            val detectionDetails = createDetectionDetailsJson(detectionResult)

            // Create AlertHistory object
            val alert = AlertHistory(
                alertLevel = alertLevel,
                title = title,
                message = message,
                timestamp = detectionResult.timestamp,
                deviceAddresses = deviceAddresses,
                locationIds = locationIds,
                threatScore = detectionResult.threatScore,
                detectionDetails = detectionDetails,
                isDismissed = false,
                dismissedAt = null
            )

            // Insert with throttling check
            val alertId = alertRepository.insertAlertWithThrottling(alert, throttleWindowMs)

            if (alertId != null) {
                Timber.d("Alert created: id=$alertId, level=$alertLevel")
            } else {
                Timber.d("Alert throttled: similar alert exists within ${throttleWindowMs}ms window")
            }

            return alertId

        } catch (e: Exception) {
            Timber.e(e, "Error generating alert for device ${detectionResult.device.address}")
            return null
        }
    }

    /**
     * Generate alerts for multiple detection results.
     *
     * @param detectionResults List of detection results
     * @param throttleWindowMs Throttle window in milliseconds
     * @return List of created alert IDs (excludes throttled alerts)
     */
    suspend fun generateAlerts(
        detectionResults: List<DetectionResult>,
        throttleWindowMs: Long = DEFAULT_THROTTLE_WINDOW_MS
    ): List<Long> {
        Timber.d("Generating alerts for ${detectionResults.size} detection results")

        val alertIds = mutableListOf<Long>()

        for (result in detectionResults) {
            val alertId = generateAlert(result, throttleWindowMs)
            if (alertId != null) {
                alertIds.add(alertId)
            }
        }

        Timber.d("Generated ${alertIds.size} alerts (${detectionResults.size - alertIds.size} throttled)")
        return alertIds
    }

    /**
     * Check if an alert should be generated for a detection result.
     *
     * This checks:
     * - Threat score meets minimum threshold
     * - No similar recent alert exists
     *
     * @param detectionResult The detection result to check
     * @param minThreatScore Minimum threat score threshold
     * @param throttleWindowMs Throttle window in milliseconds
     * @return True if alert should be generated
     */
    suspend fun shouldGenerateAlert(
        detectionResult: DetectionResult,
        minThreatScore: Double = Constants.THREAT_SCORE_THRESHOLD,
        throttleWindowMs: Long = DEFAULT_THROTTLE_WINDOW_MS
    ): Boolean {
        // Check threat score threshold
        if (detectionResult.threatScore < minThreatScore) {
            return false
        }

        // Check for similar recent alerts
        val deviceAddresses = createDeviceAddressesJson(detectionResult)
        val throttleThreshold = System.currentTimeMillis() - throttleWindowMs

        return !alertRepository.hasSimilarRecentAlert(deviceAddresses, throttleThreshold)
    }

    /**
     * Determine alert level based on threat score.
     *
     * @param threatScore The threat score (0.0 - 1.0)
     * @return Alert level string
     */
    private fun determineAlertLevel(threatScore: Double): String {
        val threatLevel = ThreatLevel.fromScore(threatScore)
        return when (threatLevel) {
            ThreatLevel.CRITICAL -> Constants.ALERT_LEVEL_CRITICAL
            ThreatLevel.HIGH -> Constants.ALERT_LEVEL_HIGH
            ThreatLevel.MEDIUM -> Constants.ALERT_LEVEL_MEDIUM
            ThreatLevel.LOW -> Constants.ALERT_LEVEL_LOW
        }
    }

    /**
     * Create alert title based on alert level.
     *
     * @param alertLevel The alert level
     * @return Alert title string
     */
    private fun createAlertTitle(alertLevel: String): String {
        return when (alertLevel) {
            Constants.ALERT_LEVEL_CRITICAL -> ALERT_TITLE_CRITICAL
            Constants.ALERT_LEVEL_HIGH -> ALERT_TITLE_HIGH
            Constants.ALERT_LEVEL_MEDIUM -> ALERT_TITLE_MEDIUM
            Constants.ALERT_LEVEL_LOW -> ALERT_TITLE_LOW
            else -> "Detection Alert"
        }
    }

    /**
     * Create user-friendly alert message from detection result.
     *
     * @param detectionResult The detection result
     * @return Alert message string
     */
    private fun createAlertMessage(detectionResult: DetectionResult): String {
        val device = detectionResult.device
        val deviceName = device.name ?: "Unknown Device"
        val locationCount = detectionResult.locations.size
        val maxDistanceKm = detectionResult.maxDistance / 1000.0
        val threatLevel = detectionResult.getThreatLevel()

        val timeSpan = formatTimeSpan(detectionResult.getTimeSpan())

        return buildString {
            append("A suspicious device has been detected following your movements.\n\n")
            append("Device: $deviceName\n")
            append("MAC Address: ${device.address}\n")
            append("Threat Level: ${threatLevel.displayName}\n")
            append("Locations: $locationCount different places\n")
            append("Max Distance: ${"%.1f".format(maxDistanceKm)} km\n")
            append("Time Period: $timeSpan\n\n")

            when (threatLevel) {
                ThreatLevel.CRITICAL -> {
                    append("⚠️ CRITICAL: This device shows a very strong pattern of tracking behavior. ")
                    append(
                        "Please review the details immediately and consider contacting " +
                            "authorities if you feel unsafe."
                    )
                }
                ThreatLevel.HIGH -> {
                    append("⚠️ HIGH: This device shows a strong pattern of following behavior. ")
                    append("Please review the detection details and take appropriate action.")
                }
                ThreatLevel.MEDIUM -> {
                    append("⚡ MEDIUM: This device may be following you. ")
                    append("Review the details to determine if this is a known device or requires action.")
                }
                ThreatLevel.LOW -> {
                    append("ℹ️ LOW: This device has appeared at multiple locations. ")
                    append("It may be a coincidence, but worth monitoring.")
                }
            }
        }
    }

    /**
     * Create JSON array of device addresses involved in the detection.
     *
     * @param detectionResult The detection result
     * @return JSON string of device addresses
     */
    private fun createDeviceAddressesJson(detectionResult: DetectionResult): String {
        val jsonArray = JSONArray()
        jsonArray.put(detectionResult.device.address)
        return jsonArray.toString()
    }

    /**
     * Create JSON array of location IDs involved in the detection.
     *
     * @param detectionResult The detection result
     * @return JSON string of location IDs
     */
    private fun createLocationIdsJson(detectionResult: DetectionResult): String {
        val jsonArray = JSONArray()
        for (location in detectionResult.locations) {
            jsonArray.put(location.id)
        }
        return jsonArray.toString()
    }

    /**
     * Create JSON object with detailed detection information.
     *
     * @param detectionResult The detection result
     * @return JSON string with detection details
     */
    private fun createDetectionDetailsJson(detectionResult: DetectionResult): String {
        val jsonObject = JSONObject()
        jsonObject.put("deviceId", detectionResult.device.id)
        jsonObject.put("deviceName", detectionResult.device.name ?: "Unknown")
        jsonObject.put("deviceAddress", detectionResult.device.address)
        jsonObject.put("locationCount", detectionResult.locations.size)
        jsonObject.put("maxDistance", detectionResult.maxDistance)
        jsonObject.put("avgDistance", detectionResult.avgDistance)
        jsonObject.put("threatScore", detectionResult.threatScore)
        jsonObject.put("timeSpan", detectionResult.getTimeSpan())
        jsonObject.put("detectionReason", detectionResult.detectionReason)
        jsonObject.put("detectionId", detectionResult.detectionId)
        return jsonObject.toString()
    }

    /**
     * Format a time span in milliseconds to a human-readable string.
     *
     * @param timeSpanMs Time span in milliseconds
     * @return Formatted string
     */
    private fun formatTimeSpan(timeSpanMs: Long): String {
        val seconds = timeSpanMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days day${if (days != 1L) "s" else ""}"
            hours > 0 -> "$hours hour${if (hours != 1L) "s" else ""}"
            minutes > 0 -> "$minutes minute${if (minutes != 1L) "s" else ""}"
            else -> "$seconds second${if (seconds != 1L) "s" else ""}"
        }
    }

    /**
     * Get count of active alerts (non-dismissed).
     *
     * @return Number of active alerts
     */
    suspend fun getActiveAlertCount(): Int {
        return alertRepository.getActiveAlertCount().first()
    }

    /**
     * Get count of active alerts by level.
     *
     * @param alertLevel The alert level
     * @return Number of active alerts at the specified level
     */
    suspend fun getActiveAlertCountByLevel(alertLevel: String): Int {
        return alertRepository.getActiveAlertCountByLevel(alertLevel).first()
    }
}
