package com.tailbait.util

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.data.repository.WhitelistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comprehensive CSV Export utility for device and location data analysis
 *
 * Exports all app data into multiple CSV files for external analysis:
 * - Devices: Complete device information and characteristics
 * - Locations: All detection locations with coordinates and accuracy
 * - Device Locations: Device-location correlation records
 * - User Paths: Raw user movement history
 * - Alerts: Alert history with metadata
 */
@Singleton
class CsvDataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val locationRepository: LocationRepository,
    private val alertRepository: AlertRepository,
    private val whitelistRepository: WhitelistRepository,
    private val settingsRepository: SettingsRepository
) {

    data class ExportResult(
        val success: Boolean,
        val exportDirectory: String? = null,
        val files: List<String> = emptyList(),
        val errorMessage: String? = null,
        val devicesExported: Int = 0,
        val locationsExported: Int = 0,
        val deviceLocationRecordsExported: Int = 0,
        val userPathsExported: Int = 0,
        val alertsExported: Int = 0,
        val whitelistExported: Int = 0,
        val settingsExported: Boolean = false
    )

    /**
     * Export all app data to separate CSV files in a zip archive
     */
    suspend fun exportAllData(): ExportResult = withContext(Dispatchers.IO) {
        try {
            Timber.i("Starting CSV data export")

            // Create export directory
            val exportDir = createExportDirectory()
            val exportedFiles = mutableListOf<String>()

            // Get all data
            val devices = deviceRepository.getAllDevices().first()
            val locations = locationRepository.getAllLocations().first()
            val deviceLocationRecords = deviceRepository.getAllDeviceLocationRecords()
            val userPaths = locationRepository.getUserPathSince(0)
            val alerts = alertRepository.getAllAlerts().first()
            val whitelist = whitelistRepository.getAllWhitelistEntries().first()
            val settings = settingsRepository.getSettingsOnce()

            Timber.i("Data loaded: ${devices.size} devices, ${locations.size} locations, ${deviceLocationRecords.size} device-location records, ${userPaths.size} user path points, ${alerts.size} alerts, ${whitelist.size} whitelist entries")

            // Export devices
            val devicesFile = File(exportDir, "devices.csv")
            exportDevices(devices, devicesFile)
            exportedFiles.add(devicesFile.absolutePath)

            // Export locations
            val locationsFile = File(exportDir, "locations.csv")
            exportLocations(locations, locationsFile)
            exportedFiles.add(locationsFile.absolutePath)

            // Export device-location records
            val deviceLocationsFile = File(exportDir, "device_locations.csv")
            exportDeviceLocationRecords(deviceLocationRecords, deviceLocationsFile)
            exportedFiles.add(deviceLocationsFile.absolutePath)

            // Export user paths
            val userPathsFile = File(exportDir, "user_paths.csv")
            exportUserPaths(userPaths, userPathsFile)
            exportedFiles.add(userPathsFile.absolutePath)

            // Export alerts
            val alertsFile = File(exportDir, "alerts.csv")
            exportAlerts(alerts, alertsFile)
            exportedFiles.add(alertsFile.absolutePath)

            // Export whitelist
            val whitelistFile = File(exportDir, "whitelist.csv")
            exportWhitelist(whitelist, whitelistFile)
            exportedFiles.add(whitelistFile.absolutePath)

            // Export settings
            val settingsFile = File(exportDir, "settings.csv")
            exportSettings(settings, settingsFile)
            exportedFiles.add(settingsFile.absolutePath)

            // Create README file with documentation
            val readmeFile = File(exportDir, "README.md")
            createReadmeFile(readmeFile, devices, locations, deviceLocationRecords, userPaths, alerts, whitelist, settings)
            exportedFiles.add(readmeFile.absolutePath)

            // Create zip file
            val zipFile = File(context.getExternalFilesDir(null), "bletracker_export_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip")
            createZipFile(exportDir, zipFile)

            Timber.i("CSV export completed successfully to: ${zipFile.absolutePath}")

            ExportResult(
                success = true,
                exportDirectory = zipFile.parent,
                files = listOf(zipFile.absolutePath),
                devicesExported = devices.size,
                locationsExported = locations.size,
                deviceLocationRecordsExported = deviceLocationRecords.size,
                userPathsExported = userPaths.size,
                alertsExported = alerts.size,
                whitelistExported = whitelist.size,
                settingsExported = true
            )

        } catch (e: Exception) {
            Timber.e(e, "Failed to export data to CSV")
            ExportResult(
                success = false,
                errorMessage = "Export failed: ${e.message}"
            )
        }
    }

    /**
     * Create export directory in app's external files directory
     */
    private fun createExportDirectory(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportDir = File(context.getExternalFilesDir(null), "bletracker_export_$timestamp")

        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        return exportDir
    }

    /**
     * Export devices to CSV with ALL fields for comprehensive debugging.
     *
     * Includes all ScannedDevice fields including:
     * - Core identification (address, name, advertised_name)
     * - Timing (first_seen, last_seen, detection_count, created_at)
     * - Manufacturer identification (manufacturer_data, manufacturer_id, manufacturer_name)
     * - Device classification (device_type, device_model, is_tracker)
     * - BLE advertisement data (service_uuids, appearance, tx_power_level, advertising_flags)
     * - Apple-specific (apple_continuity_type)
     * - Identification confidence (identification_confidence, identification_method)
     * - Find My fingerprinting (payload_fingerprint, find_my_status, find_my_separated)
     * - Device linking (linked_device_id, link_strength, link_reason, last_mac_rotation)
     * - Enhanced signal data (highest_rssi, signal_strength, beacon_type, threat_level)
     */
    private fun exportDevices(devices: List<ScannedDevice>, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header - ALL fields for debugging
            writer.appendLine(
                "id," +
                "address," +
                "name," +
                "advertised_name," +
                "first_seen," +
                "last_seen," +
                "detection_count," +
                "created_at," +
                "manufacturer_data," +
                "manufacturer_id," +
                "manufacturer_name," +
                "device_type," +
                "device_model," +
                "is_tracker," +
                "service_uuids," +
                "appearance," +
                "tx_power_level," +
                "advertising_flags," +
                "apple_continuity_type," +
                "identification_confidence," +
                "identification_method," +
                "payload_fingerprint," +
                "find_my_status," +
                "find_my_separated," +
                "linked_device_id," +
                "link_strength," +
                "link_reason," +
                "last_mac_rotation," +
                "highest_rssi," +
                "signal_strength," +
                "beacon_type," +
                "threat_level"
            )

            // CSV Data - ALL fields
            devices.forEach { device ->
                writer.appendLine(
                    "${device.id}," +
                    "\"${escapeCsvValue(device.address)}\"," +
                    "\"${escapeCsvValue(device.name)}\"," +
                    "\"${escapeCsvValue(device.advertisedName)}\"," +
                    "${device.firstSeen}," +
                    "${device.lastSeen}," +
                    "${device.detectionCount}," +
                    "${device.createdAt}," +
                    "\"${escapeCsvValue(device.manufacturerData)}\"," +
                    "${device.manufacturerId ?: ""}," +
                    "\"${escapeCsvValue(device.manufacturerName)}\"," +
                    "\"${escapeCsvValue(device.deviceType)}\"," +
                    "\"${escapeCsvValue(device.deviceModel)}\"," +
                    "${device.isTracker}," +
                    "\"${escapeCsvValue(device.serviceUuids)}\"," +
                    "${device.appearance ?: ""}," +
                    "${device.txPowerLevel ?: ""}," +
                    "${device.advertisingFlags ?: ""}," +
                    "${device.appleContinuityType ?: ""}," +
                    "${device.identificationConfidence}," +
                    "\"${escapeCsvValue(device.identificationMethod)}\"," +
                    "\"${escapeCsvValue(device.payloadFingerprint)}\"," +
                    "${device.findMyStatus ?: ""}," +
                    "${device.findMySeparated}," +
                    "${device.linkedDeviceId ?: ""}," +
                    "\"${escapeCsvValue(device.linkStrength)}\"," +
                    "\"${escapeCsvValue(device.linkReason)}\"," +
                    "${device.lastMacRotation ?: ""}," +
                    "${device.highestRssi ?: ""}," +
                    "\"${escapeCsvValue(device.signalStrength)}\"," +
                    "\"${escapeCsvValue(device.beaconType)}\"," +
                    "\"${escapeCsvValue(device.threatLevel)}\""
                )
            }
        }
    }

    /**
     * Export locations to CSV
     */
    private fun exportLocations(locations: List<Location>, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header
            writer.appendLine("id,latitude,longitude,accuracy,altitude,timestamp,provider")

            // CSV Data
            locations.forEach { location ->
                writer.appendLine(
                    "${location.id}," +
                    "${location.latitude}," +
                    "${location.longitude}," +
                    "${location.accuracy ?: ""}," +
                    "${location.altitude ?: ""}," +
                    "${location.timestamp}," +
                    "\"${escapeCsvValue(location.provider)}\""
                )
            }
        }
    }

    /**
     * Export device-location correlation records to CSV
     */
    private fun exportDeviceLocationRecords(records: List<DeviceLocationRecord>, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header
            writer.appendLine("id,device_id,location_id,rssi,timestamp,location_changed,distance_from_last,scan_trigger_type")

            // CSV Data
            records.forEach { record ->
                writer.appendLine(
                    "${record.id}," +
                    "${record.deviceId}," +
                    "${record.locationId}," +
                    "${record.rssi}," +
                    "${record.timestamp}," +
                    "${record.locationChanged}," +
                    "${record.distanceFromLast ?: ""}," +
                    "\"${escapeCsvValue(record.scanTriggerType)}\""
                )
            }
        }
    }

    /**
     * Export user paths to CSV
     */
    private fun exportUserPaths(userPaths: List<UserPath>, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header
            writer.appendLine("id,location_id,timestamp,accuracy,created_at")

            // CSV Data
            userPaths.forEach { path ->
                writer.appendLine(
                    "${path.id}," +
                    "${path.locationId}," +
                    "${path.timestamp}," +
                    "${path.accuracy}," +
                    "${path.createdAt}"
                )
            }
        }
    }

    /**
     * Export alerts to CSV
     */
    private fun exportAlerts(alerts: List<AlertHistory>, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header
            writer.appendLine("id,alert_level,title,message,timestamp,device_addresses,location_ids,threat_score,detection_details,is_dismissed,dismissed_at,created_at")

            // CSV Data
            alerts.forEach { alert ->
                writer.appendLine(
                    "${alert.id}," +
                    "\"${escapeCsvValue(alert.alertLevel)}\"," +
                    "\"${escapeCsvValue(alert.title)}\"," +
                    "\"${escapeCsvValue(alert.message)}\"," +
                    "${alert.timestamp}," +
                    "\"${escapeCsvValue(alert.deviceAddresses)}\"," +
                    "\"${escapeCsvValue(alert.locationIds)}\"," +
                    "${alert.threatScore}," +
                    "\"${escapeCsvValue(alert.detectionDetails)}\"," +
                    "${alert.isDismissed}," +
                    "${alert.dismissedAt ?: ""}," +
                    "${alert.createdAt}"
                )
            }
        }
    }

    /**
     * Export whitelist entries to CSV
     */
    private fun exportWhitelist(entries: List<WhitelistEntry>, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header
            writer.appendLine("id,device_id,label,category,added_via_learn_mode,notes,created_at")

            // CSV Data
            entries.forEach { entry ->
                writer.appendLine(
                    "${entry.id}," +
                    "${entry.deviceId}," +
                    "\"${escapeCsvValue(entry.label)}\"," +
                    "\"${escapeCsvValue(entry.category)}\"," +
                    "${entry.addedViaLearnMode}," +
                    "\"${escapeCsvValue(entry.notes)}\"," +
                    "${entry.createdAt}"
                )
            }
        }
    }

    /**
     * Export app settings to CSV (single row with all configuration)
     */
    private fun exportSettings(settings: AppSettings, file: File) {
        FileWriter(file).use { writer ->
            // CSV Header
            writer.appendLine("setting,value")

            // Export all settings as key-value pairs for easy reading
            writer.appendLine("is_tracking_enabled,${settings.isTrackingEnabled}")
            writer.appendLine("scan_interval_seconds,${settings.scanIntervalSeconds}")
            writer.appendLine("scan_duration_seconds,${settings.scanDurationSeconds}")
            writer.appendLine("min_detection_distance_meters,${settings.minDetectionDistanceMeters}")
            writer.appendLine("alert_threshold_count,${settings.alertThresholdCount}")
            writer.appendLine("alert_notification_enabled,${settings.alertNotificationEnabled}")
            writer.appendLine("alert_sound_enabled,${settings.alertSoundEnabled}")
            writer.appendLine("alert_vibration_enabled,${settings.alertVibrationEnabled}")
            writer.appendLine("learn_mode_active,${settings.learnModeActive}")
            writer.appendLine("learn_mode_started_at,${settings.learnModeStartedAt ?: ""}")
            writer.appendLine("data_retention_days,${settings.dataRetentionDays}")
            writer.appendLine("battery_optimization_enabled,${settings.batteryOptimizationEnabled}")
            writer.appendLine("theme_mode,\"${settings.themeMode}\"")
            writer.appendLine("updated_at,${settings.updatedAt}")
        }
    }

    /**
     * Create README.md with documentation
     */
    private fun createReadmeFile(
        file: File,
        devices: List<ScannedDevice>,
        locations: List<Location>,
        deviceLocationRecords: List<DeviceLocationRecord>,
        userPaths: List<UserPath>,
        alerts: List<AlertHistory>,
        whitelist: List<WhitelistEntry>,
        settings: AppSettings
    ) {
        FileWriter(file).use { writer ->
            writer.appendLine("# BLE Tracker Data Export")
            writer.appendLine("")
            writer.appendLine("Export Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            writer.appendLine("App Version: ${com.tailbait.BuildConfig.VERSION_NAME} (${com.tailbait.BuildConfig.VERSION_CODE})")
            writer.appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            writer.appendLine("")
            writer.appendLine("## Data Summary")
            writer.appendLine("- **Devices**: ${devices.size}")
            writer.appendLine("- **Locations**: ${locations.size}")
            writer.appendLine("- **Device-Location Records**: ${deviceLocationRecords.size}")
            writer.appendLine("- **User Path Points**: ${userPaths.size}")
            writer.appendLine("- **Alerts**: ${alerts.size}")
            writer.appendLine("- **Whitelist Entries**: ${whitelist.size}")
            writer.appendLine("- **Settings**: Exported")
            writer.appendLine("")
            writer.appendLine("## File Descriptions")
            writer.appendLine("")
            writer.appendLine("### devices.csv")
            writer.appendLine("Complete device information with ALL fields for debugging and analysis.")
            writer.appendLine("")
            writer.appendLine("**Core Identification:**")
            writer.appendLine("- `id`: Unique device identifier")
            writer.appendLine("- `address`: Bluetooth MAC address")
            writer.appendLine("- `name`: Cached device name")
            writer.appendLine("- `advertised_name`: Name from BLE advertisement")
            writer.appendLine("")
            writer.appendLine("**Timing:**")
            writer.appendLine("- `first_seen`/`last_seen`: Detection timestamps (ms since epoch)")
            writer.appendLine("- `detection_count`: Number of times device detected")
            writer.appendLine("- `created_at`: Database record creation time")
            writer.appendLine("")
            writer.appendLine("**Manufacturer Identification:**")
            writer.appendLine("- `manufacturer_data`: Raw hex manufacturer data")
            writer.appendLine("- `manufacturer_id`: Bluetooth SIG assigned ID (e.g., 76 = Apple)")
            writer.appendLine("- `manufacturer_name`: Human-readable manufacturer")
            writer.appendLine("")
            writer.appendLine("**Device Classification:**")
            writer.appendLine("- `device_type`: Inferred type (PHONE, TRACKER, WATCH, etc.)")
            writer.appendLine("- `device_model`: Specific model if identified")
            writer.appendLine("- `is_tracker`: Boolean - known tracking device")
            writer.appendLine("")
            writer.appendLine("**BLE Advertisement Data:**")
            writer.appendLine("- `service_uuids`: Comma-separated advertised UUIDs")
            writer.appendLine("- `appearance`: BLE GAP appearance value")
            writer.appendLine("- `tx_power_level`: Advertised TX power (dBm)")
            writer.appendLine("- `advertising_flags`: BLE advertising flags byte")
            writer.appendLine("")
            writer.appendLine("**Apple-Specific:**")
            writer.appendLine("- `apple_continuity_type`: Continuity message type (7=AirPods, 16=NearbyInfo, 18=FindMy)")
            writer.appendLine("")
            writer.appendLine("**Identification Confidence:**")
            writer.appendLine("- `identification_confidence`: Score 0.0-1.0")
            writer.appendLine("- `identification_method`: How device was identified")
            writer.appendLine("")
            writer.appendLine("**Find My Fingerprinting (for AirTag/Find My tracking):**")
            writer.appendLine("- `payload_fingerprint`: Stable identifier across MAC rotations")
            writer.appendLine("- `find_my_status`: Raw status byte from Find My payload")
            writer.appendLine("- `find_my_separated`: Boolean - device is away from owner (SUSPICIOUS!)")
            writer.appendLine("")
            writer.appendLine("**Device Linking (MAC rotation correlation):**")
            writer.appendLine("- `linked_device_id`: ID of primary device (if this is a rotated MAC)")
            writer.appendLine("- `link_strength`: STRONG (fingerprint/name match) or WEAK (temporal only)")
            writer.appendLine("- `link_reason`: Why devices were linked (e.g., 'fingerprint_match:FM:AABB')")
            writer.appendLine("- `last_mac_rotation`: Timestamp of last detected MAC rotation")
            writer.appendLine("")
            writer.appendLine("**Enhanced Signal Data:**")
            writer.appendLine("- `highest_rssi`: Strongest signal ever recorded (closest proximity)")
            writer.appendLine("- `signal_strength`: Classification (VERY_WEAK to VERY_STRONG)")
            writer.appendLine("- `beacon_type`: Detected beacon format (IBEACON, FIND_MY, etc.)")
            writer.appendLine("- `threat_level`: Calculated threat level (NONE to CRITICAL)")
            writer.appendLine("")
            writer.appendLine("### locations.csv")
            writer.appendLine("GPS location data for all detection points.")
            writer.appendLine("")
            writer.appendLine("Columns:")
            writer.appendLine("- `id`: Unique location identifier")
            writer.appendLine("- `latitude`/`longitude`: GPS coordinates (WGS84)")
            writer.appendLine("- `accuracy`: GPS accuracy in meters")
            writer.appendLine("- `altitude`: Altitude in meters")
            writer.appendLine("- `timestamp`: Location timestamp")
            writer.appendLine("- `provider`: Location provider (gps, network, etc.)")
            writer.appendLine("")
            writer.appendLine("### device_locations.csv")
            writer.appendLine("Links devices to locations with signal and movement data.")
            writer.appendLine("")
            writer.appendLine("Columns:")
            writer.appendLine("- `id`: Unique record identifier")
            writer.appendLine("- `device_id`: Foreign key to devices.csv")
            writer.appendLine("- `location_id`: Foreign key to locations.csv")
            writer.appendLine("- `rssi`: Signal strength in dBm (negative values)")
            writer.appendLine("- `timestamp`: Detection timestamp")
            writer.appendLine("- `location_changed`: Whether user moved since last detection")
            writer.appendLine("- `distance_from_last`: Distance from previous detection (meters)")
            writer.appendLine("- `scan_trigger_type`: How scan was triggered")
            writer.appendLine("")
            writer.appendLine("### user_paths.csv")
            writer.appendLine("Raw user movement history for spatial analysis.")
            writer.appendLine("")
            writer.appendLine("Columns:")
            writer.appendLine("- `id`: Unique path point identifier")
            writer.appendLine("- `location_id`: Foreign key to locations.csv (the place)")
            writer.appendLine("- `timestamp`: When this point was recorded")
            writer.appendLine("- `accuracy`: GPS accuracy at this point")
            writer.appendLine("- `created_at`: Database record creation time")
            writer.appendLine("")
            writer.appendLine("### alerts.csv")
            writer.appendLine("Alert history and threat assessment data.")
            writer.appendLine("")
            writer.appendLine("Columns:")
            writer.appendLine("- `id`: Unique alert identifier")
            writer.appendLine("- `alert_level`: Severity level (\"LOW\", \"MEDIUM\", \"HIGH\", \"CRITICAL\")")
            writer.appendLine("- `title`: Short title summarizing the alert")
            writer.appendLine("- `message`: Detailed alert message describing the detection")
            writer.appendLine("- `timestamp`: Timestamp when the alert was generated")
            writer.appendLine("- `device_addresses`: JSON array of device MAC addresses involved")
            writer.appendLine("- `location_ids`: JSON array of location IDs where suspicious activity was detected")
            writer.appendLine("- `threat_score`: Calculated threat score (0.0-1.0)")
            writer.appendLine("- `detection_details`: JSON object with detailed detection analysis information")
            writer.appendLine("- `is_dismissed`: Flag indicating if the user has dismissed this alert")
            writer.appendLine("- `dismissed_at`: Timestamp when the alert was dismissed (null if not dismissed)")
            writer.appendLine("- `created_at`: Timestamp when this database record was created")
            writer.appendLine("")
            writer.appendLine("### whitelist.csv")
            writer.appendLine("Devices marked as trusted (excluded from stalking detection).")
            writer.appendLine("")
            writer.appendLine("Columns:")
            writer.appendLine("- `id`: Unique whitelist entry identifier")
            writer.appendLine("- `device_id`: Foreign key to devices.csv")
            writer.appendLine("- `label`: User-assigned label (e.g., 'My Phone')")
            writer.appendLine("- `category`: Classification (OWN, PARTNER, TRUSTED)")
            writer.appendLine("- `added_via_learn_mode`: Boolean - added through Learn Mode")
            writer.appendLine("- `notes`: Optional user notes")
            writer.appendLine("- `created_at`: When device was whitelisted")
            writer.appendLine("")
            writer.appendLine("### settings.csv")
            writer.appendLine("Current app configuration (key-value format).")
            writer.appendLine("")
            writer.appendLine("Key settings for analysis:")
            writer.appendLine("- `scan_interval_seconds`: Time between scans")
            writer.appendLine("- `min_detection_distance_meters`: Distance threshold for stalking detection")
            writer.appendLine("- `alert_threshold_count`: Number of locations needed to trigger alert")
            writer.appendLine("")
            writer.appendLine("## Data Relationships")
            writer.appendLine("")
            writer.appendLine("The files are designed to be joined for analysis:")
            writer.appendLine("")
            writer.appendLine("- Join `device_locations.device_id` → `devices.id` to get device details")
            writer.appendLine("- Join `device_locations.location_id` → `locations.id` to get GPS coordinates")
            writer.appendLine("- Join `user_paths.location_id` → `locations.id` to get GPS coordinates for movement history")
            writer.appendLine("- For alerts: Parse `device_addresses` and `location_ids` JSON arrays to relate to devices and locations")
            writer.appendLine("")
            writer.appendLine("Example SQL-like analysis:")
            writer.appendLine("```sql")
            writer.appendLine("SELECT d.name, d.device_type, dl.rssi, l.latitude, l.longitude")
            writer.appendLine("FROM device_locations dl")
            writer.appendLine("JOIN devices d ON dl.device_id = d.id")
            writer.appendLine("JOIN locations l ON dl.location_id = l.id")
            writer.appendLine("WHERE d.device_type = 'TRACKER'")
            writer.appendLine("ORDER BY dl.timestamp DESC;")
            writer.appendLine("```")
            writer.appendLine("")
            writer.appendLine("## Format Notes")
            writer.appendLine("")
            writer.appendLine("- All timestamps are in **milliseconds since Unix epoch**")
            writer.appendLine("- Coordinates are in **decimal degrees (WGS84)**")
            writer.appendLine("- Distance measurements are in **meters**")
            writer.appendLine("- RSSI values are in **dBm** (negative values, closer to 0 = stronger signal)")
            writer.appendLine("- String values containing commas or quotes are properly CSV-escaped")
            writer.appendLine("")
            writer.appendLine("## Data Analysis Tips")
            writer.appendLine("")
            writer.appendLine("1. **Identify following devices**: Group by device_id, analyze location sequences")
            writer.appendLine("2. **Signal strength analysis**: Use RSSI values to determine proximity")
            writer.appendLine("3. **Movement patterns**: Analyze distance_from_last and timestamps")
            writer.appendLine("4. **Device categorization**: Group by device_type and manufacturer_name")
            writer.appendLine("5. **Alert investigation**: Cross-reference alerts with device_location data")
            writer.appendLine("")
            writer.appendLine("## Privacy Notice")
            writer.appendLine("")
            writer.appendLine("This export contains location data and device information. Handle with appropriate privacy considerations and only use for legitimate security analysis purposes.")
        }
    }

    /**
     * Create zip file containing all exported files
     */
    private fun createZipFile(exportDir: File, zipFile: File) {
        try {
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zipOut ->
                exportDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        zipOut.putNextEntry(java.util.zip.ZipEntry(file.name))
                        file.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            }

            // Clean up individual files after creating zip
            exportDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
            exportDir.delete()

            Timber.i("Created zip file: ${zipFile.absolutePath}")

        } catch (e: Exception) {
            Timber.e(e, "Failed to create zip file")
            throw e
        }
    }

    /**
     * Escape CSV values to handle commas, quotes, and newlines
     */
    private fun escapeCsvValue(value: String?): String {
        if (value == null) return ""

        // Escape quotes by doubling them
        var escaped = value.replace("\"", "\"\"")

        // If value contains comma, quote, or newline, wrap in quotes
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            escaped = "\"$escaped\""
        }

        return escaped
    }

    /**
     * Get manufacturer name from ID
     */
    private fun getManufacturerName(manufacturerId: Int): String {
        return when (manufacturerId) {
            0x004C -> "Apple"
            0x0075 -> "Samsung"
            0x00E0 -> "Google"
            0x0006 -> "Microsoft"
            0x0099 -> "Tile"
            0x02E5 -> "Chipolo"
            0x00D6 -> "LG"
            0x00D7 -> "Marvell"
            0x00D9 -> "Nordic Semiconductor"
            0x00DA -> "Nordic Semiconductor"
            0x00DB -> "Dialog Semiconductor"
            0x00DC -> "WiSpry"
            0x00DD -> "Apple"
            0x00DE -> "Apple"
            0x00DF -> "Apple"
            else -> "Unknown ($manufacturerId)"
        }
    }
}
