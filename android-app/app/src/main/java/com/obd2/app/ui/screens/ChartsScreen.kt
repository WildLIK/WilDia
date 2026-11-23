package com.obd2.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.obd2.app.core.telemetry.ChartPreset
import com.obd2.app.core.telemetry.ChartSample
import com.obd2.app.core.telemetry.ChartTimeWindow
import com.obd2.app.core.telemetry.TelemetryChartBuffer
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Accent
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim
import kotlinx.serialization.json.Json

private val availableChartPids = listOf(
    0x0C to "Обороты",
    0x05 to "Темп. ОЖ",
    0x0D to "Скорость",
    0x42 to "Напряжение",
    0x04 to "Нагрузка",
    0x06 to "STFT",
    0x07 to "LTFT",
    0x0F to "Темп. впуска",
    0x0B to "MAP",
    0x11 to "Дроссель",
    0x10 to "MAF",
)

private val seriesColors = listOf(Green, Accent, Amber, Red, Color(0xFFAB47BC), Color(0xFF26C6DA))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartsScreen(vm: ObdViewModel) {
    val selectedPids by vm.selectedChartPids.collectAsState()
    val viewingRecId by vm.chartViewingRecordingId.collectAsState()
    val registry by vm.pidRegistry.collectAsState()
    val settings by vm.settingsFlow.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val tick by vm.chartTick.collectAsState()
    val polling by vm.isPolling.collectAsState()
    val connection by vm.connection.collectAsState()

    var paused by remember { mutableStateOf(vm.chartBuffer.displayPaused) }
    var window by remember { mutableStateOf(ChartTimeWindow.MIN_5) }
    var scrollFraction by remember { mutableFloatStateOf(1f) }
    var showPidSelector by remember { mutableStateOf(false) }

    val savedPresets = remember(settings.chartPresetsJson) {
        val custom = runCatching {
            if (settings.chartPresetsJson.isBlank()) emptyList()
            else Json { ignoreUnknownKeys = true }.decodeFromString<List<ChartPreset>>(settings.chartPresetsJson)
        }.getOrDefault(emptyList())
        TelemetryChartBuffer.BUILTIN_PRESETS + custom
    }

    val connected = connection is com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected

    // Ensure shared repository polling feeds the chart buffer (no parallel ELM327 loop).
    LaunchedEffect(connected, viewingRecId) {
        if (connected && viewingRecId == null && !polling && selectedPids.isNotEmpty()) {
            vm.startPolling(selectedPids)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = if (viewingRecId != null) "График поездки #$viewingRecId" else "Графики параметров (в реальном времени)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (viewingRecId != null) {
                    OutlinedButton(onClick = { vm.clearHistoricalChartMode() }, modifier = Modifier.weight(1f)) {
                        Text("Вернуться к live")
                    }
                } else {
                    Button(
                        onClick = {
                            if (polling) vm.stopPolling()
                            else vm.startPolling(selectedPids)
                        },
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (polling) "Стоп опроса" else "Запустить опрос")
                    }
                }
                OutlinedButton(
                    onClick = {
                        paused = !paused
                        vm.chartBuffer.setPaused(paused)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (paused) "Продолжить экран" else "Пауза экрана")
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("Масштаб времени:", style = MaterialTheme.typography.bodySmall, color = TextDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartTimeWindow.entries.forEach { w ->
                    FilterChip(
                        selected = window == w,
                        onClick = { window = w },
                        label = { Text(w.labelRu) },
                    )
                }
            }
            if (window != ChartTimeWindow.ALL) {
                Text("Прокрутка истории (${(scrollFraction * 100).toInt()}%):", style = MaterialTheme.typography.bodySmall, color = TextDim)
                Slider(value = scrollFraction, onValueChange = { scrollFraction = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showPidSelector = !showPidSelector }) {
                    Text(if (showPidSelector) "Скрыть выбор PID" else "Настроить PID (${selectedPids.size})")
                }
                TextButton(onClick = { vm.saveCurrentChartPreset("Набор ${selectedPids.size} PID") }) {
                    Text("Сохранить набор")
                }
            }

            if (showPidSelector) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    availableChartPids.forEach { (pid, title) ->
                        FilterChip(
                            selected = pid in selectedPids,
                            onClick = { vm.toggleChartPid(pid) },
                            label = { Text(title) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Сохранённые наборы:", style = MaterialTheme.typography.bodySmall, color = TextDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    savedPresets.forEach { preset ->
                        OutlinedButton(onClick = { vm.applyChartPreset(preset.pids) }) {
                            Text(preset.name)
                        }
                    }
                }
            }
        }

        val unitGroups = vm.chartBuffer.groupByUnit(selectedPids, registry)
        unitGroups.forEach { group ->
            Panel(title = "График (${group.unit})") {
                val seriesList = group.pids.mapIndexed { idx, pid ->
                    val stats = vm.chartBuffer.statsFor(pid, registry, window, scrollFraction)
                    val pts = vm.chartBuffer.samplesFor(pid, window, scrollFraction)
                    Triple(stats, pts, seriesColors[idx % seriesColors.size])
                }
                val hasAnyData = seriesList.any { it.second.isNotEmpty() }
                if (!hasAnyData) {
                    Text("Нет данных для выбранных параметров (${group.unit}).", color = TextDim, style = MaterialTheme.typography.bodySmall)
                } else {
                    MultiSeriesChartCanvas(seriesList.map { it.second to it.third })
                }
                Spacer(Modifier.height(6.dp))
                seriesList.forEach { (stats, _, color) ->
                    val summary = if (stats.available) {
                        "${stats.latestDisplay} · мин ${"%.1f".format(stats.min)} / ср ${"%.1f".format(stats.avg)} / макс ${"%.1f".format(stats.max)}"
                    } else {
                        "Нет данных"
                    }
                    Text("■ ${stats.nameRu}: $summary", color = if (stats.available) color else TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MultiSeriesChartCanvas(series: List<Pair<List<ChartSample>, Color>>) {
    val allPoints = series.flatMap { it.first }
    if (allPoints.isEmpty()) return
    val minT = allPoints.minOf { it.timestampMs }
    val maxT = allPoints.maxOf { it.timestampMs }.coerceAtLeast(minT + 1L)
    val minV = allPoints.minOf { it.value }
    val maxV = allPoints.maxOf { it.value }.let { if (it <= minV) minV + 1.0 else it }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        val w = size.width
        val h = size.height
        // Grid lines
        for (i in 0..2) {
            val y = h * i / 2f
            drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for ((pts, color) in series) {
            if (pts.size < 2) continue
            val path = Path()
            pts.forEachIndexed { idx, p ->
                val x = ((p.timestampMs - minT).toFloat() / (maxT - minT).toFloat()) * w
                val y = h - (((p.value - minV) / (maxV - minV)).toFloat() * (h - 8f) + 4f)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 3f))
        }
    }
}
