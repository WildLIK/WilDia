package com.obd2.app.ui.screens

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.core.obd.ClearDtcGuard
import com.obd2.app.core.obd.ClearDtcResult
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

@Composable
fun QuickDiagScreen(vm: ObdViewModel) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val running by vm.quickDiagRunning.collectAsStateWithLifecycle()
    val progress by vm.quickDiagProgress.collectAsStateWithLifecycle()
    val result by vm.quickDiagResult.collectAsStateWithLifecycle()
    val clearFeedback by vm.clearDtcFeedback.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    var exportNotice by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    val connected = connection is DiagnosticRepository.ConnectionState.Connected

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Быстрая диагностика (стандарт OBD-II)") {
            Text(
                "Универсальная проверка ошибок (Mode 03/07/0A), стоп-кадра (Mode 02), мониторов I/M Readiness, Mode 06 и основных параметров без выбора модели автомобиля.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.runQuickDiagnostics() },
                enabled = connected && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Выполняется…" else "Запустить быструю диагностику")
            }
            if (!connected) {
                Text(
                    "Сначала подключите адаптер на экране «Подключение».",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
            if (running) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextDim) }
            }
        }

        val res = result
        if (res != null) {
            Panel(title = "Сводка проверки") {
                KeyValue("Статус", res.statusLabelRu)
                KeyValue("Протокол", res.protocolText ?: "не определён")
                KeyValue("Поддерживаемых PID", "${res.supportedPidsCount}")
                res.dtcScan?.let { dtc ->
                    KeyValue(
                        "Ошибки (DTC)",
                        "Сохр: ${dtc.stored.size}, Ожид: ${dtc.pending.size}, Пост: ${dtc.permanent.size}",
                    )
                }
                if (res.sessionId > 0) {
                    KeyValue("Сеанс в истории", "#${res.sessionId} (сохранено)")
                }
                if (res.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    res.warnings.forEach { w ->
                        Text("• $w", style = MaterialTheme.typography.bodySmall, color = Amber)
                    }
                }
            }

            res.readiness?.let { rd ->
                Panel(title = "I/M Readiness (${rd.completedCount}/${rd.supportedCount} завершено)") {
                    KeyValue("Лампа Check Engine (MIL)", if (rd.milOn) "ВКЛ" else "ВЫКЛ")
                    KeyValue("Счётчик DTC в ЭБУ", "${rd.dtcCount}")
                    rd.monitors.forEach { m ->
                        KeyValue(m.nameRu, m.statusRu)
                    }
                }
            }

            res.freezeFrame?.let { ff ->
                Panel(title = "Стоп-кадр (Mode 02 Freeze Frame)") {
                    if (ff.triggerDtc != null) {
                        KeyValue("Код-триггер", ff.triggerDtc)
                    }
                    if (ff.items.isNotEmpty()) {
                        ff.items.forEach { item ->
                            KeyValue(item.nameRu, item.valueText)
                        }
                    } else {
                        Text(
                            ff.unsupportedReason ?: "Стоп-кадр отсутствует.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextDim,
                        )
                    }
                }
            }

            res.mode06?.let { m06 ->
                Panel(title = "Бортовой мониторинг (Mode 06)") {
                    if (m06.results.isNotEmpty()) {
                        m06.results.take(15).forEach { r ->
                            val st = if (r.passed) "НОРМА" else "ВНЕ ПОРОГА"
                            KeyValue(r.nameRu, "${"%.2f".format(r.value)} ${r.unit} [$st]")
                        }
                    } else {
                        Text(
                            m06.unsupportedReason ?: "Нет данных Mode 06.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextDim,
                        )
                    }
                }
            }

            if (res.engineReadings.isNotEmpty()) {
                Panel(title = "Основные параметры двигателя") {
                    res.engineReadings.forEach { r ->
                        KeyValue(r.nameRu, listOfNotNull(r.valueText, r.unit).joinToString(" "))
                    }
                }
            }

            if (res.sessionId > 0) {
                Panel(title = "Отчёт и безопасные операции") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                vm.exportSessionReport(res.sessionId, "txt", includeIdentifiers = !settings.maskVin, share = false) { r ->
                                    exportNotice = r.fold(onSuccess = { "Сохранено: ${it.name}" }, onFailure = { "Ошибка: ${it.message}" })
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("TXT") }
                        OutlinedButton(
                            onClick = {
                                vm.exportSessionReport(res.sessionId, "pdf", includeIdentifiers = !settings.maskVin, share = false) { r ->
                                    exportNotice = r.fold(onSuccess = { "Сохранено: ${it.name}" }, onFailure = { "Ошибка: ${it.message}" })
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("PDF") }
                        OutlinedButton(
                            onClick = {
                                vm.exportSessionReport(res.sessionId, "pdf", includeIdentifiers = !settings.maskVin, share = true) { r ->
                                    exportNotice = r.fold(onSuccess = { "Отправлено: ${it.name}" }, onFailure = { "Ошибка: ${it.message}" })
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Поделиться") }
                    }
                    exportNotice?.let { msg ->
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = Green)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Сброс ошибок (Mode 04 — с подтверждением)", color = Amber)
                    }
                }
            }
        }

        clearFeedback?.let { fb ->
            Panel(title = "Результат проверки сброса (Mode 04)") {
                when (fb) {
                    is ClearDtcResult.Blocked -> {
                        Text("Сброс заблокирован:", color = Red, style = MaterialTheme.typography.bodyMedium)
                        fb.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Red) }
                    }
                    is ClearDtcResult.DryRunSimulated -> {
                        Text(fb.summary, color = Amber, style = MaterialTheme.typography.bodySmall)
                    }
                    is ClearDtcResult.SentOk -> {
                        Text("Команда 04 выполнена.", color = Green, style = MaterialTheme.typography.bodySmall)
                    }
                    is ClearDtcResult.Failed -> {
                        Text("Ошибка: ${fb.reason}", color = Red, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { vm.dismissClearDtcFeedback() }) { Text("Закрыть") }
            }
        }
    }

    if (showClearDialog) {
        var warningsAck by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Подтверждение сброса ошибок (Mode 04)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClearDtcGuard.requiredWarningsRu.forEach { w ->
                        Text("• $w", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = warningsAck, onCheckedChange = { warningsAck = it })
                        Text("Я понимаю последствия сброса и подтверждаю операцию", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clearDtcsGuarded(userConfirmed = true, warningsAcknowledged = warningsAck)
                        showClearDialog = false
                    },
                    enabled = warningsAck,
                ) { Text("Подтвердить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Отмена") }
            },
        )
    }
}
