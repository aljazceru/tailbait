package com.tailbait.service

import android.content.Context
import com.tailbait.data.database.TailBaitDatabase
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.dto.toDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service to handle exporting all application data for debug analysis.
 *
 * Dumps all database tables to JSON files and compresses them into a single ZIP archive.
 */
@Singleton
class DataExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TailBaitDatabase
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Export all application data to a ZIP file.
     *
     * @return The created ZIP file
     */
    suspend fun exportDebugData(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(exportDir, "tailbait_debug_export_$timestamp.zip")

        Timber.i("Starting debug data export to ${zipFile.absolutePath}")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
            // 1. Scanned Devices
            val devices = database.scannedDeviceDao().getAllDevices().first().map { it.toDto() }
            addToZip(zipOut, "scanned_devices.json", devices)

            // 2. Locations
            val locations = database.locationDao().getAllLocations().first().map { it.toDto() }
            addToZip(zipOut, "locations.json", locations)

            // 3. Device Location Records (Junction)
            val deviceLocationRecords = database.deviceLocationRecordDao().getAllRecords().first().map { it.toDto() }
            addToZip(zipOut, "device_location_records.json", deviceLocationRecords)

            // 4. User Path
            val userPath = database.userPathDao().getAllUserPaths().first().map { it.toDto() }
            addToZip(zipOut, "user_path.json", userPath)

            // 5. App Settings
            val settings = database.appSettingsDao().getSettings()
            addToZip(zipOut, "app_settings.json", listOfNotNull(settings?.toDto()))

            // 6. Alert History
            val alerts = database.alertHistoryDao().getAllAlerts().first().map { it.toDto() }
            addToZip(zipOut, "alert_history.json", alerts)

            // 7. Whitelist
            val whitelist = database.whitelistEntryDao().getAllEntries().first().map { it.toDto() }
            addToZip(zipOut, "whitelist_entries.json", whitelist)
        }

        Timber.i("Export completed: ${zipFile.length()} bytes")
        zipFile
    }

    @OptIn(ExperimentalSerializationApi::class) // For encodeToString with generic
    private inline fun <reified T> addToZip(zipOut: ZipOutputStream, fileName: String, data: T) {
        try {
            val jsonString = json.encodeToString(data)
            val entry = ZipEntry(fileName)
            zipOut.putNextEntry(entry)
            zipOut.write(jsonString.toByteArray())
            zipOut.closeEntry()
            Timber.d("Exported $fileName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to export $fileName")
            // Add error file to zip
            try {
                zipOut.putNextEntry(ZipEntry("ERROR_$fileName.txt"))
                zipOut.write("Export failed: ${e.message}".toByteArray())
                zipOut.closeEntry()
            } catch (ignore: Exception) {}
        }
    }
}
