package com.obd2.app.core.repo

import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.PidResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * «Полная проверка адаптера и автомобиля» — safe read-only sequence:
 * BLE → GATT → ELM327 → protocol → PID support → readings → DTC → timing → report.
 * Failed steps do not stop independent ones. No writes to the ECU are ever made.
 */
class FullCheckRunner(
    private val repo: DiagnosticRepository,
    private val appContext: android.content.Context,
) {

    data class Step(
        val id: Int,
        val title: String,
        val status: Status,
        val durationMs: Long,
        val details: String,
    ) {
        enum class Status { OK, WARN, FAIL, SKIPPED, RUNNING }
    }

    data class Result(
        val steps: List<Step>,
        val supportedPids: List<Int>,
        val readings: List<ReadingRow>,
        val dtc: DiagnosticRepository.DtcScanResult?,
        val vinMasked: String?,
        val voltage: Float?,
        val startedAtMs: Long,
    )

    data class ReadingRow(
        val pidHex: String,
        val mode: Int,
        val name: String,
        val unit: String?,
        val value: String,
        val raw: String,
        val durationMs: Long,
        val verified: Boolean,
    )

    suspend fun run(
        demoMode: Boolean,
        onProgress: (Int, String, Step.Status) -> Unit,
    ): Result {
        val started = System.currentTimeMillis()
        val steps = ArrayList<Step>()
        val readings = ArrayList<ReadingRow>()
        var supported: List<Int> = emptyList()
        var dtc: DiagnosticRepository.DtcScanResult? = null
        var vin: String? = null
        var voltage: Float? = repo.elm?.info?.value?.voltage

        fun finish(id: Int, title: String, status: Step.Status, dur: Long, details: String) {
            steps.add(Step(id, title, status, dur, details))
            onProgress(id, title, status)
        }

        fun markRunning(id: Int, title: String) = onProgress(id, title, Step.Status.RUNNING)

        // 1. BLE
        markRunning(1, "Проверка Bluetooth/BLE")
        var t0 = System.currentTimeMillis()
        run {
            val scanner = BleScanner(appContext)
            val missing = scanner.missingPermissions()
            val btOk = scanner.hasBluetooth() && scanner.isBluetoothEnabled()
            when {
                demoMode -> finish(1, "Проверка Bluetooth/BLE", Step.Status.SKIPPED, System.currentTimeMillis() - t0, "Демо-режим без BLE")
                !scanner.hasBluetooth() -> finish(1, "Проверка Bluetooth/BLE", Step.Status.FAIL, System.currentTimeMillis() - t0, "Bluetooth-адаптер не найден")
                !btOk -> finish(1, "Проверка Bluetooth/BLE", Step.Status.FAIL, System.currentTimeMillis() - t0, "Bluetooth выключен")
                missing.isNotEmpty() -> finish(1, "Проверка Bluetooth/BLE", Step.Status.FAIL, System.currentTimeMillis() - t0, "Нет разрешений: ${missing.joinToString()}")
                else -> finish(1, "Проверка Bluetooth/BLE", Step.Status.OK, System.currentTimeMillis() - t0, "BLE доступен, разрешения выданы")
            }
        }

        // 2. GATT
        markRunning(2, "Перечисление GATT-сервисов")
        t0 = System.currentTimeMillis()
        val gatt = repo.transport?.let { (it as? com.obd2.app.core.ble.BleTransport)?.gattInfo }
        if (demoMode) {
            finish(2, "Перечисление GATT-сервисов", Step.Status.SKIPPED, 0, "Демо-режим без GATT")
        } else if (gatt == null) {
            finish(2, "Перечисление GATT-сервисов", Step.Status.FAIL, 0, "GATT-снимок недоступен (нет BLE-транспорта)")
        } else {
            val w = gatt.selectedWriteUuid
            val n = gatt.selectedNotifyUuid
            finish(
                2, "Перечисление GATT-сервисов", Step.Status.OK, System.currentTimeMillis() - t0,
                "Сервисов: ${gatt.services.size}, MTU=${gatt.mtu}, write=${w?.takeLast(8)}, notify=${n?.takeLast(8)}",
            )
        }

        // 3. ELM327
        markRunning(3, "Проверка ELM327")
        t0 = System.currentTimeMillis()
        val info = repo.elm?.info?.value
        when {
            info == null -> finish(3, "Проверка ELM327", Step.Status.FAIL, 0, "ELM-клиент не инициализирован")
            else -> {
                val ident = info.identity ?: info.resetBanner ?: "нет ответа"
                val ok = ident.contains("327", ignoreCase = true)
                finish(
                    3, "Проверка ELM327", if (ok) Step.Status.OK else Step.Status.WARN,
                    System.currentTimeMillis() - t0,
                    "ID: $ident",
                )
            }
        }

        // 4. Protocol
        markRunning(4, "Определение диагностического протокола")
        t0 = System.currentTimeMillis()
        when (val p = info?.protocol) {
            null -> finish(4, "Определение диагностического протокола", Step.Status.FAIL, 0, "ATDP не ответил")
            else -> {
                val ok = p.contains("15765", ignoreCase = true) || p.contains("ISO", ignoreCase = true)
                finish(
                    4, "Определение диагностического протокола", if (ok) Step.Status.OK else Step.Status.WARN,
                    System.currentTimeMillis() - t0,
                    "$p (ATDPN=${info.protocolNumber})",
                )
            }
        }

        // 5. PID support
        markRunning(5, "Проверка поддерживаемых PID (0100-0160)")
        t0 = System.currentTimeMillis()
        val obd = repo.obd
        if (obd == null) {
            finish(5, "Проверка поддерживаемых PID (0100-0160)", Step.Status.FAIL, 0, "Нет OBD-клиента")
        } else {
            val sup = obd.discoverSupportedPids()
            supported = sup.supportedPids.toList()
            val status = if (supported.isNotEmpty()) Step.Status.OK else Step.Status.FAIL
            finish(
                5, "Проверка поддерживаемых PID (0100-0160)", status, System.currentTimeMillis() - t0,
                "Поддерживается: ${supported.size} PID" +
                    (if (sup.maskErrors.isNotEmpty()) "; ошибки: " + sup.maskErrors.joinToString("; ") else ""),
            )
        }

        // 6. Read available parameters
        markRunning(6, "Считывание доступных параметров")
        t0 = System.currentTimeMillis()
        if (obd == null || supported.isEmpty()) {
            finish(6, "Считывание доступных параметров", Step.Status.SKIPPED, 0, "Нет поддерживаемых PID или соединения")
        } else {
            val registry = repo.pidRegistry.value.filter { it.mode == 1 && supported.contains(it.pid) && it.decoder != "bitmap" }
            var okCount = 0
            var unsupported = 0
            for (def in registry) {
                when (val r = obd.readPid(def, timeoutMs = 4000)) {
                    is PidResult.Ok -> {
                        okCount++
                        readings.add(
                            ReadingRow(
                                pidHex = "%02X".format(def.pid), mode = 1,
                                name = def.displayName, unit = r.reading.unit,
                                value = r.reading.value?.let { formatValue(it) }
                                    ?: r.reading.text ?: "—",
                                raw = r.reading.rawHex, durationMs = r.reading.durationMs,
                                verified = r.reading.value != null || r.reading.text != null,
                            ),
                        )
                    }
                    is PidResult.Unsupported -> unsupported++
                    is PidResult.Error -> {}
                }
            }
            finish(
                6, "Считывание доступных параметров",
                if (okCount > 0) Step.Status.OK else Step.Status.FAIL,
                System.currentTimeMillis() - t0,
                "Получено: $okCount, не поддерживается ЭБУ: $unsupported",
            )
        }

        // 6b. VIN (info, not a numbered step of the user's list but part of report)
        markRunning(6, "Считывание доступных параметров")
        val vinDef = repo.pidRegistry.value.firstOrNull { it.mode == 9 && it.pid == 0x02 }
        if (obd != null && vinDef != null) {
            when (val r = obd.readPid(vinDef, timeoutMs = 6000)) {
                is PidResult.Ok -> {
                    vin = r.reading.text
                    repo.log.log(RawLog.Dir.INFO, "VIN получен (${vin?.length} симв.)")
                }
                else -> repo.log.log(RawLog.Dir.INFO, "VIN недоступен (ЭБУ не отвечает на 0902)")
            }
        }
        voltage = info?.voltage

        // 7. DTC
        markRunning(7, "Чтение стандартных кодов ошибок")
        t0 = System.currentTimeMillis()
        if (obd == null) {
            finish(7, "Чтение стандартных кодов ошибок", Step.Status.FAIL, 0, "Нет OBD-клиента")
        } else {
            dtc = repo.readDtcs(saveHistory = true)
            val total = dtc.stored.size + dtc.pending.size + dtc.permanent.size
            val status = if (dtc.errors.any { it.contains("UNABLE_TO_CONNECT") || it.contains("TIMEOUT") }) Step.Status.WARN else Step.Status.OK
            finish(
                7, "Чтение стандартных кодов ошибок", status, System.currentTimeMillis() - t0,
                "Сохранённых: ${dtc.stored.size}, ожидающих: ${dtc.pending.size}, постоянных: ${dtc.permanent.size}" +
                    (if (dtc.errors.isNotEmpty()) "; " + dtc.errors.joinToString("; ") else ""),
            )
        }

        // 8. Timing
        markRunning(8, "Анализ времени отклика")
        t0 = System.currentTimeMillis()
        val stats = repo.elm?.stats?.value
        finish(
            8, "Анализ времени отклика", Step.Status.OK, System.currentTimeMillis() - t0,
            stats?.let {
                "Запросов: ${it.sent}, успешно: ${it.ok}, таймаутов: ${it.timeouts}, ошибок адаптера: ${it.adapterErrors}; " +
                    "среднее ${it.avgMs} мс, мин ${if (it.minMs == Long.MAX_VALUE) 0 else it.minMs} мс, макс ${it.maxMs} мс"
            } ?: "нет статистики",
        )

        // 9. Report summary (constructed by caller from Result)
        finish(9, "Формирование подробного отчёта", Step.Status.OK, 0, "Данные собраны; экспорт — кнопкой «Экспорт отчёта»")

        return Result(steps, supported, readings, dtc, vin, voltage, started)
    }

    fun formatValue(v: Double): String = if (v == v.toLong().toDouble()) "%d".format(v.toLong()) else "%.2f".format(v).trimEnd('0', '.').replace('.', ',')
}
