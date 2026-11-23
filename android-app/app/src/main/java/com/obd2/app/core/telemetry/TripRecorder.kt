package com.obd2.app.core.telemetry

import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.db.TripRecordingEntity
import com.obd2.app.core.db.TripSampleEntity
import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveRecordingState(
    val recordingId: Long = -1L,
    val startedAtMs: Long = 0L,
    val selectedPids: List<Int> = emptyList(),
    val sampleCount: Int = 0,
    val errorCount: Int = 0,
    val maxDurationSec: Int = 3600,
    val maxSamples: Int = 20_000,
) {
    val active: Boolean get() = recordingId > 0
}

/**
 * Incremental, crash-safe trip telemetry recorder backed by Room (`trip_recordings` + `trip_samples`).
 *
 * - Consumes snapshots from the single existing liveData polling loop (never opens a parallel ELM327 poll).
 * - Batches/throttles disk flushes (every [flushIntervalMs] or [flushBatchSize] samples) to protect flash storage.
 * - Enforces configurable [maxDurationSec] and [maxSamples] limits.
 * - Preserves partial results on abrupt crash (recovered as INTERRUPTED on next cold start).
 */
class TripRecorder(
    private val db: AppDb,
    private val flushIntervalMs: Long = 1500L,
    private val flushBatchSize: Int = 15,
) {
    private val mutex = Mutex()
    private val pendingSamples = ArrayList<TripSampleEntity>()
    private val lastRecordedTimestampByPid = HashMap<Int, Long>()
    private var lastFlushAtMs: Long = 0L

    private val _state = MutableStateFlow(ActiveRecordingState())
    val state: StateFlow<ActiveRecordingState> = _state

    suspend fun startRecording(
        sessionId: Long?,
        selectedPids: List<Int>,
        vehicleLabel: String,
        maxDurationSec: Int = 3600,
        maxSamples: Int = 20_000,
    ): Long = mutex.withLock {
        if (_state.value.active) {
            stopRecordingLocked(SessionStatus.COMPLETED, "Автозавершение перед новой записью")
        }
        val now = System.currentTimeMillis()
        val pids = selectedPids.distinct()
        val recId = db.tripRecordingDao().insertRecording(
            TripRecordingEntity(
                sessionId = sessionId?.takeIf { it > 0 },
                startedAtMs = now,
                updatedAtMs = now,
                status = SessionStatus.ACTIVE,
                selectedPidsCsv = pids.joinToString(",") { "%02X".format(it) },
                maxDurationSec = maxDurationSec,
                maxSamples = maxSamples,
                vehicleLabel = vehicleLabel,
            ),
        )
        pendingSamples.clear()
        lastRecordedTimestampByPid.clear()
        lastFlushAtMs = now
        _state.value = ActiveRecordingState(
            recordingId = recId,
            startedAtMs = now,
            selectedPids = pids,
            maxDurationSec = maxDurationSec,
            maxSamples = maxSamples,
        )
        recId
    }

    suspend fun ingestSnapshot(
        live: Map<Int, PidResult.Ok>,
        unsupportedPids: Set<Int>,
        registry: List<PidDefinition>,
        nowMs: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val st = _state.value
        if (!st.active) return@withLock

        val elapsedSec = ((nowMs - st.startedAtMs) / 1000L).toInt()
        if (elapsedSec >= st.maxDurationSec) {
            stopRecordingLocked(SessionStatus.COMPLETED, "Достигнут лимит длительности (${st.maxDurationSec} с)")
            return@withLock
        }

        var addedSamples = 0
        var addedErrors = 0
        for (pid in st.selectedPids) {
            if (st.sampleCount + pendingSamples.size >= st.maxSamples) break
            val ok = live[pid]
            if (ok != null) {
                val ts = ok.reading.timestampMs
                if (lastRecordedTimestampByPid[pid] != ts) {
                    lastRecordedTimestampByPid[pid] = ts
                    val def = registry.firstOrNull { it.mode == 1 && it.pid == pid }
                    val v = ok.reading.value
                    pendingSamples += TripSampleEntity(
                        recordingId = st.recordingId,
                        timestampMs = ts,
                        pid = pid,
                        value = v,
                        unit = ok.reading.unit ?: def?.unit,
                        status = if (v != null) "OK" else "ERROR",
                        errorNote = if (v == null) "Нет числового значения" else null,
                    )
                    addedSamples++
                    if (v == null) addedErrors++
                }
            } else if (pid in unsupportedPids && lastRecordedTimestampByPid[pid] == null) {
                lastRecordedTimestampByPid[pid] = nowMs
                pendingSamples += TripSampleEntity(
                    recordingId = st.recordingId,
                    timestampMs = nowMs,
                    pid = pid,
                    value = null,
                    unit = null,
                    status = "UNSUPPORTED",
                    errorNote = "PID не поддерживается ЭБУ",
                )
                addedSamples++
                addedErrors++
            }
        }

        if (addedSamples > 0) {
            val newTotal = st.sampleCount + addedSamples
            val newErrors = st.errorCount + addedErrors
            _state.value = st.copy(sampleCount = newTotal, errorCount = newErrors)
        }

        val shouldFlush = pendingSamples.size >= flushBatchSize ||
            (pendingSamples.isNotEmpty() && nowMs - lastFlushAtMs >= flushIntervalMs)
        if (shouldFlush) {
            flushLocked(nowMs)
        }

        if (_state.value.sampleCount >= st.maxSamples) {
            stopRecordingLocked(SessionStatus.COMPLETED, "Достигнут лимит размера (${st.maxSamples} точек)")
        }
    }

    suspend fun flushNow() = mutex.withLock {
        if (_state.value.active && pendingSamples.isNotEmpty()) {
            flushLocked(System.currentTimeMillis())
        }
    }

    suspend fun stopRecording(
        status: String = SessionStatus.COMPLETED,
        notes: String? = null,
    ) = mutex.withLock {
        stopRecordingLocked(status, notes)
    }

    private suspend fun flushLocked(nowMs: Long) {
        val st = _state.value
        if (!st.active || pendingSamples.isEmpty()) return
        val batch = pendingSamples.toList()
        pendingSamples.clear()
        db.tripRecordingDao().insertSamples(batch)
        db.tripRecordingDao().updateRecordingProgress(
            id = st.recordingId,
            updatedAtMs = nowMs,
            sampleCount = st.sampleCount,
            errorCount = st.errorCount,
        )
        lastFlushAtMs = nowMs
    }

    private suspend fun stopRecordingLocked(status: String, notes: String?) {
        val st = _state.value
        if (!st.active) return
        val now = System.currentTimeMillis()
        flushLocked(now)
        db.tripRecordingDao().closeRecording(
            id = st.recordingId,
            endedAtMs = now,
            status = status,
            sampleCount = st.sampleCount,
            errorCount = st.errorCount,
            notes = notes,
        )
        pendingSamples.clear()
        lastRecordedTimestampByPid.clear()
        _state.value = ActiveRecordingState()
    }
}
