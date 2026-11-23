package com.obd2.app.core.obd

import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.ElmResponse
import com.obd2.app.core.log.RawLog

/** One recorded step in the read-only VIN / ECU identification sequence. */
data class VinProbeStep(
    val timestampMs: Long,
    val stage: String,
    val protocol: String?,
    val targetEcu: String,
    val command: String,
    val rawLines: List<String>,
    val outcome: String,
    val failureReason: String? = null,
)

/** Full result + audit trace of multi-stage VIN & ECU identification. */
data class VinIdentificationResult(
    val vin: String?,
    val vinSource: String?,
    val calIdsByEcu: Map<String, String>,
    val ecuNamesByEcu: Map<String, String>,
    val partNumbersByEcu: Map<String, String>,
    val protocolText: String?,
    val protocolNumber: String?,
    val discoveredEcuHeaders: List<String>,
    val steps: List<VinProbeStep>,
    val summaryRu: String,
) {
    val allCalIds: List<String>
        get() = calIdsByEcu.values.filter { it.isNotBlank() }.distinct()
}

/**
 * Strictly read-only, multi-stage VIN and ECU identifier for ELM327 adapters.
 *
 * Why a staged approach is needed (e.g. on Škoda Octavia II 1Z / VAG PQ35):
 * 1. Functional `0902` with `ATH0` interleaves Engine (`7E8`) and TCM (`7E9`) ISO-TP frames
 *    or lets a `7F 09 xx` NRC from a secondary ECU break single-ECU decoding.
 * 2. Enabling `ATH1` temporarily cleanly separates `7E8` and `7E9`.
 * 3. If functional `0902` fails/times out/returns NRC, direct CAN addressing (`ATSH 7E0` / `ATCRA 7E8`)
 *    isolates the Engine ECM.
 * 4. If Mode 09 VIN is still unavailable on CAN, documented UDS `22 F1 90` (ISO 14229 VIN DID)
 *    is queried on Engine (`7E0`->`7E8`), Instrument Cluster (`714`->`77E`), and Gateway (`710`->`77A`).
 * 5. On KWP2000 buses (`ATDPN` 4/5), documented KWP `1A 90` is queried as fallback.
 *
 * Always restores `ATH0`, `ATCRA`, and `ATSH 7DF` in `finally`. Never substitutes profile/demo VINs.
 */
object VinIdentificationEngine {

    private val headerRegex = Regex("^([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\\s+.*$")

    private data class UdsVinTarget(
        val name: String,
        val txHeader: String,
        val rxFilter: String,
    )

    private val vagAndObdUdsTargets = listOf(
        UdsVinTarget("Двигатель ECM (01 · 7E0)", "7E0", "7E8"),
        UdsVinTarget("Панель приборов VAG (17 · 714)", "714", "77E"),
        UdsVinTarget("CAN-шлюз VAG (19 · 710)", "710", "77A"),
    )

