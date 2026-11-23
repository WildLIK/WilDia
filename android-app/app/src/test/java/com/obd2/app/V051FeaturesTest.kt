package com.obd2.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidReading
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.replay.ReplayFixtureKind
import com.obd2.app.core.replay.SessionLogReplayer
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.search.DisabledDtcOnlineSearchProvider
import com.obd2.app.core.search.DtcOnlineSearchResponse
import com.obd2.app.core.search.DtcSearchCache
import com.obd2.app.core.search.DtcSearchRequest
import com.obd2.app.core.search.DtcSearchResult
import com.obd2.app.core.search.DtcSearchSource
import com.obd2.app.core.telemetry.ChartTimeWindow
import com.obd2.app.core.telemetry.TelemetryChartBuffer
import com.obd2.app.core.telemetry.TripCsvExporter
import com.obd2.app.core.telemetry.TripRecorder
import com.obd2.app.core.updates.DbApplyResult
import com.obd2.app.core.updates.DbUpdateManifest
import com.obd2.app.core.updates.DiagnosticDbUpdater
import com.obd2.app.core.updates.UpdateTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V051FeaturesTest {

    private fun testRegistry() = listOf(
        PidDefinition(1, 0x0C, "RPM", "Обороты двигателя", "об/мин", 2, "numeric", "AB", 0.25, 0.0, null, "engine"),
        PidDefinition(1, 0x05, "Coolant", "Темп. ОЖ", "°C", 1, "numeric", "A", 1.0, -40.0, null, "engine"),
        PidDefinition(1, 0x0D, "Speed", "Скорость", "км/ч", 1, "numeric", "A", 1.0, 0.0, null, "speed"),
    )

    @Test
    fun `1 - TelemetryChartBuffer computes stats, groups by unit, pauses safely and shows Нет данных`() {
        val reg = testRegistry()
        val buf = TelemetryChartBuffer()

        buf.ingestLiveSnapshot(
            live = mapOf(
                0x0C to PidResult.Ok(PidReading(1, 0x0C, 1000.0, unit = "об/мин", rawHex = "0F A0", timestampMs = 1000L)),
                0x05 to PidResult.Ok(PidReading(1, 0x05, 80.0, unit = "°C", rawHex = "78", timestampMs = 1000L)),
            ),
            unsupportedPids = setOf(0x0D),
            registry = reg,
        )
        buf.setPaused(true, nowMs = 1000L)
        // New sample arrives while display is paused -> still buffered, not lost!
        buf.ingestLiveSnapshot(
            live = mapOf(
                0x0C to PidResult.Ok(PidReading(1, 0x0C, 2000.0, unit = "об/мин", rawHex = "1F 40", timestampMs = 2000L)),
            ),
            unsupportedPids = setOf(0x0D),
            registry = reg,
        )
        assertEquals(1, buf.samplesFor(0x0C, ChartTimeWindow.ALL, nowMs = 2000L).size)
        buf.setPaused(false)
        assertEquals(2, buf.samplesFor(0x0C, ChartTimeWindow.ALL, nowMs = 2000L).size)

        val rpmStats = buf.statsFor(0x0C, reg)
        assertTrue(rpmStats.available)
        assertEquals(1000.0, rpmStats.min!!, 0.01)
        assertEquals(1500.0, rpmStats.avg!!, 0.01)
        assertEquals(2000.0, rpmStats.max!!, 0.01)

        // Unsupported PID reports "Нет данных", never 0
        val speedStats = buf.statsFor(0x0D, reg)
        assertFalse(speedStats.available)
        assertEquals("Нет данных", speedStats.latestDisplay)

        val groups = buf.groupByUnit(listOf(0x0C, 0x05), reg)
        assertEquals(2, groups.size)
    }

    @Test
    fun `2 - TripRecorder persists samples, survives forced kill as INTERRUPTED, and exports CSV offline`() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val dbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "trip_${System.currentTimeMillis()}.db")
            dbFile.delete()
            val reg = testRegistry()
            var recId: Long

            try {
                val db1 = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                    .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6)
                    .allowMainThreadQueries()
                    .build()
                val recorder = TripRecorder(db1, flushIntervalMs = 0L, flushBatchSize = 1)
                recId = recorder.startRecording(sessionId = null, selectedPids = listOf(0x0C, 0x0D), vehicleLabel = "Тест")
                recorder.ingestSnapshot(
                    live = mapOf(0x0C to PidResult.Ok(PidReading(1, 0x0C, 1250.0, unit = "об/мин", rawHex = "13 88", timestampMs = 5000L))),
                    unsupportedPids = setOf(0x0D),
                    registry = reg,
                    nowMs = 5000L,
                )
                // Simulate forced kill (close DB without calling stopRecording)
                db1.close()

                val db2 = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                    .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6)
                    .allowMainThreadQueries()
                    .build()
                try {
                    val recoveredCount = db2.tripRecordingDao().markOrphanRecordingsInterrupted(6000L)
                    assertEquals(1, recoveredCount)
                    val rec = db2.tripRecordingDao().getRecording(recId)!!
                    assertEquals(SessionStatus.INTERRUPTED, rec.status)
                    val samples = db2.tripRecordingDao().samplesForRecording(recId)
                    assertEquals(2, samples.size)
                    assertEquals("OK", samples.first { it.pid == 0x0C }.status)
                    assertEquals("UNSUPPORTED", samples.first { it.pid == 0x0D }.status)

                    val csv = TripCsvExporter.exportCsv(ctx, rec, samples, reg)
                    assertTrue(csv.exists())
                    val text = csv.readText(Charsets.UTF_8)
                    assertTrue(text.contains("recording_id,session_id,timestamp_ms"))
                    assertTrue(text.contains("1250.0000"))
                    assertTrue(text.contains("UNSUPPORTED"))
                    csv.delete()
                } finally {
                    db2.close()
                }
            } finally {
                dbFile.delete()
            }
        }
    }

    @Test
    fun `3 - SessionLogReplayer parses recorded TX RX logs and handles artificial disconnect fixture`() = runBlocking {
        val rawLog = """
            01:00:00.000 >> 010C
            01:00:00.020 << 41 0C 0F A0
            01:00:00.100 >> 0105
            01:00:00.120 << 41 05 5A
        """.trimIndent()
        val parsed = SessionLogReplayer.parseRawLogText(rawLog)
        assertEquals(listOf("41 0C 0F A0"), parsed["010C"])
        assertEquals(listOf("41 05 5A"), parsed["0105"])

        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        try {
            val replay = SessionLogReplayer.buildFixture(ReplayFixtureKind.RECORDED_LOG, rawLog)
            repo.connectTransportForTest(replay, deviceName = "Replay", demo = true)
            val r = repo.readPid(0x0C)
            assertTrue(r is PidResult.Ok)
            assertEquals(1000.0, (r as PidResult.Ok).reading.value!!, 0.5)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `4 - DiagnosticDbUpdater validates ECDSA signature, SQLite integrity, rejects corrupt DB and rolls back`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val updater = DiagnosticDbUpdater(ctx, pinnedPublicKey = kp.public)

        fun signManifest(m: DbUpdateManifest): DbUpdateManifest {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(kp.private)
            sig.update(m.canonicalPayload().toByteArray(Charsets.UTF_8))
            return m.copy(signatureBase64 = Base64.getEncoder().encodeToString(sig.sign()))
        }

        // Valid SQLite payload
        val validDbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "valid_dtc_${System.currentTimeMillis()}.db")
        val sqlDb = SQLiteDatabase.openOrCreateDatabase(validDbFile, null)
        sqlDb.execSQL("CREATE TABLE meta(version TEXT)")
        sqlDb.execSQL("INSERT INTO meta VALUES('2026.12.0')")
        sqlDb.close()
        val validBytes = validDbFile.readBytes()
        validDbFile.delete()

        val validManifest = signManifest(
            DbUpdateManifest(
                schemaVersion = 1,
                track = UpdateTrack.DTC_DB.name,
                version = "2026.12.0",
                changelog = "Updated DTCs",
                downloadUrl = "https://github.com/example/releases/dtc.db",
                sha256 = DiagnosticDbUpdater.sha256Hex(validBytes),
                signatureBase64 = "",
                releasedAt = "2026-11-22",
            ),
        )
        val okRes = updater.verifyAndApplyPayload(UpdateTrack.DTC_DB, validManifest, validBytes)
        assertTrue(okRes is DbApplyResult.Applied)
        assertEquals("2026.12.0", updater.getState(UpdateTrack.DTC_DB).currentVersion)

        // Corrupted SQLite payload even with valid signature + sha256 -> must be REJECTED and keep 2026.12.0!
        val corruptBytes = "NOT_A_VALID_SQLITE_FILE".toByteArray()
        val corruptManifest = signManifest(
            DbUpdateManifest(
                schemaVersion = 1,
                track = UpdateTrack.DTC_DB.name,
                version = "2026.13.0",
                changelog = "Broken",
                downloadUrl = "https://github.com/example/releases/bad.db",
                sha256 = DiagnosticDbUpdater.sha256Hex(corruptBytes),
                signatureBase64 = "",
                releasedAt = "2026-11-22",
            ),
        )
        val badRes = updater.verifyAndApplyPayload(UpdateTrack.DTC_DB, corruptManifest, corruptBytes)
        assertTrue(badRes is DbApplyResult.Rejected)
        assertEquals("2026.12.0", updater.getState(UpdateTrack.DTC_DB).currentVersion)

        // Bad signature -> rejected even if SHA-256 matches
        val forged = validManifest.copy(version = "2026.99.0", signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        val forgedRes = updater.verifyAndApplyPayload(UpdateTrack.DTC_DB, forged, validBytes)
        assertTrue(forgedRes is DbApplyResult.Rejected)

        // Consent guard: autoUpdate=false blocks automatic download
        val (allowed, _) = updater.canDownload(autoUpdate = false, wifiOnly = true, userInitiated = false)
        assertFalse(allowed)

        // Rollback works
        updater.rollback(UpdateTrack.DTC_DB)
        assertTrue(updater.getHistory().any { it.action == "ROLLED_BACK" })
    }

    @Test
    fun `5 - Online DTC search requires opt-in and caches real results`() = runBlocking {
        val provider = DisabledDtcOnlineSearchProvider()
        val req = DtcSearchRequest(code = "P0171", manufacturer = "VAG", includeVinConsent = false)
        val off = provider.search(req, userOptInEnabled = false)
        assertTrue(off is DtcOnlineSearchResponse.Disabled)

        val cache = DtcSearchCache()
        cache.put(
            req,
            DtcSearchResult(
                code = "P0171",
                possibleCauses = listOf("Подсос воздуха во впускном тракте"),
                symptoms = listOf("Нестабильный холостой ход"),
                recommendedChecks = listOf("Проверка герметичности впуска и значений LTFT"),
                sources = listOf(DtcSearchSource("SAE J2012", "https://www.sae.org")),
            ),
        )
        val cached = cache.get("P0171", "VAG")
        assertNotNull(cached)
        assertTrue(cached!!.fromCache)
    }

    @Test
    fun `6 - Room v4 to v5 migration preserves sessions and DTCs`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "mig45_${System.currentTimeMillis()}.db")
        dbFile.delete()
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(ctx)
                    .name(dbFile.absolutePath)
                    .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS sessions (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    startedAtMs INTEGER NOT NULL,
                                    updatedAtMs INTEGER NOT NULL,
                                    endedAtMs INTEGER,
                                    adapterName TEXT NOT NULL,
                                    transportType TEXT,
                                    demoMode INTEGER NOT NULL,
                                    protocol TEXT,
                                    profileId TEXT,
                                    profileName TEXT,
                                    identifiedVin TEXT,
                                    vinMasked TEXT,
                                    ecuIds TEXT,
                                    vehicleIdentitySource TEXT NOT NULL,
                                    storedCount INTEGER NOT NULL,
                                    pendingCount INTEGER NOT NULL,
                                    permanentCount INTEGER NOT NULL,
                                    dtcReadCompleted INTEGER NOT NULL,
                                    status TEXT NOT NULL,
                                    endReason TEXT,
                                    rawDtcResponsesJson TEXT,
                                    legacyUnverified INTEGER NOT NULL DEFAULT 0,
                                    freezeFrameJson TEXT,
                                    readinessJson TEXT,
                                    discoveredEcusJson TEXT
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS dtc_records (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    sessionId INTEGER NOT NULL,
                                    code TEXT NOT NULL,
                                    mode INTEGER NOT NULL,
                                    descriptionRu TEXT,
                                    descriptionEn TEXT,
                                    scope TEXT,
                                    timestampMs INTEGER NOT NULL,
                                    ecuId TEXT,
                                    rawResponse TEXT,
                                    translationStatus TEXT
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS session_sensors (
                                    sessionId INTEGER NOT NULL,
                                    mode INTEGER NOT NULL,
                                    pid INTEGER NOT NULL,
                                    name TEXT NOT NULL,
                                    valueText TEXT NOT NULL,
                                    numericValue REAL,
                                    unit TEXT,
                                    rawHex TEXT,
                                    timestampMs INTEGER NOT NULL,
                                    PRIMARY KEY(sessionId, mode, pid)
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO sessions (id, startedAtMs, updatedAtMs, adapterName, demoMode, vehicleIdentitySource, storedCount, pendingCount, permanentCount, dtcReadCompleted, status, legacyUnverified) VALUES (1, 1000, 1000, 'IOS-Vlink', 0, 'NONE', 1, 0, 0, 1, 'COMPLETED', 0)")
                            db.execSQL("INSERT INTO dtc_records (sessionId, code, mode, timestampMs) VALUES (1, 'P0171', 3, 1000)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build(),
            )
            helper.writableDatabase.close()

            val migrated = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(1, migrated.sessionDao().recentSessions(5).size)
                assertEquals(listOf("P0171"), migrated.dtcRecordDao().forSession(1).map { it.code })
                assertEquals(0, migrated.tripRecordingDao().recentRecordings(5).size)
            } finally {
                migrated.close()
            }
        } finally {
            dbFile.delete()
        }
    }
}
