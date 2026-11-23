package com.obd2.app.core.report

import android.content.Context
import android.os.Build
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.FullCheckRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the diagnostic ZIP (real collected data only).
 * Contents: device_info.json, bluetooth_scan.json, gatt_services.json,
 * elm327_info.json, obd_protocol.json, supported_pids.json, live_data.json,
 * dtc_results.json, raw_communication.log, diagnostic_summary.md
 */
class DiagnosticReportBuilder(private val context: Context) {

    fun outputDir(): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
        dir.mkdirs()
        return dir
    }

    fun build(
        result: FullCheckRunner.Result,
        repo: DiagnosticRepository,
        maskVins: Boolean,
        demoMode: Boolean,
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(result.startedAtMs))
        val out = File(outputDir(), "obd2_report_$ts.zip")

        val deviceInfo = buildString {
            appendLine("{")
            appendLine("  \"generated_at\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\",")
            appendLine("  \"demo_mode\": $demoMode,")
            appendLine("  \"vehicle\": {")
            appendLine("    \"maker\": \"${e(repo.settingsCache.vehicleMaker)}\",")
            appendLine("    \"model\": \"${e(repo.settingsCache.vehicleModel)}\",")
            appendLine("    \"year\": \"${e(repo.settingsCache.vehicleYear)}\",")
            appendLine("    \"engine\": \"${e(repo.settingsCache.vehicleEngine)}\",")
            appendLine("    \"ecu\": \"${e(repo.settingsCache.vehicleEcu)}\"")
            appendLine("  },")
            appendLine("  \"phone\": {")
            appendLine("    \"manufacturer\": \"${e(Build.MANUFACTURER)}\",")
            appendLine("    \"model\": \"${e(Build.MODEL)}\",")
            appendLine("    \"android_version\": \"${Build.VERSION.RELEASE}\",")
            appendLine("    \"sdk_int\": ${Build.VERSION.SDK_INT}")
            appendLine("  },")
            appendLine("  \"vin_masked\": ${j(mask(result.vinMasked, maskVins))},")
            appendLine("  \"voltage\": ${result.voltage ?: "null"}")
            appendLine("}")
        }

        val t = repo.transport
        val bluetoothScan = buildString {
            appendLine("{")
            appendLine("  \"note\": \"Адаптер, с которым выполнено подключение\",")
            appendLine("  \"transport\": ${j(t?.name)},")
            appendLine("  \"state\": ${j(t?.state?.value?.toString())},")
            appendLine("  \"demo\": $demoMode")
            appendLine("}")
        }

        val gattJson = (t as? com.obd2.app.core.ble.BleTransport)?.gattInfo?.toJson() ?: "{\"demo\": true}"

        val bleReport = (t as? com.obd2.app.core.ble.BleTransport)?.bleReport()
            ?: "# BLE-диагностика\n\nДемо-режим: BLE не использовался.\n"

        val info = repo.elm?.info?.value
        val elmJson = buildString {
            appendLine("{")
            appendLine("  \"identity\": ${j(info?.identity)},")
            appendLine("  \"reset_banner\": ${j(info?.resetBanner)},")
            appendLine("  \"voltage\": ${info?.voltage ?: "null"},")
            appendLine("  \"echo\": ${info?.echo ?: false},")
            appendLine("  \"headers\": ${info?.headers ?: false}")
            appendLine("}")
        }

        val protocolJson = buildString {
            appendLine("{")
            appendLine("  \"protocol\": ${j(info?.protocol)},")
            appendLine("  \"protocol_number\": ${info?.protocolNumber ?: "null"},")
            appendLine("  \"note\": \"ATDP/ATDPN как ответил адаптер\"")
            appendLine("}")
        }

        val pidsJson = buildString {
            appendLine("{")
            appendLine("  \"count\": ${result.supportedPids.size},")
            appendLine("  \"supported\": [${result.supportedPids.joinToString(",") { "\"%02X\"".format(it) }}]")
            appendLine("}")
        }

        val liveJson = buildString {
            appendLine("[")
            result.readings.forEachIndexed { i, r ->
                appendLine(if (i > 0) "  ,{" else "  {")
                appendLine("    \"mode\": ${r.mode},")
                appendLine("    \"pid\": \"${r.pidHex}\",")
                appendLine("    \"name\": \"${e(r.name)}\",")
                appendLine("    \"value\": \"${e(r.value)}\",")
                appendLine("    \"unit\": ${j(r.unit)},")
                appendLine("    \"raw\": \"${e(r.raw)}\",")
                appendLine("    \"duration_ms\": ${r.durationMs},")
                appendLine("    \"verified\": ${r.verified}")
                appendLine("  }")
            }
            appendLine("]")
        }

        val dtc = result.dtc
        val dtcJson = buildString {
            appendLine("{")
            appendLine("  \"stored\": ${dtcList(dtc?.stored)},")
            appendLine("  \"pending\": ${dtcList(dtc?.pending)},")
            appendLine("  \"permanent\": ${dtcList(dtc?.permanent)},")
            appendLine("  \"errors\": [${dtc?.errors?.joinToString(",") { "\"${e(it)}\"" } ?: ""}],")
            appendLine("  \"duration_ms\": ${dtc?.durationMs ?: 0},")
            appendLine("  \"timestamp\": ${dtc?.timestampMs ?: 0}")
            appendLine("}")
        }

        val rawLog = repo.log.toPlainText(maskVins)
        val summaryMd = buildSummary(result, repo, maskVins, demoMode)

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("device_info.json", deviceInfo)
            put("bluetooth_scan.json", bluetoothScan)
            put("ble_report.md", bleReport)
            put("gatt_services.json", gattJson)
            put("elm327_info.json", elmJson)
            put("obd_protocol.json", protocolJson)
            put("supported_pids.json", pidsJson)
            put("live_data.json", liveJson)
            put("dtc_results.json", dtcJson)
            put("raw_communication.log", rawLog)
            put("diagnostic_summary.md", summaryMd)
        }
        return out
    }

    private fun mask(vin: String?, maskVins: Boolean): String? =
        if (maskVins) RawVinMask.maskVin(vin) else vin

    private fun dtcList(list: List<DiagnosticRepository.DtcEntryInfo>?): String {
        if (list.isNullOrEmpty()) return "[]"
        return list.joinToString(",", "[", "]") { d ->
            buildString {
                append("{")
                append("\"code\":\"${e(d.code)}\",\"mode\":${d.mode}")
                if (d.ru != null) append(",\"description_ru\":\"${e(d.ru)}\"")
                if (d.en != null) append(",\"description_en\":\"${e(d.en)}\"")
                if (d.scopeLabel != null) append(",\"scope\":\"${e(d.scopeLabel)}\"")
                if (d.causes.isNotEmpty()) {
                    append(",\"causes\":[")
                    append(d.causes.joinToString(",") { "\"${e(it)}\"" })
                    append("]")
                }
                append(",\"source\":\"${e(d.source)}\"}")
            }
        }
    }

    private fun j(s: String?): String = s?.let { "\"${e(it)}\"" } ?: "null"

    private fun e(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun buildSummary(
        result: FullCheckRunner.Result,
        repo: DiagnosticRepository,
        maskVins: Boolean,
        demoMode: Boolean,
    ): String = buildString {
        appendLine("# Диагностический отчёт OBD2")
        appendLine()
        appendLine("Дата: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(result.startedAtMs))}")
        appendLine("Режим: ${if (demoMode) "демо (симулятор)" else "реальный адаптер"}")
        appendLine()
        appendLine("## Автомобиль")
        appendLine("- Марка/модель: ${repo.settingsCache.vehicleMaker} ${repo.settingsCache.vehicleModel}")
        appendLine("- Год: ${repo.settingsCache.vehicleYear}")
        appendLine("- Двигатель: ${repo.settingsCache.vehicleEngine}")
        appendLine("- ЭБУ: ${repo.settingsCache.vehicleEcu}")
        result.vinMasked?.let {
            appendLine("- VIN: ${if (maskVins) RawVinMask.maskVin(it) else it}")
        }
        appendLine()
        appendLine("## Этапы проверки")
        result.steps.forEach {
            appendLine("- [${it.status.name}] ${it.title}: ${it.details} (${it.durationMs} мс)")
        }
        appendLine()
        appendLine("## Протокол")
        repo.elm?.info?.value?.protocol?.let { appendLine("- $it") }
        result.voltage?.let { appendLine("- Напряжение АКБ: $it В") }
        appendLine()
        appendLine("## Поддерживаемые PID (${result.supportedPids.size})")
        appendLine(result.supportedPids.chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02X".format(it) }
        })
        appendLine()
        appendLine("## Значения параметров")
        if (result.readings.isEmpty()) {
            appendLine("— (не получено)")
        } else {
            appendLine("| PID | Параметр | Значение | Ед. | Сырой ответ | Время | Достоверно |")
            appendLine("|-----|----------|----------|-----|-------------|-------|------------|")
            result.readings.forEach { r ->
                appendLine(
                    "| ${r.mode}${r.pidHex} | ${r.name} | ${r.value} | ${r.unit ?: ""} | ${r.raw} | ${r.durationMs} мс | ${if (r.verified) "да" else "нет"} |",
                )
            }
        }
        appendLine()
        val dtc = result.dtc
        appendLine("## Коды ошибок")
        if (dtc == null) {
            appendLine("— (не считаны)")
        } else {
            appendLine("- Сохранённые (Mode 03): ${dtc.stored.joinToString(", ") { it.code }.ifEmpty { "нет" }}")
            appendLine("- Ожидающие (Mode 07): ${dtc.pending.joinToString(", ") { it.code }.ifEmpty { "нет" }}")
            appendLine("- Постоянные (Mode 0A): ${dtc.permanent.joinToString(", ") { it.code }.ifEmpty { "нет" }}")
            if (dtc.errors.isNotEmpty()) {
                appendLine("- Замечания: ${dtc.errors.joinToString("; ")}")
            }
            if (dtc.stored.isNotEmpty()) {
                appendLine()
                appendLine("### Описания сохранённых кодов")
                dtc.stored.forEach { d ->
                    appendLine("- **${d.code}** — ${d.ru ?: d.en ?: "нет описания в базе"}${d.scopeLabel?.let { " ($it)" } ?: ""}")
                    if (d.ru == null && d.en != null) appendLine("  - (оригинал) ${d.en}")
                    d.causes.take(5).forEach { c -> appendLine("  - Возможная причина: $c") }
                }
            }
        }
        appendLine()
        appendLine("## Ограничения")
        appendLine("- Приложение работает только в безопасном диагностическом режиме: чтение Mode 01/03/07/09/0A.")
        appendLine("- Запись в ЭБУ, очистка ошибок и нестандартные команды не выполняются.")
        appendLine("- Значения помечены «Достоверно: да» только при корректном ответе ЭБУ (контроль echo PID и длины данных).")
        appendLine("- Статистика ELM327: ${repo.elm?.stats?.value.toString()}")
    }
}

object RawVinMask {
    fun maskVin(vin: String?): String? {
        if (vin == null || vin.length != 17) return vin
        return vin.take(3) + "XXXXXXXXX" + vin.takeLast(5)
    }
}