    suspend fun identify(
        elm: Elm327Client,
        activeProfileKey: String? = null,
    ): VinIdentificationResult {
        val steps = mutableListOf<VinProbeStep>()
        val discoveredHeaders = linkedSetOf<String>()
        val calIdsByEcu = linkedMapOf<String, String>()
        val ecuNamesByEcu = linkedMapOf<String, String>()
        val partNumbersByEcu = linkedMapOf<String, String>()

        val protocolText = querySimpleAt(elm, "ATDP")
        val protocolNumber = querySimpleAt(elm, "ATDPN")
        val isCan = isCanProtocol(protocolText, protocolNumber)
        val isKwp = isKwpProtocol(protocolText, protocolNumber)

        var foundVin: String? = null
        var vinSource: String? = null

        try {
            if (isCan) {
                elm.command("ATH1", 1500)
            }

            // Stage 1: Standard functional Mode 09 PID 02 (with ATH1 on CAN so Engine & TCM never mix)
            val s1Resp = elm.command("0902", 4500)
            collectHeaders(s1Resp, discoveredHeaders)
            val s1Vin = (s1Resp as? ElmResponse.Ok)?.let { Mode09Parser.extractVin(it.lines) }
            steps += buildStep(
                stage = "1. Функциональный OBD-II 0902 (${if (isCan) "ATH1" else "ATH0"})",
                protocol = protocolText,
                targetEcu = "7DF (broadcast)",
                command = "0902",
                resp = s1Resp,
                success = s1Vin != null,
                successNote = "VIN считан (0902)",
            )
            if (s1Vin != null) {
                foundVin = s1Vin
                vinSource = "OBD-II Mode 09 PID 02 (функциональный запрос)"
            }

            // Stage 2: Direct CAN addressing to Engine ECM (7E0 -> 7E8) for 0902
            if (foundVin == null && isCan) {
                try {
                    elm.command("ATSH 7E0", 1500)
                    elm.command("ATCRA 7E8", 1500)
                    val s2Resp = elm.command("0902", 4500)
                    collectHeaders(s2Resp, discoveredHeaders)
                    val s2Vin = (s2Resp as? ElmResponse.Ok)?.let { Mode09Parser.extractVin(it.lines) }
                    steps += buildStep(
                        stage = "2. Прямой CAN-запрос ЭБУ двигателя (7E0 -> 7E8)",
                        protocol = protocolText,
                        targetEcu = "7E0/7E8",
                        command = "0902",
                        resp = s2Resp,
                        success = s2Vin != null,
                        successNote = "VIN считан напрямую с 7E8 (0902)",
                    )
                    if (s2Vin != null) {
                        foundVin = s2Vin
                        vinSource = "OBD-II Mode 09 PID 02 (адресно 7E0 -> 7E8)"
                    }
                } finally {
                    restoreCanAddressing(elm)
                }
            }

            // Stage 3: Documented UDS 22 F1 90 (ISO 14229 VIN DID) on Engine / VAG Cluster / Gateway
            if (foundVin == null && isCan) {
                for (target in vagAndObdUdsTargets) {
                    if (foundVin != null) break
                    try {
                        elm.command("ATSH ${target.txHeader}", 1500)
                        elm.command("ATCRA ${target.rxFilter}", 1500)
                        val udsResp = elm.command("22F190", 3500)
                        collectHeaders(udsResp, discoveredHeaders)
                        val udsVin = (udsResp as? ElmResponse.Ok)?.let {
                            Mode09Parser.extractVinFromUds22F190(it.lines)
                        }
                        steps += buildStep(
                            stage = "3. UDS 22 F1 90 (${target.name})",
                            protocol = protocolText,
                            targetEcu = "${target.txHeader}/${target.rxFilter}",
                            command = "22F190",
                            resp = udsResp,
                            success = udsVin != null,
                            successNote = "VIN считан по UDS DID F190 (${target.txHeader})",
                        )
                        if (udsVin != null) {
                            foundVin = udsVin
                            vinSource = "UDS 22 F1 90 (${target.name})"
                        }
                        // Also read optional VAG Part Number (22 F1 87) when target answered UDS
                        if (udsVin != null || (udsResp is ElmResponse.Ok && Mode09Parser.extractNrc(udsResp.lines) == null)) {
                            val pnResp = elm.command("22F187", 2500)
                            if (pnResp is ElmResponse.Ok) {
                                Mode09Parser.extractAsciiFromUds22(pnResp.lines, 0xF1, 0x87)?.let { pn ->
                                    partNumbersByEcu[target.rxFilter] = pn
                                }
                            }
                        }
                    } finally {
                        restoreCanAddressing(elm)
                    }
                }
            }

            // Stage 4: Documented KWP2000 1A 90 fallback when active bus is KWP2000
            if (foundVin == null && isKwp) {
                val kwpResp = elm.command("1A90", 4000)
                val kwpVin = (kwpResp as? ElmResponse.Ok)?.let { Mode09Parser.extractVinFromKwp1A90(it.lines) }
                steps += buildStep(
                    stage = "4. KWP2000 1A 90 (идентификация VIN)",
                    protocol = protocolText,
                    targetEcu = "KWP ECU",
                    command = "1A90",
                    resp = kwpResp,
                    success = kwpVin != null,
                    successNote = "VIN считан по KWP2000 (1A 90)",
                )
                if (kwpVin != null) {
                    foundVin = kwpVin
                    vinSource = "KWP2000 1A 90"
                }
            }

            // Stage 5: Read CalID (0904) and ECU Name (090A) with ATH1 ON so Engine (7E8) and TCM (7E9) stay separated
            val calResp = elm.command("0904", 3500)
            collectHeaders(calResp, discoveredHeaders)
            if (calResp is ElmResponse.Ok) {
                val perEcu = Mode09Parser.parseMultiEcuAscii(calResp.lines, "04")
                perEcu.forEachIndexed { idx, item ->
                    val key = item.ecuHeader ?: "ECU-${idx + 1}"
                    calIdsByEcu[key] = item.value
                }
            }
            steps += buildStep(
                stage = "5a. Чтение калибровок ЭБУ (0904)",
                protocol = protocolText,
                targetEcu = "7DF",
                command = "0904",
                resp = calResp,
                success = calIdsByEcu.isNotEmpty(),
                successNote = "Считано CalID: ${calIdsByEcu.entries.joinToString("; ") { "${it.key}=${it.value}" }}",
            )

            val nameResp = elm.command("090A", 3000)
            collectHeaders(nameResp, discoveredHeaders)
            if (nameResp is ElmResponse.Ok) {
                val perEcu = Mode09Parser.parseMultiEcuAscii(nameResp.lines, "0A")
                perEcu.forEachIndexed { idx, item ->
                    val key = item.ecuHeader ?: "ECU-${idx + 1}"
                    ecuNamesByEcu[key] = item.value
                }
            }
            steps += buildStep(
                stage = "5b. Чтение имени ЭБУ (090A)",
                protocol = protocolText,
                targetEcu = "7DF",
                command = "090A",
                resp = nameResp,
                success = ecuNamesByEcu.isNotEmpty(),
                successNote = "Считаны имена ЭБУ: ${ecuNamesByEcu.entries.joinToString("; ") { "${it.key}=${it.value}" }}",
            )
        } finally {
            if (isCan) {
                restoreCanAddressing(elm)
                runCatching { elm.command("ATH0", 1500) }
            }
        }

        val summaryRu = buildString {
            if (foundVin != null) {
                append("VIN определён (${vinSource ?: "OBD"}): ${RawLog.maskVin(foundVin)}")
            } else {
                val reasons = steps.mapNotNull { it.failureReason }.distinct().take(3)
                append("VIN не получен")
                if (!activeProfileKey.isNullOrBlank()) append(" (профиль: $activeProfileKey)")
                if (reasons.isNotEmpty()) append(": ${reasons.joinToString("; ")}")
            }
            if (calIdsByEcu.isNotEmpty()) {
                append(" | ЭБУ CalID: ")
                append(calIdsByEcu.entries.joinToString(", ") { "${it.key}:${it.value}" })
            }
        }

        return VinIdentificationResult(
            vin = foundVin,
            vinSource = vinSource,
            calIdsByEcu = calIdsByEcu,
            ecuNamesByEcu = ecuNamesByEcu,
            partNumbersByEcu = partNumbersByEcu,
            protocolText = protocolText,
            protocolNumber = protocolNumber,
            discoveredEcuHeaders = discoveredHeaders.toList(),
            steps = steps,
            summaryRu = summaryRu,
        )
    }

