package com.obd2.app

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.db.VehicleIdentitySource
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.report.SessionReportExporter
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.DiagnosticsScreen
import com.obd2.app.ui.screens.HomeScreen
import com.obd2.app.ui.theme.Obd2Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Comprehensive regression tests covering all 10 scenarios required by the
 * reliability, autosave, vehicle identity, connection loss, compact UI, and
 * offline report export rework (v0.4.3).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionAutosaveAndLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class MutableScriptedTransport(
        val answers: MutableMap<String, String?>,
        var defaultReply: String? = "NO DATA",
        var dropConnectionOnCommand: String? = null,
    ) : ByteTransport {
        override val name = "SCRIPTED-LIFECYCLE"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming

        override suspend fun connect() {
            _state.value = TransportState.Connected("scripted")
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        fun simulateUnexpectedDisconnect(reason: String = "GATT link supervision timeout") {
            _state.value = TransportState.Failed(reason)
        }

        override suspend fun write(data: ByteArray) {
            while (_incoming.subscriptionCount.value == 0) delay(5)
            val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ').uppercase()
            if (dropConnectionOnCommand != null && cmd == dropConnectionOnCommand) {
                _state.value = TransportState.Failed("Соединение разорвано во время команды $cmd")
                return
            }
            val reply = if (answers.containsKey(cmd)) answers[cmd] else defaultReply
            if (reply == null) return // simulate timeout
            _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected
    }

    private fun baseCarAnswers(): MutableMap<String, String?> = mutableMapOf(
        "ATZ" to "ELM327 v2.3",
        "ATE0" to "OK",
        "ATL0" to "OK",
        "ATS1" to "OK",
        "ATH0" to "OK",
        "ATI" to "ELM327 v2.3",
        "ATSP0" to "OK",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
        "ATDPN" to "A6",
        "ATRV" to "12.6V",
        "0100" to "41 00 BE 1F A8 13",
        "0120" to "NO DATA",
        "0140" to "NO DATA",
        "0160" to "NO DATA",
        "010C" to "41 0C 0F A0", // 1000 rpm
        "010D" to "41 0D 00",    // 0 km/h
        "0105" to "41 05 5A",    // 50 °C
        "0104" to "41 04 33",    // 20 %
        "0142" to "NO DATA",
        "03" to "43 02 01 71 04 20", // P0171, P0420
        "07" to "47 01 03 00",       // P0300
        "0A" to "7F 0A 11",          // unsupported
    )

    // -------------------------------------------------------------------------
    // Scenarios 1 & 2: Disconnected vehicle = "Автомобиль не определён",
    // Manual Škoda profile is shown separately and never passed off as auto-ID
    // -------------------------------------------------------------------------
    @Test
    fun `1 and 2 - HomeScreen shows Автомобиль не определён and separates manual Skoda profile`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)
        runBlocking { vm.repo.disconnect() }

        assertFalse(vm.sessionVehicle.value.identified)
        assertEquals("Автомобиль не определён", vm.sessionVehicle.value.displayTitle)

        composeRule.setContent {
            Obd2Theme {
                HomeScreen(vm, rememberNavController())
            }
        }
        composeRule.waitForIdle()

        // Current session vehicle must explicitly state "Автомобиль не определён"
        assertTrue(
            composeRule.onAllNodesWithText("Автомобиль не определён", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // Clean install must NOT show Škoda or auto-identification
        assertEquals(0, composeRule.onAllNodesWithText("Škoda", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Simos", substring = true).fetchSemanticsNodes().size)

        // When user explicitly loads a manual Škoda profile, it is shown separately as "Выбран вручную"
        runBlocking {
            val skoda = TestVehicleFixtures.skodaOctaviaA5Profile()
            vm.settings.addProfile(skoda)
            vm.settings.setActiveProfile(skoda.id)
        }
        val deadline = System.currentTimeMillis() + 5000
        while (vm.activeProfileId.value != TestVehicleFixtures.skodaOctaviaA5Profile().id && System.currentTimeMillis() < deadline) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(40)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Выбран вручную").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("выбран ручной профиль", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        runBlocking { vm.settings.clearDemoProfiles() }
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Immediate incremental DTC autosave (Mode 03 saved even if Mode 07 drops)
    // -------------------------------------------------------------------------
    @Test
    fun `3 - Mode 03 DTCs are saved immediately before Mode 07 finishes or when connection drops mid-scan`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        try {
            val answers = baseCarAnswers()
            // Drop connection right when Mode 07 is requested!
            val t = MutableScriptedTransport(answers, dropConnectionOnCommand = "07")
            repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)

            val scanRes = repo.readDtcs(saveHistory = true)
            assertTrue("Scan must be marked incomplete when connection drops on Mode 07", scanRes.incomplete)
            assertEquals(listOf("P0171", "P0420"), scanRes.stored.map { it.code })
            assertTrue(repo.currentDtcState.value is DiagnosticRepository.CurrentDtcState.Incomplete)

            // Crucially: Mode 03 results MUST ALREADY be saved in Room despite Mode 07 failure!
            val sessions = db.sessionDao().recentSessions(5)
            assertEquals(1, sessions.size)
            val savedSession = sessions.first()
            assertEquals(2, savedSession.storedCount)
            assertTrue(savedSession.dtcReadCompleted)
            assertEquals(SessionStatus.CONNECTION_LOST, savedSession.status)

            val savedRecords = db.dtcRecordDao().forSession(savedSession.id)
            assertEquals(listOf("P0171", "P0420"), savedRecords.map { it.code })
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // Scenarios 4 & 5: Engine turned off (ECU stops responding) and Walk-away
    // (adapter stops responding / GATT disconnects) update status & stay responsive
    // -------------------------------------------------------------------------
    @Test
    fun `4 and 5 - Engine off and adapter disconnect update status accurately without freezing`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        try {
            val answers = baseCarAnswers()
            val t = MutableScriptedTransport(answers)
            repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)
            assertTrue(repo.pipelineStatus.value.ecuResponding)
            assertEquals(DiagnosticRepository.ConnectionStage.DATA_RECEIVED, repo.pipelineStatus.value.highestStage)

            // Step A: User turns off ignition (ELM327 still answers "NO DATA", ECU is off)
            answers["010C"] = "NO DATA"
            answers["010D"] = "NO DATA"
            answers["0105"] = "NO DATA"
            answers["0104"] = "NO DATA"
            answers["0100"] = "NO DATA"

            repo.refreshQuickSnapshot()

            // Must detect ECU loss, keep ELM ready, and MUST NOT blacklist 0x0C as unsupported!
            assertFalse("ecuResponding must become false when ECU stops answering", repo.pipelineStatus.value.ecuResponding)
            assertTrue("elmResponding must remain true while adapter still answers NO DATA", repo.pipelineStatus.value.elmResponding)
            assertFalse("Supported PID 0x0C must not be blacklisted on engine-off NO DATA", 0x0C in repo.unsupportedPids.value)

            // Step B: User walks away -> transport drops during command; app must not hang!
            t.simulateUnexpectedDisconnect("Потеря сигнала BLE (вышли из зоны действия)")
            withTimeout(2000) {
                val deadline = System.currentTimeMillis() + 1500
                while (repo.connection.value !is DiagnosticRepository.ConnectionState.Failed &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(20)
                }
                assertTrue(repo.connection.value is DiagnosticRepository.ConnectionState.Failed)
                assertFalse(repo.pipelineStatus.value.bleConnected)
                // Verify repository calls remain fast and non-blocking after connection loss
                val pidAfterLoss = repo.readPid(0x0C)
                assertTrue(pidAfterLoss is com.obd2.app.core.obd.PidResult.Error)
                repo.disconnect()
            }
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Forced process termination (abrupt kill without disconnect/onStop)
    // preserves all saved DTCs and sensors on next startup and marks session INTERRUPTED
    // -------------------------------------------------------------------------
    @Test
    fun `6 - Abrupt process kill preserves saved DTCs and sensors and marks session INTERRUPTED on restart`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "fk_${System.currentTimeMillis()}.db")
        dbFile.delete()

        var crashedSessionId: Long

        // --- PROCESS 1: Active diagnostic session reads sensors & DTCs, then is KILLED abruptly ---
        run {
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val db1 = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()
            val repo1 = DiagnosticRepository(ctx, ObdLookupDb(ctx), db1, SettingsRepository(ctx), RawLog(), scope1)
            val t1 = MutableScriptedTransport(baseCarAnswers())

            repo1.connectTransportForTest(t1, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)
            crashedSessionId = repo1.currentSessionIdFlow.value
            assertTrue(
                "Active session ID must be > 0 (id=$crashedSessionId, conn=${repo1.connection.value}, dbSessions=${db1.sessionDao().recentSessions(10).size}, errLogs=${repo1.log.snapshot().filter { it.dir == RawLog.Dir.ERROR }.map { it.text }}, registrySize=${repo1.pidRegistry.value.size})",
                crashedSessionId > 0
            )

            val dtcRes = repo1.readDtcs(saveHistory = true)
            assertEquals(2, dtcRes.stored.size)
            assertEquals(1, dtcRes.pending.size)

            // Verify session is currently ACTIVE in DB (endedAtMs == null)
            val activeInDb = db1.sessionDao().getById(crashedSessionId)
            assertNotNull(activeInDb)
            assertEquals(SessionStatus.ACTIVE, activeInDb!!.status)

            // FORCE KILL simulation: cancel coroutine scope and close SQLite without calling repo1.disconnect()!
            scope1.cancel()
            db1.close()
        }

        // --- PROCESS 2: Cold restart on the same SQLite file ---
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db2 = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
            .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val repo2 = DiagnosticRepository(ctx, ObdLookupDb(ctx), db2, SettingsRepository(ctx), RawLog(), scope2)
        try {
            // NOTE: repo2's init block already launches recoverOrphanSessions()
            // (mirrors real cold start), so an explicit call here would race and
            // may report 0 even though recovery is in progress/happened. Await the
            // observable outcome: the orphan must become INTERRUPTED.
            withTimeout(5000) {
                while (repo2.loadSavedSessionDetails(crashedSessionId)?.session?.status != SessionStatus.INTERRUPTED) {
                    delay(50)
                }
            }

            val details = repo2.loadSavedSessionDetails(crashedSessionId)
            assertNotNull("Saved session details must survive forced process kill", details)
            assertEquals(SessionStatus.INTERRUPTED, details!!.session.status)
            assertTrue(details.session.dtcReadCompleted)
            assertEquals(listOf("P0171", "P0420"), details.stored.map { it.code })
            assertEquals(listOf("P0300"), details.pending.map { it.code })
            assertTrue("Saved live sensor readings must survive process kill", details.sensors.any { it.pid == 0x0C })
        } finally {
            scope2.cancel()
            db2.close()
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }

    // -------------------------------------------------------------------------
    // Scenarios 7, 8, 9, 10: New session doesn't show old DTCs as current;
    // Offline PDF/ZIP/Text export & Sharesheet work without adapter and mask VIN/MAC;
    // Demo vs Real separation; Compact DTC cards & saved session viewer in DiagnosticsScreen
    // -------------------------------------------------------------------------
    @Test
    fun `7 8 9 10 - New session resets current DTC state offline PDF ZIP export works and compact UI renders`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)
        val exporter = SessionReportExporter(ctx)

        try {
            // 1. Run a real session with VIN + DTCs
            val answers = baseCarAnswers().apply {
                put("0902", "49 02 01 54 4D 42\r49 02 02 4A 46 32 31\r49 02 03 5A 32 43 32\r49 02 04 31 35 34 39\r49 02 05 39 39")
            }
            val t = MutableScriptedTransport(answers)
            runBlocking {
                vm.repo.connectTransportForTest(t, deviceName = "IOS-Vlink D2:E0:2F:8D:56:CA", transportType = BtTransportType.BLE)
                vm.repo.readVin(masked = false)
                vm.repo.readDtcs(saveHistory = true)
            }
            val firstSessionId = vm.currentSessionIdFlow.value
            assertTrue(firstSessionId > 0)
            assertTrue(vm.currentDtcState.value is DiagnosticRepository.CurrentDtcState.Completed)

            // 2. Disconnect completely (no adapter connected) -> test offline export (Scenario 8)
            runBlocking { vm.repo.disconnect() }

            val savedDetails = runBlocking { vm.repo.loadSavedSessionDetails(firstSessionId) }
            assertNotNull(savedDetails)
            assertFalse(savedDetails!!.session.demoMode)
            assertEquals(VehicleIdentitySource.AUTO_VIN, savedDetails.session.vehicleIdentitySource)

            // Export TXT, PDF, ZIP with default privacy (includeIdentifiers = false)
            val txtFile = exporter.saveTextReport(savedDetails, includeIdentifiers = false)
            val pdfFile = exporter.exportPdf(savedDetails, includeIdentifiers = false)
            val zipFile = exporter.exportZip(savedDetails, includeIdentifiers = false)

            assertTrue(txtFile.exists() && txtFile.length() > 100)
            val txtContent = txtFile.readText()
            assertTrue(txtContent.contains("P0171"))
            assertTrue(txtContent.contains("P0420"))
            // MAC D2:E0:2F:8D:56:CA must be masked by default
            assertFalse("Full MAC must be masked by default", txtContent.contains("D2:E0:2F:8D:56:CA"))
            assertTrue(txtContent.contains("D2:E0:**:**:**:**"))

            // PDF must have valid %PDF- header
            assertTrue(pdfFile.exists() && pdfFile.length() > 200)
            val pdfHeader = pdfFile.inputStream().use { inp ->
                val b = ByteArray(5)
                inp.read(b)
                String(b, Charsets.US_ASCII)
            }
            assertEquals("%PDF-", pdfHeader)

            // ZIP must contain all technical & human files
            ZipFile(zipFile).use { zip ->
                assertNotNull(zip.getEntry("diagnostic_report.md"))
                assertNotNull(zip.getEntry("session_meta.json"))
                assertNotNull(zip.getEntry("dtc_records.json"))
                assertNotNull(zip.getEntry("sensor_readings.json"))
            }

            // Sharesheet intent via FileProvider works offline
            val shareIntent = exporter.createShareIntent(pdfFile, "application/pdf")
            assertNotNull(shareIntent)

            // 3. Start a new connection without reading DTCs -> must NOT show old errors as current (Scenario 7)
            val t2 = MutableScriptedTransport(baseCarAnswers())
            runBlocking {
                vm.repo.connectTransportForTest(t2, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)
            }
            assertTrue(
                "New session must start at NotReadYet, never showing previous session DTCs as current",
                vm.currentDtcState.value is DiagnosticRepository.CurrentDtcState.NotReadYet,
            )

            // 4. Render DiagnosticsScreen: verify "ДАННЫЕ ЕЩЁ НЕ СЧИТАНЫ", then read DTCs and verify compact cards (Scenario 10)
            composeRule.setContent {
                Obd2Theme {
                    DiagnosticsScreen(vm)
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("ДАННЫЕ ЕЩЁ НЕ СЧИТАНЫ").assertExists()
            composeRule.onNodeWithText("Последний сохранённый сеанс").assertExists()

            // Trigger DTC read in current session
            runBlocking { vm.repo.readDtcs(saveHistory = true) }
            composeRule.waitForIdle()

            // Compact card shows P0171 and "Подробнее", while detailed "Возможные причины:" is hidden until expanded
            composeRule.onAllNodesWithText("P0171").onFirst().assertExists()
            assertEquals(
                "Causes must be collapsed in compact card view",
                0,
                composeRule.onAllNodesWithText("Возможные причины:").fetchSemanticsNodes().size,
            )
            composeRule.onAllNodesWithText("Подробнее").onFirst().performScrollTo().performClick()
            composeRule.waitForIdle()
            assertTrue(
                "Clicking Подробнее must expand full details",
                composeRule.onAllNodesWithText("Источник описания:", substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        } finally {
            runBlocking { vm.repo.disconnect() }
        }
    }
}
