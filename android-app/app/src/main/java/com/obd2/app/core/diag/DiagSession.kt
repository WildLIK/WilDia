package com.obd2.app.core.diag

import com.obd2.app.core.log.RawLog
import java.io.File

/**
 * Central diagnostic session. Hooks receive structured events from
 * BleScanner / BleTransport / Elm327Client / OBD reads and feed them into the
 * rolling [DiagJournal] plus typed collectors used by the exporter and the
 * cause analyzer.
 *
 * All methods are safe to call from any thread; diagnostics must never crash
 * the app, so collectors swallow their own failures.
 */
class DiagSession(
    baseDir: File,
    private val rawLog: RawLog,
) {
    val journal = DiagJournal(baseDir)

    // ---------------- scan ----------------
    @Volatile var scanInfo = ScanSessionInfo()
        private set
    private val sightings = java.util.concurrent.ConcurrentHashMap<String, MutableList<ScanSighting>>()
    private val classicDevices = java.util.concurrent.ConcurrentHashMap<String, ClassicScanRecord>()

    // ---------------- gatt / connection ----------------
    val gattEvents = java.util.Collections.synchronizedList(ArrayList<DiagEvent>())
    val stats = ConnectionStats()

    // ---------------- elm ----------------
    val elmCommands = java.util.Collections.synchronizedList(ArrayList<ElmCommandRecord>())
    val obdResults = java.util.Collections.synchronizedList(ArrayList<ObdParamResult>())

    @Volatile var selectedTarget: String? = null
    @Volatile var connectedName: String? = null
    @Volatile var transportUsed: String? = null
    @Volatile var activeScenario: String = ""
    @Volatile var sessionStartedAt: Long = 0

    /** Full GATT tree JSON captured on discovery (set by BleTransport). */
    @Volatile var gattTreeJson: String? = null

    /** Standard Mode-01 PIDs the ECU declared via support bitmasks. */
    val supportedPids = java.util.Collections.synchronizedList(ArrayList<Int>())

    // ===================== events ===================== //

    fun event(
        category: DiagCategory,
        op: String,
        resultCode: String? = null,
        durationMs: Long? = null,
        prevState: String? = null,
        newState: String? = null,
        details: String? = null,
        severity: DiagSeverity = DiagSeverity.INFO,
    ) {
        val e = DiagEvent(System.currentTimeMillis(), category, op, resultCode, durationMs, prevState, newState, details, severity)
        journal.add(e)
        if (category == DiagCategory.GATT) gattEvents.add(e)
        if (severity == DiagSeverity.ERROR) {
            rawLog.log(RawLog.Dir.ERROR, "[DIAG] $op: ${details ?: ""} ${resultCode ?: ""}")
        }
    }

    // ===================== scan ===================== //

    fun scanStarted(seconds: Int) {
        scanInfo = ScanSessionInfo(startedAt = System.currentTimeMillis(), scanSeconds = seconds)
        event(DiagCategory.SCAN, "scan.start", details = "duration=${seconds}s; $scanInfo", newState = "SCANNING")
    }

    fun scanSighting(s: ScanSighting) {
        val list = sightings.getOrPut(s.mac) { java.util.Collections.synchronizedList(ArrayList()) }
        list.add(s)
        if (s.name.uppercase().contains("VLINK")) {
            scanInfo = scanInfo.copy(vlinkSeen = true)
        }
    }

    fun scanFinished(result: String, failureCode: String? = null) {
        val vlinkCount = sightings.entries.count { it.key.let { mac -> deviceName(mac) }.uppercase().contains("VLINK") }
        val vlinkAdvChanged = sightings.entries.any { (mac, list) ->
            deviceName(mac).uppercase().contains("VLINK") && list.size > 1 &&
                list.map { it.manufacturerDataHex }.distinct().size > 1
        }
        scanInfo = scanInfo.copy(finishedAt = System.currentTimeMillis(), result = result,
            failureCode = failureCode, vlinkSeen = scanInfo.vlinkSeen, vlinkAdvertisementChanged = vlinkAdvChanged)
        event(DiagCategory.SCAN, "scan.finish", resultCode = failureCode, details = result,
            prevState = "SCANNING", newState = "IDLE")
    }

    private fun deviceName(mac: String): String =
        sightings[mac]?.firstOrNull()?.name ?: ""

    fun scanSummaries(): List<ScanDeviceSummary> =
        sightings.entries.map { (mac, list) ->
            val sorted = list.sortedBy { it.ts }
            val rssi = sorted.map { it.rssi }
            ScanDeviceSummary(
                mac = mac,
                name = sorted.firstOrNull { it.name.isNotBlank() }?.name ?: sorted.first().name,
                firstSeen = sorted.first().ts,
                lastSeen = sorted.last().ts,
                count = sorted.size,
                rssiFirst = rssi.first(),
                rssiLast = rssi.last(),
                rssiMin = rssi.min(),
                rssiMax = rssi.max(),
                rssiTimeline = sorted.map { it.ts to it.rssi },
                advServiceUuids = sorted.flatMap { it.advServiceUuids }.distinct(),
                manufacturerDataHex = sorted.mapNotNull { it.manufacturerDataHex }.distinct().joinToString(" | "),
                serviceDataHex = sorted.map { it.serviceDataHex }.fold(mutableMapOf()) { acc, m -> acc.putAll(m); acc },
                advFlags = sorted.mapNotNull { it.advFlags }.distinct().firstOrNull(),
                isConnectable = sorted.mapNotNull { it.isConnectable }.distinct().firstOrNull(),
            )
        }.sortedByDescending { it.count }

    fun recordClassicDevice(record: ClassicScanRecord) {
        val existing = classicDevices[record.mac]
        val mergedUuids = ((existing?.sdpUuids.orEmpty()) + record.sdpUuids).distinct()
        classicDevices[record.mac] = if (existing == null) {
            record
        } else {
            record.copy(
                name = record.name.ifBlank { existing.name },
                rssi = record.rssi ?: existing.rssi,
                sdpUuids = mergedUuids,
                sppStatus = if (record.sdpUuids.isNotEmpty() || existing.sdpUuids.isEmpty()) record.sppStatus else existing.sppStatus,
            )
        }
    }

    fun classicSummaries(): List<ClassicScanRecord> =
        classicDevices.values.sortedByDescending { it.ts }

    fun resetScanSession() {
        sightings.clear()
        classicDevices.clear()
        transportUsed = null
        scanInfo = ScanSessionInfo()
    }

    // ===================== gatt / connection ===================== //

    fun connectAttemptStarted(target: String) {
        selectedTarget = target
        stats.attempts += 1
        event(DiagCategory.GATT, "connect.attempt", details = "target=$target (попытка ${stats.attempts})",
            newState = "CONNECTING")
    }

    fun connectSucceeded(durationMs: Long, mtu: Int?, rssi: Int?) {
        stats.successes += 1
        stats.connectDurationsMs.add(durationMs)
        if (mtu != null) stats.negotiatedMtu = mtu
        if (rssi != null) stats.rssi = rssi
        event(DiagCategory.GATT, "connect.ok", resultCode = "GATT_SUCCESS",
            durationMs = durationMs, newState = "CONNECTED",
            details = "mtu=$mtu rssi=${rssi ?: "недоступно"}")
    }

    fun connectFailed(reason: String, code: String?) {
        stats.failures += 1
        if (code?.contains("timeout", ignoreCase = true) == true) stats.timeouts += 1
        if (code != null) stats.gattErrors.add(code)
        event(DiagCategory.GATT, "connect.fail", resultCode = code, newState = "FAILED",
            details = reason, severity = DiagSeverity.ERROR)
    }

    fun disconnected(reason: String) {
        stats.disconnects += 1
        stats.disconnectReasons.add(reason)
        event(DiagCategory.GATT, "connect.lost", newState = "DISCONNECTED",
            details = reason, severity = DiagSeverity.WARN)
    }

    // ===================== elm / obd ===================== //

    fun elmRecord(r: ElmCommandRecord) {
        elmCommands.add(r)
        if (r.errorKind != null) {
            event(DiagCategory.ELM, "elm.${r.cmd}", resultCode = r.resultCode,
                durationMs = r.durationMs, details = r.errorKind, severity = DiagSeverity.WARN)
        }
        if (stats.timeToFirstAdapterResponseMs == null && r.firstByteMs != null) {
            stats.timeToFirstAdapterResponseMs = r.firstByteMs
        }
    }

    fun obdRecord(r: ObdParamResult) {
        obdResults.add(r)
        val sev = when (r.validity) {
            ObdValidity.OK -> DiagSeverity.INFO
            ObdValidity.READ_FAILED, ObdValidity.INVALID_RESPONSE -> DiagSeverity.WARN
            else -> DiagSeverity.INFO
        }
        event(DiagCategory.OBD, "pid ${r.pid}", resultCode = r.validity.name,
            durationMs = r.durationMs, details = "raw=${r.raw.take(120)}", severity = sev)
    }

    // ===================== lifecycle ===================== //

    fun startSession(scenario: String) {
        sessionStartedAt = System.currentTimeMillis()
        activeScenario = scenario
        resetScanSession()
        event(DiagCategory.SYS, "session.start", details = "scenario=$scenario", newState = "ACTIVE")
    }

    fun endSession(result: String) {
        event(DiagCategory.SYS, "session.end", details = result, prevState = "ACTIVE", newState = "IDLE")
        journal.writeSnapshot("session_end", "Завершение диагностической сессии: $result")
    }

    fun close() {
        journal.close()
    }
}
