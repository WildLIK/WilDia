package com.obd2.app.core.obd

/** One Mode-01/09 PID definition, loaded from the bundled lookup DB. */
data class PidDefinition(
    val mode: Int,            // 1 or 9
    val pid: Int,             // 0x00..0xFF
    val nameEn: String,
    val nameRu: String?,
    val unit: String?,
    val bytes: Int?,
    val decoder: String,      // numeric | bitmap | status | o2_voltage | ... (see build_db.py)
    val weight: String?,      // A | AB | ABCD  (numeric decoder)
    val mult: Double?,
    val offset: Double?,
    val formula: String?,
    val category: String,
) {
    val hexPid: String get() = "%02X".format(pid)
    val displayName: String get() = nameRu ?: nameEn
    val command: String get() = "%02X%02X".format(mode, pid)
}

/** A decoded reading of a PID. Only values the ECU actually returned are real. */
data class PidReading(
    val mode: Int,
    val pid: Int,
    /** Numeric value when available. */
    val value: Double?,
    /** Secondary numeric values (O2 voltage+trim, lambda+current, multi sensors). */
    val value2: Double? = null,
    val unit: String? = null,
    val unit2: String? = null,
    /** Decoded textual representation (statuses, VIN, bitmap lists...). */
    val text: String? = null,
    val rawHex: String,
    val ecuResponses: Int = 1,
    val durationMs: Long = 0,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** Result of reading one PID over the wire. */
sealed interface PidResult {
    data class Ok(val reading: PidReading) : PidResult
    data class Unsupported(val mode: Int, val pid: Int, val reason: String) : PidResult
    data class Error(val mode: Int, val pid: Int?, val kind: String, val message: String, val raw: String = "") : PidResult
}

/** A single DTC read out of the vehicle (Mode 03/07/0A). */
data class DtcEntry(
    val code: String,          // e.g. P0300
    val mode: Int,             // 3 stored / 7 pending / 10 permanent
    val raw: String = "",
    val ecuId: String? = null,
)

/** Modes for DTC reading. */
object DtcModes {
    const val STORED = 3
    const val PENDING = 7
    const val PERMANENT = 10
}
