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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.core.lpg.omvl.OmvlDecodedValue
import com.obd2.app.core.lpg.omvl.OmvlFieldConfidence
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Accent
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OmvlSkyScreen(vm: ObdViewModel) {
    val ctrl = vm.omvlSkyController
    val spp = vm.omvlSppManager
    val state by ctrl.uiState.collectAsStateWithLifecycle()
    val sppTarget by spp.selectedAdapter.collectAsStateWithLifecycle()
    val sppState by spp.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ctrl.ensureLoaded()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = Amber.copy(alpha = 0.22f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.warningBannerRu,
                color = Amber,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        Panel(title = "ГБО → OMVL SAVER SKY → Демонстрация записи (Read-Only)") {
            KeyValue("Контроллер", state.controllerFamily.labelRu)
            KeyValue("Совместимость SKY 64", "Требуется отдельный захват (не подтверждено)")
            KeyValue("Передача в ЭБУ", "Отключена (строго офлайн)")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ctrl.togglePlay() }) {
                    Text(if (state.playing) "Пауза" else "Воспроизвести")
                }
                OutlinedButton(onClick = { ctrl.stepNext() }) {
                    Text("Следующий кадр")
                }
                OutlinedButton(onClick = { ctrl.resetReplay() }) {
                    Text("Сначала")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Кадр ${if (state.totalTelemetryFrames > 0) state.currentIndex + 1 else 0} из ${state.totalTelemetryFrames}",
                color = TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
            state.errorMessage?.let { err ->
                Text(err, color = Red, style = MaterialTheme.typography.bodySmall)
            }
        }

        val tel = state.currentTelemetry
        Panel(title = "Параметры работы ГБО (из записи 0x09)") {
            if (tel == null) {
                Text("Загрузка кадров записи…", color = TextDim)
            } else {
                fun <T> fmt(v: OmvlDecodedValue<T>, formatter: (T) -> String): String =
                    if (v.value != null && v.confidence != OmvlFieldConfidence.UNDECODED) {
                        val u = v.unit?.let { " $it" }.orEmpty()
                        "${formatter(v.value)}$u (${v.confidence.labelRu})"
                    } else {
                        "Не расшифровано"
                    }

                KeyValue("Обороты двигателя", fmt(tel.rpm) { "$it" })
                KeyValue("Давление газа", fmt(tel.gasPressureBar) { "%.2f".format(it) })
                KeyValue("Давление коллектора (MAP)", fmt(tel.mapBar) { "%.2f".format(it) })
                KeyValue("Напряжение питания", fmt(tel.supplyVoltageV) { "%.2f".format(it) })
                KeyValue("Бенз. впрыск (ср.)", fmt(tel.petrolInjAvgMs) { "%.3f".format(it) })
                KeyValue("Бенз. впрыск (цил. 1–4)", fmt(tel.petrolInjCylMs) { l -> l.joinToString(" / ") { "%.2f".format(it) } })
                KeyValue("Газовый впрыск (ср.)", fmt(tel.gasInjAvgMs) { "%.3f".format(it) })
                KeyValue("Газовый впрыск (цил. 1–4)", fmt(tel.gasInjCylMs) { l -> l.joinToString(" / ") { "%.2f".format(it) } })
                KeyValue("Температура газа", fmt(tel.gasTempC) { "%.1f".format(it) })
                KeyValue("Температура редуктора", fmt(tel.reducerTempC) { "%.1f".format(it) })
                KeyValue("Режим (бензин/газ)", fmt(tel.fuelMode) { it })
                KeyValue("Состояние форсунок", fmt(tel.injectorState) { it })
            }
        }

        if (state.rpmSeries.size >= 2) {
            Panel(title = "График параметров (RPM / впрыск мс)") {
                OmvlMiniChart(
                    series = listOf(
                        "RPM" to state.rpmSeries,
                        "Бенз. мс" to state.petrolInjSeries,
                        "Газ мс" to state.gasInjSeries,
                    ),
                )
            }
        }

        Panel(title = "Исторические ошибки ГБО E*** (${state.historicalDtcs.size})") {
            if (state.historicalDtcs.isEmpty()) {
                Text("Исторические ошибки в записи отсутствуют.", color = TextDim)
            } else {
                Text(
                    "Ошибки ГБО отделены от OBD2 (P****) и не сохраняются в историю автомобиля.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                state.historicalDtcs.forEach { d ->
                    val cntStr = d.occurrenceCount?.let { "повторов: $it" } ?: "счётчик не указан"
                    val ffStr = if (d.hasFreezeFrameRecord) " · есть снимок 0x35" else ""
                    Text(
                        "${d.code} — ${d.descriptionRu}",
                        color = Amber,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Историческая · $cntStr$ffStr",
                        color = TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Panel(title = "Подготовка Bluetooth Classic SPP (передача заблокирована)") {
            KeyValue("Стек", "Отдельный бинарный SPP-канал (без ELM327)")
            KeyValue("Адаптер", sppTarget?.let { "${it.name} (${it.mac})" } ?: "Не выбран")
            val sppStateLabel = when (val st = sppState) {
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.Idle -> "Ожидание"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.AdapterSelected -> "Выбран ${st.target.name}"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.Connecting -> "Подключение…"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.ConnectedPassiveRxOnly -> "Пассивный приём (${st.rxFrameCount} кадров)"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.TimedOut -> "Таймаут: ${st.reason}"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.Cancelled -> "Отменено"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.Disconnected -> "Отключено: ${st.reason}"
                is com.obd2.app.core.lpg.omvl.OmvlSppConnectionState.Failed -> st.reason
            }
            KeyValue("Состояние SPP", sppStateLabel)
            Text(
                "Отправка команд ЭБУ (0x04, 0x09, 0x0D, 0x32, 0x35 и др.) заблокирована до отдельного разрешения.",
                color = TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { spp.cancelConnect() }) { Text("Отмена") }
                OutlinedButton(onClick = { spp.disconnect() }) { Text("Отключить") }
                OutlinedButton(onClick = { spp.clearSelectedAdapter() }) { Text("Сбросить выбор") }
            }
        }
    }
}

@Composable
private fun OmvlMiniChart(series: List<Pair<String, List<Double>>>) {
    val colors = listOf(Green, Accent, Amber)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        series.forEachIndexed { idx, (label, vals) ->
            val last = vals.lastOrNull()
            val txt = if (last != null) "$label: ${if (idx == 0) last.toInt().toString() else "%.2f".format(last)}" else label
            Text(txt, color = colors[idx % colors.size], style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(6.dp))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        val w = size.width
        val h = size.height
        drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
        series.forEachIndexed { idx, (_, vals) ->
            if (vals.size < 2) return@forEachIndexed
            val minV = vals.minOrNull() ?: 0.0
            val maxV = vals.maxOrNull() ?: 1.0
            val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0
            val path = Path()
            vals.forEachIndexed { i, v ->
                val x = (i.toFloat() / (vals.size - 1).coerceAtLeast(1)) * w
                val y = (h - 8f) - (((v - minV) / span).toFloat() * (h - 16f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = colors[idx % colors.size], style = Stroke(width = 3f))
        }
    }
}
