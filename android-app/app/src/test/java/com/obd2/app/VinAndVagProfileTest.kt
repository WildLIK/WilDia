package com.obd2.app

import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.Mode09Parser
import com.obd2.app.core.obd.VinIdentificationEngine
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.core.universal.DiagnosticProtocolKind
import com.obd2.app.core.universal.VehicleDiagnosticProfileRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinAndVagProfileTest {

    /**
     * Stateful fake ELM327 transport that tracks current CAN header/filter state
     * (`ATH1`/`ATH0`, `ATSH xxx`, `ATCRA yyy`) and responds deterministically.
     */
    private class ScriptedElmTransport(
        private val responder: (cmd: String, header: String, filter: String?, headersOn: Boolean) -> List<String>,
    ) : ByteTransport {
        override val name: String = "FakeVgate"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming

        val commandsSent = mutableListOf<String>()
        var headersOn: Boolean = false
            private set
        var currentHeader: String = "7DF"
            private set
        var currentFilter: String? = null
            private set

        override suspend fun connect() {
            _state.value = TransportState.Connected("Fake connected")
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected

        override suspend fun write(data: ByteArray) {
            val cmd = data.toString(Charsets.UTF_8).trim().uppercase()
            commandsSent.add(cmd)
            when {
                cmd == "ATH1" -> {
                    headersOn = true
                    emitLines(listOf("OK"))
                }
                cmd == "ATH0" -> {
                    headersOn = false
                    emitLines(listOf("OK"))
                }
                cmd.startsWith("ATSH ") -> {
                    currentHeader = cmd.removePrefix("ATSH ").trim()
                    emitLines(listOf("OK"))
                }
                cmd == "ATCRA" -> {
                    currentFilter = null
                    emitLines(listOf("OK"))
                }
                cmd.startsWith("ATCRA ") -> {
                    currentFilter = cmd.removePrefix("ATCRA ").trim()
                    emitLines(listOf("OK"))
                }
                else -> emitLines(responder(cmd, currentHeader, currentFilter, headersOn))
            }
        }

        private suspend fun emitLines(lines: List<String>) {
            val payload = lines.joinToString("\r\n") + "\r\n>"
            _incoming.emit(payload.toByteArray(Charsets.UTF_8))
        }
    }

    private fun asciiToHex(s: String): String =
        s.map { "%02X".format(it.code) }.joinToString(" ")

    // ------------------------------------------------------------------
    // 1. Mode09Parser & Multi-ECU / NRC / UDS / KWP2000 tests
    // ------------------------------------------------------------------

    @Test
    fun `extractVin decodes standard ATH0 ISO-TP multi-frame 0902`() {
        val lines = listOf(
            "014",
            "0: 49 02 01 54 4D 42", // T M B
            "1: 4A 43 34 31 5A 38 43", // J C 4 1 Z 8 C
            "2: 32 31 32 33 34 35 36", // 2 1 2 3 4 5 6
        )
        assertEquals("TMBJC41Z8C2123456", Mode09Parser.extractVin(lines))
    }

    @Test
    fun `extractVin ignores TCM 7F 09 NRC bytes and decodes Engine 7E8 cleanly`() {
        // Real VAG behavior: TCM (7E9) replies 7F 09 12 or 7F 09 31 (0x31 == '1' ASCII!)
        // Previously 0x31 was appended into the VIN string and broke 17-char validation.
        val lines = listOf(
            "7E9 03 7F 09 31 00 00 00 00",
            "7E8 10 14 49 02 01 54 4D 42",
            "7E8 21 4A 43 34 31 5A 38 43",
            "7E8 22 32 31 32 33 34 35 36",
        )
        val vin = Mode09Parser.extractVin(lines)
        assertEquals("TMBJC41Z8C2123456", vin)
        val nrc = Mode09Parser.extractNrc(lines)
        assertNotNull(nrc)
        assertEquals("7E9", nrc!!.ecuHeader)
        assertEquals(0x31, nrc.nrc)
    }

    @Test
    fun `parseMultiEcuAscii keeps Engine 7E8 and TCM 7E9 CalIDs completely separate`() {
        val lines = listOf(
            "7E8 10 13 49 04 01 30 36 41",
            "7E9 10 13 49 04 01 30 39 47",
            "7E8 21 39 30 36 30 33 33 4B",
            "7E9 21 39 32 37 37 35 30 4A",
            "7E8 22 44 20 32 32 33 30 00",
            "7E9 22 41 20 32 31 34 31 00",
        )
        val parsed = Mode09Parser.parseMultiEcuAscii(lines, "04")
        assertEquals(2, parsed.size)
        val ecm = parsed.first { it.ecuHeader == "7E8" }
        val tcm = parsed.first { it.ecuHeader == "7E9" }
        assertEquals("06A906033KD 2230", ecm.value)
        assertEquals("09G927750JA 2141", tcm.value)
    }

    @Test
    fun `isValidVin rejects placeholders, short strings, and invalid chars I O Q`() {
        assertFalse(Mode09Parser.isValidVin("00000000000000000"))
        assertFalse(Mode09Parser.isValidVin("XXXXXXXXXXXXXXXXX"))
        assertFalse(Mode09Parser.isValidVin("11111111111111111"))
        assertFalse(Mode09Parser.isValidVin("TMBJC41Z8C212345")) // 16 chars
        assertFalse(Mode09Parser.isValidVin("TMBIC41Z8C2123456")) // contains I
        assertTrue(Mode09Parser.isValidVin("TMBJC41Z8C2123456"))
    }

    @Test
    fun `extractVin returns null on incomplete frames and NO DATA`() {
        assertNull(Mode09Parser.extractVin(listOf("NO DATA")))
        assertNull(Mode09Parser.extractVin(listOf("7E8 10 14 49 02 01 54 4D 42")))
    }

    @Test
    fun `extractVinFromUds22F190 decodes multi-frame 62 F1 90 response`() {
        val lines = listOf(
            "77E 10 14 62 F1 90 54 4D 42",
            "77E 21 4A 43 34 31 5A 38 43",
            "77E 22 32 31 32 33 34 35 36",
        )
        assertEquals("TMBJC41Z8C2123456", Mode09Parser.extractVinFromUds22F190(lines))
    }

    @Test
    fun `extractVinFromKwp1A90 decodes 5A 90 VIN response`() {
        val hexVin = asciiToHex("TMBJC41Z8C2123456")
        val lines = listOf("5A 90 $hexVin")
        assertEquals("TMBJC41Z8C2123456", Mode09Parser.extractVinFromKwp1A90(lines))
    }

    // ------------------------------------------------------------------
    // 2. VinIdentificationEngine staged fallbacks & restoration
    // ------------------------------------------------------------------

    @Test
    fun `VinIdentificationEngine falls back to direct 7E0-7E8 0902 and restores headers`() {
        runBlocking {
            val transport = ScriptedElmTransport { cmd, header, filter, headersOn ->
                when (cmd) {
                    "ATDP" -> listOf("AUTO, ISO 15765-4 (CAN 11/500)")
                    "ATDPN" -> listOf("A6")
                    "0902" -> {
                        if (header == "7E0" && filter == "7E8" && headersOn) {
                            listOf(
                                "7E8 10 14 49 02 01 54 4D 42",
                                "7E8 21 4A 43 34 31 5A 38 43",
                                "7E8 22 32 31 32 33 34 35 36",
                            )
                        } else {
                            listOf("NO DATA")
                        }
                    }
                    "0904" -> listOf(
                        "7E8 10 13 49 04 01 30 36 41",
                        "7E8 21 39 30 36 30 33 33 4B",
                        "7E8 22 44 20 32 32 33 30 00",
                    )
                    else -> listOf("NO DATA")
                }
            }
            transport.connect()
            val elm = Elm327Client(transport, RawLog())
            val res = VinIdentificationEngine.identify(elm, "Skoda Octavia 1Z")

            assertEquals("TMBJC41Z8C2123456", res.vin)
            assertTrue(res.vinSource!!.contains("7E0 -> 7E8"))
            assertEquals("06A906033KD 2230", res.calIdsByEcu["7E8"])
            assertFalse("ATH0 must be restored", transport.headersOn)
            assertNull("ATCRA must be cleared", transport.currentFilter)
            assertEquals("7DF", transport.currentHeader)
        }
    }

    @Test
    fun `VinIdentificationEngine falls back to UDS 22 F1 90 on Cluster 714 when Mode 09 returns NRC`() {
        runBlocking {
            val transport = ScriptedElmTransport { cmd, header, _, _ ->
                when (cmd) {
                    "ATDP" -> listOf("ISO 15765-4 (CAN 11/500)")
                    "ATDPN" -> listOf("6")
                    "0902" -> listOf("7E8 03 7F 09 12")
                    "22F190" -> if (header == "714") {
                        listOf(
                            "77E 10 14 62 F1 90 54 4D 42",
                            "77E 21 4A 43 34 31 5A 38 43",
                            "77E 22 32 31 32 33 34 35 36",
                        )
                    } else {
                        listOf("7E8 03 7F 22 31")
                    }
                    "22F187" -> if (header == "714") {
                        listOf("77E 10 0E 62 F1 87 31 5A 30 39 32 30 38 30 33 51") // 1Z0920803Q
                    } else listOf("NO DATA")
                    else -> listOf("NO DATA")
                }
            }
            transport.connect()
            val elm = Elm327Client(transport, RawLog())
            val res = VinIdentificationEngine.identify(elm, "Skoda Octavia 1Z")

            assertEquals("TMBJC41Z8C2123456", res.vin)
            assertTrue(res.vinSource!!.contains("UDS 22 F1 90"))
            assertEquals("1Z0920803Q", res.partNumbersByEcu["77E"])
            assertFalse(transport.headersOn)
            assertNull(transport.currentFilter)
            assertEquals("7DF", transport.currentHeader)
        }
    }

    @Test
    fun `VinIdentificationEngine falls back to KWP2000 1A 90 when KWP bus is active`() {
        runBlocking {
            val transport = ScriptedElmTransport { cmd, _, _, _ ->
                when (cmd) {
                    "ATDP" -> listOf("ISO 14230-4 (KWP FAST)")
                    "ATDPN" -> listOf("5")
                    "1A90" -> listOf("5A 90 ${asciiToHex("TMBJC41Z8C2123456")}")
                    else -> listOf("NO DATA")
                }
            }
            transport.connect()
            val elm = Elm327Client(transport, RawLog())
            val res = VinIdentificationEngine.identify(elm)

            assertEquals("TMBJC41Z8C2123456", res.vin)
            assertTrue(res.vinSource!!.contains("KWP2000"))
        }
    }

    @Test
    fun `VinIdentificationEngine never fakes VIN when all stages fail and formats export log`() {
        runBlocking {
            val transport = ScriptedElmTransport { cmd, _, _, _ ->
                when (cmd) {
                    "ATDP" -> listOf("ISO 15765-4 (CAN 11/500)")
                    "ATDPN" -> listOf("6")
                    else -> listOf("NO DATA")
                }
            }
            transport.connect()
            val elm = Elm327Client(transport, RawLog())
            val res = VinIdentificationEngine.identify(elm, "Skoda Octavia 1Z")

            assertNull(res.vin)
            val logText = VinIdentificationEngine.formatDiagnosticLog(res, maskVin = true)
            assertTrue(logText.contains("НЕ ПОЛУЧЕН"))
            assertTrue(logText.contains("0902"))
            assertTrue(logText.contains("22F190"))
            assertTrue(logText.contains("NO DATA"))
        }
    }

    // ------------------------------------------------------------------
    // 3. Extensible VehicleDiagnosticProfileRegistry
    // ------------------------------------------------------------------

    @Test
    fun `VehicleDiagnosticProfileRegistry matches Skoda Octavia 1Z BSE and marks TP20 limitation`() {
        val profile = VehicleDiagnosticProfileRegistry.matchVehicle(
            maker = "Skoda",
            model = "Octavia 1Z",
            year = "2012",
            engine = "1.6 MPI BSE",
        )
        assertEquals("skoda_octavia_1z_2010_2013_bse", profile.id)
        assertTrue(profile.ecuTargets.any { it.vagAddressCode == "01" && it.rxAddress == "7E8" })
        assertTrue(profile.ecuTargets.any { it.vagAddressCode == "17" && it.txAddress == "714" && it.readOnlySupportedOnElm327 })
        val abs = profile.ecuTargets.first { it.vagAddressCode == "03" }
        assertEquals(DiagnosticProtocolKind.VAG_TP20, abs.protocolKind)
        assertFalse("ABS TP2.0 must honestly be marked unsupported on standard ELM327", abs.readOnlySupportedOnElm327)
        assertTrue(profile.safeReadOnlyCommands.any { it.command == "22F190" && it.requiresUds })
    }
}
