package com.obd2.app.core.obd

import com.obd2.app.core.elm327.ElmResponse
import kotlinx.serialization.Serializable

/**
 * Standard SAE J1979 / ISO 15031-5 OBD-II diagnostics extensions:
 * - Mode 02 (Freeze Frame)
 * - I/M Readiness (`01 01` / `01 41`)
 * - Mode 06 (On-Board Monitoring Test Results)
 * - Mode 09 extended identifiers
 * - Guarded Mode 04 clear infrastructure (dry-run by default)
 */

@Serializable
data class FreezeFrameItem(
    val pid: Int,
    val pidHex: String,
    val nameRu: String,
    val valueText: String,
    val unit: String? = null,
    val rawHex: String,
)

@Serializable
data class FreezeFrameReport(
    val frameIndex: Int = 0,
    val triggerDtc: String? = null,
    val items: List<FreezeFrameItem> = emptyList(),
    val supportedPids: List<Int> = emptyList(),
    val unsupportedReason: String? = null,
    val rawLines: List<String> = emptyList(),
)

@Serializable
data class ReadinessMonitor(
    val id: String,
    val nameRu: String,
    val supported: Boolean,
    val complete: Boolean,
) {
    val statusRu: String
        get() = when {
            !supported -> "Не поддерживается"
            complete -> "Завершён"
            else -> "Не завершён"
        }
}

@Serializable
data class ImReadinessReport(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<ReadinessMonitor>,
    val rawHex: String,
    val modePid: String = "0101",
) {
    val completedCount: Int get() = monitors.count { it.supported && it.complete }
    val supportedCount: Int get() = monitors.count { it.supported }
}

@Serializable
data class Mode06TestResult(
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val nameRu: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    val passed: Boolean,
    val rawHex: String,
)

