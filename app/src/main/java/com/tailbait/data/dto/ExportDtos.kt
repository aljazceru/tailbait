package com.tailbait.data.dto

import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.AppSettings
import com.tailbait.data.database.entities.DeviceLocationRecord
import com.tailbait.data.database.entities.Location
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.UserPath
import com.tailbait.data.database.entities.WhitelistEntry
import kotlinx.serialization.Serializable

@Serializable
data class ScannedDeviceDto(
    val id: Long,
    val address: String,
    val name: String?,
    val advertisedName: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val detectionCount: Int,
    val createdAt: Long,
    val manufacturerData: String?,
    val manufacturerId: Int?,
    val manufacturerName: String?,
    val deviceType: String?,
    val deviceModel: String?,
    val isTracker: Boolean,
    val serviceUuids: String?,
    val appearance: Int?,
    val txPowerLevel: Int?,
    val advertisingFlags: Int?,
    val appleContinuityType: Int?,
    val identificationConfidence: Float,
    val identificationMethod: String?,
    val payloadFingerprint: String?,
    val findMyStatus: Int?,
    val findMySeparated: Boolean,
    val linkedDeviceId: Long?,
    val linkStrength: String?,
    val linkReason: String?,
    val lastMacRotation: Long?,
    val highestRssi: Int?,
    val signalStrength: String?,
    val beaconType: String?,
    val threatLevel: String?
)

fun ScannedDevice.toDto() = ScannedDeviceDto(
    id = id,
    address = address,
    name = name,
    advertisedName = advertisedName,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    detectionCount = detectionCount,
    createdAt = createdAt,
    manufacturerData = manufacturerData,
    manufacturerId = manufacturerId,
    manufacturerName = manufacturerName,
    deviceType = deviceType,
    deviceModel = deviceModel,
    isTracker = isTracker,
    serviceUuids = serviceUuids,
    appearance = appearance,
    txPowerLevel = txPowerLevel,
    advertisingFlags = advertisingFlags,
    appleContinuityType = appleContinuityType,
    identificationConfidence = identificationConfidence,
    identificationMethod = identificationMethod,
    payloadFingerprint = payloadFingerprint,
    findMyStatus = findMyStatus,
    findMySeparated = findMySeparated,
    linkedDeviceId = linkedDeviceId,
    linkStrength = linkStrength,
    linkReason = linkReason,
    lastMacRotation = lastMacRotation,
    highestRssi = highestRssi,
    signalStrength = signalStrength,
    beaconType = beaconType,
    threatLevel = threatLevel
)

@Serializable
data class LocationDto(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val timestamp: Long,
    val provider: String,
    val createdAt: Long
)

fun Location.toDto() = LocationDto(
    id = id,
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    timestamp = timestamp,
    provider = provider,
    createdAt = createdAt
)

@Serializable
data class DeviceLocationRecordDto(
    val id: Long,
    val deviceId: Long,
    val locationId: Long,
    val rssi: Int,
    val timestamp: Long,
    val scanDurationMs: Long,
    val locationChanged: Boolean,
    val distanceFromLast: Double?,
    val scanTriggerType: String,
    val createdAt: Long
)

fun DeviceLocationRecord.toDto() = DeviceLocationRecordDto(
    id = id,
    deviceId = deviceId,
    locationId = locationId,
    rssi = rssi,
    timestamp = timestamp,
    scanDurationMs = scanDurationMs,
    locationChanged = locationChanged,
    distanceFromLast = distanceFromLast,
    scanTriggerType = scanTriggerType,
    createdAt = createdAt
)

@Serializable
data class UserPathDto(
    val id: Long,
    val locationId: Long,
    val timestamp: Long,
    val accuracy: Float,
    val createdAt: Long
)

fun UserPath.toDto() = UserPathDto(
    id = id,
    locationId = locationId,
    timestamp = timestamp,
    accuracy = accuracy,
    createdAt = createdAt
)

@Serializable
data class AppSettingsDto(
    val id: Int,
    val isTrackingEnabled: Boolean,
    val trackingMode: String = "CONTINUOUS", // Default as per previous schema if missing, or handle removal? Wait, did user remove trackingMode? Yes in previous conversation.
    // Wait, AppSettings.kt I just viewed DOES NOT have trackingMode! It was removed.
    // Let me check AppSettings.kt content again from Step 319.
    // Lines 45-90.
    // It has: isTrackingEnabled, scanIntervalSeconds, scanDurationSeconds, minDetectionDistanceMeters, alertThresholdCount...
    // It DOES NOT have trackingMode.
    // So I must NOT include it in DTO.
    val scanIntervalSeconds: Int,
    val scanDurationSeconds: Int,
    val minDetectionDistanceMeters: Double,
    val alertThresholdCount: Int,
    val alertNotificationEnabled: Boolean,
    val alertSoundEnabled: Boolean,
    val alertVibrationEnabled: Boolean,
    val learnModeActive: Boolean,
    val learnModeStartedAt: Long?,
    val dataRetentionDays: Int,
    val batteryOptimizationEnabled: Boolean,
    val themeMode: String,
    val updatedAt: Long
)

fun AppSettings.toDto() = AppSettingsDto(
    id = id,
    isTrackingEnabled = isTrackingEnabled,
    // trackingMode removed
    scanIntervalSeconds = scanIntervalSeconds,
    scanDurationSeconds = scanDurationSeconds,
    minDetectionDistanceMeters = minDetectionDistanceMeters,
    alertThresholdCount = alertThresholdCount,
    alertNotificationEnabled = alertNotificationEnabled,
    alertSoundEnabled = alertSoundEnabled,
    alertVibrationEnabled = alertVibrationEnabled,
    learnModeActive = learnModeActive,
    learnModeStartedAt = learnModeStartedAt,
    dataRetentionDays = dataRetentionDays,
    batteryOptimizationEnabled = batteryOptimizationEnabled,
    themeMode = themeMode,
    updatedAt = updatedAt
)

@Serializable
data class AlertHistoryDto(
    val id: Long,
    val alertLevel: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    val deviceAddresses: String,
    val locationIds: String,
    val threatScore: Double,
    val detectionDetails: String,
    val isDismissed: Boolean,
    val dismissedAt: Long?,
    val createdAt: Long
)

fun AlertHistory.toDto() = AlertHistoryDto(
    id = id,
    alertLevel = alertLevel,
    title = title,
    message = message,
    timestamp = timestamp,
    deviceAddresses = deviceAddresses,
    locationIds = locationIds,
    threatScore = threatScore,
    detectionDetails = detectionDetails,
    isDismissed = isDismissed,
    dismissedAt = dismissedAt,
    createdAt = createdAt
)

@Serializable
data class WhitelistEntryDto(
    val id: Long,
    val deviceId: Long,
    val label: String,
    val category: String,
    val addedViaLearnMode: Boolean,
    val notes: String?,
    val createdAt: Long
)

fun WhitelistEntry.toDto() = WhitelistEntryDto(
    id = id,
    deviceId = deviceId,
    label = label,
    category = category,
    addedViaLearnMode = addedViaLearnMode,
    notes = notes,
    createdAt = createdAt
)
