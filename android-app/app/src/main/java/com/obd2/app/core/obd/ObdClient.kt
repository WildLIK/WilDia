package com.obd2.app.core.obd

import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.ElmResponse
import java.util.SortedSet

/**
 * High-level OBD-II operations (Modes 01/03/07/09/0A). Read-only.
 *
 * Requests use the functional address implicitly (ELM327 ATSP auto/6), i.e.
 * plain "01 xx"/"03"/"09 02" commands — nothing is ever written to the bus.
 */
class ObdClient(private val elm: Elm327Client) {

    /** Reads one PID and decodes it using the definition. */
    suspend fun readPid(def: PidDefinition, timeoutMs: Long = 3000): PidResult {
        val resp = elm.command(def.command, timeoutMs)
        return when (resp) {
            is ElmResponse.Ok -> decodeResponse(def, resp.lines, resp.durationMs)
            is ElmResponse.AdapterMessage -> when (resp.kind) {
                ElmResponse.AdapterMessage.Kind.NO_DATA ->
                    PidResult.Unsupported(def.mode, def.pid, "NO DATA — ЭБУ не отвечает на этот PID")
                ElmResponse.AdapterMessage.Kind.UNABLE_TO_CONNECT ->
                    PidResult.Error(def.mode, def.pid, "BUS", resp.text)
                ElmResponse.AdapterMessage.Kind.STOPPED ->
                    PidResult.Error(def.mode, def.pid, "STOPPED", resp.text)
                else -> PidResult.Error(def.mode, def.pid, resp.kind.name, resp.text)
            }
            is ElmResponse.Timeout ->
                PidResult.Error(def.mode, def.pid, "TIMEOUT", "Таймаут ответа", resp.partial)
        }
    }

    /** Raw read used by "Все параметры" — returns raw lines without decoding. */
    suspend fun readRaw(command: String, timeoutMs: Long = 3000): ElmResponse =
        elm.command(command, timeoutMs)

    fun decodeResponse(def: PidDefinition, lines: List<String>, durationMs: Long): PidResult {
        return try {
            when (def.mode) {
                1 -> decodeMode01(def, lines, durationMs)
                9 -> decodeMode09(def, lines, durationMs)
                else -> PidResult.Error(def.mode, def.pid, "MODE", "Режим не поддерживается: ${def.mode}")
            }
        } catch (e: Exception) {
            PidResult.Error(def.mode, def.pid, "DECODE", e.message ?: "ошибка декодирования")
        }
    }