@Serializable
data class Mode06Report(
    val supportedMids: List<Int> = emptyList(),
    val results: List<Mode06TestResult> = emptyList(),
    val unsupportedReason: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class StandardIdentifiers(
    val supportedInfoTypes: List<Int> = emptyList(),
    val vin: String? = null,
    val calIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
    val ecuName: String? = null,
)

data class ClearDtcPrecheck(
    val dtcSaved: Boolean,
    val freezeFrameSaved: Boolean,
    val connectionStable: Boolean,
    val userConfirmed: Boolean,
    val warningsAcknowledged: Boolean,
    /** Safety interlock: real Mode 04 transmission is disabled by default in v0.5.0 Stage A. */
    val realTransmissionEnabled: Boolean = false,
)

sealed interface ClearDtcResult {
    data class Blocked(val reasons: List<String>) : ClearDtcResult
    data class DryRunSimulated(val summary: String, val warnings: List<String>) : ClearDtcResult
    data class SentOk(val rawLines: List<String>) : ClearDtcResult
    data class Failed(val reason: String) : ClearDtcResult
}

object ClearDtcGuard {
    val requiredWarningsRu: List<String> = listOf(
        "Сброс удалит сохранённые и ожидающие ошибки (DTC) и данные стоп-кадра (Freeze Frame).",
        "Готовность мониторов I/M Readiness будет сброшена в «Не завершён» до завершения ездового цикла.",
        "Сброс ошибок не устраняет техническую неисправность автомобиля.",
    )

    fun validate(precheck: ClearDtcPrecheck): List<String> {
        val reasons = ArrayList<String>()
        if (!precheck.dtcSaved) reasons += "Сначала сохраните текущие коды ошибок (DTC) в историю сеанса."
        if (!precheck.freezeFrameSaved) reasons += "Сначала прочитайте и сохраните доступный стоп-кадр (Mode 02)."
        if (!precheck.connectionStable) reasons += "Соединение с адаптером и ЭБУ нестабильно или отсутствует."
        if (!precheck.warningsAcknowledged) reasons += "Пользователь не ознакомлен с предупреждениями о последствиях сброса."
        if (!precheck.userConfirmed) reasons += "Требуется явное подтверждение пользователя для сброса ошибок."
        return reasons
    }
}

object StandardObdParsers {

    fun parseMode02Payload(lines: List<String>, expectedPid: Int, frameIndex: Int = 0): ByteArray? {
        for (line in lines) {
            val tokens = stripOptionalHeader(line, DtcParser.parseHexTokens(line), expectedSid = 0x42, expectedMode = 0x02)
            if (tokens.size >= 4 && tokens[0] == 0x42 && tokens[1] == expectedPid && tokens[2] == frameIndex) {
                return tokens.drop(3).map { it.toByte() }.toByteArray()
            }
        }
        return null
    }

    fun parseImReadiness(data: ByteArray, modePid: String = "0101"): ImReadinessReport? {
        if (data.size < 4) return null
        val a = data[0].toInt() and 0xFF
        val b = data[1].toInt() and 0xFF
        val c = data[2].toInt() and 0xFF
        val d = data[3].toInt() and 0xFF

        val milOn = (a and 0x80) != 0
        val dtcCount = a and 0x7F
        val compression = (b and 0x08) != 0

        val monitors = ArrayList<ReadinessMonitor>()
        // Common continuous monitors in byte B (bits 0..2 support, bits 4..6 incomplete when 1)
        val continuous = listOf(
            0 to ("misfire" to "Пропуски зажигания"),
            1 to ("fuel" to "Топливная система"),
            2 to ("components" to "Комплексные компоненты"),
        )
        for ((bit, meta) in continuous) {
            val sup = (b and (1 shl bit)) != 0
            val incomplete = (b and (1 shl (bit + 4))) != 0
            monitors += ReadinessMonitor(meta.first, meta.second, sup, sup && !incomplete)
        }

        // Non-continuous monitors in C (supported) and D (1 = incomplete)
        val nonContinuous = if (!compression) {
            listOf(
                0 to ("catalyst" to "Катализатор"),
                1 to ("heated_cat" to "Подогрев катализатора"),
                2 to ("evap" to "Система EVAP"),
                3 to ("sec_air" to "Вторичный воздух"),
                5 to ("o2_sensor" to "Датчик кислорода (O2)"),
                6 to ("o2_heater" to "Подогрев датчика O2"),
                7 to ("egr" to "Система EGR / VVT"),
            )
        } else {
            listOf(
                0 to ("nmhc" to "Катализатор NMHC"),
                1 to ("nox" to "Доочистка NOx / SCR"),
                3 to ("boost" to "Давление наддува"),
                5 to ("exhaust_gas" to "Датчик ОГ"),
                6 to ("pm_filter" to "Сажевый фильтр (DPF)"),
                7 to ("egr" to "Система EGR / VVT"),
            )
        }
        for ((bit, meta) in nonContinuous) {
            val sup = (c and (1 shl bit)) != 0
            val incomplete = (d and (1 shl bit)) != 0
            monitors += ReadinessMonitor(meta.first, meta.second, sup, sup && !incomplete)
        }

        return ImReadinessReport(
            milOn = milOn,
            dtcCount = dtcCount,
            compressionIgnition = compression,
            monitors = monitors,
            rawHex = data.take(4).joinToString(" ") { "%02X".format(it) },
            modePid = modePid,
        )
    }

    fun parseMode06Mask(lines: List<String>, baseMid: Int): List<Int> {
        val supported = ArrayList<Int>()
        for (line in lines) {
            val tokens = stripOptionalHeader(line, DtcParser.parseHexTokens(line), expectedSid = 0x46, expectedMode = 0x06)
            if (tokens.size >= 6 && tokens[0] == 0x46 && tokens[1] == baseMid) {
                val mask = tokens.subList(2, 6).map { it.toByte() }.toByteArray()
                supported.addAll(PidDecoder.supportMask(baseMid, mask))
            }
        }
        return supported.distinct().sorted()
    }

    /**
     * Parses CAN ISO 15031-5 Mode 06 records: `46 <MID> <TID> <UASID> <ValH> <ValL> <MinH> <MinL> <MaxH> <MaxL>`.
     * Safely skips malformed or shorter non-CAN legacy frames with a warning instead of inventing values.
     */
    fun parseMode06Results(lines: List<String>, requestedMid: Int): Pair<List<Mode06TestResult>, List<String>> {
        val results = ArrayList<Mode06TestResult>()
        val warnings = ArrayList<String>()
        for (line in lines) {
            val tokens = stripOptionalHeader(line, DtcParser.parseHexTokens(line), expectedSid = 0x46, expectedMode = 0x06)
            if (tokens.size >= 3 && tokens[0] == 0x7F && tokens[1] == 0x06) {
                warnings += "MID %02X: NRC %02X".format(requestedMid, tokens[2])
                continue
            }
            if (tokens.isEmpty() || tokens[0] != 0x46) continue
            // Walk 9-byte chunks after 0x46
            var idx = 1
            while (idx + 8 < tokens.size) {
                val mid = tokens[idx]
                val tid = tokens[idx + 1]
                val uasid = tokens[idx + 2]
                val rawVal = (tokens[idx + 3] shl 8) or tokens[idx + 4]
                val rawMin = (tokens[idx + 5] shl 8) or tokens[idx + 6]
                val rawMax = (tokens[idx + 7] shl 8) or tokens[idx + 8]
                val (scale, signed, unit) = uasidScale(uasid)
                val v = decodeUasValue(rawVal, scale, signed)
                val minV = decodeUasValue(rawMin, scale, signed)
                val maxV = decodeUasValue(rawMax, scale, signed)
                val passed = v in minV..maxV
                results += Mode06TestResult(
                    mid = mid,
                    tid = tid,
                    uasid = uasid,
                    nameRu = midNameRu(mid) + " (TID %02X)".format(tid),
                    value = v,
                    min = minV,
                    max = maxV,
                    unit = unit,
                    passed = passed,
                    rawHex = tokens.subList(idx, idx + 9).joinToString(" ") { "%02X".format(it) },
                )
                idx += 9
            }
            if (idx == 1 && tokens.size in 2..8) {
                warnings += "Нестандартная длина кадра Mode 06 (${tokens.size} байт) для MID %02X".format(requestedMid)
            }
        }
        return results to warnings
    }

    private fun decodeUasValue(raw16: Int, scale: Double, signed: Boolean): Double {
        val value = if (signed && (raw16 and 0x8000) != 0) raw16 - 0x10000 else raw16
        return value * scale
    }

    private fun uasidScale(uasid: Int): Triple<Double, Boolean, String> = when (uasid) {
        0x01 -> Triple(1.0, false, "отсч.")
        0x02 -> Triple(0.1, false, "отсч.")
        0x05 -> Triple(0.001, false, "В")
        0x0A -> Triple(0.122, false, "мВ")
        0x0B -> Triple(0.001, false, "В")
        0x10 -> Triple(1.0, false, "мс")
        0x11 -> Triple(100.0, false, "мс")
        0x12 -> Triple(1.0, false, "с")
        0x20 -> Triple(0.00390625, false, "")
        0x81 -> Triple(1.0, true, "отсч.")
        0x85 -> Triple(0.001, true, "В")
        0x8A -> Triple(0.122, true, "мВ")
        0x90 -> Triple(1.0, true, "мс")
        0x96 -> Triple(0.1, true, "°C")
        else -> Triple(1.0, false, "у.е.")
    }

    fun midNameRu(mid: Int): String = when (mid) {
        in 0x01..0x08 -> "Датчик кислорода (Банк ${(mid + 3) / 4}, Датчик ${((mid - 1) % 4) + 1})"
        in 0x21..0x22 -> "Монитор катализатора (Банк ${mid - 0x20})"
        in 0x31..0x34 -> "Монитор EGR / VVT"
        in 0x39..0x3D -> "Монитор испарений EVAP"
        0xA1 -> "Общие пропуски зажигания"
        in 0xA2..0xB0 -> "Пропуски зажигания: цилиндр ${mid - 0xA1}"
        else -> "Монитор MID %02X".format(mid)
    }

    internal fun stripOptionalHeader(
        rawLine: String,
        bytes: List<Int>,
        expectedSid: Int,
        expectedMode: Int,
    ): List<Int> {
        val trimmed = rawLine.trim().uppercase()
        val firstTok = trimmed.split(' ', '\t').firstOrNull { it.isNotBlank() } ?: return bytes
        if ((firstTok.length == 3 || firstTok.length == 8) && firstTok.all { it in '0'..'9' || it in 'A'..'F' }) {
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
}
