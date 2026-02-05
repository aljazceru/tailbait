package com.tailbait.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for sharing files from the app's external storage
 */
@Singleton
class FileShareHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Create an intent to share the exported zip file
     */
    suspend fun createShareIntent(exportDirectory: String, files: List<String>): Intent? = withContext(Dispatchers.IO) {
        try {
            if (files.isEmpty()) {
                Timber.e("No files to share")
                return@withContext null
            }

            val zipFile = File(files.first()) // Get the zip file
            if (!zipFile.exists()) {
                Timber.e("Zip file does not exist: ${zipFile.absolutePath}")
                return@withContext null
            }

            // Get URI using FileProvider for security
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            // Create share intent
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, createShareMessage(zipFile))
                putExtra(Intent.EXTRA_SUBJECT, "BLE Tracker Data Export")
                type = "application/zip"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Timber.i("Created share intent for zip file: ${zipFile.name}")
            shareIntent

        } catch (e: Exception) {
            Timber.e(e, "Failed to create share intent")
            null
        }
    }

    /**
     * Create a share message with export statistics
     */
    private fun createShareMessage(zipFile: File): String {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        return "BLE Tracker Data Export\n" +
                "Exported on: $timestamp\n" +
                "File: ${zipFile.name}\n" +
                "\nThis zip archive contains comprehensive BLE tracking data:\n" +
                "• devices.csv - Complete device information and characteristics\n" +
                "• locations.csv - GPS coordinates with accuracy and timestamps\n" +
                "• device_locations.csv - Device-location correlation records\n" +
                "• alerts.csv - Alert history and threat assessment scores\n" +
                "• README.md - Complete documentation and analysis guide\n" +
                "\nFiles are designed for easy data analysis and can be joined using:\n" +
                "- device_locations.device_id → devices.id\n" +
                "- device_locations.location_id → locations.id\n" +
                "- alerts.device_id → devices.id\n" +
                "\nNote: This data is for security analysis purposes only."
    }

    /**
     * Get the location where exported files are stored
     */
    fun getExportDirectory(): File {
        return context.getExternalFilesDir(null) ?: File(context.filesDir, "exports")
    }

    /**
     * Check if the app has permission to write to external storage
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true // Scoped storage handles permissions
        } else {
            // For older Android versions, check storage permission
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
