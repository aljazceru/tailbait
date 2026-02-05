package com.tailbait.util

import android.content.Context
import android.net.Uri
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class for exporting app data to various formats.
 *
 * Supports exporting devices, locations, alerts, and location records to:
 * - CSV (Comma-Separated Values) for spreadsheet analysis
 * - JSON (JavaScript Object Notation) for structured data exchange
 * - GPX (GPS Exchange Format) for mapping applications
 *
 * All export operations are performed on background threads to avoid blocking the UI.
 *
 * Thread Safety:
 * This class is thread-safe and can be safely used as a singleton.
 * All public methods use appropriate dispatchers for I/O operations.
 */
@Singleton
class DataExporter @Inject constructor() {

    companion object {
        private const val TAG = "DataExporter"

        // Date format for timestamps
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // GPX namespace
        private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    }

    /**
     * Export data types
     */
    enum class ExportFormat {
        CSV,
        JSON,
        GPX
    }

    /**
     * Export result
     */
    data class ExportResult(
        val success: Boolean,
        val filePath: String? = null,
        val errorMessage: String? = null,
        val recordCount: Int = 0
    )

    // ==================== CSV Export ====================

    /**
     * Export devices to CSV format.
     *
     * CSV columns: ID, Address, Name, First Seen, Last Seen, Detection Count, Manufacturer Data
     *
     * @param devices List of devices to export
     * @param outputFile Output file path
     * @return Export result with file path and status
     */
    suspend fun exportDevicesToCsv(
        devices: List<ScannedDevice>,
        outputFile: File
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            FileWriter(outputFile).use { writer ->
                // Write header
                writer.append("ID,Address,Name,First Seen,Last Seen,Detection Count,Manufacturer Data\n")

                // Write data rows
                devices.forEach { device ->
                    writer.append("${device.id},")
                    writer.append("\"${device.address}\",")
                    writer.append("\"${device.name ?: ""}\",")
                    writer.append("\"${dateFormat.format(Date(device.firstSeen))}\",")
                    writer.append("\"${dateFormat.format(Date(device.lastSeen))}\",")
                    writer.append("${device.detectionCount},")
                    writer.append("\"${device.manufacturerData ?: ""}\"\n")
                }
            }

            Timber.i("Exported ${devices.size} devices to CSV: ${outputFile.absolutePath}")
            ExportResult(
                success = true,
                filePath = outputFile.absolutePath,
                recordCount = devices.size
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to export devices to CSV")
            ExportResult(
                success = false,
                errorMessage = "Failed to export devices: ${e.message}"
            )
        }
    }

    /**
     * Export locations to CSV format.
     *
     * CSV columns: ID, Latitude, Longitude, Accuracy, Timestamp, Provider
     *
     * @param locations List of locations to export
     * @param outputFile Output file path
     * @return Export result with file path and status
     */
    suspend fun exportLocationsToCsv(
        locations: List<Location>,
        outputFile: File
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            FileWriter(outputFile).use { writer ->
                // Write header
                writer.append("ID,Latitude,Longitude,Accuracy,Timestamp,Provider\n")

                // Write data rows
                locations.forEach { location ->
                    writer.append("${location.id},")
                    writer.append("${location.latitude},")
                    writer.append("${location.longitude},")
                    writer.append("${location.accuracy},")
                    writer.append("\"${dateFormat.format(Date(location.timestamp))}\",")
                    writer.append("\"${location.provider}\"\n")
                }
            }

            Timber.i("Exported ${locations.size} locations to CSV: ${outputFile.absolutePath}")
            ExportResult(
                success = true,
                filePath = outputFile.absolutePath,
                recordCount = locations.size
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to export locations to CSV")
            ExportResult(
                success = false,
                errorMessage = "Failed to export locations: ${e.message}"
            )
        }
    }

    /**
     * Export alerts to CSV format.
     *
     * CSV columns: ID, Level, Title, Message, Timestamp, Device Addresses, Threat Score, Dismissed
     *
     * @param alerts List of alerts to export
     * @param outputFile Output file path
     * @return Export result with file path and status
     */
    suspend fun exportAlertsToCsv(
        alerts: List<AlertHistory>,
        outputFile: File
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            FileWriter(outputFile).use { writer ->
                // Write header
                writer.append("ID,Level,Title,Message,Timestamp,Device Addresses,Threat Score,Dismissed\n")

                // Write data rows
                alerts.forEach { alert ->
                    writer.append("${alert.id},")
                    writer.append("\"${alert.alertLevel}\",")
                    writer.append("\"${escapeCsv(alert.title)}\",")
                    writer.append("\"${escapeCsv(alert.message)}\",")
                    writer.append("\"${dateFormat.format(Date(alert.timestamp))}\",")
                    writer.append("\"${alert.deviceAddresses}\",")
                    writer.append("${alert.threatScore},")
                    writer.append("${alert.isDismissed}\n")
                }
            }

            Timber.i("Exported ${alerts.size} alerts to CSV: ${outputFile.absolutePath}")
            ExportResult(
                success = true,
                filePath = outputFile.absolutePath,
                recordCount = alerts.size
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to export alerts to CSV")
            ExportResult(
                success = false,
                errorMessage = "Failed to export alerts: ${e.message}"
            )
        }
    }

