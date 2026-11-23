package com.obd2.app

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.ble.BleDevice
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.ClassicBluetoothScanner
import com.obd2.app.core.bt.ClassicBluetoothTransport
import com.obd2.app.core.bt.ClassicDeviceInfo
import com.obd2.app.core.bt.SppProfileStatus
import com.obd2.app.core.bt.TransportResolver
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.Mode09Parser
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.PidReading
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SavedAdapterInfo
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.search.ConfidenceStatus
import com.obd2.app.core.search.DtcOnlineSearchResponse
import com.obd2.app.core.search.DtcSearchRequest
import com.obd2.app.core.search.PersistentDtcSearchCache
import com.obd2.app.core.search.PublicDtcOnlineSearchProvider
import com.obd2.app.core.telemetry.ChartTimeWindow
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

private class ScriptedElmTransport(
    private val responder: (String) -> String,
) : ByteTransport {
    override val name: String = "ScriptedElm"
    override val lastWriteUuid: String = "test"
    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming

    override fun isConnected(): Boolean = _state.value is TransportState.Connected
    override suspend fun connect() {
        _state.value = TransportState.Connected("Test")
    }

    override suspend fun write(data: ByteArray) {
        val cmd = String(data, Charsets.US_ASCII).trim()
        val reply = responder(cmd)
        if (reply.isNotEmpty()) {
            _incoming.emit(reply.toByteArray(Charsets.US_ASCII))
        }
    }

    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class V054FieldTestFixesTest {

    private fun baseHandler(cmd: String): String = when {
        cmd.startsWith("ATZ") -> "ELM327 v1.5\r>"
        cmd.startsWith("AT") -> "OK\r>"
        cmd == "0100" -> "41 00 BE 3E B8 13\r>"
        cmd == "0120" -> "41 20 80 00 00 00\r>"
        cmd == "010C" -> "41 0C 0B B8\r>"
        cmd == "0105" -> "41 05 7A\r>"
        else -> "NO DATA\r>"
    }

    @Test
    fun test01_dtcNoData_isReadFailed_neverNoErrors() {
        runBlocking {
            val t = ScriptedElmTransport { cmd ->
                when (cmd) {
                    "03" -> "NO DATA\r>"
                    else -> baseHandler(cmd)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val elm = Elm327Client(t, RawLog(), scope)
            t.connect()
            elm.initialize()
            val obd = ObdClient(elm)

            val res = obd.readDtc(3, timeoutMs = 1500)
            assertTrue("NO DATA must NOT return Ok(emptyList()), got $res", res is ObdClient.DtcReadResult.Error)
            assertEquals("NO_DATA", (res as ObdClient.DtcReadResult.Error).kind)
        }
    }

    @Test
    fun test02_dtcTimeout_isReadFailed_neverNoErrors() {
        runBlocking {
            val t = ScriptedElmTransport { cmd ->
                if (cmd == "03") "" else baseHandler(cmd)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val elm = Elm327Client(t, RawLog(), scope)
            t.connect()
            elm.initialize()
            val obd = ObdClient(elm)

            val res = obd.readDtc(3, timeoutMs = 250)
            assertTrue("Timeout must return Error, got $res", res is ObdClient.DtcReadResult.Error)
            assertEquals("TIMEOUT", (res as ObdClient.DtcReadResult.Error).kind)
        }
    }

    @Test
    fun test03_dtcConfirmedEmpty_isNoneConfirmed() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            try {
                val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog())
                val t = ScriptedElmTransport { cmd ->
                    when (cmd) {
                        "03" -> "43 00\r>"
                        "07" -> "47 00\r>"
                        "0A" -> "4A 00\r>"
                        else -> baseHandler(cmd)
                    }
                }
                repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)
                val scan = repo.readDtcs(saveHistory = true)
                assertEquals(DiagnosticRepository.DtcScanStatus.DTC_NONE_CONFIRMED, scan.status)
                assertFalse(scan.incomplete)
                assertTrue(scan.stored.isEmpty())
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun test04_dtcFoundThenFailed_doesNotOverwriteFoundAndKeepsSeparateRuns() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            try {
                val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog())
                var mode03Reply = "43 02 03 22 03 35\r>"
                val t = ScriptedElmTransport { cmd ->
                    when (cmd) {
                        "03" -> mode03Reply
                        "07" -> "47 00\r>"
                        "0A" -> "4A 00\r>"
                        else -> baseHandler(cmd)
                    }
                }
                repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)

                // Run 1: P0322 + P0335 found
                val run1 = repo.readDtcs(saveHistory = true)
                assertEquals(DiagnosticRepository.DtcScanStatus.DTC_FOUND, run1.status)
                assertEquals(listOf("P0322", "P0335"), run1.stored.map { it.code })

                // Run 2: Mode 03 returns NO DATA -> must be DTC_READ_FAILED and NOT wipe P0322/P0335 in Room!
                mode03Reply = "NO DATA\r>"
                val run2 = repo.readDtcs(saveHistory = true)
                assertEquals(DiagnosticRepository.DtcScanStatus.DTC_READ_FAILED, run2.status)
                assertTrue(run2.incomplete)

                val savedDtcs = db.dtcRecordDao().forSession(run1.sessionId).map { it.code }
                assertEquals(listOf("P0322", "P0335"), savedDtcs)
                assertEquals(2, repo.dtcScanRuns.value.size)
                assertEquals(2, db.dtcScanRunDao().forSession(run1.sessionId).size)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun test05_dtcAuditLog_recordsCommandsAndMasksSensitiveByDefault() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            try {
                val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog())
                val t = ScriptedElmTransport { cmd ->
                    when (cmd) {
                        "03" -> "43 01 03 22\r>"
                        "07" -> "NO DATA\r>"
                        "0A" -> "4A 00\r>"
                        else -> baseHandler(cmd)
                    }
                }
                repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)
                val scan = repo.readDtcs(saveHistory = false)
                assertEquals(3, scan.auditEntries.size)
                assertEquals(listOf("03", "07", "0A"), scan.auditEntries.map { it.command })
                assertEquals(DiagnosticRepository.DtcScanStatus.DTC_FOUND, scan.auditEntries[0].status)
                assertEquals(DiagnosticRepository.DtcScanStatus.DTC_READ_FAILED, scan.auditEntries[1].status)

                val exported = repo.exportDtcAuditLogSafe(includeSensitive = false)
                assertTrue(exported.contains("cmd=03"))
                assertTrue(exported.contains("P0322"))
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun test06_sensorFreshness_marksStaleUnsupportedAndNoConnection() {
        val now = 100_000L
        val freshReading = PidReading(1, 0x0C, 750.0, unit = "об/мин", rawHex = "0B B8", timestampMs = 97_000L)
        assertEquals(
            DiagnosticRepository.SensorFreshness.FRESH,
            DiagnosticRepository.evaluateSensorFreshness(freshReading, isConnected = true, isUnsupported = false, nowMs = now, staleThresholdMs = 6000L),
        )
        val staleReading = freshReading.copy(timestampMs = 90_000L)
        assertEquals(
            DiagnosticRepository.SensorFreshness.STALE,
            DiagnosticRepository.evaluateSensorFreshness(staleReading, isConnected = true, isUnsupported = false, nowMs = now, staleThresholdMs = 6000L),
        )
        assertEquals(
            DiagnosticRepository.SensorFreshness.NO_CONNECTION,
            DiagnosticRepository.evaluateSensorFreshness(freshReading, isConnected = false, isUnsupported = false, nowMs = now, staleThresholdMs = 6000L),
        )
        assertEquals(
            DiagnosticRepository.SensorFreshness.UNSUPPORTED,
            DiagnosticRepository.evaluateSensorFreshness(null, isConnected = true, isUnsupported = true, nowMs = now, staleThresholdMs = 6000L),
        )
        assertEquals(
            DiagnosticRepository.SensorFreshness.READ_ERROR,
            DiagnosticRepository.evaluateSensorFreshness(freshReading, isConnected = true, isUnsupported = false, nowMs = now, staleThresholdMs = 6000L, hasReadError = true),
        )
    }

    @Test
    fun test07_chartsSingleStream_receivesRpmAndCoolantFromQuickSnapshot() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            try {
                val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog())
                val t = ScriptedElmTransport { cmd -> baseHandler(cmd) }
                repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)

                val rpmPoints = repo.chartBuffer.samplesFor(0x0C, ChartTimeWindow.ALL)
                val coolantPoints = repo.chartBuffer.samplesFor(0x05, ChartTimeWindow.ALL)
                assertTrue("RPM (0x0C) must be fed to chartBuffer from single stream", rpmPoints.isNotEmpty())
                assertTrue("Coolant (0x05) must be fed to chartBuffer from single stream", coolantPoints.isNotEmpty())
                assertEquals(750.0, rpmPoints.last().value, 0.1)
                assertEquals(82.0, coolantPoints.last().value, 0.1)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun test08_mode09MultiEcu_separatesEngineAndTcmWithoutConcatenation() {
        // Artificial multi-ECU fixture simulating CAN 7E8 (Engine) + 7E9 (TCM)
        val lines = listOf(
            "7E8 10 13 49 04 01 30 36 41",
            "7E8 21 39 30 36 30 33 33 4B",
            "7E8 22 44 20 32 32 33 30 00",
            "7E9 10 13 49 04 01 30 39 47",
            "7E9 21 39 32 37 37 35 30 4A",
            "7E9 22 41 20 32 31 34 31 00",
        )
        val parsed = Mode09Parser.parseMultiEcuAscii(lines, "04")
        assertEquals(2, parsed.size)
        assertEquals("7E8", parsed[0].ecuHeader)
        assertEquals("06A906033KD 2230", parsed[0].value)
        assertEquals("7E9", parsed[1].ecuHeader)
        assertEquals("09G927750JA 2141", parsed[1].value)

        val joined = Mode09Parser.extractAscii(lines, "04")
        assertEquals("06A906033KD 2230 | 09G927750JA 2141", joined)
    }

    @Test
    fun test09_mode09VinValidation_acceptsValid17AndRejectsCorrupted() {
        assertTrue(Mode09Parser.isValidVin("TMBCA21Z0D2012345"))
        assertFalse(Mode09Parser.isValidVin("TMBCA21Z0D201234")) // 16 chars
        assertFalse(Mode09Parser.isValidVin("TMBCA21Z0Q2012345")) // contains forbidden 'Q'
        assertFalse(Mode09Parser.isValidVin("06A93KD223009G927750JA"))

        val validLines = listOf(
            "7E8 10 14 49 02 01 54 4D 42",
            "7E8 21 43 41 32 31 5A 30 44",
            "7E8 22 32 30 31 32 33 34 35",
        )
        assertEquals("TMBCA21Z0D2012345", Mode09Parser.extractVin(validLines))
    }

    @Test
    fun test10_bluetoothGrouping_separatesCurrentScanLastSavedAndOther() {
        val now = 500_000L
        val last = SavedAdapterInfo("AA:BB:CC:00:00:01", "IOS-Vlink", "BLE", lastConnectedAtMs = 400_000L)
        val savedOther = SavedAdapterInfo("AA:BB:CC:00:00:02", "Vgate iCar Pro", "BLE", lastConnectedAtMs = 300_000L)

        val adapters = buildList {
            // Current scan discovered OBD adapter
            add(
                TransportResolver.mergeAdapters(
                    null,
                    ble = BleDevice("AA:BB:CC:00:00:03", "KONNWEI OBDII", -61, ts = now),
                ),
            )
            // 24 unrelated paired devices from bonded list
            for (i in 10..33) {
                add(
                    TransportResolver.mergeAdapters(
                        null,
                        classic = ClassicDeviceInfo(
                            mac = "11:22:33:44:55:%02X".format(i),
                            name = "Phone/Headset #$i",
                            rssi = null,
                            deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC,
                            bondState = BluetoothDevice.BOND_BONDED,
                            deviceClass = 0x200404,
                            sdpUuids = emptyList(),
                            sppStatus = SppProfileStatus.UNCONFIRMED,
                            source = "bonded",
                            ts = now,
                        ),
                    ),
                )
            }
        }

        val groups = TransportResolver.groupBluetoothDevices(adapters, last, listOf(last, savedOther))
        assertEquals(1, groups.availableObdAdapters.size)
        assertEquals("KONNWEI OBDII", groups.availableObdAdapters.first().name)
        assertNotNull(groups.lastUsedAdapter)
        assertFalse(groups.lastUsedAdapter!!.isAvailableNow)
        assertNull(groups.lastUsedAdapter!!.rssi)
        assertEquals(1, groups.savedAdapters.size)
        assertEquals("Vgate iCar Pro", groups.savedAdapters.first().saved.name)
        assertEquals(24, groups.otherDevices.size)
    }

    @Test
    fun test11_classicTransport_noReflectionFallbackByDefaultAndUsesCancelDiscovery() {
        val methods = ClassicBluetoothTransport.defaultAttemptMethods(allowReflection = false)
        assertEquals(
            listOf(
                ClassicBluetoothTransport.RfcommAttemptMethod.SECURE_SDP,
                ClassicBluetoothTransport.RfcommAttemptMethod.INSECURE_SDP,
            ),
            methods,
        )
        assertFalse(ClassicBluetoothTransport.RfcommAttemptMethod.REFLECTION_CH1 in methods)

        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            var canceled = 0
            val fakeConnector = object : ClassicBluetoothTransport.ClassicSocketConnector {
                override suspend fun probeProfile(mac: String) = ClassicBluetoothScanner.SdpProbeResult(
                    status = SppProfileStatus.CONFIRMED,
                    uuids = listOf(TransportResolver.SPP_UUID_STRING),
                    deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC,
                    bondState = BluetoothDevice.BOND_BONDED,
                    fromCache = true,
                )

                override fun cancelDiscovery() {
                    canceled++
                }

                override fun connect(mac: String, method: ClassicBluetoothTransport.RfcommAttemptMethod): ClassicBluetoothTransport.ClassicSocketStream {
                    throw IOException("read failed, socket might closed or timeout, read ret: -1")
                }
            }
            val transport = ClassicBluetoothTransport(
                context = ctx,
                mac = "00:1D:A5:68:98:8A",
                deviceName = "Android-Vlink",
                log = RawLog(),
                reconnectAttempts = 0,
                connectorOverride = fakeConnector,
            )
            transport.connect()
            assertEquals(1, canceled)
            val failed = transport.state.value as TransportState.Failed
            assertTrue(failed.reason.contains("read ret: -1"))
            assertTrue(failed.reason.contains("IOS-Vlink"))
        }
    }

    @Test
    fun test12_lastAdapterPersistence_andNoAutoConnect() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val settings = SettingsRepository(ctx)
            settings.recordSuccessfulAdapter(
                mac = "aa:bb:cc:dd:ee:ff",
                name = "IOS-Vlink",
                transport = "BLE",
                verifiedMethod = "BLE GATT",
                connectedAtMs = 123456L,
            )
            val saved = settings.lastAdapterFlow.first()
            assertNotNull(saved)
            assertEquals("AA:BB:CC:DD:EE:FF", saved!!.mac)
            assertEquals("IOS-Vlink", saved.name)
            assertEquals("BLE", saved.transport)

            val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
            try {
                val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, settings, RawLog())
                assertTrue(repo.connection.value is DiagnosticRepository.ConnectionState.Idle)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun test13_dtcCredibility_noEnglishLikelihoodAndNoFakeConfirmedByDocs() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val lookupDb = ObdLookupDb(ctx)
            val res = lookupDb.lookupDtc("P0322")
            res.flatMap { it.causes }.forEach { cause ->
                assertFalse("Must not leak raw English likelihood: $cause", cause.contains("вероятность: high", ignoreCase = true))
                assertFalse("Must not leak raw English likelihood: $cause", cause.contains("вероятность: medium", ignoreCase = true))
                assertFalse("Must not leak raw English likelihood: $cause", cause.contains("вероятность: low", ignoreCase = true))
            }

            val searcher = PublicDtcOnlineSearchProvider(lookupDb, PersistentDtcSearchCache(ctx))
            val resp = searcher.search(DtcSearchRequest(code = "P0322"), userOptInEnabled = true)
            assertTrue(resp is DtcOnlineSearchResponse.Found)
            val card = (resp as DtcOnlineSearchResponse.Found).card
            card.possibleCauses.forEach { cause ->
                assertFalse(
                    "Local DB cause without external doc URL must not claim CONFIRMED_BY_DOCS",
                    cause.status == ConfidenceStatus.CONFIRMED_BY_DOCS,
                )
            }
        }
    }

    @Test
    fun test14_roomMigration5to6_preservesSessionsAndDtcs() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".")
        val dbFile = File(tmpDir, "v054_mig56_${System.currentTimeMillis()}.db")
        dbFile.delete()
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
                for (i in 1..6) {
                    sdb.execSQL(
                        "INSERT INTO sessions (id, startedAtMs, updatedAtMs, adapterName, demoMode, vehicleIdentitySource, " +
                            "storedCount, pendingCount, permanentCount, dtcReadCompleted, status, legacyUnverified) " +
                            "VALUES ($i, ${1000L * i}, ${1000L * i}, 'IOS-Vlink', 0, 'NONE', 2, 0, 0, 1, 'COMPLETED', 0)",
                    )
                }
                for (i in 1..16) {
                    val sid = ((i - 1) % 6) + 1
                    sdb.execSQL(
                        "INSERT INTO dtc_records (sessionId, code, mode, timestampMs) VALUES ($sid, 'P0322', 3, ${1000L * i})",
                    )
                }
                sdb.version = 5
            }

            val upgraded = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()
            try {
                runBlocking {
                    assertEquals(6, upgraded.sessionDao().recentSessions(20).size)
                    val totalDtcs = (1..6).sumOf { sid -> upgraded.dtcRecordDao().forSession(sid.toLong()).size }
                    assertEquals(16, totalDtcs)
                    upgraded.dtcScanRunDao().insert(
                        com.obd2.app.core.db.DtcScanRunEntity(
                            runId = "run_test",
                            sessionId = 1L,
                            timestampMs = 9999L,
                            status = "DTC_FOUND",
                            storedCodesJson = "[\"P0322\"]",
                            pendingCodesJson = "[]",
                            permanentCodesJson = "[]",
                            errorsJson = "[]",
                            auditJson = "[]",
                        ),
                    )
                    assertEquals(1, upgraded.dtcScanRunDao().forSession(1L).size)
                }
            } finally {
                upgraded.close()
            }
        } finally {
            dbFile.delete()
        }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp")
class HeadunitWideLayoutSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun test15_headunitWideLayout_andScrollableDrawerSmokeTest() {
        rule.onAllNodesWithText("Главная").onFirst().assertExists()
        rule.onAllNodesWithText("Подключение").onFirst().assertExists()
        rule.onNodeWithContentDescription("Меню").performClick()
        rule.onAllNodesWithText("Обновления приложения").onFirst().assertExists()
    }
}
