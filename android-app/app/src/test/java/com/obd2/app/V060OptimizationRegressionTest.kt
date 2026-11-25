package com.obd2.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.diag.DiagCategory
import com.obd2.app.core.diag.DiagEvent
import com.obd2.app.core.diag.DiagJournal
import com.obd2.app.core.diag.DiagSeverity
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidReading
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.search.DtcDiagnosticCard
import com.obd2.app.core.search.DtcSearchRequest
import com.obd2.app.core.search.PersistentDtcSearchCache
import com.obd2.app.core.telemetry.ChartTimeWindow
import com.obd2.app.core.telemetry.TelemetryChartBuffer
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.core.updates.ApkReleaseManifest
import com.obd2.app.core.updates.DbApplyResult
import com.obd2.app.core.updates.DbUpdateManifest
import com.obd2.app.core.updates.UpdateTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Targeted regression suite for v0.6.0 optimization mechanisms:
 * - Room migration 6 -> 7 (data preservation + performance indexes)
 * - Lazy pids_seed.json startup & seamless handoff to DB updates / rollbacks
 * - Compacted SQLite lookup completeness (causes, symptoms, checks, RU search, LRU cache)
 * - Adaptive ECU polling (fast vs slow PID throttling, accurate decoding, foreground gating)
 * - Chart min-max downsampling extrema preservation
 * - Lazy startup initialization for DiagJournal & PersistentDtcSearchCache
 * - Android 6.0 (minSdk 23) manifest/APK metadata & AppIcons coverage
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V060OptimizationRegressionTest {

    @Test
    fun roomMigration6to7_preservesUserHistoryAndCreatesPerformanceIndexes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(ctx.cacheDir, "v6_migration_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()

        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sdb ->
                sdb.execSQL(
                    "CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "startedAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, endedAtMs INTEGER, " +
                        "adapterName TEXT NOT NULL, transportType TEXT, demoMode INTEGER NOT NULL, protocol TEXT, " +
                        "profileId TEXT, profileName TEXT, identifiedVin TEXT, vinMasked TEXT, ecuIds TEXT, " +
                        "vehicleIdentitySource TEXT NOT NULL, storedCount INTEGER NOT NULL, pendingCount INTEGER NOT NULL, " +
                        "permanentCount INTEGER NOT NULL, dtcReadCompleted INTEGER NOT NULL, status TEXT NOT NULL, " +
                        "endReason TEXT, rawDtcResponsesJson TEXT, legacyUnverified INTEGER NOT NULL DEFAULT 0, " +
                        "freezeFrameJson TEXT, readinessJson TEXT, discoveredEcusJson TEXT)",
                )
                sdb.execSQL(
                    "CREATE TABLE IF NOT EXISTS dtc_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER NOT NULL, code TEXT NOT NULL, mode INTEGER NOT NULL, descriptionRu TEXT, " +
                        "descriptionEn TEXT, scope TEXT, timestampMs INTEGER NOT NULL, ecuId TEXT, rawResponse TEXT, " +
                        "translationStatus TEXT)",
                )
                sdb.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_sensors (sessionId INTEGER NOT NULL, mode INTEGER NOT NULL, " +
                        "pid INTEGER NOT NULL, name TEXT NOT NULL, valueText TEXT NOT NULL, numericValue REAL, unit TEXT, " +
                        "rawHex TEXT, timestampMs INTEGER NOT NULL, PRIMARY KEY(sessionId, mode, pid))",
                )
                sdb.execSQL(
                    "CREATE TABLE IF NOT EXISTS trip_recordings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER, startedAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, endedAtMs INTEGER, " +
                        "status TEXT NOT NULL, selectedPidsCsv TEXT NOT NULL, sampleCount INTEGER NOT NULL, " +
                        "errorCount INTEGER NOT NULL, maxDurationSec INTEGER NOT NULL, maxSamples INTEGER NOT NULL, " +
                        "vehicleLabel TEXT NOT NULL, notes TEXT)",
                )
                sdb.execSQL(
                    "CREATE TABLE IF NOT EXISTS trip_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "recordingId INTEGER NOT NULL, timestampMs INTEGER NOT NULL, pid INTEGER NOT NULL, value REAL, " +
                        "unit TEXT, status TEXT NOT NULL, errorNote TEXT)",
                )
                sdb.execSQL("CREATE INDEX IF NOT EXISTS index_trip_samples_recordingId_timestampMs ON trip_samples(recordingId, timestampMs)")
                sdb.execSQL(
                    "CREATE TABLE IF NOT EXISTS dtc_scan_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "runId TEXT NOT NULL, sessionId INTEGER NOT NULL, timestampMs INTEGER NOT NULL, status TEXT NOT NULL, " +
                        "storedCodesJson TEXT NOT NULL, pendingCodesJson TEXT NOT NULL, permanentCodesJson TEXT NOT NULL, " +
                        "errorsJson TEXT NOT NULL, auditJson TEXT NOT NULL)",
                )
                sdb.execSQL("CREATE INDEX IF NOT EXISTS index_dtc_scan_runs_sessionId_timestampMs ON dtc_scan_runs(sessionId, timestampMs)")

                sdb.execSQL(
                    "INSERT INTO sessions (id, startedAtMs, updatedAtMs, endedAtMs, adapterName, transportType, demoMode, " +
                        "identifiedVin, vinMasked, vehicleIdentitySource, storedCount, pendingCount, permanentCount, " +
                        "dtcReadCompleted, status, legacyUnverified) " +
                        "VALUES (42, 1700000000000, 1700000060000, 1700000060000, 'Vlink', 'WIFI_TCP', 0, " +
                        "'TMBCA21Z082000001', 'TMBCA21Z*****0001', 'AUTO_VIN', 1, 0, 0, 1, 'COMPLETED', 0)",
                )
                sdb.execSQL(
                    "INSERT INTO dtc_records (sessionId, code, mode, descriptionRu, scope, timestampMs) " +
                        "VALUES (42, 'P0420', 3, 'Низкая эффективность катализатора', 'GENERIC', 1700000005000)",
                )
                sdb.execSQL(
                    "INSERT INTO session_sensors (sessionId, mode, pid, name, valueText, numericValue, unit, rawHex, timestampMs) " +
                        "VALUES (42, 1, 12, 'Обороты двигателя', '780 об/мин', 780.0, 'об/мин', '41 0C 0C 30', 1700000006000)",
                )
                sdb.execSQL(
                    "INSERT INTO trip_recordings (id, sessionId, startedAtMs, updatedAtMs, status, selectedPidsCsv, " +
                        "sampleCount, errorCount, maxDurationSec, maxSamples, vehicleLabel) " +
                        "VALUES (7, 42, 1700000001000, 1700000010000, 'COMPLETED', '12,13', 1, 0, 600, 5000, 'Octavia')",
                )
                sdb.execSQL(
                    "INSERT INTO trip_samples (recordingId, timestampMs, pid, value, unit, status) " +
                        "VALUES (7, 1700000002000, 12, 780.0, 'об/мин', 'OK')",
                )
                sdb.version = 6
            }

            val upgraded = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                .addMigrations(*AppDb.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            try {
                runBlocking {
                    val sessions = upgraded.sessionDao().recentSessions(10)
                    assertEquals(1, sessions.size)
                    assertEquals(42L, sessions.first().id)
                    assertEquals("TMBCA21Z082000001", sessions.first().identifiedVin)
                    assertEquals("WIFI_TCP", sessions.first().transportType)

                    val dtcs = upgraded.dtcRecordDao().forSession(42L)
                    assertEquals(1, dtcs.size)
                    assertEquals("P0420", dtcs.first().code)

                    val sensors = upgraded.sessionSensorDao().forSession(42L)
                    assertEquals(1, sensors.size)
                    assertEquals(780.0, sensors.first().numericValue!!, 0.01)

                    val samples = upgraded.tripRecordingDao().samplesForRecording(7L)
                    assertEquals(1, samples.size)
                }

                val indexes = mutableSetOf<String>()
                upgraded.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='index'",
                ).use { c ->
                    while (c.moveToNext()) indexes.add(c.getString(0))
                }
                assertTrue(indexes.contains("index_sessions_startedAtMs"))
                assertTrue(indexes.contains("index_sessions_status"))
                assertTrue(indexes.contains("index_dtc_records_sessionId_mode"))
                assertTrue(indexes.contains("index_dtc_records_timestampMs"))
            } finally {
                upgraded.close()
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun pidsSeedJson_matchesBundledDbAndReloadsOnDbUpdateAndRollback() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dbDir = ctx.getDatabasePath("lookup").parentFile ?: ctx.filesDir
        File(dbDir, "obd_data.version").delete()
        dbDir.listFiles { f -> f.name.startsWith("obd_data") }?.forEach { it.delete() }

        val lookup = ObdLookupDb(ctx)
        val seedPids = lookup.allPids()
        assertEquals(132, seedPids.size)
        // Lazy startup must not force extraction of obd_data.db just to list standard PIDs
        assertFalse(File(dbDir, "obd_data.version").exists())

        // Force extraction via meta() and verify 100% row-for-row equivalence between pids_seed.json and obd_data.db
        val meta = lookup.meta()
        assertTrue(meta.pids == seedPids.size)
        assertTrue(File(dbDir, "obd_data.version").exists())
        val dbPids = lookup.allPids()
        assertEquals(dbPids, seedPids)

        // Build a valid minimal updated SQLite DB adding a new custom Mode 01 PID (0x7F)
        val customDbFile = File(ctx.cacheDir, "custom_update_${System.nanoTime()}.db")
        if (customDbFile.exists()) customDbFile.delete()
        val unpackedName = File(dbDir, "obd_data.version").readText().trim()
        File(dbDir, unpackedName).copyTo(customDbFile, overwrite = true)

        SQLiteDatabase.openDatabase(customDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { sdb ->
            sdb.execSQL(
                "INSERT OR REPLACE INTO pids (mode, pid, name_en, name_ru, unit, bytes, decoder, weight, mult, \"offset\", formula, category) " +
                    "VALUES (1, 127, 'Custom Test Pressure', 'Тестовое давление', 'кПа', 1, 'linear', 'A', 1.0, 0.0, 'A', 'engine')",
            )
            sdb.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES ('version', '2.1')")
        }

        val payload = customDbFile.readBytes()
        customDbFile.delete()
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = DiagnosticRepository(ctx, lookup, AppDb.get(ctx), SettingsRepository(ctx), RawLog(), scope)
        try {
            // Prime _pidRegistry from initial state
            assertEquals(132, repo.reloadPidRegistry().size)

            val manifest = DbUpdateManifest(
                schemaVersion = com.obd2.app.core.updates.DiagnosticDbUpdater.SUPPORTED_SCHEMA_VERSION,
                track = UpdateTrack.DTC_DB.name,
                version = "2.1",
                changelog = "Added PID 0x7F",
                downloadUrl = "https://example.invalid/db.sqlite",
                sha256 = sha,
                signatureBase64 = "",
            )
            val applied = repo.dbUpdater.verifyAndApplyPayload(UpdateTrack.DTC_DB, manifest, payload)
            assertTrue("Expected Applied, got $applied", applied is DbApplyResult.Applied)

            // Cache must be invalidated so reloadPidRegistry() & findPidDef() see the new PID immediately
            val reloaded = repo.reloadPidRegistry()
            assertEquals(133, reloaded.size)
            val customDef = reloaded.firstOrNull { it.mode == 1 && it.pid == 0x7F }
            assertNotNull(customDef)
            assertEquals("Тестовое давление", customDef!!.nameRu)

            // Rollback must restore bundled DB & invalidate PID registry back to 132
            repo.dbUpdater.rollback(UpdateTrack.DTC_DB)
            assertEquals(132, repo.reloadPidRegistry().size)
        } finally {
            lookup.close()
            scope.cancel()
        }
    }

    @Test
    fun compactedSqliteDb_preservesFullDtcCausesSymptomsChecksAndLruCache() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = ObdLookupDb(ctx)
        try {
            val p0420 = db.lookupDtc("P0420").first { it.scope == "GENERIC" }
            assertFalse(p0420.titleRu.isNullOrBlank())
            assertFalse(p0420.detailRu.isNullOrBlank())
            assertFalse(p0420.recommendationsRu.isNullOrBlank())
            assertTrue("causes must remain intact", p0420.causes.isNotEmpty())
            assertTrue("symptoms must remain intact", p0420.symptoms.isNotEmpty())
            assertTrue("checks must remain intact", p0420.checks.isNotEmpty())
            assertTrue("relatedCodes must remain intact", p0420.relatedCodes.isNotEmpty())

            // Cache hit parity
            val cached = db.lookupDtc("p0420").first { it.scope == "GENERIC" }
            assertEquals(p0420, cached)

            // Verify legacy table still serves Russian search
            val ruHits = db.searchDtc("нейтрализатора", 25)
            assertTrue(ruHits.any { it.code == "P0420" })

            // Verify manufacturer-specific codes (e.g. VAG) remain intact
            val p1136 = db.lookupDtc("P1136")
            assertTrue(p1136.any { it.scope == "VOLKSWAGEN" })
        } finally {
            db.close()
        }
    }

    @Test
    fun adaptiveEcuPolling_prioritizesFastPidsPreservesTelemetryAndThrottlesInBackground() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val lookup = ObdLookupDb(ctx)
        val settings = SettingsRepository(ctx)
        settings.setRefreshMs(100)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = DiagnosticRepository(ctx, lookup, AppDb.get(ctx), settings, RawLog(), scope)
        val countingTransport = CountingPidTransport()

        try {
            repo.connectTransportForTest(countingTransport, deviceName = "CountingELM", demo = false)
            // Reset counts after initial handshake/quick-snapshot so only adaptive polling cycles are measured
            countingTransport.counts.clear()
            repo.setUiForeground(true)
            repo.startPolling(listOf(0x0C, 0x0D, 0x05, 0x0F))

            withTimeout(6000) {
                while ((countingTransport.counts["010C"] ?: 0) < 5 || (countingTransport.counts["010F"] ?: 0) < 2) {
                    delay(20)
                }
            }
            repo.stopPolling()

            val rpmCount = countingTransport.counts["010C"] ?: 0
            val speedCount = countingTransport.counts["010D"] ?: 0
            val coolantCount = countingTransport.counts["0105"] ?: 0
            val intakeCount = countingTransport.counts["010F"] ?: 0

            // Cycle 0 polls all PIDs once, then slow PIDs only every 4th cycle -> fast >> slow
            assertTrue("Expected >= 5 fast RPM reads, got $rpmCount", rpmCount >= 5)
            assertEquals(rpmCount, speedCount)
            assertTrue("Slow coolant should be polled at cycle 0 and decimated afterwards ($coolantCount vs $rpmCount)", coolantCount in 1..2)
            assertEquals(coolantCount, intakeCount)

            // Verify exact decoded values in _liveData
            val live = repo.liveData.value
            assertEquals(2500.0, live[0x0C]?.reading?.value!!, 0.01) // (0x27*256 + 0x10) / 4 = 2500
            assertEquals(72.0, live[0x0D]?.reading?.value!!, 0.01)   // 0x48 = 72 km/h
            assertEquals(90.0, live[0x05]?.reading?.value!!, 0.01)   // 0x82 - 40 = 90 °C
            assertEquals(35.0, live[0x0F]?.reading?.value!!, 0.01)   // 0x4B - 40 = 35 °C

            // Background slowdown should greatly reduce polling frequency while UI is stopped
            val beforeBg = countingTransport.counts["010C"] ?: 0
            repo.setUiForeground(false)
            repo.startPolling(listOf(0x0C))
            delay(600)
            repo.stopPolling()
            val bgReads = (countingTransport.counts["010C"] ?: 0) - beforeBg
            assertTrue("Expected <= 2 reads during 600ms background throttle (>=2500ms interval), got $bgReads", bgReads <= 2)
        } finally {
            repo.stopPolling()
            repo.disconnect()
            lookup.close()
            scope.cancel()
        }
    }

    @Test
    fun chartBuffer_downsampleMinMaxPreservesExtremaAndOrder() {
        val buf = TelemetryChartBuffer(maxSamplesPerPid = 2000)
        val reg = listOf(
            PidDefinition(
                mode = 1,
                pid = 0x0C,
                nameEn = "RPM",
                nameRu = "Обороты",
                unit = "об/мин",
                bytes = 2,
                decoder = "numeric",
                weight = "AB",
                mult = 0.25,
                offset = 0.0,
                formula = "AB*0.25",
                category = "engine",
            ),
        )
        val base = 1_700_000_000_000L
        for (i in 0 until 1000) {
            val value = when (i) {
                237 -> 450.0   // sharp global min inside inner buckets
                743 -> 6420.0  // sharp global peak inside inner buckets
                else -> 1500.0 + (i % 50) * 10.0
            }
            buf.ingestLiveSnapshot(
                live = mapOf(
                    0x0C to PidResult.Ok(
                        PidReading(
                            mode = 1,
                            pid = 0x0C,
                            value = value,
                            unit = "об/мин",
                            rawHex = "410C",
                            timestampMs = base + i * 100L,
                        ),
                    ),
                ),
                unsupportedPids = emptySet(),
                registry = reg,
            )
        }

        val (stats, rendered) = buf.snapshotForChart(
            pid = 0x0C,
            registry = reg,
            window = ChartTimeWindow.ALL,
            scrollFraction = 1f,
            maxRenderPoints = 240,
            nowMs = base + 100_000L,
        )

        assertTrue(stats.available)
        assertEquals(1000, stats.count)
        assertEquals(450.0, stats.min!!, 0.001)
        assertEquals(6420.0, stats.max!!, 0.001)

        assertTrue("Rendered count ${rendered.size} must stay <= 240", rendered.size <= 240)
        assertEquals(base, rendered.first().timestampMs)
        assertEquals(base + 999 * 100L, rendered.last().timestampMs)
        assertTrue("Global min spike must be preserved in downsampled series", rendered.any { it.value == 450.0 })
        assertTrue("Global max peak must be preserved in downsampled series", rendered.any { it.value == 6420.0 })
        for (i in 1 until rendered.size) {
            assertTrue("Samples must stay chronologically sorted", rendered[i].timestampMs >= rendered[i - 1].timestampMs)
        }
    }

    @Test
    fun lazyStartupComponents_avoidDiskIoUntilAccessed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val diagDir = File(ctx.cacheDir, "lazy_diag_${System.nanoTime()}")
        assertFalse(diagDir.exists())

        val journal = DiagJournal(diagDir)
        assertFalse("DiagJournal constructor must not create directory/files", diagDir.exists())
        journal.add(
            DiagEvent(
                ts = System.currentTimeMillis(),
                category = DiagCategory.SYS,
                op = "BOOT",
                details = "lazy check",
                severity = DiagSeverity.INFO,
            ),
        )
        assertTrue("DiagJournal creates spill on first event", diagDir.exists())
        journal.close()
        diagDir.deleteRecursively()

        val cacheFile = File(ctx.filesDir, "dtc_search_cache.json")
        cacheFile.writeText("{corrupt_unaccessed", Charsets.UTF_8)
        try {
            val cache = PersistentDtcSearchCache(ctx)
            // Constructor did not parse file yet; accessing getAnyCached loads lazily and survives malformed JSON
            assertEquals(null, cache.getAnyCached("P0300"))
            cache.put(
                DtcSearchRequest(code = "P0300"),
                DtcDiagnosticCard(
                    code = "P0300",
                    title = "Пропуски зажигания",
                    standardMeaning = "Random/Multiple Cylinder Misfire Detected",
                    fallbackSearchUrl = "https://example.invalid/P0300",
                    fetchedAtMs = 1L,
                ),
            )
            assertEquals(1, cache.allCached().size)
        } finally {
            cacheFile.delete()
        }
    }

    @Test
    fun releaseMetadataMinSdk23AndLocalAppIconsAreConsistent() {
        assertEquals("0.6.0", BuildConfig.VERSION_NAME)
        assertEquals(19, BuildConfig.VERSION_CODE)

        val decodedManifest = Json { ignoreUnknownKeys = true }.decodeFromString<ApkReleaseManifest>(
            """{"versionCode":19,"versionName":"0.6.0","apkUrl":"https://example.com/app.apk","sha256":"abc"}""",
        )
        assertEquals(23, decodedManifest.minAndroidSdk)

        // Every Screen route must resolve a valid non-null ImageVector via AppIcons / Icons.Filled
        assertEquals(19, Screen.all.size)
        for (screen in Screen.entries) {
            assertNotNull("Icon for ${screen.route} must not be null", screen.icon)
        }
    }

    private class CountingPidTransport : ByteTransport {
        override val name: String = "CountingELM"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming
        val counts = ConcurrentHashMap<String, Int>()

        override suspend fun connect() {
            _state.value = TransportState.Connected("CountingELM ready")
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected

        override suspend fun write(data: ByteArray) {
            val cmd = data.decodeToString().trim().uppercase()
            counts[cmd] = (counts[cmd] ?: 0) + 1
            val resp = when {
                cmd == "ATI" -> "ELM327 v1.5\r\n>"
                cmd.startsWith("ATZ") -> "ELM327 v1.5\r\n>"
                cmd.startsWith("ATDPN") -> "6\r\n>"
                cmd.startsWith("ATDP") -> "AUTO, ISO 15765-4 (CAN 11/500)\r\n>"
                cmd.startsWith("ATRV") -> "13.8V\r\n>"
                cmd.startsWith("AT") -> "OK\r\n>"
                cmd == "0100" -> "41 00 BE 3E B8 11\r\n>"
                cmd == "0120" -> "41 20 80 00 00 00\r\n>"
                cmd == "010C" -> "41 0C 27 10\r\n>"
                cmd == "010D" -> "41 0D 48\r\n>"
                cmd == "0105" -> "41 05 82\r\n>"
                cmd == "010F" -> "41 0F 4B\r\n>"
                else -> "NO DATA\r\n>"
            }
            _incoming.emit(resp.toByteArray(Charsets.US_ASCII))
        }
    }
}