    // ==================== JSON Export ====================

    /**
     * Export all data to JSON format.
     *
     * Creates a complete data dump including devices, locations, alerts, and metadata.
     *
     * @param devices List of devices
     * @param locations List of locations
     * @param alerts List of alerts
     * @param records List of device-location records
     * @param outputFile Output file path
     * @return Export result with file path and status
     */
    suspend fun exportToJson(
        devices: List<ScannedDevice>,
        locations: List<Location>,
        alerts: List<AlertHistory>,
        records: List<DeviceLocationRecord>,
        outputFile: File
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()

            // Metadata
            val metadata = JSONObject()
            metadata.put("exportDate", dateFormat.format(Date()))
            metadata.put("version", "1.0")
            metadata.put("totalDevices", devices.size)
            metadata.put("totalLocations", locations.size)
            metadata.put("totalAlerts", alerts.size)
            metadata.put("totalRecords", records.size)
            root.put("metadata", metadata)

            // Devices - ALL fields for comprehensive debugging
            val devicesArray = JSONArray()
            devices.forEach { device ->
                val deviceObj = JSONObject()
                // Core identification
                deviceObj.put("id", device.id)
                deviceObj.put("address", device.address)
                deviceObj.put("name", device.name)
                deviceObj.put("advertisedName", device.advertisedName)

                // Timing
                deviceObj.put("firstSeen", device.firstSeen)
                deviceObj.put("lastSeen", device.lastSeen)
                deviceObj.put("detectionCount", device.detectionCount)
                deviceObj.put("createdAt", device.createdAt)

                // Manufacturer identification
                deviceObj.put("manufacturerData", device.manufacturerData)
                deviceObj.put("manufacturerId", device.manufacturerId)
                deviceObj.put("manufacturerName", device.manufacturerName)

                // Device classification
                deviceObj.put("deviceType", device.deviceType)
                deviceObj.put("deviceModel", device.deviceModel)
                deviceObj.put("isTracker", device.isTracker)

                // BLE advertisement data
                deviceObj.put("serviceUuids", device.serviceUuids)
                deviceObj.put("appearance", device.appearance)
                deviceObj.put("txPowerLevel", device.txPowerLevel)
                deviceObj.put("advertisingFlags", device.advertisingFlags)

                // Apple-specific
                deviceObj.put("appleContinuityType", device.appleContinuityType)

                // Identification confidence
                deviceObj.put("identificationConfidence", device.identificationConfidence)
                deviceObj.put("identificationMethod", device.identificationMethod)

                // Find My fingerprinting
                deviceObj.put("payloadFingerprint", device.payloadFingerprint)
                deviceObj.put("findMyStatus", device.findMyStatus)
                deviceObj.put("findMySeparated", device.findMySeparated)

                // Device linking (MAC rotation correlation)
                deviceObj.put("linkedDeviceId", device.linkedDeviceId)
                deviceObj.put("linkStrength", device.linkStrength)
                deviceObj.put("linkReason", device.linkReason)
                deviceObj.put("lastMacRotation", device.lastMacRotation)

                // Enhanced signal data
                deviceObj.put("highestRssi", device.highestRssi)
                deviceObj.put("signalStrength", device.signalStrength)
                deviceObj.put("beaconType", device.beaconType)
                deviceObj.put("threatLevel", device.threatLevel)

                devicesArray.put(deviceObj)
            }
            root.put("devices", devicesArray)

            // Locations
            val locationsArray = JSONArray()
            locations.forEach { location ->
                val locationObj = JSONObject()
                locationObj.put("id", location.id)
                locationObj.put("latitude", location.latitude)
                locationObj.put("longitude", location.longitude)
                locationObj.put("accuracy", location.accuracy)
                locationObj.put("timestamp", location.timestamp)
                locationObj.put("provider", location.provider)
                locationsArray.put(locationObj)
            }
            root.put("locations", locationsArray)

            // Alerts
            val alertsArray = JSONArray()
            alerts.forEach { alert ->
                val alertObj = JSONObject()
                alertObj.put("id", alert.id)
                alertObj.put("alertLevel", alert.alertLevel)
                alertObj.put("title", alert.title)
                alertObj.put("message", alert.message)
                alertObj.put("timestamp", alert.timestamp)
                alertObj.put("deviceAddresses", alert.deviceAddresses)
                alertObj.put("locationIds", alert.locationIds)
                alertObj.put("threatScore", alert.threatScore)
                alertObj.put("detectionDetails", alert.detectionDetails)
                alertObj.put("isDismissed", alert.isDismissed)
                alertObj.put("dismissedAt", alert.dismissedAt)
                alertsArray.put(alertObj)
            }
            root.put("alerts", alertsArray)

            // Device-Location Records
            val recordsArray = JSONArray()
            records.forEach { record ->
                val recordObj = JSONObject()
                recordObj.put("id", record.id)
                recordObj.put("deviceId", record.deviceId)
                recordObj.put("locationId", record.locationId)
                recordObj.put("rssi", record.rssi)
                recordObj.put("timestamp", record.timestamp)
                recordObj.put("locationChanged", record.locationChanged)
                recordObj.put("distanceFromLast", record.distanceFromLast)
                recordObj.put("scanTriggerType", record.scanTriggerType)
                recordsArray.put(recordObj)
            }
            root.put("deviceLocationRecords", recordsArray)

            // Write to file
            FileWriter(outputFile).use { writer ->
                writer.write(root.toString(2)) // Pretty print with 2-space indent
            }

            val totalRecords = devices.size + locations.size + alerts.size + records.size
            Timber.i("Exported $totalRecords records to JSON: ${outputFile.absolutePath}")
            ExportResult(
                success = true,
                filePath = outputFile.absolutePath,
                recordCount = totalRecords
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to export to JSON")
            ExportResult(
                success = false,
                errorMessage = "Failed to export data: ${e.message}"
            )
        }
    }

