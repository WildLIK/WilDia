package com.obd2.app.core.telemetry

import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidResult
import kotlinx.serialization.Serializable

@Serializable
data class ChartSample(
    val timestampMs: Long,
    val value: Double,
)

data class PidSeriesStats(
    val pid: Int,
    val nameRu: String,
    val unit: String,
    val min: Double?,
    val avg: Double?,
    val max: Double?,
    val latest: Double?,
    val count: Int,
    val available: Boolean,
) {
    val latestDisplay: String
        get() = if (!available || latest == null) {
            "Нет данных"
        } else {
            val v = if (latest == latest.toLong().toDouble()) latest.toLong().toString() else "%.2f".format(latest)
            if (unit.isBlank()) v else "$v $unit"
        }
}

@Serializable
data class ChartPreset(
    val id: String,
    val name: String,
    val pids: List<Int>,
)

enum class ChartTimeWindow(val labelRu: String, val durationMs: Long) {
    SEC_30("30 с", 30_000L),
    MIN_1("1 мин", 60_000L),
    MIN_5("5 мин", 300_000L),
    MIN_15("15 мин", 900_000L),
    ALL("Все", Long.MAX_VALUE),
}

data class UnitChartGroup(
    val unit: String,
    val pids: List<Int>,
)

/**
 * Single-source-of-truth in-memory telemetry buffer for real-time & historical charts.
 *
 * Consumes samples from the existing [DiagnosticRepository.liveData] stream
 * (never starts a parallel ELM327 polling loop) and never substitutes zeroes
 * for unsupported/missing PIDs.
 */
