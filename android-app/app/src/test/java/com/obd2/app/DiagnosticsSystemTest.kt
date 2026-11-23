package com.obd2.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.ble.BleDevice
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.diag.DiagCategory
import com.obd2.app.core.diag.DiagJournal
import com.obd2.app.core.diag.DiagRunner
import com.obd2.app.core.diag.StageState
import com.obd2.app.core.diag.DiagSeverity
import com.obd2.app.core.diag.DiagEvent
import com.obd2.app.core.diag.DiagExporter
import com.obd2.app.core.diag.DiagSession
import com.obd2.app.core.elm327.DiagExchangeRecorder
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Regression tests for the technical connection diagnostics (§15).
 *
 * The critical scenario: **IOS-Vlink discovered → GATT connect fails → user
 * exports the ZIP → the archive contains every event needed to determine the
 * cause without visiting the car again.**
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DiagnosticsSystemTest {

    private val VLINK_MAC = "AA:BB:CC:DD:EE:FF"

    /** Scanner that sees exactly one adapter: IOS-Vlink, with advertising data. */
    private class VlinkScanner : BleScanner(ApplicationProvider.getApplicationContext()) {
        var sightings = 3
        override fun missingPermissions(): List<String> = emptyList()
        override fun isBluetoothEnabled(): Boolean = true
        override fun scan(nameFilter: String?, scanSeconds: Int) = flow {
            repeat(sightings) { i ->
                emit(
                    BleDevice(
                        mac = "AA:BB:CC:DD:EE:FF",
                        name = "IOS-Vlink",
                        rssi = -60 - i,
                        serviceUuids = listOf("0000fff0-0000-1000-8000-00805f9b34fb"),
                        manufacturerDataHex = "0x004D:0102",
                        advFlags = 0x06,
                        connectable = true,
                    )
                )
                kotlinx.coroutines.delay(20)
            }
        }
    }

    /** Transport whose connect always fails like a real 133. */
    private class FailingTransport : ByteTransport {
        override val name = "FAKE-BLE"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        override val incoming: SharedFlow<ByteArray> = MutableSharedFlow()
        override suspend fun connect() {
            kotlinx.coroutines.delay(50)
            _state.value = TransportState.Failed("Ошибка BLE (status=133)")
        }
        override suspend fun disconnect() { _state.value = TransportState.Disconnected }
        override suspend fun write(data: ByteArray) {}
        override fun isConnected() = false
    }

    /** Scripted transport for ELM tests: answers chunk-by-chunk. */
    private class ChunkedElmTransport(private val chunksPerCmd: Map<String, List<String>>) : ByteTransport {
        override val name = "FAKE-ELM"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val lastWriteUuid: String? = "0000fff2-0000-1000-8000-00805f9b34fb"
        var silent = false
        override suspend fun connect() { _state.value = TransportState.Connected("fake") }
        override suspend fun disconnect() { _state.value = TransportState.Disconnected }
        override suspend fun write(data: ByteArray) {
            if (silent) return
            // wait for the ELM collector to subscribe, so no chunk is dropped
            while (incoming.subscriptionCount.value == 0) kotlinx.coroutines.delay(10)
            val cmd = String(data, Charsets.US_ASCII).trim('\r')
            chunksPerCmd[cmd]?.forEach { incoming.emit(it.toByteArray()) }
        }
        override fun isConnected() = _state.value is TransportState.Connected
    }

    private lateinit var vm: ObdViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        vm = ObdViewModel(app)
    }

    @After
    fun drainDbCoroutines() {
        // Let in-flight Room queries (container init recovery, etc.) finish
        // before Robolectric swaps the environment; otherwise a query running
        // on the shared arch_disk_io pool crosses into the next test
        // environment and fails with "Illegal connection pointer", poisoning
        // the next test class.
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
    }

    // ------------------------------------------------------------------
    // DiagJournal: ring window, spill, restart restore, snapshots, crash
    // ------------------------------------------------------------------

    @Test
    fun journalKeepsBoundedRingAndSpillsToDisk() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "jtest1")
        dir.deleteRecursively()
        val journal = DiagJournal(dir, maxEvents = 100)
        repeat(150) {
            journal.add(DiagEvent(System.currentTimeMillis(), DiagCategory.SCAN, "ev$it"))
        }
        assertTrue("in-memory ring must be bounded", journal.allInMemory().size <= 100)
        assertTrue("spill file must exist", journal.spillFile().length() > 0)
        journal.close()
        dir.deleteRecursively()
    }

    @Test
    fun journalSurvivesAppRestart() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "jtest2")
        dir.deleteRecursively()
        val first = DiagJournal(dir)
        first.add(DiagEvent(System.currentTimeMillis(), DiagCategory.GATT, "restart.marker"))
        first.close()

        val second = DiagJournal(dir)
        val restored = second.allInMemory()
        assertTrue(
            "events must be restored after restart",
            restored.any { ev -> ev.op == "restart.marker" },
        )
        second.close()
        dir.deleteRecursively()
    }

    @Test
    fun errorSnapshotIsCreatedAndNeverOverwritten() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "jtest3")
        dir.deleteRecursively()
        val journal = DiagJournal(dir)
        journal.add(DiagEvent(System.currentTimeMillis(), DiagCategory.GATT, "err1",
            severity = DiagSeverity.ERROR))
        journal.add(DiagEvent(System.currentTimeMillis(), DiagCategory.GATT, "err2",
            severity = DiagSeverity.ERROR))
        val snaps = journal.snapshotsDir.listFiles { f -> f.name.startsWith("error_") } ?: emptyArray<java.io.File>()
        assertTrue("one snapshot per error event expected", snaps.size >= 2)
        val names = snaps.map { it.name }
        assertEquals("snapshot names must be unique (never overwritten)", names.size, names.toSet().size)
        journal.close()
        dir.deleteRecursively()
    }

    @Test
    fun crashPersistsAndSurvivesRestart() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "jtest4")
        dir.deleteRecursively()
        val journal = DiagJournal(dir)
        journal.add(DiagEvent(System.currentTimeMillis(), DiagCategory.GATT, "before.crash"))
        journal.persistCrash("main", "java.lang.RuntimeException: boom")
        val crash = journal.readLastCrashJsonl()
        assertNotNull(crash)
        assertTrue(crash!!.readText().contains("boom"))
        journal.close()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Masking (§13)
    // ------------------------------------------------------------------

    @Test
    fun macMaskingKeepsVendorPrefix() {
        assertEquals("AA:BB:**:**:**:**", DiagExporter.maskMac("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun exportMasksMacsByDefaultAndShowsThemWithToggle() = runBlocking {
        val session = vm.diagSession
        session.startSession("mask-test")
        session.scanSighting(
            com.obd2.app.core.diag.ScanSighting(
                System.currentTimeMillis(), VLINK_MAC, "IOS-Vlink", -55,
                listOf("0000fff0-0000-1000-8000-00805f9b34fb"), "0x004D:0102", emptyMap(), 6, true,
            )
        )
        session.scanFinished("mask test")

        val exporter = DiagExporter(ApplicationProvider.getApplicationContext(), session)
        exporter.rawLogSource = { vm.container.log.snapshot() }

        val maskedZip = exporter.export(maskIds = true)
        val maskedNames = zipNames(maskedZip)
        val bleScan = zipEntry(maskedZip, "ble_scan.json")
        assertTrue("masked MAC expected", bleScan.contains("AA:BB:**:**:**:**"))
        assertFalse("raw MAC must not leak by default", bleScan.contains(VLINK_MAC))

        val rawZip = exporter.export(maskIds = false)
        assertTrue(zipEntry(rawZip, "ble_scan.json").contains(VLINK_MAC))
        assertTrue(maskedNames.contains("device_info.json"))
        session.endSession("mask test done")
    }

    // ------------------------------------------------------------------
    // ELM327 raw exchange (§7): split BLE chunks, timeouts, HEX/ASCII
    // ------------------------------------------------------------------

    @Test
    fun elmSplitChunksAreRecordedAndMerged() = runBlocking {
        val session = vm.diagSession
        val log = RawLog()
        val recorder = DiagExchangeRecorder(session)
        val transport = ChunkedElmTransport(
            mapOf("0100" to listOf("41 00 ", "BE 3F B8 13 ", ">\r\n"))
        )
        val elm = Elm327Client(transport, log, exchangeRecorder = recorder)
        transport.connect()
        val resp = elm.command("0100", timeoutMs = 3000, allowRetries = false)

        assertTrue("merged response expected", resp is com.obd2.app.core.elm327.ElmResponse.Ok)
        val rec = session.elmCommands.lastOrNull { r -> r.cmd == "0100" }
        assertNotNull("exchange record expected", rec)
        rec!!
        assertEquals("three BLE fragments must be counted", 3, rec.fragments)
        assertTrue("fragments merged into prompt", rec.promptSeen)
        assertNotNull("first-byte latency expected", rec.firstByteMs)
        // HEX is the byte view of the captured ASCII text ("B"=0x42, "E"=0x45…)
        assertTrue(rec.responseHex.contains("42 45 20 33 46 20 42 38 20 31 33"))
        assertTrue(rec.responseAscii.contains("BE 3F B8 13"))
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", rec.writeUuid)
    }

    @Test
    fun elmTimeoutMarksMissingTerminator() = runBlocking {
        val session = vm.diagSession
        val log = RawLog()
        val recorder = DiagExchangeRecorder(session)
        val transport = ChunkedElmTransport(emptyMap()).apply { silent = true }
        val elm = Elm327Client(transport, log, exchangeRecorder = recorder)
        transport.connect()
        val resp = elm.command("0100", timeoutMs = 400, allowRetries = false)
        assertTrue(resp is com.obd2.app.core.elm327.ElmResponse.Timeout)
        val rec = session.elmCommands.last { r -> r.cmd == "0100" }
        assertTrue("timeout must flag missing terminator", rec.terminatorMissing)
        assertEquals("TIMEOUT", rec.resultCode)
        assertNotNull(rec.errorKind)
    }

    // ------------------------------------------------------------------
    // The critical scenario (§15): IOS-Vlink found → connect failed →
    // export ZIP → all evidence present
    // ------------------------------------------------------------------

    @Test
    fun vlinkFoundConnectFailsZipContainsFullEvidence() = runBlocking {
        val runner = DiagRunner(
            context = ApplicationProvider.getApplicationContext(),
            session = vm.diagSession,
            repo = vm.repo,
            log = vm.container.log,
            scanner = VlinkScanner(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            transportFactory = { _, _ -> FailingTransport() },
        )
        runner.run(DiagRunner.Scenario.B_CONNECT)

        val st = runner.state.value
        assertFalse(st.running)
        val connectStage = st.stages.firstOrNull { it.id == "connect" }
        assertNotNull(connectStage)
        assertEquals(StageState.FAIL, connectStage!!.state)
        assertTrue(connectStage.errors.first().contains("GATT-подключение не удалось"))

        val session = vm.diagSession
        assertEquals(1, session.stats.attempts)
        assertTrue(session.stats.failures >= 1)
        assertTrue("Vlink must be recorded as seen", session.scanInfo.vlinkSeen)
        assertTrue(session.scanSummaries().any { d -> d.name == "IOS-Vlink" })

        val analyzer = com.obd2.app.core.diag.DiagAnalyzer()
        val conclusions = analyzer.analyze(session, st.stages)
        assertTrue(
            "confirmed GATT failure conclusion expected",
            conclusions.any { c -> c.confirmed && c.text.contains("GATT-подключение завершилось ошибкой") },
        )
        updateStateConclusions(runner, conclusions)

        val exporter = DiagExporter(ApplicationProvider.getApplicationContext(), session)
        exporter.rawLogSource = { vm.container.log.snapshot() }
        val zip = exporter.export(maskIds = true)

        val names = zipNames(zip)
        val expected = listOf(
            "device_info.json", "permissions.json", "ble_scan.json", "ble_advertisements.json",
            "ble_connection.json", "gatt_services.json", "gatt_events.json", "elm327_commands.json",
            "obd_responses.json", "supported_pids.json", "connection_statistics.json", "errors.json",
            "timeline.json", "raw_ble.log", "raw_elm327.log", "crash_report.txt", "diagnostic_summary.md",
        )
        val missing = expected.filter { it !in names }
        assertTrue("missing files in ZIP: $missing", missing.isEmpty())

        // every file must carry real recorded evidence or an explicit reason
        assertTrue(zipEntry(zip, "ble_scan.json").contains("IOS-Vlink"))
        assertTrue(zipEntry(zip, "ble_advertisements.json").contains("0x004D"))
        assertTrue(zipEntry(zip, "ble_connection.json").contains("\"attempts\": 1"))
        assertTrue(zipEntry(zip, "gatt_services.json").contains("note"))
        assertTrue(zipEntry(zip, "errors.json").contains("connect.fail"))
        assertTrue(zipEntry(zip, "connection_statistics.json").contains("packet_loss"))
        assertTrue(zipEntry(zip, "diagnostic_summary.md").contains("GATT-подключение завершилось ошибкой"))
        assertTrue(zipEntry(zip, "timeline.json").contains("GATT"))
    }

    @Test
    fun exportWorksEvenWithoutAnyBleActivity() = runBlocking {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "emptydiag")
        dir.deleteRecursively()
        val session = DiagSession(dir, RawLog())
        val exporter = DiagExporter(ApplicationProvider.getApplicationContext(), session)
        val zip = exporter.export(maskIds = true)
        val names = zipNames(zip)
        assertTrue("device_info.json must always be present", "device_info.json" in names)
        assertTrue(zipEntry(zip, "ble_scan.json").contains("note"))
        assertTrue(zipEntry(zip, "gatt_services.json").contains("note"))
        assertTrue(zipEntry(zip, "elm327_commands.json").contains("note"))
        session.close()
        dir.deleteRecursively()
        Unit
    }

    // ------------------------------------------------------------------
    // Analyzer conclusions (§12)
    // ------------------------------------------------------------------

    @Test
    fun analyzerSeparatesConfirmedFromHypothesis() {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "analyzer")
        dir.deleteRecursively()
        val session = DiagSession(dir, RawLog())

        // silent ELM327 with a live connection
        session.connectAttemptStarted("IOS-Vlink")
        session.connectSucceeded(1200, 247, -58)
        session.elmRecord(
            com.obd2.app.core.diag.ElmCommandRecord(
                ts = System.currentTimeMillis(), cmd = "ATZ", writeUuid = "x",
                writeResult = "принято", firstByteMs = null, responseHex = "", responseAscii = "",
                durationMs = 3000, promptSeen = false, resultCode = "TIMEOUT",
                errorKind = "таймаут ответа", fragments = 0, fragmentsMergedOk = false,
                terminatorMissing = true,
            )
        )
        val conclusions = com.obd2.app.core.diag.DiagAnalyzer().analyze(
            session,
            listOf(
                com.obd2.app.core.diag.DiagStageState("perm", "Разрешения").apply { state = StageState.PASS },
                com.obd2.app.core.diag.DiagStageState("connect", "GATT").apply { state = StageState.PASS },
                com.obd2.app.core.diag.DiagStageState("elm", "ELM327").apply { state = StageState.FAIL },
            ),
        )
        assertTrue(conclusions.any { c -> c.confirmed && c.text.contains("ELM327 не отвечает") })
        assertTrue(conclusions.any { c -> c.confirmed && c.text.contains("GATT-подключение установлено") })
        session.close()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ //

    private fun updateStateConclusions(runner: DiagRunner, conclusions: List<com.obd2.app.core.diag.Conclusion>) {
        // the runner sets conclusions itself at the end; this mirrors that for
        // assertions when analyzing after a manual run
        runner.state.value.copy(conclusions = conclusions)
    }

    private fun zipNames(zip: File): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                e = zis.nextEntry
            }
        }
        return names
    }

    private fun zipEntry(zip: File, name: String): String {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == name) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                e = zis.nextEntry
            }
        }
        throw AssertionError("entry not found: $name")
    }
}
