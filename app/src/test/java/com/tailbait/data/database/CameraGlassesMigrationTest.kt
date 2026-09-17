package com.tailbait.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import com.tailbait.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration test for v12 -> v13 (camera glasses presence alerts toggle).
 *
 * Simulates a real upgrade: builds a database with the exact schema exported
 * for version 12 (schemas/.../12.json), seeds a settings row as the old app
 * would have left it, then opens it with the production database builder.
 * Room runs MIGRATION_12_13 and validates the migrated schema against the
 * current entities — a schema mismatch fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraGlassesMigrationTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getDatabasePath(TailBaitDatabase.DATABASE_NAME).parentFile?.mkdirs()
        context.getDatabasePath(TailBaitDatabase.DATABASE_NAME).delete()
    }

    @After
    fun tearDown() {
        TailBaitDatabase.closeInstanceForTest()
    }

    /** Locate the exported schema json (Gradle test working dir is the module dir). */
    private fun schemaFile(version: Int): File {
        val rel = "schemas/com.tailbait.data.database.TailBaitDatabase/$version.json"
        val candidates = listOf(File(rel), File("app/$rel"))
        return candidates.firstOrNull { it.exists() }
            ?: error("Schema file not found: $rel (working dir: ${File(".").absolutePath})")
    }

    @Test
    fun migrate12To13_addsToggleColumnPreservesRowsAndDaoRoundTrips() =
        runTest {
            // 1. Build a v12 database using the exported v12 schema verbatim
            val schema = JSONObject(schemaFile(12).readText()).getJSONObject("database")
            assertEquals(12, schema.getInt("version"))
            val database =
                SQLiteDatabase.openOrCreateDatabase(
                    context.getDatabasePath(TailBaitDatabase.DATABASE_NAME),
                    null,
                )
            schema.getJSONArray("entities").let { entities ->
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    database.execSQL(
                        entity.getString("createSql")
                            .replace("\${TABLE_NAME}", entity.getString("tableName")),
                    )
                    // Recreate indices too, so Room's schema validation sees them
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) {
                            val index = indices.getJSONObject(j)
                            database.execSQL(
                                index.getString("createSql")
                                    .replace("\${TABLE_NAME}", entity.getString("tableName")),
                            )
                        }
                    }
                }
            }

            // 2. Seed a settings row exactly as the v12 app left it (no toggle column)
            database.execSQL(
                """
                INSERT INTO app_settings (
                    id, is_tracking_enabled, scan_interval_seconds, scan_duration_seconds,
                    min_detection_distance_meters, alert_threshold_count,
                    alert_notification_enabled, alert_sound_enabled, alert_vibration_enabled,
                    learn_mode_active, data_retention_days, battery_optimization_enabled,
                    companion_enabled, theme_mode, updated_at
                ) VALUES (
                    1, 1, 300, 30,
                    100.0, 3,
                    1, 1, 1,
                    0, 30, 1,
                    0, 'SYSTEM', ${System.currentTimeMillis()}
                )
                """.trimIndent(),
            )
            database.version = 12
            database.close()

            // 3. Open with the production builder: runs MIGRATION_12_13 and
            //    validates the migrated schema against the current entities
            //    (throws on any mismatch), then adopts the v13 identity hash.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val room = TailBaitDatabase.getInstance(context)

            // 4. Existing row survived; the new column defaults to 0 (alerts off)
            room.query(SimpleSQLiteQuery("SELECT camera_glasses_alerts_enabled FROM app_settings WHERE id = 1")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            room.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM app_settings")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            // 5. The settings repository round-trips the new toggle on the migrated DB
            val settingsRepository = SettingsRepositoryImpl(room.appSettingsDao())
            settingsRepository.updateCameraGlassesAlertsEnabled(true)
            assertTrue(settingsRepository.getSettingsOnce().cameraGlassesAlertsEnabled)
            settingsRepository.updateCameraGlassesAlertsEnabled(false)
            assertFalse(settingsRepository.getSettingsOnce().cameraGlassesAlertsEnabled)
            }
        }
}
