package com.obd2.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.diag.DiagRunner
import com.obd2.app.core.diag.DiagSeverity
import com.obd2.app.core.diag.StageState
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim
import java.io.File

/**
 * «Диагностика подключения» — technical screen (§2): sequential scenario
 * runner with per-stage status, progress, cancel and ZIP export. Runs
 * read-only checks only; never sends unsafe AT/CAN commands.
 */
@Composable
fun DiagScreen(vm: ObdViewModel) {
    val state by vm.diagRunner.state.collectAsState()
    var scenario by remember { mutableStateOf(DiagRunner.Scenario.D_FULL) }
    var diagScanMode by remember { mutableStateOf(BtScanMode.AUTO) }
    var maskIds by remember { mutableStateOf(true) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Сценарий") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiagRunner.Scenario.entries.forEach { sc ->
                    FilterChip(
                        selected = scenario == sc,
                        onClick = { if (!state.running) scenario = sc },
                        label = { Text(sc.id) },
                    )
                }
            }
            Text(
                when (scenario) {
                    DiagRunner.Scenario.A_SCAN -> "Только поиск устройств и запись рекламных данных BLE"
                    DiagRunner.Scenario.B_CONNECT -> "Поиск IOS-Vlink → GATT-подключение → подписка на уведомления"
                    DiagRunner.Scenario.C_ELM -> "Подключение + безопасная инициализация адаптера"
                    DiagRunner.Scenario.D_FULL -> "Все проверки последовательно, включая параметры автомобиля"
                    DiagRunner.Scenario.E_PASSIVE -> "Только запись событий текущего сеанса, без команд автомобилю"
                },
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BtScanMode.entries.forEach { mode ->
                    FilterChip(
                        selected = diagScanMode == mode,
                        onClick = { if (!state.running) diagScanMode = mode },
                        label = { Text(mode.labelRu) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.running) {
                Button(onClick = { vm.cancelDiag() }) { Text("Отменить") }
            } else {
                Button(onClick = {
                    exportPath = null
                    exportError = null
                    vm.runDiag(scenario, diagScanMode)
                }) { Text("Запустить полную диагностику") }
            }
            OutlinedButton(
                onClick = {
                    vm.exportDiagZip(maskIds) { result ->
                        result.fold(
                            onSuccess = { exportPath = it.absolutePath; exportError = null },
                            onFailure = { exportError = it.message; exportPath = null },
                        )
                    }
                },
                enabled = !state.running || state.finishedMessage != null,
            ) { Text("Экспортировать технический отчёт ZIP") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = maskIds, onCheckedChange = { maskIds = it })
            Spacer(Modifier.padding(4.dp))
            Text(
                "Маскировать VIN и MAC-адреса (выключите, если для анализа нужны реальные идентификаторы)",
                color = TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (state.running || state.stages.isNotEmpty()) {
            Panel(title = "Выполнение (${state.progressPercent}%)") {
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                state.stages.forEach { st ->
                    val color = when (st.state) {
                        StageState.PASS -> Green
                        StageState.FAIL -> Red
                        StageState.WARN, StageState.RUNNING -> Amber
                        StageState.SKIP, StageState.PENDING -> TextDim
                    }
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${stageMark(st.state)} ${st.title}" +
                                (if (st.durationMs != null) " — ${st.durationMs} мс" else ""),
                            color = color,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (st.currentOp.isNotBlank() && st.state == StageState.RUNNING) {
                            Text("операция: ${st.currentOp}", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (st.resultText.isNotBlank()) {
                            Text(st.resultText, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                        }
                        st.errors.forEach { Text("ошибка: $it", color = Red, style = MaterialTheme.typography.bodyMedium) }
                        st.warnings.forEach { Text("предупреждение: $it", color = Amber, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                state.finishedMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Итог: $it", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (state.conclusions.isNotEmpty()) {
            Panel(title = "Заключение") {
                state.conclusions.forEach { c ->
                    Text(
                        "${if (c.confirmed) "✓ подтверждено" else "? гипотеза"}: ${c.text}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        exportPath?.let {
            Panel(title = "Экспорт") {
                Text("Отчёт сохранён:", style = MaterialTheme.typography.bodyMedium)
                Text(it, color = TextDim, style = MaterialTheme.typography.labelSmall)
                Text(
                    "Отправить его можно через приложение «Файлы» или «Мои файлы» → Android/data/com.obd2.app/files/diag_reports/",
                    color = TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        exportError?.let { Text("Ошибка экспорта: $it", color = Red) }

        Panel(title = "Как это работает") {
            Text(
                "Проверки выполняются последовательно; параллельные BLE-подключения не создаются — " +
                    "если приложение уже подключено, сценарий использует это соединение. При ошибке этапа " +
                    "результаты сохраняются, продолжаются только независимые безопасные проверки. " +
                    "Кольцевой журнал хранит последние 120 с событий; при ошибке создаётся снимок, " +
                    "который не перезаписывается новыми сессиями.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Запрещены и отсутствуют: очистка ошибок, кодирование, SecurityAccess, управление " +
                    "исполнительными механизмами, изменение CAN-конфигурации и прошивки, неизвестные команды.",
                color = TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun stageMark(s: StageState): String = when (s) {
    StageState.PASS -> "✓"
    StageState.FAIL -> "✗"
    StageState.WARN -> "⚠"
    StageState.RUNNING -> "…"
    StageState.SKIP -> "—"
    StageState.PENDING -> "·"
}