    /** Formats a human-readable diagnostic log of VIN & ECU identification for export/clipboard. */
    fun formatDiagnosticLog(result: VinIdentificationResult, maskVin: Boolean = true): String {
        val sb = StringBuilder()
        sb.appendLine("=== ДИАГНОСТИЧЕСКИЙ ОТЧЁТ ЧТЕНИЯ VIN И ИДЕНТИФИКАЦИИ ЭБУ ===")
        sb.appendLine("Активный протокол (ATDP): ${result.protocolText ?: "не определён"} (ATDPN=${result.protocolNumber ?: "?"})")
        sb.appendLine(
            "Обнаруженные адреса ЭБУ: " +
                if (result.discoveredEcuHeaders.isEmpty()) "не зафиксированы"
                else result.discoveredEcuHeaders.joinToString(", "),
        )
        val shownVin = when {
            result.vin == null -> "НЕ ПОЛУЧЕН"
            maskVin -> RawLog.maskVin(result.vin)
            else -> result.vin
        }
        sb.appendLine("Итог VIN: $shownVin" + (result.vinSource?.let { " (источник: $it)" } ?: ""))
        if (result.calIdsByEcu.isNotEmpty()) {
            sb.appendLine("Калибровки ЭБУ (раздельно по адресам):")
            result.calIdsByEcu.forEach { (ecu, cal) -> sb.appendLine("  - $ecu: $cal") }
        }
        if (result.ecuNamesByEcu.isNotEmpty()) {
            sb.appendLine("Имена ЭБУ:")
            result.ecuNamesByEcu.forEach { (ecu, name) -> sb.appendLine("  - $ecu: $name") }
        }
        if (result.partNumbersByEcu.isNotEmpty()) {
            sb.appendLine("Номера деталей VAG (22 F1 87):")
            result.partNumbersByEcu.forEach { (ecu, pn) -> sb.appendLine("  - $ecu: $pn") }
        }
        sb.appendLine()
        sb.appendLine("--- Пошаговый журнал запросов ---")
        result.steps.forEachIndexed { idx, step ->
            sb.appendLine("[${idx + 1}] ${step.stage} | ECU=${step.targetEcu} | CMD=${step.command}")
            sb.appendLine("    Результат: ${step.outcome}")
            step.failureReason?.let { sb.appendLine("    Причина/диагностика: $it") }
            if (step.rawLines.isNotEmpty()) {
                sb.appendLine("    Сырой HEX/ответ: ${step.rawLines.joinToString(" | ")}")
            }
        }
        return sb.toString().trimEnd()
    }