class TelemetryChartBuffer(
    private val maxSamplesPerPid: Int = 2000,
) {
    private val series = LinkedHashMap<Int, ArrayDeque<ChartSample>>()
    private val metaName = LinkedHashMap<Int, String>()
    private val metaUnit = LinkedHashMap<Int, String>()
    private val unsupported = HashSet<Int>()

    @Volatile
    var displayPaused: Boolean = false
        private set

    private var frozenAtMs: Long? = null

    fun setPaused(paused: Boolean, nowMs: Long = System.currentTimeMillis()) {
        displayPaused = paused
        frozenAtMs = if (paused) nowMs else null
    }

    @Synchronized
    fun ingestLiveSnapshot(
        live: Map<Int, PidResult.Ok>,
        unsupportedPids: Set<Int>,
        registry: List<PidDefinition>,
    ) {
        unsupported.clear()
        unsupported.addAll(unsupportedPids)
        val regByPid = if (registry.isNotEmpty()) registry else emptyList()
        for ((pid, ok) in live) {
            if (!metaName.containsKey(pid) || !metaUnit.containsKey(pid) || ok.reading.unit != null) {
                val def = regByPid.firstOrNull { it.mode == 1 && it.pid == pid }
                if (!metaName.containsKey(pid)) {
                    metaName[pid] = def?.displayName ?: "PID %02X".format(pid)
                }
                metaUnit[pid] = ok.reading.unit ?: metaUnit[pid] ?: def?.unit ?: ""
            }
            val v = ok.reading.value ?: continue
            val q = series.getOrPut(pid) { ArrayDeque() }
            val last = q.lastOrNull()
            if (last == null || last.timestampMs != ok.reading.timestampMs) {
                q.addLast(ChartSample(ok.reading.timestampMs, v))
                while (q.size > maxSamplesPerPid) q.removeFirst()
            }
        }
    }

    @Synchronized
    fun loadHistoricalSamples(
        samplesByPid: Map<Int, List<ChartSample>>,
        unitsByPid: Map<Int, String>,
        namesByPid: Map<Int, String>,
    ) {
        series.clear()
        metaUnit.clear()
        metaName.clear()
        unsupported.clear()
        for ((pid, list) in samplesByPid) {
            val q = ArrayDeque<ChartSample>(list.size)
            list.sortedBy { it.timestampMs }.forEach { q.addLast(it) }
            series[pid] = q
            metaUnit[pid] = unitsByPid[pid] ?: ""
            metaName[pid] = namesByPid[pid] ?: "PID %02X".format(pid)
        }
    }

    @Synchronized
    fun clear() {
        series.clear()
        unsupported.clear()
    }

    private fun sliceDeque(
        q: ArrayDeque<ChartSample>,
        window: ChartTimeWindow,
        scrollFraction: Float,
        nowMs: Long,
    ): List<ChartSample> {
        if (q.isEmpty()) return emptyList()
        val effectiveEnd = frozenAtMs ?: maxOf(nowMs, q.last().timestampMs)
        var endExclusive = q.size
        while (endExclusive > 0 && q[endExclusive - 1].timestampMs > effectiveEnd) {
            endExclusive--
        }
        if (endExclusive == 0) return emptyList()

        var startIdx = 0
        if (window != ChartTimeWindow.ALL) {
            val minTs = q[0].timestampMs
            val maxTs = q[endExclusive - 1].timestampMs
            val span = (maxTs - minTs).coerceAtLeast(0L)
            if (span > window.durationMs) {
                val maxStart = maxTs - window.durationMs
                val clamped = scrollFraction.coerceIn(0f, 1f)
                val startTs = minTs + ((maxStart - minTs) * clamped).toLong()
                val endTs = startTs + window.durationMs
                while (startIdx < endExclusive && q[startIdx].timestampMs < startTs) {
                    startIdx++
                }
                while (endExclusive > startIdx && q[endExclusive - 1].timestampMs > endTs) {
                    endExclusive--
                }
            }
        }
        val count = endExclusive - startIdx
        if (count <= 0) return emptyList()
        val out = ArrayList<ChartSample>(count)
        for (i in startIdx until endExclusive) {
            out.add(q[i])
        }
        return out
    }

    @Synchronized
    fun samplesFor(
        pid: Int,
        window: ChartTimeWindow,
        scrollFraction: Float = 1f, // 0f = oldest, 1f = newest
        nowMs: Long = System.currentTimeMillis(),
    ): List<ChartSample> {
        val q = series[pid] ?: return emptyList()
        return sliceDeque(q, window, scrollFraction, nowMs)
    }

    /**
     * Single-pass stats + downsampled points for Compose rendering (caps vertices at [maxRenderPoints]
     * via min-max bucketing while computing exact stats over all points in the window).
     */
    @Synchronized
    fun snapshotForChart(
        pid: Int,
        registry: List<PidDefinition>,
        window: ChartTimeWindow = ChartTimeWindow.ALL,
        scrollFraction: Float = 1f,
        maxRenderPoints: Int = 240,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<PidSeriesStats, List<ChartSample>> {
        val name = metaName[pid] ?: registry.firstOrNull { it.mode == 1 && it.pid == pid }?.displayName ?: "PID %02X".format(pid)
        val unit = metaUnit[pid] ?: registry.firstOrNull { it.mode == 1 && it.pid == pid }?.unit ?: ""
        val q = series[pid]
        val pts = if (q == null || pid in unsupported) emptyList() else sliceDeque(q, window, scrollFraction, nowMs)
        if (pts.isEmpty()) {
            return PidSeriesStats(pid, name, unit, null, null, null, null, 0, available = false) to emptyList()
        }
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (p in pts) {
            if (p.value < minV) minV = p.value
            if (p.value > maxV) maxV = p.value
            sum += p.value
        }
        val stats = PidSeriesStats(
            pid = pid,
            nameRu = name,
            unit = unit,
            min = minV,
            avg = sum / pts.size,
            max = maxV,
            latest = pts.last().value,
            count = pts.size,
            available = true,
        )
        return stats to downsampleMinMax(pts, maxRenderPoints)
    }

    private fun downsampleMinMax(pts: List<ChartSample>, maxPoints: Int): List<ChartSample> {
        if (pts.size <= maxPoints || maxPoints < 4) return pts
        val buckets = (maxPoints - 2) / 2
        if (buckets <= 0) return pts
        val out = ArrayList<ChartSample>(maxPoints)
        out.add(pts.first())
        val innerSize = pts.size - 2
        val step = innerSize.toDouble() / buckets.toDouble()
        for (b in 0 until buckets) {
            val s = 1 + (b * step).toInt()
            val e = (1 + ((b + 1) * step).toInt()).coerceAtMost(pts.size - 1)
            if (s >= e) continue
            var minP = pts[s]
            var maxP = pts[s]
            for (i in s + 1 until e) {
                val p = pts[i]
                if (p.value < minP.value) minP = p
                if (p.value > maxP.value) maxP = p
            }
            if (minP.timestampMs <= maxP.timestampMs) {
                out.add(minP)
                if (maxP !== minP) out.add(maxP)
            } else {
                out.add(maxP)
                out.add(minP)
            }
        }
        out.add(pts.last())
        return out
    }

    @Synchronized
    fun statsFor(
        pid: Int,
        registry: List<PidDefinition>,
        window: ChartTimeWindow = ChartTimeWindow.ALL,
        scrollFraction: Float = 1f,
    ): PidSeriesStats {
        val def = registry.firstOrNull { it.mode == 1 && it.pid == pid }
        val name = metaName[pid] ?: def?.displayName ?: "PID %02X".format(pid)
        val unit = metaUnit[pid] ?: def?.unit ?: ""
        val pts = samplesFor(pid, window, scrollFraction)
        if (pts.isEmpty() || pid in unsupported) {
            return PidSeriesStats(
                pid = pid,
                nameRu = name,
                unit = unit,
                min = null,
                avg = null,
                max = null,
                latest = null,
                count = 0,
                available = false,
            )
        }
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (p in pts) {
            if (p.value < minV) minV = p.value
            if (p.value > maxV) maxV = p.value
            sum += p.value
        }
        return PidSeriesStats(
            pid = pid,
            nameRu = name,
            unit = unit,
            min = minV,
            avg = sum / pts.size,
            max = maxV,
            latest = pts.last().value,
            count = pts.size,
            available = true,
        )
    }

    @Synchronized
    fun groupByUnit(selectedPids: List<Int>, registry: List<PidDefinition>): List<UnitChartGroup> {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for (pid in selectedPids) {
            val unit = metaUnit[pid]?.takeIf { it.isNotBlank() }
                ?: registry.firstOrNull { it.mode == 1 && it.pid == pid }?.unit?.takeIf { it.isNotBlank() }
                ?: "ед."
            groups.getOrPut(unit) { ArrayList() }.add(pid)
        }
        return groups.map { (u, pids) -> UnitChartGroup(u, pids) }
    }

    companion object {
        val DEFAULT_CHART_PIDS = listOf(0x0C, 0x05, 0x0D, 0x42, 0x04, 0x06)

        val BUILTIN_PRESETS = listOf(
            ChartPreset("engine_core", "Двигатель (RPM / ОЖ / Нагрузка)", listOf(0x0C, 0x05, 0x04)),
            ChartPreset("fuel_trim", "Топливные коррекции (STFT / LTFT)", listOf(0x06, 0x07, 0x0C)),
            ChartPreset("power_speed", "Скорость и бортсеть", listOf(0x0D, 0x42, 0x0C)),
        )
    }
}
