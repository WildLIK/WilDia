package com.obd2.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.db.VehicleIdentitySource
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.ConnectScreen
import com.obd2.app.ui.screens.HomeScreen
import com.obd2.app.ui.screens.SensorsScreen
import com.obd2.app.ui.screens.SettingsScreen
import com.obd2.app.ui.screens.VehicleScreen
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression suite verifying the universal clean UI & no-fake-data guarantees (v0.4.4):
 *  1. Clean install -> vehicle unknown (no Škoda, no BSE, no Simos 7.1).
 *  2. Bluetooth connected without ECU response -> vehicle stays unknown.
 *  3. VIN unavailable -> app does not invent a model.
 *  4. VIN read -> actual identifier is displayed.
 *  5. Another car connected -> Škoda data is never substituted.
 *  6. Demo mode hidden by default (ConnectScreen has no demo switch; Settings dev section off).
 *  7. Demo data never leaks into real sessions & clearDemoData() leaves real history intact.
 *  8. Real history survives DB upgrade & legacy phantom-DTC sessions are marked unverified.
 *  9. Manual car profile selection works.
 * 10. SensorsScreen is empty/clean until real ECU readings arrive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UniversalCleanUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ScriptedCarTransport(
        private val answers: Map<String, String?>,
        private val defaultReply: String? = "NO DATA",
    ) : ByteTransport {
        override val name = "SCRIPTED-UNIVERSAL"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming
        override suspend fun connect() { _state.value = TransportState.Connected("BLE") }
        override suspend fun disconnect() { _state.value = TransportState.Disconnected }
        override suspend fun write(data: ByteArray) {
            while (_incoming.subscriptionCount.value == 0) delay(5)
            val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ').uppercase()
            val reply = if (answers.containsKey(cmd)) answers[cmd] else defaultReply
            if (reply != null) _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
        }
        override fun isConnected() = _state.value is TransportState.Connected
    }

    @Test
    fun `1 and 6 and 10 - Clean install has unknown vehicle hidden demo mode and empty SensorsScreen`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)

        // 1. Clean install: no profiles, empty activeProfileId, sessionVehicle = unknown
        assertTrue(vm.profiles.value.isEmpty())
        assertEquals("", vm.activeProfileId.value)
        assertFalse(vm.sessionVehicle.value.identified)
        assertEquals("Автомобиль не определён", vm.sessionVehicle.value.displayTitle)
        assertFalse(vm.settingsFlow.value.devMode)

        composeRule.setContent {
            Obd2Theme { HomeScreen(vm, rememberNavController()) }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Автомобиль не определён", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("Škoda", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Octavia", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Simos", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `6a - ConnectScreen has no demo controls`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)

        composeRule.setContent {
            Obd2Theme { ConnectScreen(vm) }
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Демо-режим", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Подключить демо", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `6b - SettingsScreen hides demo and dev tools by default`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)
        runBlocking { vm.settings.setDevMode(false) }

        composeRule.setContent {
            Obd2Theme { SettingsScreen(vm) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Для разработчиков").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Подключить демо ELM327").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Очистить демонстрационные данные").fetchSemanticsNodes().size)
    }

    @Test
    fun `2 and 3 and 4 and 5 - Connection without ECU or VIN stays honest and another car VIN never uses Skoda`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        try {
            // 2. BLE + ELM327 ready, NO ECU response -> vehicle remains unknown
            val noEcu = ScriptedCarTransport(
                mapOf(
                    "ATZ" to "ELM327 v2.3", "ATE0" to "OK", "ATL0" to "OK",
                    "ATS1" to "OK", "ATH0" to "OK", "ATI" to "ELM327 v2.3",
                    "ATSP0" to "OK", "ATDP" to "AUTO", "ATDPN" to "A0", "ATRV" to "12.5V",
                ),
            )
            repo.connectTransportForTest(noEcu, "IOS-Vlink", transportType = BtTransportType.BLE)
            assertFalse(repo.pipelineStatus.value.ecuResponding)
            assertFalse(repo.sessionVehicle.value.identified)
            assertEquals("Автомобиль не определён", repo.sessionVehicle.value.displayTitle)

            // 3. ECU responds to 0100, but VIN (0902) and ECU Name (090A) return NO DATA -> does not invent model
            val noVinCar = ScriptedCarTransport(
                mapOf(
                    "ATZ" to "ELM327 v2.3", "ATE0" to "OK", "ATL0" to "OK",
                    "ATS1" to "OK", "ATH0" to "OK", "ATI" to "ELM327 v2.3",
                    "ATSP0" to "OK", "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)", "ATDPN" to "A6", "ATRV" to "12.6V",
                    "0100" to "41 00 98 18 80 00",
                    "010C" to "41 0C 0F A0",
                ),
            )
            repo.connectTransportForTest(noVinCar, "IOS-Vlink", transportType = BtTransportType.BLE)
            assertTrue(repo.pipelineStatus.value.ecuResponding)
            assertFalse(repo.sessionVehicle.value.identified)
            assertEquals("Автомобиль не определён", repo.sessionVehicle.value.displayTitle)

            // 4 & 5. Another car (Toyota: JT1...) returns VIN -> shows Toyota VIN and never Škoda!
            // "JT111111111111111" = 4A 54 31 ... (17 ASCII chars)
            val vinHex = "JT111111111111111".toByteArray(Charsets.US_ASCII).joinToString(" ") { "%02X".format(it) }
            val toyotaCar = ScriptedCarTransport(
                mapOf(
                    "ATZ" to "ELM327 v2.3", "ATE0" to "OK", "ATL0" to "OK",
                    "ATS1" to "OK", "ATH0" to "OK", "ATI" to "ELM327 v2.3",
                    "ATSP0" to "OK", "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)", "ATDPN" to "A6", "ATRV" to "13.8V",
                    "0100" to "41 00 98 18 80 00",
                    "0902" to "49 02 01 $vinHex",
                ),
            )
            repo.connectTransportForTest(toyotaCar, "IOS-Vlink", transportType = BtTransportType.BLE)
            assertTrue(repo.sessionVehicle.value.identified)
            assertTrue(repo.sessionVehicle.value.displayTitle.contains("Toyota"))
            assertFalse(repo.sessionVehicle.value.displayTitle.contains("Škoda"))
            assertEquals(VehicleIdentitySource.AUTO_FULL, repo.sessionVehicle.value.source)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `7 - Demo sessions are isolated and clearDemoData removes only DEMO without touching real history`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(ctx)
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, settings, RawLog(), scope)
        try {
            // Insert 1 real session + 1 demo session + 1 user profile + 1 test Skoda profile
            val realId = db.sessionDao().insertSession(
                SessionEntity(startedAtMs = 1000L, adapterName = "IOS-Vlink", demoMode = false, status = "COMPLETED"),
            )
            repo.connectDemo(settings.settings.first())
            repo.readDtcs(saveHistory = true)
            repo.disconnect()

            settings.addProfile(VehicleProfile(id = "user-lada", name = "Lada Vesta", maker = "Lada", model = "Vesta", identityStatus = VehicleProfile.ID_MANUAL))
            settings.addProfile(TestVehicleFixtures.skodaOctaviaA5Profile())

            assertEquals(2, db.sessionDao().recentSessions(10).size)

            val removedDemoSessions = repo.clearDemoData()
            assertEquals(1, removedDemoSessions)

            // Real session and user Lada profile MUST remain untouched!
            val remainingSessions = db.sessionDao().recentSessions(10)
            assertEquals(1, remainingSessions.size)
            assertEquals(realId, remainingSessions.first().id)

            val remainingProfiles = settings.profilesFlow.first()
            assertTrue("User Lada profile must remain", remainingProfiles.any { it.id == "user-lada" })
            assertFalse("Built-in test Skoda profile must be removed", remainingProfiles.any { it.builtIn })
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `8 - Database migration preserves real history and marks legacy phantom-DTC sessions unverified`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dbFile = File(ctx.cacheDir, "migration_v2_v3_${System.currentTimeMillis()}.db")
        dbFile.delete()
        try {
            // Create a v2 database manually with one legacy phantom-DTC session (rawDtcResponsesJson = NULL)
            // and one clean real session
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(ctx)
                    .name(dbFile.absolutePath)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(2) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE sessions (
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
                                        rawDtcResponsesJson TEXT
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE dtc_records (
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
                                    CREATE TABLE session_sensors (
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
                                db.execSQL(
                                    "INSERT INTO sessions (id, startedAtMs, updatedAtMs, endedAtMs, adapterName, demoMode, vehicleIdentitySource, storedCount, pendingCount, permanentCount, dtcReadCompleted, status, rawDtcResponsesJson) " +
                                        "VALUES (1, 1000, 2000, 3000, 'IOS-Vlink', 0, 'NONE', 1, 0, 0, 1, 'COMPLETED', NULL)",
                                )
                                db.execSQL(
                                    "INSERT INTO dtc_records (sessionId, code, mode, timestampMs) VALUES (1, 'C3F0A', 10, 2000)",
                                )
                                db.execSQL(
                                    "INSERT INTO sessions (id, startedAtMs, updatedAtMs, endedAtMs, adapterName, demoMode, vehicleIdentitySource, storedCount, pendingCount, permanentCount, dtcReadCompleted, status, rawDtcResponsesJson) " +
                                        "VALUES (2, 4000, 5000, 6000, 'IOS-Vlink', 0, 'AUTO_VIN', 0, 0, 0, 1, 'COMPLETED', '{\"03\":[]}')",
                                )
                            }

                            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                        },
                    )
                    .build(),
            )
            helper.writableDatabase.close()
            helper.close()

            val upgradedDb = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                .addMigrations(*AppDb.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            try {
                val marked = upgradedDb.sessionDao().markLegacyUnverifiedSessions()
                assertEquals(1, marked)

                val legacy = upgradedDb.sessionDao().getById(1)
                val clean = upgradedDb.sessionDao().getById(2)
                assertNotNull(legacy)
                assertNotNull(clean)
                assertTrue("Legacy phantom-DTC session must be flagged unverified", legacy!!.legacyUnverified)
                assertFalse("Verified v2+ session must not be flagged legacyUnverified", clean!!.legacyUnverified)
            } finally {
                upgradedDb.close()
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `9 and 10 - Manual profile selection works and SensorsScreen is empty before real data`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)

        runBlocking {
            vm.settings.addProfile(
                VehicleProfile(
                    id = "manual-car",
                    name = "Mazda 3",
                    maker = "Mazda",
                    model = "3",
                    year = "2016",
                    identityStatus = VehicleProfile.ID_MANUAL,
                ),
            )
            vm.settings.setActiveProfile("manual-car")
        }
        val deadline = System.currentTimeMillis() + 5000
        while (vm.activeProfileId.value != "manual-car" && System.currentTimeMillis() < deadline) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(40)
        }
        assertEquals("manual-car", vm.activeProfileId.value)

        var showVehicle by androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            Obd2Theme {
                if (showVehicle) VehicleScreen(vm) else SensorsScreen(vm)
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("Mazda 3").fetchSemanticsNodes().isNotEmpty())

        showVehicle = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Автомобиль не подключён — данные датчиков появятся после подключения и ответа ЭБУ", substring = true).assertExists()
    }
}
