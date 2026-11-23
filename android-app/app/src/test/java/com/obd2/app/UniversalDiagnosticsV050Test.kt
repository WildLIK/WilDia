package com.obd2.app

import android.app.Application
import androidx.datastore.preferences.core.edit
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
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.ClearDtcPrecheck
import com.obd2.app.core.obd.ClearDtcResult
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.StandardIdentifiers
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.core.universal.AdapterCapabilityProbe
import com.obd2.app.core.universal.CapabilityEvaluator
import com.obd2.app.core.universal.CapabilityStatus
import com.obd2.app.core.universal.CatalogUpdateResult
import com.obd2.app.core.universal.CompatibilityCatalogManager
import com.obd2.app.core.universal.EcuDiscovery
import com.obd2.app.core.universal.EcuStatus
import com.obd2.app.core.universal.ManufacturerModuleRegistry
import com.obd2.app.core.universal.OperationType
import com.obd2.app.core.universal.VagModule
import com.obd2.app.core.universal.VehicleIdentifier
import com.obd2.app.core.universal.VehicleIdentityStatus
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UniversalDiagnosticsV050Test {

    @Before
    fun resetSettings() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            val sr = SettingsRepository(ctx)
            sr.clearDemoProfiles()
            sr.setDevMode(false)
            sr.setActiveProfile("")
        }
    }

    private class ScriptedTransport(
        private val answers: MutableMap<String, String?>,
        private val defaultReply: String? = "NO DATA",
        private val dropOnCommand: String? = null,
    ) : ByteTransport {
        override val name = "SCRIPTED-V050"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming
        val sentCommands = ArrayList<String>()

        override suspend fun connect() { _state.value = TransportState.Connected("BLE") }
        override suspend fun disconnect() { _state.value = TransportState.Disconnected }
        override fun isConnected() = _state.value is TransportState.Connected

        override suspend fun write(data: ByteArray) {
            while (_incoming.subscriptionCount.value == 0) delay(5)
            val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ').uppercase()
            sentCommands += cmd
            if (dropOnCommand != null && cmd == dropOnCommand) {
                _state.value = TransportState.Failed("Обрыв связи на команде $cmd")
                return
            }
            val reply = if (answers.containsKey(cmd)) answers[cmd] else defaultReply
            if (reply != null) {
                _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
            }
        }
    }

    private fun baseCanAnswers(
        includeVin: Boolean = false,
        dropFreezeFrame: Boolean = false,
    ): MutableMap<String, String?> = mutableMapOf(
        "ATZ" to "ELM327 v2.3",
        "ATE0" to "OK",
        "ATL0" to "OK",
        "ATS1" to "OK",
        "ATH0" to "OK",
        "ATH1" to "OK",
        "ATI" to "ELM327 v2.3",
        "ATSP0" to "OK",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
        "ATDPN" to "A6",
        "ATRV" to "12.7V",
        "ATSH 7DF" to "OK",
        "ATCRA" to "OK",
        "0100" to "41 00 BE 1F A8 13",
        "0101" to "41 01 81 07 65 00",
        "0120" to "NO DATA",
        "0140" to "NO DATA",
        "0160" to "NO DATA",
        "010C" to "41 0C 0F A0",
        "0105" to "41 05 5A",
        "0104" to "41 04 33",
        "010D" to "41 0D 00",
        "03" to "43 01 01 71",
        "07" to "47 00",
        "0A" to "4A 00",
        "020200" to if (dropFreezeFrame) null else "42 02 00 01 71",
        "020000" to "42 00 00 18 00 00 00",
        "020400" to "42 04 00 40",
        "020500" to "42 05 00 5A",
        "0600" to "46 00 80 00 00 00",
        "0601" to "46 01 01 0A 01 00 00 50 02 00",
    ).apply {
        if (includeVin) {
            put("0900", "49 00 54 00 00 00")
            // 1FA6P8CF5H5000001 (Ford)
            put(
                "0902",
                "014\r0: 49 02 01 31 46 41\r1: 36 50 38 43 46 35 48\r2: 35 30 30 30 30 30 31",
            )
        } else {
            put("0900", "NO DATA")
            put("0902", "NO DATA")
            put("0904", "NO DATA")
            put("090A", "NO DATA")
        }
    }

    @Test
    fun `1 and 2 and 3 - Clean install has no default vehicle and Quick Diagnostics works for unknown brand without VIN`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        try {
            assertEquals("Автомобиль не определён", repo.sessionVehicle.value.displayTitle)
            val t = ScriptedTransport(baseCanAnswers(includeVin = false))
            repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)

            val quick = repo.runQuickDiagnostics()
            assertTrue(quick.completed)
            assertFalse(quick.incomplete)
            assertEquals(1, quick.dtcScan?.stored?.size)
            assertEquals("P0171", quick.dtcScan?.stored?.first()?.code)
            assertNotNull(quick.readiness)
            assertTrue(quick.readiness!!.milOn)
            assertEquals("P0171", quick.freezeFrame?.triggerDtc)
            assertTrue((quick.mode06?.results?.size ?: 0) >= 1)
            assertFalse(repo.sessionVehicle.value.identified)
            assertTrue(quick.sessionId > 0)
            val savedSession = db.sessionDao().getById(quick.sessionId)
            assertNotNull(savedSession?.freezeFrameJson)
            assertNotNull(savedSession?.readinessJson)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `4 and 6 - Partial identification with VIN only and unknown manufacturer`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val catalog = CompatibilityCatalogManager(ctx).load()

        // 4. Known WMI (Ford) without CalID/ECU Name -> AUTO_PARTIAL (VIN alone is not full trim proof)
        val partial = VehicleIdentifier.identify(
            identifiers = StandardIdentifiers(vin = "1FA6P8CF5H5000001"),
            protocolText = "ISO 15765-4",
            manualProfile = null,
            catalog = catalog,
        )
        assertEquals(VehicleIdentityStatus.AUTO_PARTIAL, partial.status)
        assertEquals("Ford", partial.manufacturer)
        assertTrue(partial.needsManualConfirmation)

        // 6. Unknown manufacturer WMI ("ZZZ...") -> AUTO_PARTIAL with null manufacturer, never defaults to Skoda
        val unknownMaker = VehicleIdentifier.identify(
            identifiers = StandardIdentifiers(vin = "ZZZ12345678901234"),
            protocolText = "ISO 15765-4",
            manualProfile = null,
            catalog = catalog,
        )
        assertEquals(VehicleIdentityStatus.AUTO_PARTIAL, unknownMaker.status)
        assertNull(unknownMaker.manufacturer)
        assertFalse(unknownMaker.summaryRu.contains("Škoda"))
    }

    @Test
    fun `5 - Multiple ECUs discovered safely via ATH1 and documented UDS targets`() = runBlocking {
        val answers = baseCanAnswers(includeVin = false).apply {
            put("0100", "7E8 06 41 00 BE 1F A8 13\r7E9 06 41 00 98 18 80 01")
            put("ATSH 710", "OK")
            put("ATCRA 77A", "OK")
            put("22F187", "62 F1 87 37 4E 30 39 30 37 35 33 30 52") // 7N0907530R
        }
        val t = ScriptedTransport(answers)
        t.connect()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val elm = Elm327Client(t, RawLog(), scope)
            elm.initialize()
            elm.refreshProtocolAndVoltage()
            val caps = AdapterCapabilityProbe.probe("IOS-Vlink", BtTransportType.BLE, false, elm.info.value, true)
            val ecus = EcuDiscovery.discover(elm, caps, VagModule.documentedEcus)
            assertTrue(ecus.any { it.address == "7E8" && it.status == EcuStatus.DETECTED_RESPONDING })
            assertTrue(ecus.any { it.address == "7E9" && it.status == EcuStatus.DETECTED_RESPONDING })
            val gateway = ecus.firstOrNull { it.address == "77A" }
            assertNotNull(gateway)
            assertEquals(EcuStatus.DETECTED_RESPONDING, gateway!!.status)
            assertTrue(gateway.partNumber?.contains("7N0907530R") == true)
            // Verify ATH0 restored after discovery
            assertTrue(t.sentCommands.last() == "ATSH 7DF" || "ATH0" in t.sentCommands)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `7 and 8 - Incompatible protocol and adapter mark capabilities ADAPTER_UNSUPPORTED`() {
        val nonCanInfo = Elm327Client.Elm327Info(
            identity = "ELM327 v1.5",
            protocol = "ISO 9141-2",
            protocolNumber = 3,
            isCanProtocol = false,
        )
        val caps = AdapterCapabilityProbe.probe("Generic-BT", BtTransportType.CLASSIC_SPP, false, nonCanInfo, true)
        assertFalse(caps.isCanProtocol)
        assertFalse(caps.supportsUdsReadOnly)
        assertFalse(caps.supportsVagTp20)

        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val catalog = CompatibilityCatalogManager(ctx).load()
        val vehicle = VehicleIdentifier.identify(
            identifiers = StandardIdentifiers(vin = "TMBCA21Z0C2000001", calIds = listOf("06A906033KD")),
            protocolText = "ISO 9141-2",
            manualProfile = null,
            catalog = catalog,
        )
        assertEquals(VehicleIdentityStatus.AUTO_FULL, vehicle.status)

        val evaluated = CapabilityEvaluator.evaluateAll(
            specs = ManufacturerModuleRegistry.combinedSpecsFor("VAG"),
            vehicle = vehicle,
            adapterCaps = caps,
            ecus = emptyList(),
            supportedObdPids = setOf(0x0C),
            hasFreezeFrameSupport = false,
            hasMode06Support = false,
            hasMode09Support = true,
        )
        val vagUds = evaluated.first { it.spec.functionId == "vag_uds_ident" }
        assertEquals(CapabilityStatus.ADAPTER_UNSUPPORTED, vagUds.status)
        assertFalse(vagUds.visibleToNormalUser)
        assertFalse(vagUds.executableInUi)
    }

    @Test
    fun `9 and 10 - Function in catalog not confirmed by vehicle is hidden from normal user`() {
        val canInfo = Elm327Client.Elm327Info(
            identity = "ELM327 v2.3",
            protocol = "ISO 15765-4 (CAN 11/500)",
            protocolNumber = 6,
            isCanProtocol = true,
        )
        val caps = AdapterCapabilityProbe.probe("IOS-Vlink", BtTransportType.BLE, false, canInfo, true)
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val catalog = CompatibilityCatalogManager(ctx).load()
        val vehicle = VehicleIdentifier.identify(
            identifiers = StandardIdentifiers(vin = "TMBCA21Z0C2000001", calIds = listOf("06A906033KD")),
            protocolText = canInfo.protocol,
            manualProfile = null,
            catalog = catalog,
        )
        // Only 7E8 detected, 77A Gateway NOT detected -> vag_uds_ident (if 7E8 not confirmed by UDS 62F187) or only 77E specs stay hidden
        val evaluated = CapabilityEvaluator.evaluateAll(
            specs = ManufacturerModuleRegistry.combinedSpecsFor("VAG"),
            vehicle = vehicle,
            adapterCaps = caps,
            ecus = emptyList(), // no ECU confirmed UDS
            supportedObdPids = setOf(0x01, 0x0C),
            hasFreezeFrameSupport = false,
            hasMode06Support = false,
            hasMode09Support = true,
        )
        val vagUds = evaluated.first { it.spec.functionId == "vag_uds_ident" }
        assertEquals(CapabilityStatus.UNKNOWN, vagUds.status)
        assertFalse(vagUds.visibleToNormalUser)
        assertFalse(vagUds.executableInUi)

        // Modifying & service functions are always disabled and hidden from normal user
        val modifySpecs = evaluated.filter { it.spec.operationType != OperationType.READ }
        assertTrue(modifySpecs.isNotEmpty())
        assertTrue(modifySpecs.none { it.visibleToNormalUser || it.executableInUi })
    }

    @Test
    fun `11 and 12 - Connection loss mid-scan preserves partial DTC results in Room`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        try {
            val t = ScriptedTransport(baseCanAnswers(includeVin = false), dropOnCommand = "07")
            repo.connectTransportForTest(t, deviceName = "IOS-Vlink", transportType = BtTransportType.BLE)

            val quick = repo.runQuickDiagnostics()
            assertTrue(quick.incomplete)
            assertEquals(listOf("P0171"), quick.dtcScan?.stored?.map { it.code })
            val session = db.sessionDao().getById(quick.sessionId)
            assertNotNull(session)
            assertEquals(SessionStatus.CONNECTION_LOST, session!!.status)
            assertEquals(1, session.storedCount)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `13 - Modifying Mode 04 clear blocked without confirmation and dry-run only in v050`() = runBlocking {
        val t = ScriptedTransport(baseCanAnswers())
        t.connect()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val elm = Elm327Client(t, RawLog(), scope)
            elm.initialize()
            val obd = ObdClient(elm)

            // Without confirmation -> Blocked
            val blocked = obd.clearDtcsGuarded(
                ClearDtcPrecheck(
                    dtcSaved = false,
                    freezeFrameSaved = false,
                    connectionStable = true,
                    userConfirmed = false,
                    warningsAcknowledged = false,
                ),
            )
            assertTrue(blocked is ClearDtcResult.Blocked)
            assertFalse("04" in t.sentCommands)

            // With all confirmations but default realTransmissionEnabled=false -> DryRunSimulated, 04 never sent
            val dryRun = obd.clearDtcsGuarded(
                ClearDtcPrecheck(
                    dtcSaved = true,
                    freezeFrameSaved = true,
                    connectionStable = true,
                    userConfirmed = true,
                    warningsAcknowledged = true,
                ),
            )
            assertTrue(dryRun is ClearDtcResult.DryRunSimulated)
            assertFalse("04" in t.sentCommands)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `14 and 15 - Room v3 to v4 migration preserves user history and offline catalog update verifies SHA256 and rolls back`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()

        // 14. Room v3 -> v4 migration preserves existing sessions and DTCs
        val dbFile = File(System.getProperty("java.io.tmpdir") ?: ".", "mig34_${System.currentTimeMillis()}.db")
        dbFile.delete()
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(ctx)
                    .name(dbFile.absolutePath)
                    .callback(object : SupportSQLiteOpenHelper.Callback(3) {
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
                                    legacyUnverified INTEGER NOT NULL DEFAULT 0
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
                            db.execSQL(
                                "INSERT INTO sessions (id, startedAtMs, updatedAtMs, adapterName, demoMode, vehicleIdentitySource, storedCount, pendingCount, permanentCount, dtcReadCompleted, status, legacyUnverified) VALUES (1, 1000, 1000, 'IOS-Vlink', 0, 'NONE', 1, 0, 0, 1, 'COMPLETED', 0)",
                            )
                            db.execSQL(
                                "INSERT INTO dtc_records (sessionId, code, mode, timestampMs) VALUES (1, 'P0420', 3, 1000)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build(),
            )
            helper.writableDatabase.close()

            val migratedDb = Room.databaseBuilder(ctx, AppDb::class.java, dbFile.absolutePath)
                .addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3, AppDb.MIGRATION_3_4, AppDb.MIGRATION_4_5, AppDb.MIGRATION_5_6)
                .allowMainThreadQueries()
                .build()
            try {
                val sessions = migratedDb.sessionDao().recentSessions(10)
                assertEquals(1, sessions.size)
                assertEquals("IOS-Vlink", sessions.first().adapterName)
                val dtcs = migratedDb.dtcRecordDao().forSession(1)
                assertEquals(listOf("P0420"), dtcs.map { it.code })
            } finally {
                migratedDb.close()
            }
        } finally {
            dbFile.delete()
        }

        // 15. Offline CompatibilityCatalog update integrity + rollback without internet
        val mgr = CompatibilityCatalogManager(ctx)
        val badUpdate = mgr.applyUpdate("""{"schemaVersion":1,"catalogVersion":"2099.1"}""", expectedSha256 = "0000")
        assertTrue(badUpdate is CatalogUpdateResult.Rejected)

        val validJson = """{"schemaVersion":1,"catalogVersion":"2026.12.0","vehicles":[],"wmiBrands":{}}"""
        val okHash = CompatibilityCatalogManager.sha256Hex(validJson)
        val okUpdate = mgr.applyUpdate(validJson, okHash)
        assertTrue(okUpdate is CatalogUpdateResult.Updated)
        assertEquals("2026.12.0", mgr.status().activeVersion)

        val rolledBack = mgr.rollback()
        assertEquals("2026.11.1", rolledBack.activeVersion)
    }
}