    // ==================== GPX Export ====================

    /**
     * Export locations to GPX format for use in mapping applications.
     *
     * GPX (GPS Exchange Format) is an XML schema for GPS data interchange.
     * It can be opened in Google Earth, GPX viewers, and most mapping applications.
     *
     * @param locations List of locations to export
     * @param trackName Name for the GPS track (e.g., "Device Detections")
     * @param outputFile Output file path
     * @return Export result with file path and status
     */
    suspend fun exportLocationsToGpx(
        locations: List<Location>,
        trackName: String = "BLE Device Detections",
        outputFile: File
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            FileWriter(outputFile).use { writer ->
                // Write GPX header
                writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                writer.append("<gpx version=\"1.1\" creator=\"TailBait\" ")
                writer.append("xmlns=\"$GPX_NAMESPACE\">\n")

                // Metadata
                writer.append("  <metadata>\n")
                writer.append("    <name>$trackName</name>\n")
                writer.append("    <time>${dateFormat.format(Date())}</time>\n")
                writer.append("  </metadata>\n")

                // Create track with all locations
                writer.append("  <trk>\n")
                writer.append("    <name>$trackName</name>\n")
                writer.append("    <trkseg>\n")

                // Sort locations by timestamp for proper track
                val sortedLocations = locations.sortedBy { it.timestamp }

                sortedLocations.forEach { location ->
                    writer.append("      <trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">\n")
                    writer.append("        <time>${formatGpxTime(location.timestamp)}</time>\n")
                    writer.append("        <name>Location ${location.id}</name>\n")
                    writer.append(
                        "        <desc>Accuracy: ${location.accuracy}m, " +
                            "Provider: ${location.provider}</desc>\n"
                    )
                    writer.append("      </trkpt>\n")
                }

                writer.append("    </trkseg>\n")
                writer.append("  </trk>\n")

                // Also add waypoints for each location
                sortedLocations.forEach { location ->
                    writer.append("  <wpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">\n")
                    writer.append("    <time>${formatGpxTime(location.timestamp)}</time>\n")
                    writer.append("    <name>Location ${location.id}</name>\n")
                    writer.append("    <desc>Accuracy: ${location.accuracy}m</desc>\n")
                    writer.append("  </wpt>\n")
                }

                writer.append("</gpx>\n")
            }

            Timber.i("Exported ${locations.size} locations to GPX: ${outputFile.absolutePath}")
            ExportResult(
                success = true,
                filePath = outputFile.absolutePath,
                recordCount = locations.size
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to export to GPX")
            ExportResult(
                success = false,
                errorMessage = "Failed to export locations: ${e.message}"
            )
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Escape special characters in CSV values.
     */
    private fun escapeCsv(value: String): String {
        return value.replace("\"", "\"\"")
    }

    /**
     * Format timestamp for GPX (ISO 8601 format).
     */
    private fun formatGpxTime(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestamp))
    }

    /**
     * Get suggested filename for export.
     *
     * @param dataType Type of data being exported (e.g., "devices", "locations", "alerts")
     * @param format Export format
     * @return Suggested filename with timestamp
     */
    fun getSuggestedFilename(dataType: String, format: ExportFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extension = when (format) {
            ExportFormat.CSV -> "csv"
            ExportFormat.JSON -> "json"
            ExportFormat.GPX -> "gpx"
        }
        return "ble_tracker_${dataType}_${timestamp}.$extension"
    }

    /**
     * Get export directory for the app.
     *
     * @param context Application context
     * @return Export directory (creates if doesn't exist)
     */
    fun getExportDirectory(context: Context): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
        return exportDir
    }
}
