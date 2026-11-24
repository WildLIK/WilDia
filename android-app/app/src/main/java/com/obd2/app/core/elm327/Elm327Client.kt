package com.obd2.app.core.elm327

import com.obd2.app.core.log.RawLog
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.Charset

/**
 * Serial-safe ELM327 client over any [ByteTransport].
 *
 * Guarantees:
 *  - one command in flight at a time (mutex-protected)
 *  - a single long-lived collector feeds an unbounded channel, so no BLE chunk
 *    is lost to subscription races
 *  - response collected until the ELM prompt '>'
 *  - tolerant to split chunks, echoes, linefeeds, "SEARCHING..."/"BUS INIT" chatter
 *  - configurable timeouts; never sends unsafe AT commands automatically
 */
class Elm327Client(
    private val transport: ByteTransport,
    private val log: RawLog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val exchangeRecorder: ElmExchangeRecorder? = null,
) {
    private val mutex = Mutex()
    private val lineBuffer = StringBuilder()
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var echoEnabled = false
    private var collectorStarted = false
    private var collectorJob: kotlinx.coroutines.Job? = null

    private val _info = MutableStateFlow(Elm327Info())
    val info: StateFlow<Elm327Info> = _info

    private val _lastSuccessfulResponseAtMs = MutableStateFlow<Long?>(null)
    val lastSuccessfulResponseAtMs: StateFlow<Long?> = _lastSuccessfulResponseAtMs

    private val _consecutiveTimeouts = MutableStateFlow(0)
    val consecutiveTimeouts: StateFlow<Int> = _consecutiveTimeouts

    /** Live request statistics (research screen + report). */
    data class Stats(
        val sent: Long = 0,
        val ok: Long = 0,
        val timeouts: Long = 0,
        val adapterErrors: Long = 0,
        val totalMs: Long = 0,
        val minMs: Long = Long.MAX_VALUE,
        val maxMs: Long = 0,
    ) {
        val avgMs: Long get() = if (ok + adapterErrors > 0) totalMs / (ok + adapterErrors) else 0
    }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    data class Elm327Info(
        val identity: String? = null,
        val resetBanner: String? = null,
        val protocol: String? = null,
        val protocolNumber: Int? = null,
        val isCanProtocol: Boolean? = null,
        val voltage: Float? = null,
        val echo: Boolean = false,
        val headers: Boolean = false,
        val supportsEchoOff: Boolean = false,
        val supportsHeaders: Boolean? = null,
        val supportsSpacesControl: Boolean = false,
        val supportsAutoProtocol: Boolean = false,
        val supportsCanAddressing: Boolean = false,
        val supportsAdaptiveTiming: Boolean = false,
        val capabilityNotes: List<String> = emptyList(),
    )

    /** Connects transport and runs the safe init sequence. Returns null on success, else error text. */
    suspend fun initialize(config: InitConfig = InitConfig()): String? {
        startCollector()
        transport.connect()
        transport.state.first { it is TransportState.Connected || it is TransportState.Failed }
        if (!transport.isConnected()) {
            val st = transport.state.value
            return (st as? TransportState.Failed)?.reason ?: "Транспорт не подключился"
        }
        return runSafeInitSequence(config)
    }

    data class InitConfig(
        val autoProtocol: Boolean = true,
        val protocolNumber: Int = 0,
    )

    /**
     * Safe init sequence for an ALREADY connected transport (diagnostics
     * reuse): identical commands to [initialize], no second connect.
     */
    suspend fun initializeOnConnected(config: InitConfig = InitConfig()): String? {
        startCollector()
        return runSafeInitSequence(config)
    }

    private suspend fun runSafeInitSequence(config: InitConfig): String? {
        // ATZ first: reset. Clones may emit garbage on connect; drain it.
        drainPending()
        val atz = command("ATZ", timeoutMs = 3000, allowRetries = false)
        val banner = (atz as? ElmResponse.Ok)?.lines?.lastOrNull()
        _info.value = _info.value.copy(resetBanner = banner)

        // ATE0 — echo off. Any echoed command line is stripped by matching anyway.
        val ate0 = command("ATE0", timeoutMs = 2000, allowRetries = false)
        echoEnabled = false
        val echoOffOk = isCommandAccepted(ate0)

        val ati = command("ATI", timeoutMs = 2000, allowRetries = false)
        val identity = (ati as? ElmResponse.Ok)?.lines?.firstOrNull()
        _info.value = _info.value.copy(identity = identity, echo = false, supportsEchoOff = echoOffOk)

        val anyElmOk = atz is ElmResponse.Ok || ate0 is ElmResponse.Ok || ati is ElmResponse.Ok
        if (!anyElmOk) {
            return "Адаптер ELM327 не отвечает (таймаут ATZ/ATE0/ATI)"
        }

        val atl0 = command("ATL0", timeoutMs = 2000)   // linefeeds off
        val ats1 = command("ATS1", timeoutMs = 2000)   // spaces on (readable logs, easy parsing)
        val ath1 = command("ATH1", timeoutMs = 2000)   // probe header control
        val ath0 = command("ATH0", timeoutMs = 2000)   // restore headers off for standard parsing
        val atat1 = command("ATAT1", timeoutMs = 2000) // probe adaptive timing

        val atspResp = if (config.autoProtocol) {
            command("ATSP0", timeoutMs = 2000)
        } else {
            command("ATSP${config.protocolNumber}", timeoutMs = 2000)
        }

        refreshProtocolAndVoltage()

        val canHdrOk = if (_info.value.isCanProtocol == true) {
            val shOk = isCommandAccepted(command("ATSH 7DF", timeoutMs = 1500))
            val craOk = isCommandAccepted(command("ATCRA", timeoutMs = 1500))
            shOk || craOk
        } else {
            false
        }

        val notes = buildList {
            if (!echoOffOk) add("ATE0 отклонён (эхо может оставаться)")
            if (!isCommandAccepted(atl0)) add("ATL0 не поддержан")
            if (!isCommandAccepted(ats1)) add("ATS1 не поддержан")
            if (!isCommandAccepted(ath1) || !isCommandAccepted(ath0)) {
                add("ATH1/ATH0 не подтверждён (возможны ограничения разделения ЭБУ)")
            }
            if (!isCommandAccepted(atat1)) add("ATAT1 не поддержан")
            if (!isCommandAccepted(atspResp)) add("ATSP отклонён")
        }

        _info.value = _info.value.copy(
            headers = false,
            supportsSpacesControl = isCommandAccepted(ats1),
            supportsHeaders = isCommandAccepted(ath1) && isCommandAccepted(ath0),
            supportsAutoProtocol = config.autoProtocol && isCommandAccepted(atspResp),
            supportsCanAddressing = canHdrOk,
            supportsAdaptiveTiming = isCommandAccepted(atat1),
            capabilityNotes = notes,
        )
        return null
    }

    private fun isCommandAccepted(resp: ElmResponse): Boolean {
        if (resp !is ElmResponse.Ok) return false
        return resp.lines.none { line ->
            val t = line.trim().uppercase()
            t == "?" || t.contains("ERROR")
        }
    }

    /**
     * Re-queries ATDP, ATDPN and ATRV. Must be called after the first ECU probe
     * when ATSP0 was used, because ELM327 reports only "AUTO" until a vehicle
     * bus handshake succeeds.
     */
    suspend fun refreshProtocolAndVoltage() {
        val dp = command("ATDP", timeoutMs = 4000)
        val protoLine = (dp as? ElmResponse.Ok)?.lines?.firstOrNull()
        val dpn = command("ATDPN", timeoutMs = 2000)
        val dpnRaw = (dpn as? ElmResponse.Ok)?.lines?.firstOrNull()
        val protoNum = parseProtocolNumber(dpnRaw)
        val isCan = inferIsCanProtocol(protoLine, protoNum)
        val rv = command("ATRV", timeoutMs = 2000)
        val volt = parseVoltage((rv as? ElmResponse.Ok)?.lines?.firstOrNull())
        _info.value = _info.value.copy(
            protocol = protoLine ?: _info.value.protocol,
            protocolNumber = protoNum ?: _info.value.protocolNumber,
            isCanProtocol = isCan ?: _info.value.isCanProtocol,
            voltage = volt ?: _info.value.voltage,
        )
    }

    /**
     * Sends one command and collects the response until the prompt '>' or timeout.
     * The only bytes written are [cmd] + CR.
     */
    suspend fun command(
        cmd: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        allowRetries: Boolean = true,
    ): ElmResponse {
        startCollector()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
            if (!transport.isConnected()) {
                log.log(RawLog.Dir.ERROR, "transport not connected, drop '$cmd'")
                recordExchange(cmd, ElmResponse.AdapterMessage(
                    ElmResponse.AdapterMessage.Kind.ERROR, "Нет соединения", 0),
                    startedAt = System.currentTimeMillis(), writeUuid = null,
                    writeFailed = "транспорт не подключён")
                return@withContext ElmResponse.AdapterMessage(
                    ElmResponse.AdapterMessage.Kind.ERROR, "Нет соединения", 0)
            }
            var attempt = 0
            var last: ElmResponse = ElmResponse.Timeout("", 0)
            var writeErr: String? = null
            var writeUuid: String? = null
            val startedAt = System.currentTimeMillis()
            exchangeRecorder?.onCommandStart(cmd)
            while (attempt < (if (allowRetries) 2 else 1)) {
                if (!transport.isConnected()) break
                attempt++
                lineBuffer.setLength(0)
                drainPending()
                val t0 = System.currentTimeMillis()
                log.log(RawLog.Dir.TX, cmd)
                try {
                    transport.write((cmd + "\r").toByteArray(CHARSET))
                    writeUuid = transport.lastWriteUuid
                    writeErr = null
                    exchangeRecorder?.onWriteResult(writeUuid, true, null)
                } catch (e: Exception) {
                    writeErr = e.message
                    writeUuid = transport.lastWriteUuid
                    log.log(RawLog.Dir.ERROR, "write failed: ${e.message}")
                    exchangeRecorder?.onWriteResult(writeUuid, false, e.message)
                    recordExchange(cmd, ElmResponse.AdapterMessage(
                        ElmResponse.AdapterMessage.Kind.ERROR, "Ошибка записи: ${e.message}", 0),
                        startedAt, writeUuid, writeErr)
                    return@withContext ElmResponse.AdapterMessage(
                        ElmResponse.AdapterMessage.Kind.ERROR, "Ошибка записи: ${e.message}", 0)
                }
                last = collect(cmd, timeoutMs, t0)
                if (last !is ElmResponse.Timeout || !transport.isConnected()) break
            }
                recordStats(last)
                recordExchange(cmd, last, startedAt, writeUuid, writeErr)
                last
            }
        }
    }

    /** Feeds the technical exchange recorder with the final record (§7). */
    private fun recordExchange(
        cmd: String,
        response: ElmResponse,
        startedAt: Long,
        writeUuid: String?,
        writeFailed: String?,
    ) {
        val rec = exchangeRecorder ?: return
        val now = System.currentTimeMillis()
        val (rc, kind) = when (response) {
            is ElmResponse.Ok -> "OK" to null
            is ElmResponse.Timeout -> "TIMEOUT" to "таймаут ответа"
            is ElmResponse.AdapterMessage -> "ADAPTER_${response.kind.name}" to "адаптер: ${response.text}"
        }
        rec.onCommandDone(
            ElmExchangeRecord(
                ts = startedAt,
                cmd = cmd,
                writeUuid = writeUuid,
                writeResult = if (writeFailed != null) "ошибка записи: $writeFailed" else "принято транспортом",
                firstByteMs = (rec as? DiagExchangeRecorder)?.firstByteDelta(),
                responseHex = (rec as? DiagExchangeRecorder)?.capturedHex() ?: "",
                responseAscii = (rec as? DiagExchangeRecorder)?.capturedAscii() ?: "",
                durationMs = now - startedAt,
                promptSeen = response is ElmResponse.Ok,
                resultCode = rc,
                errorKind = kind,
                fragments = (rec as? DiagExchangeRecorder)?.fragmentCount() ?: 0,
                fragmentsMergedOk = response !is ElmResponse.Timeout || response.partial.isNotBlank(),
                unexpectedChars = (rec as? DiagExchangeRecorder)?.unexpectedList() ?: emptyList(),
                terminatorMissing = response is ElmResponse.Timeout,
                bufferOverflow = (rec as? DiagExchangeRecorder)?.overflowFlag() ?: false,
                requestCancelled = false,
            )
        )
    }

    private fun recordStats(r: ElmResponse) {
        when (r) {
            is ElmResponse.Ok -> {
                _lastSuccessfulResponseAtMs.value = System.currentTimeMillis()
                _consecutiveTimeouts.value = 0
            }
            is ElmResponse.AdapterMessage -> {
                if (r.kind != ElmResponse.AdapterMessage.Kind.ERROR) {
                    _lastSuccessfulResponseAtMs.value = System.currentTimeMillis()
                    _consecutiveTimeouts.value = 0
                }
            }
            is ElmResponse.Timeout -> {
                _consecutiveTimeouts.value = _consecutiveTimeouts.value + 1
            }
        }
        _stats.value = _stats.value.let { s ->
            when (r) {
                is ElmResponse.Ok -> s.copy(
                    sent = s.sent + 1, ok = s.ok + 1, totalMs = s.totalMs + r.durationMs,
                    minMs = minOf(s.minMs, r.durationMs), maxMs = maxOf(s.maxMs, r.durationMs),
                )
                is ElmResponse.Timeout -> s.copy(sent = s.sent + 1, timeouts = s.timeouts + 1)
                is ElmResponse.AdapterMessage -> s.copy(
                    sent = s.sent + 1, adapterErrors = s.adapterErrors + 1,
                    totalMs = s.totalMs + r.durationMs,
                )
            }
        }
    }

    private fun startCollector() {
        if (collectorStarted && collectorJob?.isActive == true) return
        collectorStarted = true
        collectorJob?.cancel()
        collectorJob = scope.launch {
            transport.incoming.collect { chunks.trySend(it) }
        }
    }

    private suspend fun collect(cmd: String, timeoutMs: Long, startedAt: Long): ElmResponse {
        val echo = cmd.uppercase().replace(" ", "")
        val lines = ArrayList<String>()
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawPrompt = false

        while (true) {
            if (!transport.isConnected()) break
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val slice = minOf(remaining, 250L)
            val chunk = withTimeoutOrNull(slice) { chunks.receive() }
            if (chunk == null) {
                if (!transport.isConnected() || System.currentTimeMillis() >= deadline) break
                continue
            }
            exchangeRecorder?.onChunk(chunk)
            val text = String(chunk, CHARSET)
            collected.append(text)
            lineBuffer.append(text)

            while (true) {
                val idx = indexOfTokenEnd(lineBuffer)
                if (idx < 0) break
                val rawToken = lineBuffer.substring(0, idx)
                lineBuffer.delete(0, idx + 1)
                var line = rawToken.trim { it == '\r' || it == '\n' || it == ' ' || it == '\t' }
                if (line.isEmpty()) continue
                if (line == ">") { sawPrompt = true; break }
                if (line.endsWith(">")) {
                    // prompt glued to the data line: "41 00 BE 3F B8 13>"
                    line = line.dropLast(1).trim { it == '\r' || it == '\n' || it == ' ' || it == '\t' }
                    sawPrompt = true
                    if (line.isNotEmpty() && line.uppercase().replace(" ", "") != echo) lines.add(line)
                    break
                }
                if (line.uppercase().replace(" ", "") == echo) continue // echoed command
                lines.add(line)
            }
            if (sawPrompt) break
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val raw = collected.toString()

        if (!sawPrompt) {
            log.log(RawLog.Dir.ERROR, "timeout ${elapsed}ms, partial: ${raw.take(120)}")
            return ElmResponse.Timeout(raw.trim(), elapsed)
        }

        val meaningful = ArrayList<String>(lines.size)
        var searching = false
        for (l in lines) {
            when (val kind = classifyLine(l)) {
                null -> meaningful.add(l)
                ElmResponse.AdapterMessage.Kind.SEARCHING -> searching = true
                ElmResponse.AdapterMessage.Kind.OK_ONLY -> meaningful.add(l)
                else -> {
                    log.log(RawLog.Dir.RX, l, elapsed)
                    return ElmResponse.AdapterMessage(kind, l, elapsed)
                }
            }
        }
        log.log(RawLog.Dir.RX, meaningful.joinToString(" | "), elapsed)
        if (meaningful.isEmpty() && searching) {
            return ElmResponse.Timeout(raw.trim(), elapsed)
        }
        return ElmResponse.Ok(meaningful, elapsed)
    }

    /** Index of CR/LF or of the '>' prompt (inclusive). */
    private fun indexOfTokenEnd(sb: StringBuilder): Int {
        for (i in 0 until sb.length) {
            val c = sb[i]
            if (c == '\r' || c == '\n') return i
            if (c == '>') return i + 1
        }
        return -1
    }

    private fun drainPending() {
        while (true) chunks.tryReceive().getOrNull() ?: break
        lineBuffer.setLength(0)
    }

    suspend fun shutdown() {
        collectorJob?.cancel()
        collectorJob = null
        collectorStarted = false
        drainPending()
        transport.disconnect()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 3000
        private val CHARSET: Charset = Charsets.US_ASCII

        /** Parses "12.34V" from ATRV output; null when unreadable. */
        fun parseVoltage(line: String?): Float? {
            val m = Regex("([0-9]+[.,][0-9]+)").find(line ?: return null) ?: return null
            return m.groupValues[1].replace(',', '.').toFloatOrNull()
        }

        /** Parses ATDPN output such as "6", "A6", "AA" (protocols 1..C) into Int. */
        fun parseProtocolNumber(line: String?): Int? {
            val clean = line?.trim()?.uppercase()?.removePrefix("A") ?: return null
            if (clean.isEmpty()) return null
            return clean.toIntOrNull(16)
        }

        /** Infers whether the active ELM327 protocol is CAN (ISO 15765-4, protocols 6..C). */
        fun inferIsCanProtocol(protocolText: String?, protocolNumber: Int?): Boolean? {
            if (protocolNumber != null && protocolNumber in 1..12) {
                return protocolNumber in 6..12
            }
            val u = protocolText?.uppercase() ?: return null
            return when {
                u.contains("15765") || u.contains("CAN") -> true
                u.contains("9141") || u.contains("14230") || u.contains("KWP") || u.contains("J1850") -> false
                else -> null
            }
        }
    }
}
