package com.obd2.app.core.obd

import com.obd2.app.core.repo.DiagnosticRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class QuickSensorReading(
    val pidHex: String,
    val nameRu: String,
    val valueText: String,
    val unit: String? = null,
)

data class QuickDiagnosticsResult(
    val completed: Boolean,
    val incomplete: Boolean,
    val protocolText: String?,
    val dtcScan: DiagnosticRepository.DtcScanResult?,
    val freezeFrame: FreezeFrameReport?,
    val readiness: ImReadinessReport?,
    val mode06: Mode06Report?,
    val supportedPidsCount: Int,
    val engineReadings: List<QuickSensorReading>,
    val sessionId: Long,
    val warnings: List<String>,
    val startedAtMs: Long,
    val finishedAtMs: Long,
) {
    val totalDtcCount: Int
        get() = dtcScan?.let { it.stored.size + it.pending.size + it.permanent.size } ?: 0

    val statusLabelRu: String
        get() = when {
            incomplete -> "Завершено частично (связь прервана или неполный ответ)"
            totalDtcCount > 0 -> "Обнаружены коды неисправности: $totalDtcCount"
            else -> "Ошибки не обнаружены"
        }
}

/**
 * Universal Mode 1: Quick Diagnostics ("Быстрая диагностика").
 * Works for ANY OBD-II vehicle without requiring manufacturer/model selection.
 * Strictly read-only (never sends Mode 04 or coding/service commands).
 */
class QuickDiagnosticsRunner(
    private val repo: DiagnosticRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val coreEnginePids = listOf(0x0C, 0x05, 0x04, 0x0D, 0x0B, 0x10, 0x11, 0x42)

    suspend fun run(
        onProgress: (stepText: String) -> Unit = {},
    ): QuickDiagnosticsResult {
        val started = System.currentTimeMillis()
        val warnings = ArrayList<String>()
        val obd = repo.obd
        if (obd == null || !repo.isConnectedNow) {
            return QuickDiagnosticsResult(
                completed = false,
                incomplete = true,
                protocolText = repo.elm?.info?.value?.protocol,
                dtcScan = null,
                freezeFrame = null,
                readiness = null,
                mode06 = null,
                supportedPidsCount = 0,
                engineReadings = emptyList(),
                sessionId = repo.currentSessionIdFlow.value,
                warnings = listOf("Нет активного подключения к адаптеру/ЭБУ."),
                startedAtMs = started,
                finishedAtMs = System.currentTimeMillis(),
            )
        }

        // 1. Protocol & supported PIDs
        onProgress("1/5: Проверка протокола и поддерживаемых PID…")
        val protocolText = repo.elm?.info?.value?.protocol
        val supportedSet: Set<Int> = repo.supportedPids.value ?: obd.discoverSupportedPids().supportedPids
        val initialSessionId = repo.currentSessionIdFlow.value

        // 2. Standard DTCs (Mode 03 / 07 / 0A) — saved incrementally per mode inside repo.readDtcs
        onProgress("2/5: Чтение кодов ошибок (Mode 03 / 07 / 0A)…")
        val dtcScan = repo.readDtcs(saveHistory = true)
        if (dtcScan.incomplete) {
            warnings += "Чтение ошибок завершилось частично: сохранённые результаты зафиксированы в истории."
        }
        warnings.addAll(dtcScan.errors)

        // 3. I/M Readiness (01 01)
        var readiness: ImReadinessReport? = null
        if (repo.isConnectedNow) {
            onProgress("3/5: Проверка готовности мониторов I/M Readiness…")
            readiness = obd.readImReadiness(timeoutMs = 3000)
            if (readiness == null) {
                warnings += "Мониторы I/M Readiness (01 01) не ответили."
            }
        }

        // 4. Freeze Frame (Mode 02)
        var freezeFrame: FreezeFrameReport? = null
        if (repo.isConnectedNow) {
            onProgress("4/5: Чтение стоп-кадра (Mode 02 Freeze Frame)…")
            freezeFrame = obd.readFreezeFrame(
                frameIndex = 0,
                registry = repo.pidRegistry.value,
                timeoutMs = 3000,
            )
            if (freezeFrame.unsupportedReason != null && freezeFrame.items.isEmpty()) {
                warnings += freezeFrame.unsupportedReason!!
            }
        }

        // 5. Mode 06 & Core Engine Parameters
        var mode06: Mode06Report? = null
        val readings = ArrayList<QuickSensorReading>()
        if (repo.isConnectedNow) {
            onProgress("5/5: Чтение Mode 06 и основных параметров двигателя…")
            mode06 = obd.readMode06(timeoutMs = 3000)
            if (mode06.unsupportedReason != null && mode06.results.isEmpty()) {
                warnings += mode06.unsupportedReason!!
            }
            val registry = repo.pidRegistry.value
            for (pid in coreEnginePids) {
                if (!repo.isConnectedNow) break
                if (supportedSet.isNotEmpty() && pid !in supportedSet) continue
                val def = registry.firstOrNull { it.mode == 1 && it.pid == pid } ?: continue
                when (val r = obd.readPid(def, timeoutMs = 2500)) {
                    is PidResult.Ok -> {
                        repo.publishLiveReading(pid, r)
                        val txt = r.reading.value?.let {
                            if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2f".format(it)
                        } ?: r.reading.text ?: r.reading.rawHex
                        readings += QuickSensorReading(
                            pidHex = "%02X".format(pid),
                            nameRu = def.displayName,
                            valueText = txt,
                            unit = r.reading.unit,
                        )
                    }
                    else -> {}
                }
            }
        }

        val sid = repo.currentSessionIdFlow.value.takeIf { it > 0 } ?: initialSessionId
        val ffJson = freezeFrame?.let { runCatching { json.encodeToString(it) }.getOrNull() }
        val rdJson = readiness?.let { runCatching { json.encodeToString(it) }.getOrNull() }
        if (sid > 0 && (ffJson != null || rdJson != null)) {
            repo.saveExtendedSessionArtifacts(
                sessionId = sid,
                freezeFrameJson = ffJson,
                readinessJson = rdJson,
                discoveredEcusJson = null,
            )
        }

        val incomplete = dtcScan.incomplete || !repo.isConnectedNow
        return QuickDiagnosticsResult(
            completed = !incomplete,
            incomplete = incomplete,
            protocolText = protocolText,
            dtcScan = dtcScan,
            freezeFrame = freezeFrame,
            readiness = readiness,
            mode06 = mode06,
            supportedPidsCount = supportedSet.size,
            engineReadings = readings,
            sessionId = sid,
            warnings = warnings.distinct(),
            startedAtMs = started,
            finishedAtMs = System.currentTimeMillis(),
        )
    }
}
