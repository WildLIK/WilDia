package com.obd2.app.core.diag

/**
 * Technical diagnostic data models (connection diagnostics screen + export).
 *
 * Every record is factual: it is filled from measured events only. When a
 * metric cannot be measured through the public Android API the field is
 * absent or marked "недоступно" — never invented.
 */

enum class DiagCategory { SYS, PERM, SCAN, GATT, ELM, OBD, STATS }
enum class DiagSeverity { INFO, WARN, ERROR }

data class DiagEvent(
    val ts: Long,
    val category: DiagCategory,
    val op: String,
    val resultCode: String? = null,
    val durationMs: Long? = null,
    val prevState: String? = null,
    val newState: String? = null,
    val details: String? = null,
    val severity: DiagSeverity = DiagSeverity.INFO,
)

enum class StageState { PENDING, RUNNING, PASS, FAIL, SKIP, WARN }

data class DiagStageState(
    val id: String,
    val title: String,
    var state: StageState = StageState.PENDING,
    var startedAt: Long? = null,
    var durationMs: Long? = null,
    var currentOp: String = "",
    var resultText: String = "",
    val errors: MutableList<String> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
)

/** One BLE advertising sighting (multiple sightings per device are tracked). */
data class ScanSighting(
    val ts: Long,
    val mac: String,
    val name: String,
    val rssi: Int,
    val advServiceUuids: List<String>,
    val manufacturerDataHex: String?,
    val serviceDataHex: Map<String, String>,
    val advFlags: Int?,
    val isConnectable: Boolean?,
)

/** Aggregated per-device scan summary. */
data class ScanDeviceSummary(
    val mac: String,
    val name: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
    val rssiFirst: Int,
    val rssiLast: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val rssiTimeline: List<Pair<Long, Int>>,
    val advServiceUuids: List<String>,
    val manufacturerDataHex: String?,
    val serviceDataHex: Map<String, String>,
    val advFlags: Int?,
    val isConnectable: Boolean?,
)

data class ScanSessionInfo(
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val api: String = "BluetoothLeScanner.startScan (BLE, Android BLE API)",
    val filters: String = "none (пассивный сбор всех рекламных пакетов)",
    val settings: String = "SCAN_MODE_LOW_LATENCY, legacy=false",
    val scanSeconds: Int = 12,
    val result: String = "",           // completed / cancelled-by-user / timeout / failed: ...
    val failureCode: String? = null,
    val vlinkSeen: Boolean = false,
    val vlinkAdvertisementChanged: Boolean = false,
    val transportMode: String = "BLE",
)

/** One Bluetooth Classic discovery/bonded/SDP sighting. */
data class ClassicScanRecord(
    val mac: String,
    val name: String,
    val rssi: Int?,
    val deviceType: Int,
    val bondState: Int,
    val sdpUuids: List<String>,
    val sppStatus: String,
    val source: String,
    val ts: Long = System.currentTimeMillis(),
)

/** Structured GATT connection attempt statistics. */
data class ConnectionStats(
    var attempts: Int = 0,
    var successes: Int = 0,
    var failures: Int = 0,
    var disconnects: Int = 0,
    val disconnectReasons: MutableList<String> = mutableListOf(),
    val connectDurationsMs: MutableList<Long> = mutableListOf(),
    val discoveryDurationsMs: MutableList<Long> = mutableListOf(),
    val subscribeDurationsMs: MutableList<Long> = mutableListOf(),
    var timeouts: Int = 0,
    val gattErrors: MutableList<String> = mutableListOf(),
    var writeErrors: Int = 0,
    var notifyErrors: Int = 0,
    var negotiatedMtu: Int? = null,
    var rssi: Int? = null,
    var timeToFirstAdapterResponseMs: Long? = null,
) {
    fun minAvgMax(values: List<Long>): String =
        if (values.isEmpty()) "недоступно"
        else "min=${values.min()} avg=${values.sum() / values.size} max=${values.max()}"
}

/** One ELM327 command exchange record (raw, both HEX and ASCII). */
data class ElmCommandRecord(
    val ts: Long,
    val cmd: String,
    val writeUuid: String? = null,
    val writeResult: String = "",
    val firstByteMs: Long? = null,
    val responseHex: String = "",
    val responseAscii: String = "",
    val durationMs: Long? = null,
    val promptSeen: Boolean = false,
    val resultCode: String = "",
    val errorKind: String? = null,
    val fragments: Int = 0,
    val fragmentsMergedOk: Boolean = true,
    val unexpectedChars: List<String> = emptyList(),
    val terminatorMissing: Boolean = false,
    val bufferOverflow: Boolean = false,
    val requestCancelled: Boolean = false,
)

/** OBD parameter read result with the five-way validity status (§8). */
enum class ObdValidity { OK, READ_FAILED, INVALID_RESPONSE, NOT_SUPPORTED, NOT_CHECKED }

data class ObdParamResult(
    val pid: String,
    val raw: String,
    val decoded: String?,
    val unit: String?,
    val durationMs: Long?,
    val supportCheck: String = "",
    val validity: ObdValidity = ObdValidity.NOT_CHECKED,
    val error: String? = null,
)

/** Analysis conclusion: confirmed facts vs hypotheses (§12). */
data class Conclusion(
    val confirmed: Boolean,
    val text: String,
)
