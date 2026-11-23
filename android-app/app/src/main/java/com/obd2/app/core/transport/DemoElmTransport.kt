package com.obd2.app.core.transport

import com.obd2.app.core.obd.DtcParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline ELM327 + ECU simulator (demo mode / unit tests).
 * Emulates a Vgate-like adapter with an ISO 15765-4 (CAN 11/500) ECU:
 *  - safe AT command responses
 *  - Mode 01/03/09 answers with realistic values for a 1.6 MPI
 *  - responses emitted in small chunks to exercise BLE reassembly
 *
 * Only queries are answered; unknown commands get the ELM '?'.
 */
class DemoElmTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val latencyMs: Long = 30,
) : ByteTransport {

    private val connected = AtomicBoolean(false)
    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    override val incoming: SharedFlow<ByteArray> = _incoming

    override val name: String = "Демо (без адаптера)"

    // ----- simulated vehicle state -----
    @Volatile var rpm: Int = 860
    @Volatile var speed: Int = 0
    @Volatile var coolantC: Int = 88
    @Volatile var loadPct: Int = 24
    @Volatile var throttlePct: Int = 14
    @Volatile var intakeAirC: Int = 31
    @Volatile var mafGs: Int = 350       // /100
    @Volatile var voltageV10: Int = 139  // *10
    @Volatile var runtimeS: Int = 300
    @Volatile var fuelLevelPct: Int = 63
    @Volatile var oilTempC: Int = 82
    @Volatile var mapKpa: Int = 32
    @Volatile var timingAdvX2: Int = 24  // /2 deg BTDC
    @Volatile var baroKpa: Int = 101
    @Volatile var ambientC: Int = 18
    @Volatile var stftB1: Int = 132      // (A-128)*100/128 => +3.1%
    @Volatile var ltftB1: Int = 122
    @Volatile var obdStd: Int = 17       // EOBD phase 2
    @Volatile var vin: String = "TMBJJ7NE9C0123456"
    @Volatile var calId: String = "06A906033KD"
    @Volatile var storedDtcs: List<String> = listOf("P0300", "P0441")
    @Volatile var pendingDtcs: List<String> = emptyList()
    @Volatile var milOn: Boolean = false

    private val supportedPids = sortedSetOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x1C, 0x1F,
        0x20, 0x21, 0x2F, 0x30, 0x31, 0x33,
        0x40, 0x41, 0x42, 0x45, 0x46, 0x47, 0x49, 0x5C,
    )
    private val mode9Supported = setOf(0x02, 0x04)

    override fun isConnected(): Boolean = connected.get()

    override suspend fun connect() {
        _state.value = TransportState.Connecting
        delay(400)
        connected.set(true)
        _state.value = TransportState.Connected("Демо-адаптер ELM327 v2.3 (симулятор)")
    }

    override suspend fun disconnect() {
        connected.set(false)
        _state.value = TransportState.Disconnected
    }

    override suspend fun write(data: ByteArray) {
        if (!connected.get()) return
        val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ')
        scope.launch { respond(cmd) }
    }

    private suspend fun respond(cmd: String) {
        val upper = cmd.uppercase()
        val text: String = when {
            upper == "ATZ" -> { delay(300); "ELM327 v2.3" }
            upper == "ATI" -> "ELM327 v2.3"
            upper in setOf("ATE0", "ATE1", "ATL0", "ATL1", "ATS0", "ATS1", "ATH0", "ATH1", "ATAT0", "ATAT1") -> "OK"
            upper.startsWith("ATSP") -> "OK"
            upper == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11 bit ID, 500 kbaud)"
            upper == "ATDPN" -> "6"
            upper == "ATRV" -> "%d.%02dV".format(voltageV10 / 10, voltageV10 % 10)
            upper.startsWith("01") -> mode01(upper)
            upper.startsWith("09") -> mode09(upper)
            upper == "03" -> dtcList(storedDtcs, 0x43)
            upper == "07" -> dtcList(pendingDtcs, 0x47)
            upper == "0A" -> dtcList(emptyList(), 0x4A)
            else -> "?"
        }
        delay(latencyMs)
        emitChunked(text + "\r\r>")
    }

    private fun mode01(cmd: String): String {
        if (cmd.length != 4) return "?"
        val pid = cmd.drop(2).toIntOrNull(16) ?: return "?"
        // Support-mask bases answered only when the (simulated) ECU really
        // supports that range; higher ranges respond NO DATA like a real ECU.
        val maskBase = pid in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)
        if (pid !in supportedPids) return "NO DATA"
        val data: String = when (pid) {
            0x00 -> mask(0x00); 0x20 -> mask(0x20); 0x40 -> mask(0x40)
            0x01 -> if (milOn) "8100" else "0000"  // MIL + DTC count in A
            0x02 -> bytesToDtcHex(storedDtcs.firstOrNull())
            0x03 -> "0100"
            0x04 -> byte(loadPct * 255 / 100)
            0x05 -> byte(coolantC + 40)
            0x06 -> byte(stftB1)
            0x07 -> byte(ltftB1)
            0x0B -> byte(mapKpa)
            0x0C -> bytes16(rpm * 4)
            0x0D -> byte(speed)
            0x0E -> byte(timingAdvX2 + 128)
            0x0F -> byte(intakeAirC + 40)
            0x10 -> bytes16(mafGs)
            0x11 -> byte(throttlePct * 255 / 100)
            0x1C -> byte(obdStd)
            0x1F -> bytes16(runtimeS)
            0x21 -> bytes16(0)
            0x2F -> byte(fuelLevelPct * 255 / 100)
            0x30 -> byte(5)
            0x31 -> bytes16(12345)
            0x33 -> byte(baroKpa)
            0x41 -> "00000000"
            0x42 -> bytes16(voltageV10 * 100)
            0x45 -> byte(15 * 255 / 100)
            0x46 -> byte(ambientC + 40)
            0x47 -> byte(throttlePct * 255 / 100)
            0x49 -> byte(12 * 255 / 100)
            0x5C -> byte(oilTempC + 40)
            else -> return "NO DATA"
        }
        return when (pid) {
            0x01, 0x41 -> "41 %02X %s".format(pid, data.chunked(2).joinToString(" "))
            else -> "41 %02X %s".format(pid, data.chunked(2).joinToString(" "))
        }
    }

    private fun mode09(cmd: String): String {
        if (cmd.length != 4) return "?"
        val pid = cmd.drop(2).toIntOrNull(16) ?: return "?"
        if (pid !in mode9Supported) return "NO DATA"
        return when (pid) {
            0x02 -> "49 02 01 " + vin.toByteArray(Charsets.US_ASCII)
                .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            0x04 -> "49 04 01 " + calId.toByteArray(Charsets.US_ASCII)
                .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            else -> "NO DATA"
        }
    }

    private fun dtcList(codes: List<String>, header: Int): String {
        if (codes.isEmpty()) return "%02X 00".format(header)
        val pairs = codes.map { c ->
            val (b1, b2) = DtcParser.codeToBytes(c)
            "%02X %02X".format(b1, b2)
        }
        return "%02X %02X %s".format(header, codes.size, pairs.joinToString(" "))
    }

    private fun bytesToDtcHex(code: String?): String =
        code?.let { val (b1, b2) = DtcParser.codeToBytes(it); "%02X%02X".format(b1, b2) } ?: "0000"

    /** Builds the 4-byte support mask (raw hex, formatted by mode01 caller). */
    private fun mask(base: Int): String {
        var m = 0
        for (p in supportedPids) {
            if (p in base + 1..base + 32) {
                m = m or (1 shl (32 - (p - base)))
            }
        }
        return "%08X".format(m)
    }

    private fun byte(v: Int): String = "%02X".format(v.coerceIn(0, 255))

    private fun bytes16(v: Int): String = "%04X".format(v.coerceIn(0, 65535))

    /** Splits the response into small chunks like real BLE notifications do. */
    private suspend fun emitChunked(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i < bytes.size) {
            val size = minOf(12, bytes.size - i)
            _incoming.emit(bytes.copyOfRange(i, i + size))
            i += size
            delay(3)
        }
    }
}