    private suspend fun querySimpleAt(elm: Elm327Client, cmd: String): String? =
        when (val r = elm.command(cmd, 1500)) {
            is ElmResponse.Ok -> r.lines.joinToString(" ").trim().takeIf { it.isNotBlank() }
            is ElmResponse.AdapterMessage -> r.text.trim().takeIf { it.isNotBlank() }
            is ElmResponse.Timeout -> null
        }

    private suspend fun restoreCanAddressing(elm: Elm327Client) {
        runCatching { elm.command("ATCRA", 1000) }
        runCatching { elm.command("ATSH 7DF", 1000) }
    }

    private fun isCanProtocol(atdp: String?, atdpn: String?): Boolean {
        val text = atdp.orEmpty().uppercase()
        if (text.contains("CAN") || text.contains("15765")) return true
        val num = atdpn.orEmpty().trim().uppercase().removePrefix("A")
        return num in setOf("6", "7", "8", "9", "A", "B", "C")
    }

    private fun isKwpProtocol(atdp: String?, atdpn: String?): Boolean {
        val text = atdp.orEmpty().uppercase()
        if (text.contains("KWP") || text.contains("14230")) return true
        val num = atdpn.orEmpty().trim().uppercase().removePrefix("A")
        return num in setOf("4", "5")
    }

    private fun collectHeaders(resp: ElmResponse, out: MutableSet<String>) {
        if (resp !is ElmResponse.Ok) return
        for (line in resp.lines) {
            val m = headerRegex.matchEntire(line.trim()) ?: continue
            val hdr = m.groupValues[1].uppercase()
            if (hdr.length == 8 || hdr.startsWith("7")) {
                out.add(hdr)
            }
        }
    }

    private fun buildStep(
        stage: String,
        protocol: String?,
        targetEcu: String,
        command: String,
        resp: ElmResponse,
        success: Boolean,
        successNote: String,
    ): VinProbeStep {
        val raw = when (resp) {
            is ElmResponse.Ok -> resp.lines
            is ElmResponse.AdapterMessage -> listOf(resp.text)
            is ElmResponse.Timeout -> listOf("<TIMEOUT>")
        }
        val failureReason = when {
            success -> null
            resp is ElmResponse.Timeout -> "Таймаут ответа адаптера"
            resp is ElmResponse.AdapterMessage -> "Ответ адаптера: ${resp.text}"
            resp is ElmResponse.Ok -> {
                val nrc = Mode09Parser.extractNrc(resp.lines)
                nrc?.formatShort() ?: "Ответ получен, но валидный идентификатор не выделен"
            }
            else -> "Неизвестная ошибка"
        }
        return VinProbeStep(
            timestampMs = System.currentTimeMillis(),
            stage = stage,
            protocol = protocol,
            targetEcu = targetEcu,
            command = command,
            rawLines = raw,
            outcome = if (success) successNote else "Неудача",
            failureReason = failureReason,
        )
    }
}
