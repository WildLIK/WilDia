package com.obd2.app.core.diag

import android.content.Context
import com.obd2.app.core.log.RawLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the technical diagnostic ZIP (§11). The archive is created even when
 * no BLE connection exists: every missing-data file explains WHY (stage not
 * run / permission denied / connection not established).
 *
 * Privacy (§13): VIN and Bluetooth MACs are masked by default; the [maskIds]
 * switch keeps technical identifiers when explicitly requested.
 */
class DiagExporter(
    private val context: Context,
    private val session: DiagSession,
) {
    @Volatile
    private var maskEnabled: Boolean = false

    private fun mask(v: String?): String? = when {
        v == null -> null
        maskEnabled -> RawLog.maskVin(maskMac(v))
        else -> v
    }

    private fun j(v: String?): String =
        v?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" } ?: "null"

    private fun reason(whenAbsent: String, present: Boolean): String =
        if (present) "" else whenAbsent

    /** Mask Bluetooth MACs keeping the vendor prefix: 12:34:56:**:**:**. */
    companion object {
        val MAC_RX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        fun maskMac(text: String): String = MAC_RX.replace(text) { m ->
            val parts = m.value.split(":")
            "${parts[0]}:${parts[1]}:**:**:**:**"
        }
    }

    fun export(maskIds: Boolean): File {
        maskEnabled = maskIds
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diag_reports")
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "ble_diag_$ts.zip")

        val phone = PhoneInfo.collect(context)
        val stats = session.stats
        val summaries = session.scanSummaries()
        val classicSummaries = session.classicSummaries()
        val gattEvents = session.gattEvents.toList()
        val elm = session.elmCommands.toList()
        val obd = session.obdResults.toList()
        val gattTree = session.gattTreeJson
        val events = session.journal.allInMemory()
        val analyzer = DiagAnalyzer()
        val conclusions = analyzer.analyze(session, stagesFromEvents())

        val files = LinkedHashMap<String, String>()

        // 1. device_info.json
        files["device_info.json"] = buildString {
            appendLine("{")
            appendLine("  \"generated_at\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\",")
            appendLine("  \"scenario\": ${j(session.activeScenario)},")
            appendLine("  \"manufacturer\": ${j(phone.manufacturer)},")
            appendLine("  \"model\": ${j(phone.model)},")
            appendLine("  \"android\": ${j(phone.androidVersion)},")
            appendLine("  \"sdk_int\": ${phone.sdkInt},")
            appendLine("  \"app_version\": ${j(phone.appVersionName)},")
            appendLine("  \"app_version_code\": ${phone.appVersionCode ?: "null"},")
            appendLine("  \"bluetooth_api_note\": ${j(phone.bluetoothApiNote)},")
            appendLine("  \"le_available\": ${phone.leAvailable},")
            appendLine("  \"bluetooth_state\": ${j(phone.bluetoothState)},")
            appendLine("  \"ble_capabilities\": ${phone.bleCapabilities.entries.joinToString(",", "{", "}") { "\"${it.key}\": ${it.value}"}},")
            appendLine("  \"power_saving\": ${phone.powerSaving.entries.joinToString(",", "{", "}") { "\"${it.key}\": ${j(it.value)}"}},")
            appendLine("  \"bluetooth_service_available\": ${phone.bluetoothServiceAvailable},")
            appendLine("  \"app_foreground\": ${phone.appForeground}")
            appendLine("}")
        }

        // 2. permissions.json
        files["permissions.json"] = buildString {
            appendLine("{")
            appendLine("  \"runtime_permissions\": ${phone.runtimePermissions.entries.joinToString(",", "{", "}") { "\"${it.key}\": ${it.value}"}},")
            appendLine("  \"manifest_permissions\": ${phone.manifestPermissions.entries.joinToString(",", "{", "}") { "\"${it.key}\": ${it.value}"}},")
            appendLine("  \"note\": \"Фактические проверки выполняются перед каждой Bluetooth-операцией; события PERM см. в timeline.json\"")
            appendLine("}")
        }

        // 3. ble_scan.json
        if (summaries.isEmpty()) {
            files["ble_scan.json"] = scanAbsentJson(reason("этап сканирования не запускался либо устройств не обнаружено", false))
        } else {
            files["ble_scan.json"] = buildString {
                appendLine("{")
                appendLine("  \"session\": {\"started\": ${session.scanInfo.startedAt ?: "null"}, \"finished\": ${session.scanInfo.finishedAt ?: "null"},")
                appendLine("    \"api\": ${j(session.scanInfo.api)}, \"filters\": ${j(session.scanInfo.filters)},")
                appendLine("    \"settings\": ${j(session.scanInfo.settings)}, \"result\": ${j(session.scanInfo.result)},")
                appendLine("    \"failure_code\": ${j(session.scanInfo.failureCode)}, \"seconds\": ${session.scanInfo.scanSeconds}},")
                appendLine("  \"devices\": [")
                summaries.forEachIndexed { i, d ->
                    val comma = if (i < summaries.size - 1) "," else ""
                    appendLine("    {\"mac\": ${j(if (maskIds) maskMac(d.mac) else d.mac)}, \"name\": ${j(d.name)}, " +
                        "\"count\": ${d.count}, \"rssi_first\": ${d.rssiFirst}, \"rssi_last\": ${d.rssiLast}, " +
                        "\"rssi_min\": ${d.rssiMin}, \"rssi_max\": ${d.rssiMax}, " +
                        "\"first_seen\": ${d.firstSeen}, \"last_seen\": ${d.lastSeen}, " +
                        "\"adv_uuids\": [${d.advServiceUuids.joinToString() { "\"$it\"" }}], " +
                        "\"adv_flags\": ${d.advFlags ?: "null"}, " +
                        "\"connectable\": ${d.isConnectable ?: "null"} }$comma")
                }
                appendLine("  ]")
                appendLine("}")
            }
        }

        // 4. ble_advertisements.json — все обнаружения с manufacturer/service data и RSSI-таймлайном
        if (summaries.isEmpty()) {
            files["ble_advertisements.json"] = scanAbsentJson("рекламные пакеты не записаны: скан не выполнялся или ничего не поймал")
        } else {
            files["ble_advertisements.json"] = buildString {
                appendLine("[")
                summaries.forEachIndexed { di, d ->
                    val tl = d.rssiTimeline.joinToString(",") { "[${it.first},${it.second}]" }
                    appendLine("  {\"mac\": ${j(if (maskIds) maskMac(d.mac) else d.mac)}, \"name\": ${j(d.name)}, " +
                        "\"count\": ${d.count}, \"rssi_timeline_ms\": [$tl], " +
                        "\"manufacturer_data\": ${j(d.manufacturerDataHex)}, " +
                        "\"service_data\": {" + d.serviceDataHex.entries.joinToString { "\"${it.key}\": \"${it.value}\"" } + "}}${if (di < summaries.size - 1) "," else ""}")
                }
                appendLine("]")
            }
        }

        // 5. ble_connection.json
        files["ble_connection.json"] = buildString {
            appendLine("{")
            appendLine("  \"target\": ${j(mask(session.selectedTarget))},")
            appendLine("  \"connected_name\": ${j(session.connectedName)},")
            appendLine("  \"transport_used\": ${j(session.transportUsed)},")
            appendLine("  \"attempts\": ${stats.attempts}, \"successes\": ${stats.successes}, \"failures\": ${stats.failures},")
            appendLine("  \"negotiated_mtu\": ${stats.negotiatedMtu ?: "null"},")
            appendLine("  \"rssi\": ${stats.rssi ?: "null"},")
            appendLine("  \"time_to_first_adapter_response_ms\": ${stats.timeToFirstAdapterResponseMs ?: "null"},")
            appendLine("  \"note\": \"наличие GATT-дерева/UUID не является доказательством работоспособности соединения\"")
            appendLine("}")
        }

        // 6. gatt_services.json
        files["gatt_services.json"] = gattTree
            ?: "{ \"note\": \"GATT-дерево не получено: " + reason("подключение не установлено или обнаружение сервисов не выполнялось", gattTree != null) + "\" }"

        // 7. gatt_events.json
        files["gatt_events.json"] = eventsJson(gattEvents, maskIds)

        // 8. elm327_commands.json
        if (elm.isEmpty()) {
            files["elm327_commands.json"] = "{ \"note\": \"ELM327-обмен не выполнялся: " +
                (if (stats.successes == 0) "соединение не установлено" else "этап не запускался") + "\" }"
        } else {
            files["elm327_commands.json"] = buildString {
                appendLine("[")
                elm.forEachIndexed { i, c ->
                    val comma = if (i < elm.size - 1) "," else ""
                    appendLine("  {\"ts\": ${c.ts}, \"cmd\": ${j(c.cmd)}, \"write_uuid\": ${j(mask(c.writeUuid))}, " +
                        "\"write_result\": ${j(c.writeResult)}, \"first_byte_ms\": ${c.firstByteMs ?: "null"}, " +
                        "\"duration_ms\": ${c.durationMs ?: "null"}, \"prompt_seen\": ${c.promptSeen}, " +
                        "\"result\": ${j(c.resultCode)}, \"error\": ${j(c.errorKind)}, " +
                        "\"fragments\": ${c.fragments}, \"fragments_merged_ok\": ${c.fragmentsMergedOk}, " +
                        "\"terminator_missing\": ${c.terminatorMissing}, \"buffer_overflow\": ${c.bufferOverflow}, " +
                        "\"unexpected_chars\": [${c.unexpectedChars.joinToString() { "\"$it\"" }}], " +
                        "\"response_ascii\": ${j(mask(c.responseAscii))}, " +
                        "\"response_hex\": ${j(c.responseHex)} }$comma")
                }
                appendLine("]")
            }
        }

        // 9. obd_responses.json
        if (obd.isEmpty()) {
            files["obd_responses.json"] = "{ \"note\": \"чтение параметров не выполнялось: " +
                (if (stats.successes == 0) "соединение не установлено" else "этап не запускался или поддержки не заявлено") + "\" }"
        } else {
            files["obd_responses.json"] = buildString {
                appendLine("[")
                obd.forEachIndexed { i, r ->
                    val comma = if (i < obd.size - 1) "," else ""
                    appendLine("  {\"pid\": ${j(r.pid)}, \"raw\": ${j(r.raw)}, \"decoded\": ${j(r.decoded)}, " +
                        "\"unit\": ${j(r.unit)}, \"duration_ms\": ${r.durationMs ?: "null"}, " +
                        "\"support_check\": ${j(r.supportCheck)}, \"validity\": \"${r.validity}\", " +
                        "\"error\": ${j(r.error)} }$comma")
                }
                appendLine("]")
            }
        }

        // 10. supported_pids.json
        val pids = session.supportedPids.toList()
        files["supported_pids.json"] = if (pids.isEmpty()) {
            "{ \"note\": \"маски поддержки не опрашивались: " + reason("соединение не установлено", pids.isNotEmpty()) + "\" }"
        } else {
            "{ \"count\": ${pids.size}, \"pids\": [${pids.joinToString() { "\"%02X\"".format(it) }}] }"
        }

        // 11. connection_statistics.json
        files["connection_statistics.json"] = buildString {
            appendLine("{")
            appendLine("  \"attempts\": ${stats.attempts}, \"successes\": ${stats.successes}, \"failures\": ${stats.failures},")
            appendLine("  \"disconnects\": ${stats.disconnects},")
            appendLine("  \"disconnect_reasons\": [${stats.disconnectReasons.joinToString() { "\"${e(it)}\"" }}],")
            appendLine("  \"timeouts\": ${stats.timeouts},")
            appendLine("  \"gatt_errors\": [${stats.gattErrors.joinToString() { "\"${e(it)}\"" }}],")
            appendLine("  \"write_errors\": ${stats.writeErrors}, \"notify_errors\": ${stats.notifyErrors},")
            appendLine("  \"connect_duration\": ${j(stats.minAvgMax(stats.connectDurationsMs))},")
            appendLine("  \"discovery_duration\": ${j(stats.minAvgMax(stats.discoveryDurationsMs))},")
            appendLine("  \"subscribe_duration\": ${j(stats.minAvgMax(stats.subscribeDurationsMs))},")
            appendLine("  \"negotiated_mtu\": ${stats.negotiatedMtu ?: "null"},")
            appendLine("  \"rssi\": ${stats.rssi ?: "null"},")
            appendLine("  \"packet_loss\": \"недоступно (публичный Android API не позволяет измерить)\"")
            appendLine("}")
        }

        // 12. errors.json
        val errorEvents = events.filter { it.severity == DiagSeverity.ERROR }
        files["errors.json"] = errorEventsJson(errorEvents, maskIds) + "\n"

        // 13. timeline.json — всё с ISO-временем
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        files["timeline.json"] = buildString {
            appendLine("[")
            events.forEachIndexed { i, ev ->
                val comma = if (i < events.size - 1) "," else ""
                appendLine("  {\"t\": \"${df.format(Date(ev.ts))}\", \"ts\": ${ev.ts}, \"cat\": \"${ev.category}\", " +
                    "\"op\": ${j(mask(ev.op))}, \"rc\": ${j(mask(ev.resultCode))}, \"ms\": ${ev.durationMs ?: "null"}, " +
                    "\"prev\": ${j(mask(ev.prevState))}, \"new\": ${j(mask(ev.newState))}, " +
                    "\"sev\": \"${ev.severity}\", \"det\": ${j(mask(ev.details))} }$comma")
            }
            appendLine("]")
        }

        // 14. raw_ble.log
        val bleLines = rawLogLines().filter { it.contains("[BLE]") || it.contains("[CLASSIC]") || it.contains("[DIAG]") }
        files["raw_ble.log"] = if (bleLines.isEmpty()) {
            "# BLE-обмен не записан: " + reason("соединение не устанавливалось и скан не выполнялся", bleLines.isNotEmpty())
        } else bleLines.joinToString("\n")

        // 15. raw_elm327.log
        val elmLines = rawLogLines().filter { it.contains(">>") || it.contains("<<") }
        files["raw_elm327.log"] = if (elmLines.isEmpty()) {
            "# ELM327-обмен не записан: " + reason("соединение не установлено или этап ELM не выполнялся", elmLines.isNotEmpty())
        } else elmLines.joinToString("\n")

        // 16. crash_report.txt
        val crash = session.journal.readLastCrashJsonl()?.readText()
        files["crash_report.txt"] = crash ?: "# Крашей за период диагностики не зафиксировано.\n"

        // 17. diagnostic_summary.md
        files["diagnostic_summary.md"] = buildSummary(conclusions, maskIds)

        // 18. classic_scan.json
        files["classic_scan.json"] = if (classicSummaries.isEmpty()) {
            scanAbsentJson("Bluetooth Classic поиск не запускался либо устройства не обнаружены")
        } else {
            buildString {
                appendLine("[")
                classicSummaries.forEachIndexed { i, c ->
                    val comma = if (i < classicSummaries.size - 1) "," else ""
                    appendLine(
                        "  {\"mac\": ${j(if (maskIds) maskMac(c.mac) else c.mac)}, \"name\": ${j(c.name)}, " +
                            "\"rssi\": ${c.rssi ?: "null"}, \"device_type\": ${c.deviceType}, " +
                            "\"bond_state\": ${c.bondState}, \"spp_status\": ${j(c.sppStatus)}, " +
                            "\"source\": ${j(c.source)}, " +
                            "\"sdp_uuids\": [${c.sdpUuids.joinToString { "\"$it\"" }}]}$comma",
                    )
                }
                appendLine("]")
            }
        }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        session.event(DiagCategory.SYS, "export.zip", details = out.absolutePath)
        return out
    }

    private fun stagesFromEvents(): List<DiagStageState> {
        // the analyzer mostly needs stage states; rebuild minimal view from memory
        val ops = session.journal.allInMemory()
        val stages = ArrayList<DiagStageState>()
        val permFailed = ops.any { it.category == DiagCategory.PERM && it.severity == DiagSeverity.ERROR }
        if (permFailed) stages.add(DiagStageState("perm", "Разрешения").apply { state = StageState.FAIL })
        if (session.scanInfo.startedAt != null) {
            stages.add(DiagStageState("scan", "Сканирование").apply {
                state = if (session.scanSummaries().isEmpty()) StageState.FAIL else StageState.PASS
            })
        }
        if (session.stats.attempts > 0) {
            stages.add(DiagStageState("connect", "GATT-подключение").apply {
                state = if (session.stats.successes > 0) StageState.PASS else StageState.FAIL
            })
        }
        if (session.elmCommands.isNotEmpty()) stages.add(DiagStageState("elm", "ELM327").apply { state = StageState.PASS })
        if (session.obdResults.isNotEmpty()) stages.add(DiagStageState("obd", "Mode 01").apply { state = StageState.PASS })
        return stages
    }

    private fun scanAbsentJson(reasonText: String): String =
        "{ \"note\": \"$reasonText\", \"devices\": [] }"

    private fun eventsJson(events: List<DiagEvent>, maskIds: Boolean): String {
        if (events.isEmpty()) {
            return "{ \"note\": \"GATT-события отсутствуют: " + reason("подключение не выполнялось", events.isNotEmpty()) + "\" }"
        }
        return buildString {
            appendLine("[")
            events.forEachIndexed { i, ev ->
                val comma = if (i < events.size - 1) "," else ""
                appendLine("  {\"ts\": ${ev.ts}, \"op\": ${j(mask(ev.op))}, \"rc\": ${j(mask(ev.resultCode))}, " +
                    "\"ms\": ${ev.durationMs ?: "null"}, \"prev\": ${j(mask(ev.prevState))}, " +
                    "\"new\": ${j(mask(ev.newState))}, \"sev\": \"${ev.severity}\", \"det\": ${j(mask(ev.details))} }$comma")
            }
            appendLine("]")
        }
    }

    private fun errorEventsJson(events: List<DiagEvent>, maskIds: Boolean): String {
        if (events.isEmpty()) return "{ \"errors\": [], \"note\": \"ошибок в ходе сессии не зафиксировано\" }"
        return buildString {
            appendLine("{ \"errors\": [")
            events.forEachIndexed { i, ev ->
                val comma = if (i < events.size - 1) "," else ""
                appendLine("    ${j("${ev.op}: ${ev.resultCode ?: ""} ${ev.details ?: ""}")}$comma")
            }
            appendLine("  ], \"note\": \"полный контекст см. timeline.json\" }")
        }
    }

    private fun rawLogLines(): List<String> = sessionJournalLines()

    private fun sessionJournalLines(): List<String> {
        // text view of the journal (BLE + ELM raw exchange live in RawLog; the
        // repository's log is shared) — RawLog is exposed through the session hook
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return rawLogSource?.invoke().orEmpty().map {
            "${df.format(Date(it.timestampMs))} ${
                when (it.dir) {
                    RawLog.Dir.TX -> ">>"
                    RawLog.Dir.RX -> "<<"
                    RawLog.Dir.INFO -> "--"
                    RawLog.Dir.ERROR -> "!!"
                }
            } ${RawLog.maskVin(it.text)}"
        }
    }

    private fun e(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** RawLog supplier set at construction time (avoids a hard dependency). */
    var rawLogSource: (() -> List<RawLog.Entry>)? = null

    private fun buildSummary(conclusions: List<Conclusion>, maskIds: Boolean): String = buildString {
        appendLine("# Диагностическое заключение")
        appendLine()
        appendLine("Сценарий: ${session.activeScenario.ifEmpty { "не указан" }}; устройство: ${mask(session.selectedTarget) ?: "не выбрано"}")
        appendLine()
        appendLine("## Выводы (по записанным событиям)")
        conclusions.forEach { c ->
            appendLine("- ${if (c.confirmed) "**подтверждено**" else "*гипотеза*"}: ${c.text}")
        }
        appendLine()
        appendLine("## Пользователю понятными словами")
        val permFail = conclusions.any { it.confirmed && it.text.contains("разрешения отсутствуют") }
        val connFail = conclusions.any { it.confirmed && it.text.contains("GATT-подключение завершилось ошибкой") }
        when {
            permFail -> appendLine("Приложению не выданы Bluetooth-разрешения: откройте Настройки → Приложения → OBD2 Сканер → Разрешения → «Устройства поблизости» → Разрешить.")
            connFail -> appendLine("Телефон не смог установить Bluetooth-соединение с адаптером. Вставьте адаптер в разъём OBD2, включите зажигание, подойдите ближе и повторите. Подробные технические причины — в файлах gatt_events.json и ble_connection.json.")
            session.stats.successes > 0 && session.elmCommands.any { it.errorKind?.contains("таймаут") == true } ->
                appendLine("Bluetooth-соединение установлено, но адаптер не отвечает на команды ELM327. Вероятно, выбран неподходящий транспорт или интерфейс адаптера — для BLE выбирайте IOS-Vlink, для Classic SPP — Android-Vlink с подтверждённым профилем SPP.")
            else -> appendLine("Полные данные — в timeline.json; статистика — в connection_statistics.json.")
        }
        appendLine()
        appendLine("## Ограничения")
        appendLine("- Диагностика выполняется на телефоне без root; перехват системного HCI-трафика недоступен из приложения.")
        appendLine("- Потери пакетов BLE: недоступно через публичный Android API.")
        appendLine("- Маскирование: VIN и MAC-адреса " + if (maskIds) "замаскированы" else "экспортированы как есть (включён переключатель технических идентификаторов)")
    }
}