    private fun decodeMode01(def: PidDefinition, lines: List<String>, durationMs: Long): PidResult {
        val header = 0x40 + def.mode // 0x41
        val payloads = ArrayList<ByteArray>()
        var negativeNrc: Int? = null
        for (line in lines) {
            val rawTokens = DtcParser.parseHexTokens(line)
            if (rawTokens.isEmpty()) continue
            // Handle optional 3-char CAN header ("7E8") + PCI byte ("04") if ATH1 was active
            val tokens = stripOptionalCanHeader(line, rawTokens, expectedSid = header, expectedMode = def.mode)
            if (tokens.size >= 3 && tokens[0] == 0x7F && tokens[1] == def.mode) {
                negativeNrc = tokens[2]
                continue
            }
            if (tokens.size < 2) continue
            if (tokens[0] != header || tokens[1] != def.pid) continue
            val bytes = tokens.drop(2).map { it.toByte() }.toByteArray()
            if (bytes.isNotEmpty()) payloads.add(bytes)
        }
        if (payloads.isEmpty() && negativeNrc != null) {
            return if (negativeNrc in setOf(0x11, 0x12, 0x31)) {
                PidResult.Unsupported(def.mode, def.pid, "7F 01 %02X — не поддерживается ЭБУ".format(negativeNrc))
            } else {
                PidResult.Error(def.mode, def.pid, "NRC", "Отрицательный ответ ЭБУ 7F 01 %02X".format(negativeNrc), lines.joinToString(" | "))
            }
        }
        if (payloads.isEmpty()) {
            return PidResult.Error(def.mode, def.pid, "PARSE", "Нет корректных данных 41 ${def.hexPid}", lines.joinToString(" | "))
        }
        val data = payloads.first()
        val reading = when (def.decoder) {
            "numeric" -> {
                val need = when (def.weight) {
                    "A" -> 1; "AB" -> 2; "ABC" -> 3; "ABCD" -> 4; else -> 1
                }
                if (data.size < need) {
                    return PidResult.Error(def.mode, def.pid, "DECODE", "Мало байт: ${data.size} < $need")
                }
                PidReading(
                    def.mode, def.pid, value = PidDecoder.numeric(def, data),
                    unit = def.unit, rawHex = data.joinToString(" ") { "%02X".format(it) },
                    ecuResponses = payloads.size, durationMs = durationMs,
                )
            }
            "bitmap" -> {
                if (data.size < 4) {
                    return PidResult.Error(def.mode, def.pid, "DECODE", "Мало байт маски: ${data.size} < 4")
                }
                // Multiple ECUs may answer the same mask; union their bits.
                val supported = payloads.filter { it.size >= 4 }.flatMap { PidDecoder.supportMask(def.pid, it) }.distinct().sorted()
                PidReading(
                    def.mode, def.pid, value = null,
                    text = supported.joinToString(",") { "%02X".format(it) },
                    rawHex = payloads.joinToString(" | ") { p -> p.joinToString(" ") { "%02X".format(it) } },
                    ecuResponses = payloads.size, durationMs = durationMs,
                )
            }
            "status" -> {
                val st = PidDecoder.monitorStatus(data)
                PidReading(
                    def.mode, def.pid, value = st.dtcCount.toDouble(),
                    text = (if (st.milOn) "MIL: ВКЛ" else "MIL: ВЫКЛ") +
                        (if (st.readiness.isNotEmpty()) "; готовность: " + st.readiness.joinToString(",") else ""),
                    rawHex = data.joinToString(" ") { "%02X".format(it) },
                    ecuResponses = payloads.size, durationMs = durationMs,
                )
            }
            "freeze_dtc" -> {
                val code = DtcParser.bytesToCode(data[0].toInt() and 0xFF, data.getOrNull(1)?.toInt()?.and(0xFF) ?: 0)
                PidReading(
                    def.mode, def.pid, value = null,
                    text = code ?: "нет сохранённого кода",
                    rawHex = data.joinToString(" ") { "%02X".format(it) },
                    durationMs = durationMs,
                )
            }
            "fuel_status" -> PidReading(
                def.mode, def.pid, value = null,
                text = PidDecoder.fuelSystemStatus(data),
                rawHex = data.joinToString(" ") { "%02X".format(it) },
                durationMs = durationMs,
            )
            "trim" -> {
                val v = ((data[0].toInt() and 0xFF) - 128) * 100.0 / 128.0
                PidReading(def.mode, def.pid, value = v, unit = def.unit,
                    rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs)
            }
            "o2_voltage" -> {
                val v = (data[0].toInt() and 0xFF) / 200.0
                val trimByte = data.getOrNull(1)?.toInt()?.and(0xFF)
                val t = if (trimByte != null && trimByte != 0xFF) (trimByte - 128) * 100.0 / 128.0 else null
                val sensor = def.pid - 0x13 // 14..1B -> 1..8
                val bank = if (sensor <= 4) 1 else 2
                val sIdx = if (sensor <= 4) sensor else sensor - 4
                PidReading(
                    def.mode, def.pid, value = v, unit = "В", value2 = t, unit2 = if (t != null) "%" else null,
                    text = "ДК банк $bank датчик $sIdx",
                    rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
                )
            }
            "obd_std" -> PidReading(
                def.mode, def.pid, value = null,
                text = PidDecoder.obdStandard(data[0].toInt() and 0xFF),
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
            "aux" -> PidReading(
                def.mode, def.pid, value = null,
                text = if ((data[0].toInt() and 0x01) != 0) "PTO активен" else "PTO не активен",
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
            "o2_lambda_v" -> {
                if (data.size < 4) {
                    return PidResult.Error(def.mode, def.pid, "DECODE", "Мало байт: ${data.size} < 4")
                }
                val lambda = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) * 2.0 / 65536.0
                val volt = (((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)) * 8.0 / 65536.0
                PidReading(def.mode, def.pid, value = lambda, unit = "λ", value2 = volt, unit2 = "В",
                    rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs)
            }
            "o2_lambda_i" -> {
                if (data.size < 4) {
                    return PidResult.Error(def.mode, def.pid, "DECODE", "Мало байт: ${data.size} < 4")
                }
                val lambda = (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) * 2.0 / 65536.0
                val current = ((((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)) / 256.0) - 128.0
                PidReading(def.mode, def.pid, value = lambda, unit = "λ", value2 = current, unit2 = "мА",
                    rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs)
            }
            "air_status" -> PidReading(
                def.mode, def.pid, value = null,
                text = PidDecoder.airStatus(data[0].toInt() and 0xFF),
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
            "o2_present" -> PidReading(
                def.mode, def.pid, value = null,
                text = PidDecoder.o2Present2Banks(data),
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
            "o2_present_4" -> PidReading(
                def.mode, def.pid, value = null,
                text = PidDecoder.o2Present4Banks(data),
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
            "fuel_type" -> PidReading(
                def.mode, def.pid, value = null,
                text = PidDecoder.fuelType(data[0].toInt() and 0xFF),
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
            else -> PidReading(
                def.mode, def.pid, value = null,
                text = null,
                rawHex = data.joinToString(" ") { "%02X".format(it) }, durationMs = durationMs,
            )
        }
        return PidResult.Ok(reading)
    }

    private fun stripOptionalCanHeader(
        rawLine: String,
        bytes: List<Int>,
        expectedSid: Int,
        expectedMode: Int,
    ): List<Int> {
        val trimmed = rawLine.trim().uppercase()
        val firstTok = trimmed.split(' ', '\t').firstOrNull { it.isNotBlank() } ?: return bytes
        if ((firstTok.length == 3 || firstTok.length == 8) && firstTok.all { it in '0'..'9' || it in 'A'..'F' }) {
            // Line starts with CAN ID; next byte is ISO-TP single-frame PCI 0x0N
            if (bytes.size >= 2 && (bytes[0] and 0xF0) == 0x00) {
                val len = bytes[0] and 0x0F
                val body = bytes.drop(1).take(len)
                if (body.firstOrNull() == expectedSid || (body.firstOrNull() == 0x7F && body.getOrNull(1) == expectedMode)) {
                    return body
                }
            }
        }
        return bytes
    }

    private fun decodeMode09(def: PidDefinition, lines: List<String>, durationMs: Long): PidResult {
        val raw = lines.joinToString(" | ")
        val reading = when (def.decoder) {
            "vin" -> {
                val vin = Mode09Parser.extractVin(lines)
                    ?: return PidResult.Error(def.mode, def.pid, "PARSE", "VIN не найден в ответе", raw)
                PidReading(def.mode, def.pid, value = null, text = vin,
                    rawHex = raw, durationMs = durationMs)
            }
            "calid" -> {
                val id = Mode09Parser.extractAscii(lines, "04")
                    ?: return PidResult.Error(def.mode, def.pid, "PARSE", "Calibration ID не найден", raw)
                PidReading(def.mode, def.pid, value = null, text = id, rawHex = raw, durationMs = durationMs)
            }
            "ecu_name" -> {
                val name = Mode09Parser.extractAscii(lines, "0A")
                    ?: return PidResult.Error(def.mode, def.pid, "PARSE", "Имя ЭБУ не найдено", raw)
                PidReading(def.mode, def.pid, value = null, text = name, rawHex = raw, durationMs = durationMs)
            }
            "cvn" -> PidReading(def.mode, def.pid, value = null,
                text = lines.firstOrNull(), rawHex = raw, durationMs = durationMs)
            else -> PidReading(def.mode, def.pid, value = null, text = lines.firstOrNull(),
                rawHex = raw, durationMs = durationMs)
        }
        return PidResult.Ok(reading)
    }

    /** Reads stored (03) / pending (07) / permanent (0A) DTCs. */
    suspend fun readDtc(mode: Int, timeoutMs: Long = 5000): DtcReadResult {
        val header = "%02X".format(0x40 + mode)
        val resp = elm.command("%02X".format(mode), timeoutMs)
        return when (resp) {
            is ElmResponse.Ok -> {
                val parsed = DtcParser.parseDtcResponse(
                    lines = resp.lines,
                    mode = mode,
                    isCanProtocol = elm.info.value.isCanProtocol,
                )
                if (parsed.dtcs.isEmpty() && parsed.ecuCount == 0 && parsed.negativeResponses.isNotEmpty()) {
                    val negText = parsed.negativeResponses.joinToString("; ") { it.describeRu() }
                    DtcReadResult.Unsupported(negText, resp.lines, resp.durationMs)
                } else if (parsed.dtcs.isEmpty() && parsed.ecuCount == 0) {
                    val reason = parsed.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
                        ?: "Ответ ЭБУ не содержит подтверждённого кадра $header"
                    DtcReadResult.Error("PARSE_ERROR", reason, resp.lines)
                } else {
                    DtcReadResult.Ok(
                        dtcs = parsed.dtcs.distinctBy { it.code },
                        rawLines = resp.lines,
                        durationMs = resp.durationMs,
                        warnings = parsed.warnings + parsed.negativeResponses.map { it.describeRu() },
                    )
                }
            }
            is ElmResponse.AdapterMessage -> when (resp.kind) {
                ElmResponse.AdapterMessage.Kind.NO_DATA ->
                    DtcReadResult.Error("NO_DATA", "NO DATA (нет ответа ЭБУ на Mode ${"%02X".format(mode)})", listOf(resp.text))
                ElmResponse.AdapterMessage.Kind.UNABLE_TO_CONNECT ->
                    DtcReadResult.Error("UNABLE_TO_CONNECT", resp.text, listOf(resp.text))
                else -> DtcReadResult.Error(resp.kind.name, resp.text, listOf(resp.text))
            }
            is ElmResponse.Timeout -> DtcReadResult.Error("TIMEOUT", "Таймаут чтения ошибок ($header)")
        }
    }

    sealed interface DtcReadResult {
        data class Ok(
            val dtcs: List<DtcEntry>,
            val rawLines: List<String>,
            val durationMs: Long,
            val warnings: List<String> = emptyList(),
        ) : DtcReadResult

        data class Unsupported(
            val reason: String,
            val rawLines: List<String>,
            val durationMs: Long,
        ) : DtcReadResult

        data class Error(
            val kind: String,
            val message: String,
            val rawLines: List<String> = emptyList(),
        ) : DtcReadResult
    }

    /**
     * Discovers supported Mode-01 PIDs via support bitmasks (0100/0120/0140/0160,
     * plus 0180/01A0/01C0 when chained by bit 32).
     * Reports per-mask failures instead of failing wholesale.
     */
    suspend fun discoverSupportedPids(
        onProgress: (stage: String) -> Unit = {},
    ): PidSupportResult {
        val supported = mutableSetOf<Int>()
        val errors = ArrayList<String>()
        val bitmapPids = mutableListOf(0x00, 0x20, 0x40, 0x60)
        var idx = 0
        while (idx < bitmapPids.size) {
            val base = bitmapPids[idx++]
            onProgress("Опрос поддержки 01 %02X".format(base))
            val def = PidDefinition(
                mode = 1, pid = base, nameEn = "PIDs supported", nameRu = null, unit = null,
                bytes = 4, decoder = "bitmap", weight = null, mult = null, offset = null,
                formula = null, category = "status",
            )
            when (val r = readPid(def, timeoutMs = 5000)) {
                is PidResult.Ok -> {
                    val list = r.reading.text?.split(",")?.mapNotNull { it.toIntOrNull(16) }.orEmpty()
                    supported.addAll(list)
                    val nextBase = base + 0x20
                    if (nextBase <= 0xC0 && nextBase in list && nextBase !in bitmapPids) {
                        bitmapPids.add(nextBase)
                    }
                }
                is PidResult.Unsupported -> errors.add("01 %02X: NO DATA".format(base))
                is PidResult.Error -> errors.add("01 %02X: ${r.kind}".format(base))
            }
        }
        return PidSupportResult(supported.toSortedSet(), errors)
    }

    data class PidSupportResult(
        val supportedPids: SortedSet<Int>,
        val maskErrors: List<String>,
    )

    /**
     * Reads Freeze Frame (Mode 02) for [frameIndex].
     * Starts with 0202{frame} (trigger DTC); if a trigger DTC is present, queries supported 0200 mask or core PIDs.
     */
    suspend fun readFreezeFrame(
        frameIndex: Int = 0,
        registry: List<PidDefinition> = emptyList(),
        timeoutMs: Long = 3000,
    ): FreezeFrameReport {
        val frameHex = "%02X".format(frameIndex)
        val dtcResp = elm.command("0202$frameHex", timeoutMs)
        val rawAll = ArrayList<String>()
        if (dtcResp is ElmResponse.AdapterMessage && dtcResp.kind == ElmResponse.AdapterMessage.Kind.NO_DATA) {
            return FreezeFrameReport(
                frameIndex = frameIndex,
                unsupportedReason = "Стоп-кадр (Mode 02) пуст или не поддерживается ЭБУ",
            )
        }
        val triggerDtc = when (dtcResp) {
            is ElmResponse.Ok -> {
                rawAll.addAll(dtcResp.lines)
                val payload = StandardObdParsers.parseMode02Payload(dtcResp.lines, 0x02, frameIndex)
                if (payload != null && payload.size >= 2) {
                    DtcParser.bytesToCode(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
                } else {
                    null
                }
            }
            is ElmResponse.AdapterMessage -> {
                return FreezeFrameReport(frameIndex = frameIndex, unsupportedReason = dtcResp.text)
            }
            is ElmResponse.Timeout -> {
                return FreezeFrameReport(frameIndex = frameIndex, unsupportedReason = "Таймаут чтения Mode 02")
            }
        }

        // Probe Mode 02 supported PIDs (0200) if available, otherwise fall back to safe core freeze-frame PIDs
        val maskResp = elm.command("0200$frameHex", timeoutMs)
        val supportedFfPids = if (maskResp is ElmResponse.Ok) {
            rawAll.addAll(maskResp.lines)
            val maskPayload = StandardObdParsers.parseMode02Payload(maskResp.lines, 0x00, frameIndex)
            if (maskPayload != null && maskPayload.size >= 4) {
                PidDecoder.supportMask(0x00, maskPayload)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val candidatePids = if (supportedFfPids.isNotEmpty()) {
            supportedFfPids.filter { it != 0x00 && it != 0x02 && it != 0x20 }.take(12)
        } else {
            listOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x11)
        }

        val items = ArrayList<FreezeFrameItem>()
        for (pid in candidatePids) {
            val resp = elm.command("02%02X%02X".format(pid, frameIndex), timeoutMs)
            if (resp is ElmResponse.Ok) {
                rawAll.addAll(resp.lines)
                val payload = StandardObdParsers.parseMode02Payload(resp.lines, pid, frameIndex) ?: continue
                val def = registry.firstOrNull { it.mode == 1 && it.pid == pid }
                val valueText = if (def != null && def.decoder == "numeric") {
                    val v = runCatching { PidDecoder.numeric(def, payload) }.getOrNull()
                    if (v != null) {
                        val fmt = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
                        listOfNotNull(fmt, def.unit).joinToString(" ")
                    } else {
                        payload.joinToString(" ") { "%02X".format(it) }
                    }
                } else {
                    payload.joinToString(" ") { "%02X".format(it) }
                }
                items += FreezeFrameItem(
                    pid = pid,
                    pidHex = "%02X".format(pid),
                    nameRu = def?.displayName ?: "PID %02X".format(pid),
                    valueText = valueText,
                    unit = def?.unit,
                    rawHex = payload.joinToString(" ") { "%02X".format(it) },
                )
            }
        }

        return FreezeFrameReport(
            frameIndex = frameIndex,
            triggerDtc = triggerDtc,
            items = items,
            supportedPids = supportedFfPids,
            unsupportedReason = if (triggerDtc == null && items.isEmpty()) "Стоп-кадр не зафиксирован (0000)" else null,
            rawLines = rawAll,
        )
    }

    /** Reads I/M Readiness status via 0101 (since DTCs cleared). */
    suspend fun readImReadiness(timeoutMs: Long = 3000): ImReadinessReport? {
        val resp = elm.command("0101", timeoutMs)
        if (resp !is ElmResponse.Ok) return null
        for (line in resp.lines) {
            val tokens = StandardObdParsers.stripOptionalHeader(
                line, DtcParser.parseHexTokens(line), expectedSid = 0x41, expectedMode = 0x01,
            )
            if (tokens.size >= 6 && tokens[0] == 0x41 && tokens[1] == 0x01) {
                val payload = tokens.subList(2, 6).map { it.toByte() }.toByteArray()
                return StandardObdParsers.parseImReadiness(payload, "0101")
            }
        }
        return null
    }

    /** Reads Mode 06 supported MIDs (0600..) and test results for non-zero MIDs. */
    suspend fun readMode06(timeoutMs: Long = 4000): Mode06Report {
        val maskResp = elm.command("0600", timeoutMs)
        if (maskResp is ElmResponse.AdapterMessage) {
            return Mode06Report(unsupportedReason = "Mode 06 не поддерживается: ${maskResp.text}")
        }
        if (maskResp is ElmResponse.Timeout) {
            return Mode06Report(unsupportedReason = "Таймаут опроса Mode 06 (0600)")
        }
        val okMask = maskResp as ElmResponse.Ok
        val supported = StandardObdParsers.parseMode06Mask(okMask.lines, 0x00).toMutableList()
        if (0x20 in supported) {
            val r20 = elm.command("0620", timeoutMs)
            if (r20 is ElmResponse.Ok) supported += StandardObdParsers.parseMode06Mask(r20.lines, 0x20)
        }
        val queryMids = supported.filter { it !in setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0) }.distinct().take(12)
        if (queryMids.isEmpty()) {
            return Mode06Report(
                supportedMids = supported.distinct().sorted(),
                unsupportedReason = "Нет доступных мониторов Mode 06",
            )
        }
        val allResults = ArrayList<Mode06TestResult>()
        val warnings = ArrayList<String>()
        for (mid in queryMids) {
            val r = elm.command("06%02X".format(mid), timeoutMs)
            if (r is ElmResponse.Ok) {
                val (parsed, warns) = StandardObdParsers.parseMode06Results(r.lines, mid)
                allResults += parsed
                warnings += warns
            }
        }
        return Mode06Report(
            supportedMids = supported.distinct().sorted(),
            results = allResults,
            warnings = warnings.distinct(),
        )
    }

    /** Multi-stage, read-only VIN & per-ECU identification with full diagnostic trace. */
    suspend fun identifyVehicleDetailed(activeProfileKey: String? = null): VinIdentificationResult =
        VinIdentificationEngine.identify(elm, activeProfileKey)

    /** Reads Mode 09 standard identifiers (supported bitmap, VIN, CalID, CVN, ECU Name). */
    suspend fun readStandardIdentifiers(timeoutMs: Long = 3000): StandardIdentifiers {
        val supResp = elm.command("0900", timeoutMs)
        val supported = if (supResp is ElmResponse.Ok) {
            val lineTokens = supResp.lines.map {
                StandardObdParsers.stripOptionalHeader(it, DtcParser.parseHexTokens(it), 0x49, 0x09)
            }.firstOrNull { it.size >= 6 && it[0] == 0x49 && it[1] == 0x00 }
            if (lineTokens != null) {
                PidDecoder.supportMask(0x00, lineTokens.subList(2, 6).map { it.toByte() }.toByteArray())
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        val detailed = identifyVehicleDetailed()
        val cvn = if (0x06 in supported) {
            (elm.command("0906", timeoutMs) as? ElmResponse.Ok)?.lines?.joinToString(" | ")
        } else null

        return StandardIdentifiers(
            supportedInfoTypes = supported,
            vin = detailed.vin,
            calIds = detailed.allCalIds,
            cvns = listOfNotNull(cvn?.takeIf { it.isNotBlank() }),
            ecuName = detailed.ecuNamesByEcu.values.joinToString(" | ").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Guarded Mode 04 clear entry point.
     * In v0.5.0 Stage A, `realTransmissionEnabled` defaults to false, returning a safe dry-run result
     * even after all preconditions pass, unless explicitly unlocked for validated builds.
     */
    suspend fun clearDtcsGuarded(precheck: ClearDtcPrecheck, timeoutMs: Long = 4000): ClearDtcResult {
        val blocked = ClearDtcGuard.validate(precheck)
        if (blocked.isNotEmpty()) {
            return ClearDtcResult.Blocked(blocked)
        }
        if (!precheck.realTransmissionEnabled) {
            return ClearDtcResult.DryRunSimulated(
                summary = "Тестовый режим защиты: все предварительные проверки пройдены, но реальная отправка Mode 04 заблокирована в текущей сборке безопасности.",
                warnings = ClearDtcGuard.requiredWarningsRu,
            )
        }
        return when (val resp = elm.command("04", timeoutMs)) {
            is ElmResponse.Ok -> ClearDtcResult.SentOk(resp.lines)
            is ElmResponse.AdapterMessage -> ClearDtcResult.Failed(resp.text)
            is ElmResponse.Timeout -> ClearDtcResult.Failed("Таймаут выполнения Mode 04")
        }
    }
}
