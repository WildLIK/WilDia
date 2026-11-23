package com.obd2.app.ui.screens

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obd2.app.core.repo.FullCheckRunner
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

@Composable
fun FullCheckScreen(vm: ObdViewModel) {
    val steps by vm.fullCheckSteps.collectAsState()
    val running by vm.fullCheckRunning.collectAsState()
    val result by vm.fullCheckResult.collectAsState()
    val reportFile by vm.reportFile.collectAsState()
    val reportError by vm.reportError.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Полная проверка адаптера и автомобиля") {
            Text(
                "Безопасная последовательность: BLE → GATT → ELM327 → протокол → PID → параметры → ошибки → тайминги → отчёт. " +
                    "Ошибочный этап не прерывает независимые проверки. Запись в ЭБУ не выполняется.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(top = 10.dp))
            Button(onClick = { vm.runFullCheck() }, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).fillMaxWidth(0.06f), strokeWidth = 2.dp)
                }
                Text(if (running) "Проверка идёт…" else "Запустить полную проверку")
            }
        }

        if (steps.isNotEmpty()) {
            Panel(title = "Прогресс") {
                steps.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val (color, label) = when (s.status) {
                            FullCheckRunner.Step.Status.OK -> Green to "OK"
                            FullCheckRunner.Step.Status.WARN -> Amber to "ПРЕДУПР."
                            FullCheckRunner.Step.Status.FAIL -> Red to "ОШИБКА"
                            FullCheckRunner.Step.Status.SKIPPED -> TextDim to "ПРОПУЩЕНО"
                            FullCheckRunner.Step.Status.RUNNING -> Amber to "…"
                        }
                        Text(
                            "[$label] ",
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${s.title}: ${s.details}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (s.status == FullCheckRunner.Step.Status.RUNNING) TextDim else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        result?.let { res ->
            Panel(title = "Сводка") {
                KeyValue("Поддерживаемых PID", res.supportedPids.size.toString())
                KeyValue("Считано параметров", res.readings.size.toString())
                res.dtc?.let {
                    KeyValue("Ошибок сохранённых", it.stored.size.toString())
                    KeyValue("ожидающих", it.pending.size.toString())
                    KeyValue("постоянных", it.permanent.size.toString())
                }
                res.vinMasked?.let {
                    KeyValue("VIN", com.obd2.app.core.report.RawVinMask.maskVin(it) ?: it)
                }
                res.voltage?.let { KeyValue("Напряжение", "%.1f В".format(it)) }
            }

            if (res.readings.isNotEmpty()) {
                Panel(title = "Параметры (реальные ответы)") {
                    res.readings.forEach { r ->
                        KeyValue(
                            "${r.mode} ${r.pidHex} — ${r.name}",
                            "${r.value} ${r.unit ?: ""} | raw: ${r.raw} | ${r.durationMs} мс",
                            mono = true,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.exportReport() }) { Text("Экспорт отчёта (ZIP)") }
            }
            reportError?.let { Text("Ошибка: $it", color = Red) }
            reportFile?.let { f ->
                Text("Отчёт: ${f.name} (${f.length() / 1024} КБ)", color = Green, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "com.obd2.app.fileprovider", f)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
                }) { Text("Поделиться ZIP") }
            }
        }

        Text(
            "Отчёт содержит реальные данные обмена. VIN маскируется согласно настройкам приватности.",
            color = TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
