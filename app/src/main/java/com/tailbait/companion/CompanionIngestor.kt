package com.tailbait.companion

import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.repository.CompanionDeviceRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.LocationRepository
import com.tailbait.util.Constants
import com.tailbait.util.DeviceIdentifier
import com.tailbait.util.ManufacturerDataParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Companion record ingestor: turns decoded companion records into database
 * rows using the same repositories as the phone's own scanner — so detection,
 * maps and export see companion observations identically.
 *
 * - BLE_ADV records run the FULL existing identification pipeline
 *   (DeviceIdentifier + fingerprint extraction + MAC-rotation linking).
 * - WiFi records (probe/sta/assoc/beacon) create radio-aware device rows
 *   (WIFI_STA / WIFI_AP) with SSID/channel metadata — the wardriving
 *   dataset: every observation is GPS-tagged via device_location_records.
 * - Alerts from a companion's local brain (sentinel mode) are stored in
 *   alert_history so they surface in the app's alert list.
 *
 * Location: fresh GPS fix is preferred, falling back to last-known. The
 * location id is cached for [LOCATION_CACHE_MS] so bursty record batches
 * do not hammer the location tables.
 */
@Singleton
class CompanionIngestor
    @Inject
    constructor(
        private val deviceRepository: DeviceRepository,
        private val locationRepository: LocationRepository,
        private val companionRepository: CompanionDeviceRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private var cachedLocationId: Long? = null
        private var cachedLocationAt: Long = 0

        /** Counters exposed to UI. */
        val counters = Counters()

        class Counters {
            val ble = java.util.concurrent.atomic.AtomicLong()
            val probe = java.util.concurrent.atomic.AtomicLong()
            val sta = java.util.concurrent.atomic.AtomicLong()
            val assoc = java.util.concurrent.atomic.AtomicLong()
            val beacon = java.util.concurrent.atomic.AtomicLong()
            val alert = java.util.concurrent.atomic.AtomicLong()
            val status = java.util.concurrent.atomic.AtomicLong()
        }

        /** Attach to a link manager; collects records until [shutdown]. */
        fun attach(link: CompanionLinkManager) {
            scope.launch {
                link.records.collect { record ->
                    try {
                        ingest(record)
                    } catch (e: Exception) {
                        Timber.e(e, "companion ingest failed: ${record::class.simpleName}")
                    }
                }
            }
        }

        fun shutdown() {
            scope.cancel()
        }

        private suspend fun ingest(record: CompanionProtocol.Record) {
            when (record) {
                is CompanionProtocol.Record.BleAdv -> {
                    counters.ble.incrementAndGet()
                    ingestBleAdv(record)
                }
                is CompanionProtocol.Record.WifiProbe -> {
                    counters.probe.incrementAndGet()
                    ingestWifi(record.mac, record.rssi, record.channel, record.timestamp, record.ssid, null)
                }
                is CompanionProtocol.Record.WifiSta -> {
                    counters.sta.incrementAndGet()
                    ingestWifi(record.mac, record.rssi, record.channel, record.timestamp, null, null)
                }
                is CompanionProtocol.Record.WifiBeacon -> {
                    counters.beacon.incrementAndGet()
                    ingestWifi(
                        record.bssid,
                        record.rssi,
                        record.channel,
                        record.timestamp,
                        record.ssid,
                        if (record.encrypted) 1 else 0,
                        radio = CompanionRadio.WIFI_AP,
                    )
                }
                // assoc: client joining a network — deterministic join event
                is CompanionProtocol.Record.WifiAssoc -> {
                    counters.assoc.incrementAndGet()
                    ingestWifi(record.mac, record.rssi, record.channel, record.timestamp, record.ssid, null)
                }
                is CompanionProtocol.Record.Alert -> {
                    counters.alert.incrementAndGet()
                    ingestAlert(record)
                }
                is CompanionProtocol.Record.Status -> {
                    counters.status.incrementAndGet()
                    // status snapshots go to the registry for UI display
                }
            }
        }

        // ------------------------------------------------------------------
        private suspend fun ingestBleAdv(record: CompanionProtocol.Record.BleAdv) {
            val parsed = AdvPayloadParser.parse(record.adv)

            val serviceUuids = parsed.serviceUuids?.map { android.os.ParcelUuid.fromString(it) }
            val identification =
                DeviceIdentifier.identifyDevice(
                    manufacturerId = parsed.manufacturerId,
                    manufacturerData = parsed.manufacturerData,
                    serviceUuids = serviceUuids,
                    appearance = parsed.appearance,
                    deviceName = parsed.name,
                )

            val manufacturerInfo =
                if (parsed.manufacturerId != null && parsed.manufacturerData != null) {
                    ManufacturerDataParser.parseManufacturerData(parsed.manufacturerId, parsed.manufacturerData)
                } else {
                    null
                }

            val fingerprint =
                manufacturerInfo?.payloadFingerprint
                    ?: ManufacturerDataParser.extractBestFingerprint(
                        parsed.manufacturerId,
                        parsed.manufacturerData,
                        serviceUuids,
                    )

            val deviceId =
                deviceRepository.upsertDeviceWithFingerprint(
                    address = record.mac,
                    name = parsed.name,
                    advertisedName = parsed.name,
                    lastSeen = record.timestamp,
                    manufacturerData = parsed.manufacturerData,
                    manufacturerId = identification.manufacturerId,
                    manufacturerName = identification.manufacturerName,
                    deviceType = identification.deviceType.name,
                    deviceModel = identification.deviceModel,
                    isTracker = identification.isTracker,
                    serviceUuids = DeviceIdentifier.serviceUuidsToString(serviceUuids),
                    appearance = parsed.appearance,
                    txPowerLevel = parsed.txPowerLevel,
                    advertisingFlags = parsed.advertisingFlags,
                    appleContinuityType = identification.appleContinuityType,
                    identificationConfidence = identification.confidence,
                    identificationMethod = identification.identificationMethod,
                    payloadFingerprint = fingerprint,
                    findMyStatus = manufacturerInfo?.findMyInfo?.statusByte,
                    findMySeparated = manufacturerInfo?.findMyInfo?.separatedFromOwner ?: false,
                    highestRssi = record.rssi,
                )

            attachLocation(deviceId, record.rssi, record.timestamp)
        }

        private suspend fun ingestWifi(
            mac: String,
            rssi: Int,
            channel: Int,
            timestamp: Long,
            ssid: String?,
            wifiFlags: Int?,
            radio: String = CompanionRadio.WIFI_STA,
        ) {
            val deviceType =
                when (radio) {
                    CompanionRadio.WIFI_AP -> "ACCESS_POINT"
                    else -> if (mac.startsWith("02:") || isLocallyAdministered(mac)) "PHONE" else "WIFI_CLIENT"
                }
            val deviceId =
                deviceRepository.upsertCompanionObservation(
                    radio = radio,
                    address = mac,
                    ssid = ssid,
                    channel = channel,
                    rssi = rssi,
                    timestamp = timestamp,
                    deviceType = deviceType,
                    wifiFlags = wifiFlags,
                )
            attachLocation(deviceId, rssi, timestamp)
        }

        private suspend fun ingestAlert(record: CompanionProtocol.Record.Alert) {
            val level =
                when {
                    record.score >= 0.75f -> "CRITICAL"
                    record.score >= 0.5f -> "HIGH"
                    record.score >= 0.25f -> "MEDIUM"
                    else -> "LOW"
                }
            Timber.i("companion alert: ${record.label} score=%.2f places=${record.places}".format(record.score))
            companionRepository.insertAlert(
                AlertHistory(
                    alertLevel = level,
                    title = "Companion: ${record.label}",
                    message =
                        "Companion device flagged '${record.label}' at ${record.places} places " +
                            "(score ${"%.2f".format(record.score)}${if (record.tracker) ", tracker" else ""}" +
                            "${if (record.separated) ", separated from owner" else ""}).",
                    timestamp = record.timestamp,
                    deviceAddresses = "[\"${record.label}\"]",
                    locationIds = "[]",
                    threatScore = record.score.toDouble(),
                    detectionDetails = "{}",
                ),
            )
        }

        // ------------------------------------------------------------------
        private suspend fun attachLocation(
            deviceId: Long,
            rssi: Int,
            timestamp: Long,
        ) {
            val locationId = currentLocationId() ?: return
            try {
                deviceRepository.insertDeviceLocationRecord(
                    deviceId = deviceId,
                    locationId = locationId,
                    rssi = rssi,
                    timestamp = timestamp,
                    locationChanged = false,
                    distanceFromLast = null,
                    scanTriggerType = Constants.SCAN_TRIGGER_COMPANION,
                )
            } catch (e: Exception) {
                Timber.e(e, "companion location record failed")
            }
        }

        private suspend fun currentLocationId(): Long? {
            val now = System.currentTimeMillis()
            if (cachedLocationId != null && now - cachedLocationAt < LOCATION_CACHE_MS) {
                return cachedLocationId
            }
            val location =
                locationRepository.getCurrentLocation()
                    ?: locationRepository.getLastKnownLocation()
                    ?: locationRepository.getLastLocation()
                    ?: return null
            val (id, _) = locationRepository.findOrCreateLocation(location, radiusMeters = 50.0)
            cachedLocationId = id
            cachedLocationAt = now
            return id
        }

        private fun isLocallyAdministered(mac: String): Boolean {
            val first = mac.substringBefore(':').toIntOrNull(16) ?: return false
            return (first and 0x40) != 0
        }

        companion object {
            private const val LOCATION_CACHE_MS = 30_000L
        }
    }

/** Radio-layer constants for companion observations. */
object CompanionRadio {
    const val BLE = "BLE"
    const val WIFI_STA = "WIFI_STA"
    const val WIFI_AP = "WIFI_AP"
}
