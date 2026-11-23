package com.obd2.app.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.obd2.app.Screen
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.telemetry.TelemetryChartBuffer
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val recordablePids = listOf(
    0x0C to "Обороты",
    0x05 to "Темп. ОЖ",
    0x0D to "Скорость",
    0x42 to "Напряжение",
    0x04 to "Нагрузка",
    0x06 to "STFT",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripRecordingsScreen(vm: ObdViewModel, nav: NavHostController) {
    val activeState by vm.tripRecordingState.collectAsState()
    val recordings by vm.tripRecordings.collectAsState()
    var selectedPids by remember { mutableStateOf(TelemetryChartBuffer.DEFAULT_CHART_PIDS) }
    var notice by remember { mutableStateOf<String?>(null) }
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Запись поездки") {
            Text(
                "Данные сохраняются в базу Room порциями по мере поступления с ограничением по времени и размеру, чтобы сохраняться при сбоях.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recordablePids.forEach { (pid, label) ->
                    FilterChip(
                        selected = pid in selectedPids,
                        onClick = {
                            if (!activeState.active) {
                                selectedPids = if (pid in selectedPids) (selectedPids - pid).ifEmpty { listOf(pid) } else selectedPids + pid
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (activeState.active) {
                Text(
                    "Идёт запись #${activeState.recordingId} · точек: ${activeState.sampleCount}, ошибок чтения: ${activeState.errorCount}",
                    color = Green,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { vm.stopTripRecording() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Остановить запись")
                }
            } else {
                Button(onClick = { vm.startTripRecording(selectedPids) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Начать запись")
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Green) }
        }

        Panel(title = "История записей (${recordings.size})") {
            if (recordings.isEmpty()) {
                Text("Сохранённых записей поездок пока нет.", style = MaterialTheme.typography.bodySmall, color = TextDim)
            } else {
                recordings.forEach { rec ->
                    val durSec = ((rec.endedAtMs ?: rec.updatedAtMs) - rec.startedAtMs).coerceAtLeast(0L) / 1000L
                    val statusColor = when (rec.status) {
                        SessionStatus.ACTIVE -> Green
                        SessionStatus.INTERRUPTED -> Amber
                        else -> TextDim
                    }
                    Text("#${rec.id} · ${df.format(Date(rec.startedAtMs))}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${rec.vehicleLabel} · статус: ${rec.status} · длит: ${durSec}с · точек: ${rec.sampleCount} · ошибок: ${rec.errorCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.loadRecordingIntoCharts(rec.id)
                                nav.navigate(Screen.CHARTS.route) { launchSingleTop = true }
                            },
                        ) { Text("График") }
                        OutlinedButton(
                            onClick = {
                                vm.exportTripCsv(rec.id, share = false) { r ->
                                    notice = r.fold(onSuccess = { "CSV: ${it.name}" }, onFailure = { "Ошибка: ${it.message}" })
                                }
                            },
                        ) { Text("CSV") }
                        OutlinedButton(
                            onClick = {
                                vm.exportTripCsv(rec.id, share = true) { r ->
                                    notice = r.fold(onSuccess = { "Отправлено: ${it.name}" }, onFailure = { "Ошибка: ${it.message}" })
                                }
                            },
                        ) { Text("Отправить") }
                        TextButton(onClick = { vm.deleteTripRecording(rec.id) }) {
                            Text("Удалить", color = Red)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
