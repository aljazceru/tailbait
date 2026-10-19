package com.tailbait.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailbait.algorithm.ShadowKeyGenerator
import com.tailbait.data.database.entities.ScannedDevice
import com.tailbait.data.database.entities.WhitelistEntry
import com.tailbait.data.repository.WhitelistRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectionIdentityMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: TailBaitDatabase? = null

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            TailBaitDatabase::class.java,
        )

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun upgrade13_preservesUserDataAndMakesInvalidLegacyLinksEligibleAgain() =
        runBlocking {
            val before =
                helper.createDatabase(TEST_DATABASE, 13).use { legacy ->
                    seedFixture(legacy)
                    preservedData(legacy)
                }

            val room = TailBaitDatabase.buildDatabase(context, TEST_DATABASE)
            database = room
            val migrated = room.openHelper.writableDatabase
            assertEquals(14, migrated.version)
            assertEquals(before, preservedData(migrated))
            assertEquals(86, snapshot(migrated, "SELECT id FROM device_location_records").size)
            val statusTimestampColumn =
                snapshot(migrated, "PRAGMA table_info(scanned_devices)").single { it[1] == "find_my_status_timestamp" }
            assertEquals("INTEGER", statusTimestampColumn[2])
            assertEquals("0", statusTimestampColumn[3])
            assertNull(statusTimestampColumn[4])

            val devices = room.scannedDeviceDao().getAllDevices().first().associateBy { it.id }
            assertEquals(DEVICE_IDS.toSet(), devices.keys)
            val retainedLinks = mapOf(8L to 7L, 14L to 13L, 18L to 17L)
            devices.forEach { (id, device) ->
                assertEquals("Unexpected identity link for $id", retainedLinks[id], device.linkedDeviceId)
                if (id !in retainedLinks) {
                    assertNull(device.linkStrength)
                    assertNull(device.linkReason)
                    assertNull(device.lastMacRotation)
                } else {
                    assertEquals("STRONG", device.linkStrength)
                }
                assertNull(device.findMyStatus)
                assertNull(device.findMyStatusTimestamp)
                assertFalse(device.findMySeparated)
                assertEquals("Stale shadow classification for $id", ShadowKeyGenerator.generate(device), device.shadowKey)
            }
            (listOf(1L, 2L, 3L, 5L, 6L, 15L, 16L) + (22L..39L) + (41L..43L)).forEach { id ->
                assertNull("Unsupported fingerprint survived for $id", devices.getValue(id).payloadFingerprint)
            }
            assertEquals(FIND_MY_ID, devices.getValue(7L).payloadFingerprint)
            assertEquals(FIND_HUB_ID, devices.getValue(13L).payloadFingerprint)
            assertEquals(LONG_FIND_HUB_ID, devices.getValue(17L).payloadFingerprint)
            assertEquals(FIND_MY_ID, devices.getValue(40L).payloadFingerprint)
            assertTrackerClassification(devices)

            val trust = WhitelistRepositoryImpl(room.whitelistEntryDao(), room.scannedDeviceDao())
            assertEquals(setOf(2L, 7L, 8L), trust.getAllWhitelistedDeviceIds().first().toSet())
            assertFalse(trust.isDeviceWhitelisted(1L))
            assertFalse(trust.isDeviceWhitelisted(3L))
            assertFalse(trust.isDeviceWhitelisted(12L))
            val candidates = room.scannedDeviceDao().getSuspiciousDevicesWithLinked(2, 0L).first().map { it.id }.toSet()
            assertEquals(DEVICE_IDS.toSet() - setOf(2L, 7L, 8L, 14L, 18L), candidates)

            val refreshed = devices.getValue(7L).copy(findMyStatus = 4, findMyStatusTimestamp = 1_800_000_000_000L)
            room.scannedDeviceDao().update(refreshed)
            assertEquals(refreshed, room.scannedDeviceDao().getById(refreshed.id))
            assertEquals(before, preservedData(migrated))
        }

    @Test
    fun upgrade13_confirmedFamilyTrustPrefersRootAndDeletesAllEntries() =
        runBlocking {
            helper.createDatabase(TEST_DATABASE, 13).use { seedFixture(it) }
            val room = TailBaitDatabase.buildDatabase(context, TEST_DATABASE)
            database = room
            val entries = room.whitelistEntryDao()
            assertEquals(502L, entries.getByConfirmedFamily(7L)!!.id)

            val rootEntry = WhitelistEntry(id = 503L, deviceId = 7L, label = "My tag", category = "OWN", createdAt = 702L)
            entries.insert(rootEntry)
            assertEquals(rootEntry, entries.getByConfirmedFamily(7L))
            assertEquals(rootEntry, entries.getByConfirmedFamily(8L))
            assertEquals(2, entries.deleteByConfirmedFamily(8L))
            assertNull(entries.getByConfirmedFamily(7L))
            assertNull(entries.getByConfirmedFamily(8L))
            assertEquals(setOf(2L), entries.getAllWhitelistedDeviceIds().first().toSet())
            assertEquals(501L, entries.getByConfirmedFamily(2L)!!.id)
            assertEquals(0, entries.deleteByConfirmedFamily(7L))
            assertEquals(86, room.deviceLocationRecordDao().getAllRecords().first().size)
        }

    private fun assertTrackerClassification(devices: Map<Long, ScannedDevice>) {
        val unsupported = listOf(1L, 2L, 3L, 5L, 6L, 15L, 16L) + (22L..28L) + (36L..39L) + (41L..43L)
        unsupported.forEach { id ->
            val device = devices.getValue(id)
            assertFalse("Unsupported tracker flag survived for $id", device.isTracker)
            assertFalse("Unsupported tracker type survived for $id", device.deviceType == "TRACKER")
            assertNull("Old derived model became name evidence for $id", device.deviceModel)
            assertTrue("Old confidence survived for $id", device.identificationConfidence < 0.95f)
            assertNull(device.appleContinuityType)
            assertFalse(device.beaconType == "FIND_MY")
            assertFalse(device.shadowKey?.contains("TR:1") == true)
            assertFalse(device.shadowKey?.contains("T:TRACKER") == true)
            assertFalse(device.shadowKey?.contains("SEP:1") == true)
        }
        val protocolModels =
            mapOf(
                7L to "Find My device",
                8L to "Find My device",
                29L to "Samsung SmartTag",
                30L to "Samsung SmartTag",
                31L to "Tile",
                32L to "Tile",
                33L to "Tile",
                34L to "Find My device",
                35L to "Find My device",
            )
        protocolModels.forEach { (id, model) ->
            val device = devices.getValue(id)
            assertTrue("Valid protocol lost for $id", device.isTracker)
            assertEquals("TRACKER", device.deviceType)
            assertEquals(model, device.deviceModel)
            assertEquals(0.95f, device.identificationConfidence, 0.0f)
            assertEquals(
                if (model == "Find My device") "MANUFACTURER_DATA" else "SERVICE_UUID",
                device.identificationMethod,
            )
        }
        // Complete identities remain evidence even when the latest raw payload was not retained.
        listOf(4L, 9L, 10L, 11L, 12L, 13L, 14L, 17L, 18L, 19L, 20L, 21L, 40L).forEach { id ->
            val device = devices.getValue(id)
            assertTrue("Complete identity lost for $id", device.isTracker)
            assertEquals("TRACKER", device.deviceType)
            assertNull(device.deviceModel)
            assertEquals(0.0f, device.identificationConfidence, 0.0f)
            assertNull(device.identificationMethod)
        }
    }

    private fun seedFixture(db: SupportSQLiteDatabase) {
        seedDevice(db, 1, "PP:12345678")
        seedDevice(db, 2, "PP:12345678", 1, "STRONG")
        seedDevice(db, 3, "C:shared-profile", 1, "WEAK")
        seedDevice(db, 4, FIND_MY_ID, 999, "STRONG")
        seedDevice(db, 5, "FM:AABBCCDDEEFF")
        seedDevice(db, 6, "FM:AABBCCDDEEFF", 5, "STRONG")
        seedDevice(db, 7, FIND_MY_ID, manufacturerData = FIND_MY_PAYLOAD, manufacturerId = 76)
        seedDevice(db, 8, FIND_MY_ID, 7, "STRONG", manufacturerData = FIND_MY_PAYLOAD, manufacturerId = 76)
        seedDevice(db, 9, FIND_MY_ID, 9, "STRONG")
        seedDevice(db, 10, FIND_MY_ID, 11, "STRONG")
        seedDevice(db, 11, FIND_MY_ID, 10, "STRONG")
        seedDevice(db, 12, FIND_MY_ID, 8, "STRONG")
        seedDevice(db, 13, FIND_HUB_ID, serviceUuids = "feaa")
        seedDevice(db, 14, FIND_HUB_ID, 13, "STRONG", serviceUuids = "feaa")
        seedDevice(db, 15, "FM:" + "G".repeat(44), 7, "STRONG")
        seedDevice(db, 16, "FHN:" + "ab".repeat(20), 13, "STRONG")
        seedDevice(db, 17, LONG_FIND_HUB_ID)
        seedDevice(db, 18, LONG_FIND_HUB_ID, 17, "STRONG")
        seedDevice(db, 19, FIND_MY_ID, 7, "WEAK")
        seedDevice(db, 20, "FM:" + "CD".repeat(22), 7, "STRONG")
        seedDevice(db, 21, FIND_MY_ID, 7)
        seedDevice(db, 22, serviceUuids = "0000FE2C-0000-1000-8000-00805F9B34FB")
        seedDevice(db, 23, serviceUuids = "fd6f")
        seedDevice(db, 24, serviceUuids = "0000FD5A-1111-1000-8000-00805F9B34FB")
        seedDevice(db, 25, serviceUuids = "FEED0001-C497-4476-A7ED-727DE7648AB2")
        seedDevice(db, 26, manufacturerData = "121904", manufacturerId = 76)
        seedDevice(db, 27, manufacturerData = "120204", manufacturerId = 76)
        seedDevice(db, 28, "C:TRACKER|M:004C")
        seedDevice(db, 29, serviceUuids = "fd5a", manufacturerId = 117, manufacturerData = "01")
        seedDevice(
            db,
            30,
            serviceUuids = "180d, 0000FD5A-0000-1000-8000-00805F9B34FB ,invalid",
            manufacturerId = 76,
            manufacturerData = "121904",
        )
        seedDevice(db, 31, serviceUuids = "feed")
        seedDevice(db, 32, serviceUuids = "0000FEEC-0000-1000-8000-00805F9B34FB")
        seedDevice(db, 33, serviceUuids = "feed0001-c497-4476-a7ed-727de7648ab1")
        seedDevice(db, 34, manufacturerData = "12020400", manufacturerId = 76)
        seedDevice(db, 35, manufacturerData = FIND_MY_PAYLOAD.lowercase(), manufacturerId = 76)
        seedDevice(db, 36, serviceUuids = "d5a")
        seedDevice(db, 37, serviceUuids = "fd5a-0-1000-8000-805f9b34fb")
        seedDevice(db, 38, manufacturerData = FIND_MY_PAYLOAD.replace("AB", "GG"), manufacturerId = 76)
        seedDevice(db, 39, manufacturerData = FIND_MY_PAYLOAD.dropLast(1), manufacturerId = 76)
        seedDevice(db, 40, FIND_MY_ID)
        seedDevice(db, 41, serviceUuids = "feaa")
        seedDevice(db, 42, serviceUuids = "FE8C,FE8D,FE8E,FE9F,FEA0")
        seedDevice(db, 43, serviceUuids = "1234FD5A-0000-1000-8000-00805F9B34FB")
        for (location in 201L..202L) {
            db.execSQL(
                """
                INSERT INTO locations (id, latitude, longitude, accuracy, timestamp, provider, created_at)
                VALUES (?, 51.5, -0.1, 5.0, ?, 'gps', 900)
                """.trimIndent(),
                arrayOf(location, location * 10),
            )
            for (device in DEVICE_IDS) {
                db.execSQL(
                    """
                    INSERT INTO device_location_records (
                        id, device_id, location_id, rssi, timestamp, scan_duration_ms,
                        location_changed, distance_from_last, scan_trigger_type, created_at
                    ) VALUES (?, ?, ?, -55, ?, 30000, 1, 200.0, 'PERIODIC', 900)
                    """.trimIndent(),
                    arrayOf(device * 10 + location - 200, device, location, location * 10),
                )
            }
            db.execSQL(
                "INSERT INTO user_path (id, location_id, timestamp, accuracy, created_at) VALUES (?, ?, ?, 5.0, 900)",
                arrayOf(location, location, location * 10),
            )
        }
        db.execSQL(
            """
            INSERT INTO whitelist_entries (id, device_id, label, category, added_via_learn_mode, notes, created_at)
            VALUES (501, 2, 'My headphones', 'OWN', 1, 'Keep this label and note', 700),
                   (502, 8, 'My tag alias', 'TRUSTED', 0, 'Keep the alias trust entry', 701)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO app_settings (
                id, is_tracking_enabled, scan_interval_seconds, scan_duration_seconds,
                min_detection_distance_meters, alert_threshold_count, alert_notification_enabled,
                alert_sound_enabled, alert_vibration_enabled, learn_mode_active, learn_mode_started_at,
                data_retention_days, battery_optimization_enabled, companion_enabled,
                camera_glasses_alerts_enabled, theme_mode, updated_at
            ) VALUES (1, 0, 120, 45, 175.0, 4, 1, 0, 1, 1, 600, 90, 0, 1, 1, 'DARK', 800)
            """.trimIndent(),
        )
    }

    private fun seedDevice(
        db: SupportSQLiteDatabase,
        id: Long,
        fingerprint: String? = null,
        linkedId: Long? = null,
        strength: String? = null,
        manufacturerData: String? = null,
        manufacturerId: Int? = null,
        serviceUuids: String? = null,
    ) {
        db.execSQL(
            """
            INSERT INTO scanned_devices (
                id, address, name, advertised_name, first_seen, last_seen, detection_count, created_at,
                manufacturer_data, manufacturer_id, payload_fingerprint, find_my_status,
                find_my_separated, linked_device_id, link_strength, link_reason,
                last_mac_rotation, shadow_key, device_type, device_model, is_tracker,
                service_uuids, apple_continuity_type, beacon_type, identification_confidence,
                identification_method, tx_power_level
            ) VALUES (?, ?, ?, ?, ?, ?, 12, 500, ?, ?, ?, 4, 1, ?, ?, 'legacy inference',
                1500, 'same-profile', 'TRACKER', 'AirTag', 1, ?, 18, 'FIND_MY', 1.0, 'SERVICE_UUID', -8)
            """.trimIndent(),
            arrayOf(
                id,
                "00:00:00:00:00:${id.toString(16).padStart(2, '0')}",
                "Device $id",
                "Advertised $id",
                1000 + id,
                4000 + id,
                manufacturerData,
                manufacturerId,
                fingerprint,
                linkedId,
                strength,
                serviceUuids,
            ),
        )
    }

    private fun preservedData(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = listOf("device_location_records", "locations", "user_path", "whitelist_entries", "app_settings")
        return tables.associateWith { snapshot(db, "SELECT * FROM $it ORDER BY id") } +
            (
                "scanned_devices" to
                    snapshot(
                        db,
                        """
                        SELECT id, address, name, advertised_name, first_seen, last_seen, detection_count, created_at,
                            manufacturer_data, manufacturer_id, service_uuids, appearance, tx_power_level,
                            highest_rssi, signal_strength, radio, ssid, channel, wifi_flags
                        FROM scanned_devices ORDER BY id
                        """.trimIndent(),
                    )
            )
    }

    private fun snapshot(
        db: SupportSQLiteDatabase,
        query: String,
    ): List<List<String?>> =
        db.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(List(cursor.columnCount) { column -> if (cursor.isNull(column)) null else cursor.getString(column) })
                }
            }
        }

    companion object {
        private const val TEST_DATABASE = "detection-identity-migration-test"
        private val DEVICE_IDS = 1L..43L
        private val FIND_MY_PAYLOAD = "121904" + "AB".repeat(22) + "0000"
        private val FIND_MY_ID = "FM:" + "AB".repeat(22)
        private val FIND_HUB_ID = "FHN:" + "CD".repeat(20)
        private val LONG_FIND_HUB_ID = "FHN:" + "EF".repeat(32)
    }
}
