package com.obd2.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.ElmResponse
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.DtcModes
import com.obd2.app.core.obd.DtcParser
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.PidDecoder
import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Protocol & DTC audit regression tests (Stage 5).
 *
 * Covers the exact field-test false DTC failure modes (P3503, P2200, P0043,
 * P0047, C3F0A, P117F, P0A11), multi-ECU isolation, ISO-TP reassembly (ATH0/ATH1),
 * negative responses (7F), ATDPN hex parsing, silent adapter initialization,
 * O2/readiness decoding, and Room session persistence across disconnect.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ObdProtocolAuditTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.coroutineContext.cancelChildren()
    }

    private class ScriptedTransport(
        private val answers: Map<String, String?>,
    ) : ByteTransport {
        override val name = "SCRIPTED"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        override val incoming: SharedFlow<ByteArray>
            get() = _incoming
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

        override suspend fun connect() {
            _state.value = TransportState.Connected("scripted")
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        override suspend fun write(data: ByteArray) {
            while (_incoming.subscriptionCount.value == 0) kotlinx.coroutines.delay(5)
            val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ').uppercase()
            val reply = answers[cmd] ?: return
            _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected
    }

    @Test
    fun `negative response 7F 0A 11 from two ECUs never produces C3F0A P117F P0A11`() {
        val lines = listOf("7F 0A 11", "7F 0A 11")
        val res = DtcParser.parseDtcResponse(lines, DtcModes.PERMANENT, isCanProtocol = true)
        assertEquals(emptyList<String>(), res.dtcs.map { it.code })
        assertEquals(2, res.negativeResponses.size)
        assertEquals(0x11, res.negativeResponses[0].nrc)
        assertTrue(res.negativeResponses[0].describeRu().contains("не поддерживается"))
    }

    @Test
    fun `multi ECU zero DTC responses never produce P0043 or P0047`() {
        val storedLines = listOf("43 00", "43 00")
        val pendingLines = listOf("47 00", "47 00")
        val storedPadded = listOf("43 00 00 00 00 00 00", "43 00 00 00 00 00 00")

        assertEquals(emptyList<String>(), DtcParser.parseDtcResponse(storedLines, 3, isCanProtocol = true).dtcs.map { it.code })
        assertEquals(emptyList<String>(), DtcParser.parseDtcResponse(pendingLines, 7, isCanProtocol = true).dtcs.map { it.code })
        assertEquals(emptyList<String>(), DtcParser.parseDtcResponse(storedPadded, 3, isCanProtocol = true).dtcs.map { it.code })
    }

    @Test
    fun `non-CAN 6-byte DTC frame with second ECU does not shift bytes into P3503 P2200 P0043`() {
        // Real KWP/ISO-9141 style: ECU1 has P0335 (03 35) and P0322 (03 22), ECU2 has 00 00
        // Old flattener combined to [03, 35, 03, 22, 00, 00, 43, 00...], read count=3 -> P3503, P2200, P0043
        val lines = listOf("43 03 35 03 22 00 00", "43 00 00 00 00 00 00")
        val resNonCan = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = false)
        assertEquals(listOf("P0335", "P0322"), resNonCan.dtcs.map { it.code })
        // Even when protocol is unknown (null), inference must reject count=3 for 6-byte payload
        val resUnknown = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = null)
        assertEquals(listOf("P0335", "P0322"), resUnknown.dtcs.map { it.code })
    }

    @Test
    fun `multi ECU CAN responses isolate each ECU and deduplicate cleanly`() {
        // ECU 1 reports P0300, P0441; ECU 2 reports P0700 and duplicate P0300
        val lines = listOf(
            "43 02 03 00 04 41 00",
            "43 02 07 00 03 00 00",
        )
        val res = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = true)
        assertEquals(2, res.ecuCount)
        assertEquals(listOf("P0300", "P0441", "P0700"), res.dtcs.map { it.code })
    }

    @Test
    fun `ATH1 CAN headers separate ECUs and reassemble ISO-TP multi-frame`() {
        val lines = listOf(
            "7E8 10 0E 43 06 01 33 01 71",
            "7E8 21 03 00 04 20 04 41 05",
            "7E8 22 00 00 00 00 00 00 00",
            "7E9 02 43 00 00 00 00 00 00",
        )
        val res = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = true)
        assertEquals(2, res.ecuCount)
        assertEquals(
            listOf("P0133", "P0171", "P0300", "P0420", "P0441", "P0500"),
            res.dtcs.map { it.code },
        )
        assertTrue(res.dtcs.all { it.ecuId == "7E8" })
    }

    @Test
    fun `ATH0 ISO-TP numbered frames 0 1 2 with byte-count header reassemble accurately`() {
        val lines = listOf(
            "00E",
            "0: 43 06 01 33 01 71 03",
            "1: 00 04 20 04 41 05 00",
        )
        val res = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = true)
        assertEquals(1, res.ecuCount)
        assertEquals(
            listOf("P0133", "P0171", "P0300", "P0420", "P0441", "P0500"),
            res.dtcs.map { it.code },
        )
    }

    @Test
    fun `ATH1 7F negative response per ECU is reported without corrupting other ECU positive DTCs`() {
        val lines = listOf(
            "7E8 04 43 01 03 00 00 00 00",
            "7E9 03 7F 03 11 00 00 00 00",
        )
        val res = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = true)
        assertEquals(listOf("P0300"), res.dtcs.map { it.code })
        assertEquals(1, res.negativeResponses.size)
        assertEquals("7E9", res.negativeResponses[0].ecuId)
        assertEquals(0x11, res.negativeResponses[0].nrc)
    }

    @Test
    fun `compact hex ATS0 format decodes cleanly`() {
        val lines = listOf("430201330420")
        val res = DtcParser.parseDtcResponse(lines, 3, isCanProtocol = true)
        assertEquals(listOf("P0133", "P0420"), res.dtcs.map { it.code })
    }

    @Test
    fun `ATDPN parses A6 and hex protocol numbers and detects CAN`() {
        assertEquals(6, Elm327Client.parseProtocolNumber("A6"))
        assertEquals(6, Elm327Client.parseProtocolNumber("6"))
        assertEquals(10, Elm327Client.parseProtocolNumber("AA"))
        assertEquals(0, Elm327Client.parseProtocolNumber("A0"))
        assertEquals(true, Elm327Client.inferIsCanProtocol("AUTO, ISO 15765-4 (CAN 11/500)", 6))
        assertEquals(false, Elm327Client.inferIsCanProtocol("ISO 14230-4 (KWP FAST)", 5))
    }

    @Test
    fun `silent ELM327 transport fails initialize instead of succeeding`() = runBlocking {
        val silentTransport = ScriptedTransport(emptyMap())
        val elm = Elm327Client(silentTransport, RawLog(), scope)
        // Use fast timeouts indirectly? Default timeouts: ATZ 4s + ATE0 3s (x2) + ATL0/ATS1/ATH0/ATI...
        // Test command timeout behavior directly & initialize failure:
        val err = elm.initialize()
        assertNotNull("Silent adapter must return init error", err)
        assertTrue(err!!.contains("ELM327 не отвечает"))
    }

    @Test
    fun `ObdClient readDtc turns Mode 0A 7F 0A 11 into Unsupported instead of phantom codes`() = runBlocking {
        val t = ScriptedTransport(
            mapOf(
                "ATZ" to "ELM327 v2.3",
                "ATE0" to "OK",
                "ATL0" to "OK",
                "ATS1" to "OK",
                "ATH0" to "OK",
                "ATI" to "ELM327 v2.3",
                "ATSP0" to "OK",
                "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
                "ATDPN" to "A6",
                "ATRV" to "12.5V",
                "0A" to "7F 0A 11\r7F 0A 11",
            )
        )
        val elm = Elm327Client(t, RawLog(), scope)
        assertNull(elm.initialize())
        assertEquals(6, elm.info.value.protocolNumber)
        assertEquals(true, elm.info.value.isCanProtocol)
        val obd = ObdClient(elm)
        val res = obd.readDtc(DtcModes.PERMANENT)
        assertTrue("Expected Unsupported, got $res", res is ObdClient.DtcReadResult.Unsupported)
    }

    @Test
    fun `ObdClient decodeMode01 handles compact hex and 7F negative response`() {
        val elm = Elm327Client(ScriptedTransport(emptyMap()), RawLog(), scope)
        val obd = ObdClient(elm)
        val rpmDef = PidDefinition(1, 0x0C, "RPM", "Обороты", "rpm", 2, "numeric", "AB", 0.25, 0.0, null, "engine")

        val compact = obd.decodeResponse(rpmDef, listOf("410C0FA0"), 20)
        assertTrue(compact is PidResult.Ok)
        assertEquals(1000.0, (compact as PidResult.Ok).reading.value!!, 0.01)

        val nrc = obd.decodeResponse(rpmDef, listOf("7F 01 12"), 20)
        assertTrue(nrc is PidResult.Unsupported)
    }

    @Test
    fun `PidDecoder fixes Korea string and O2 2-bank vs 4-bank layout`() {
        val k = PidDecoder.obdStandard(38)
        assertFalse("Chinese characters must not remain", k.contains("国家"))
        assertTrue(k.contains("KOBD"))

        // PID 0x13 bit 4 (0x10) = B2S1 in 2-bank layout, whereas PID 0x1D bit 4 = B3S1 in 4-bank layout
        assertEquals("B2S1", PidDecoder.o2Present2Banks(byteArrayOf(0x10)))
        assertEquals("B3S1", PidDecoder.o2Present4Banks(byteArrayOf(0x10)))
    }

    @Test
    fun `disconnect preserves saved session DTC counts and maker enrichment isolates OEM codes`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val settings = SettingsRepository(ctx)
            val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, settings, RawLog(), scope)

            repo.connectDemo(settings.settings.first())
            val dtcScan = repo.readDtcs(saveHistory = true, vehicleMaker = "Lada")
            assertTrue(dtcScan.stored.isNotEmpty())
            assertTrue("rawStored must not be empty", dtcScan.rawStored.isNotEmpty())
            // Verify non-VAG maker does not assign VAG-specific meaning for OEM code P1136
            val p1136Check = repo.readDtcEnrichPreviewForTest("P1136", "Lada")
            assertFalse("Lada must not be labeled VAG for P1136", p1136Check.scopeLabel?.contains("Volkswagen", ignoreCase = true) == true)

            repo.disconnect()
            kotlinx.coroutines.delay(150)
            val recent = db.sessionDao().recentSessions(5)
            assertTrue(recent.isNotEmpty())
            val latest = recent.first()
            assertEquals(2, latest.storedCount)
            assertNotNull(latest.endedAtMs)
        } finally {
            db.close()
        }
    }
}
