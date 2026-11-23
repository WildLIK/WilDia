package com.obd2.app.core.replay

import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ReplayFixtureKind(val labelRu: String, val isArtificial: Boolean) {
    RECORDED_LOG("Воспроизведение записанного лога", isArtificial = false),
    ARTIFICIAL_DRIVE("Симуляция поездки (искусственная фикстура)", isArtificial = true),
    ARTIFICIAL_TIMEOUT("Фикстура таймаутов и неполных ответов", isArtificial = true),
    ARTIFICIAL_DISCONNECT("Фикстура обрыва связи в процессе опроса", isArtificial = true),
    ARTIFICIAL_MISSING_PIDS("Фикстура отсутствующих PID (NO DATA)", isArtificial = true),
}

/**
 * Replays recorded TX/RX OBD-II log lines or labeled artificial test fixtures
 * through the standard [ByteTransport] interface for offline verification of
 * charts, trip recording, timeouts, partial answers, and disconnect handling.
 *
 * Strictly restricted to Developer Mode and automated tests.
 */
class ReplayTransport(
    val fixtureKind: ReplayFixtureKind,
    private val commandSequences: Map<String, List<String?>>,
    private val defaultReply: String? = "NO DATA",
    private val disconnectAfterCommands: Int? = null,
) : ByteTransport {

    override val name: String = "REPLAY-${fixtureKind.name}"
    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private val counters = HashMap<String, Int>()
    private var totalCommands = 0

    override suspend fun connect() {
        _state.value = TransportState.Connected(
            if (fixtureKind.isArtificial) "Симулятор (фикстура)" else "Воспроизведение лога",
        )
    }

    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
    }

    override fun isConnected(): Boolean = _state.value is TransportState.Connected

    override suspend fun write(data: ByteArray) {
        while (_incoming.subscriptionCount.value == 0) delay(5)
        val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ').uppercase()
        totalCommands++
        if (disconnectAfterCommands != null && totalCommands >= disconnectAfterCommands) {
            _state.value = TransportState.Failed("Воспроизведение: моделированный обрыв связи")
            return
        }
        val list = commandSequences[cmd]
        val reply = if (list != null && list.isNotEmpty()) {
            val idx = counters.getOrDefault(cmd, 0)
            counters[cmd] = idx + 1
            list[idx % list.size]
        } else {
            defaultReply
        }
        if (reply == null) {
            // Simulate timeout / silent ECU
            return
        }
        _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
    }
}

object SessionLogReplayer {

    /**
     * Parses TX/RX pairs from a plain-text RawLog export (`>> CMD` followed by `<< REPLY`).
     */
    fun parseRawLogText(rawText: String): Map<String, List<String?>> {
        val result = LinkedHashMap<String, MutableList<String?>>()
        var lastCmd: String? = null
        val rxBuf = ArrayList<String>()

        fun flush() {
            val cmd = lastCmd ?: return
            if (rxBuf.isNotEmpty()) {
                result.getOrPut(cmd) { ArrayList() }.add(rxBuf.joinToString("\r"))
            }
            rxBuf.clear()
            lastCmd = null
        }

        for (rawLine in rawText.lines()) {
            val line = rawLine.trim()
            when {
                line.contains(" >> ") -> {
                    flush()
                    lastCmd = line.substringAfter(" >> ").trim().uppercase()
                }
                line.contains(" << ") -> {
                    val rx = line.substringAfter(" << ").trim()
                    if (lastCmd != null && rx.isNotBlank()) rxBuf += rx
                }
            }
        }
        flush()
        return result
    }

    fun buildFixture(kind: ReplayFixtureKind, recordedLogText: String? = null): ReplayTransport {
        val base = linkedMapOf<String, List<String?>>(
            "ATZ" to listOf("ELM327 v2.3"),
            "ATE0" to listOf("OK"),
            "ATL0" to listOf("OK"),
            "ATS1" to listOf("OK"),
            "ATH0" to listOf("OK"),
            "ATI" to listOf("ELM327 v2.3"),
            "ATSP0" to listOf("OK"),
            "ATDP" to listOf("AUTO, ISO 15765-4 (CAN 11/500)"),
            "ATDPN" to listOf("A6"),
            "ATRV" to listOf("12.8V", "13.9V", "14.1V"),
            "0100" to listOf("41 00 BE 1F A8 13"),
            "0120" to listOf("NO DATA"),
            "0140" to listOf("NO DATA"),
            "0160" to listOf("NO DATA"),
            "010C" to listOf("41 0C 0C 80", "41 0C 13 88", "41 0C 1B 58", "41 0C 0F A0"),
            "0105" to listOf("41 05 55", "41 05 5A", "41 05 5F"),
            "0104" to listOf("41 04 30", "41 04 66", "41 04 4D"),
            "010D" to listOf("41 0D 00", "41 0D 28", "41 0D 3C"),
            "0142" to listOf("41 42 35 20", "41 42 36 4C"),
            "0106" to listOf("41 06 80", "41 06 84", "41 06 7C"),
        )
        return when (kind) {
            ReplayFixtureKind.RECORDED_LOG -> {
                val parsed = recordedLogText?.let(::parseRawLogText).orEmpty()
                val merged = LinkedHashMap<String, List<String?>>(base)
                merged.putAll(parsed)
                ReplayTransport(kind, merged)
            }
            ReplayFixtureKind.ARTIFICIAL_DRIVE -> ReplayTransport(kind, base)
            ReplayFixtureKind.ARTIFICIAL_TIMEOUT -> {
                base["0105"] = listOf("41 05 55", null, "41 05") // partial & timeout
                base["010D"] = listOf(null)
                ReplayTransport(kind, base)
            }
            ReplayFixtureKind.ARTIFICIAL_DISCONNECT -> {
                ReplayTransport(kind, base, disconnectAfterCommands = 16)
            }
            ReplayFixtureKind.ARTIFICIAL_MISSING_PIDS -> {
                base["0100"] = listOf("41 00 08 00 00 00") // only 0105 supported
                base["010C"] = listOf("NO DATA")
                base["010D"] = listOf("NO DATA")
                base["0142"] = listOf("NO DATA")
                ReplayTransport(kind, base)
            }
        }
    }
}
