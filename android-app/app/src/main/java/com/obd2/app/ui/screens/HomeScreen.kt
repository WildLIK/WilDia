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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.obd2.app.Screen
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

@Composable
fun HomeScreen(vm: ObdViewModel, nav: NavHostController) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val pipeline by vm.pipelineStatus.collectAsStateWithLifecycle()
    val isPolling by vm.isPolling.collectAsStateWithLifecycle()
    val live by vm.liveData.collectAsStateWithLifecycle()
    val unsupportedPids by vm.unsupportedPids.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val sessionVehicle by vm.sessionVehicle.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val currentSessionId by vm.currentSessionIdFlow.collectAsStateWithLifecycle()
    val elmInfo by vm.elmInfo.collectAsStateWithLifecycle()
    val stats by vm.elmStats.collectAsStateWithLifecycle()
    val lastAdapter by vm.lastAdapter.collectAsStateWithLifecycle()

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    val registryError by vm.registryError.collectAsStateWithLifecycle()
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    val lastCrash by vm.lastCrash.collectAsStateWithLifecycle()

    if (storageError != null) {
        Panel(title = "Ошибка хранилища данных") {
            Text(
                storageError!!,
                color = Red,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (lastCrash != null) {
        Panel(title = "Предупреждение") {
            Text(
                "Прошлый запуск завершился ошибкой. Работа приложения продолжена; " +
                    "подробный отчёт об ошибке — в разделе «Диагностика».",
                color = Amber,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { nav.navigate(Screen.DIAGNOSTICS.route) { launchSingleTop = true } }) {
                Text("Подробнее")
            }
        }
    }

    if (registryError != null) {
        Panel(title = "Ошибка инициализации") {
            Text(
                "Офлайн-база не загрузилась: $registryError. " +
                    "Диагностика и чтение ошибок будут недоступны до устранения; остальной интерфейс работает.",
                color = Red,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        val lastSavedSession = sessions.firstOrNull { !it.demoMode && it.id != currentSessionId }
        Panel(title = "Автомобиль") {
            KeyValue(
                "Текущий сеанс",
                if (sessionVehicle.identified) sessionVehicle.displayTitle else "Автомобиль не определён",
            )
            val statusText = when {
                sessionVehicle.demo -> "ДЕМО — данные симулированы"
                sessionVehicle.identified -> sessionVehicle.confidenceLabelRu
                activeProfile != null -> "Автомобиль не определён (выбран ручной профиль)"
                else -> "Автомобиль не определён"
            }
            KeyValue("Статус идентификации", statusText)
            if (sessionVehicle.needsUserConfirmation) {
                Text(
                    "Определение частичное — подтвердите модель в разделе «Автомобиль».",
                    color = Amber,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (activeProfile != null) {
                val profileTitle = listOf(activeProfile.maker, activeProfile.model, activeProfile.year)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { activeProfile.name.ifBlank { "Профиль" } }
                KeyValue("Выбран вручную", profileTitle)
            } else {
                KeyValue("Ручной профиль", "не выбран")
            }
            val historyDf = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }
            val lastSavedText = if (lastSavedSession != null) {
                "${lastSavedSession.displayVehicleTitle()} (${historyDf.format(java.util.Date(lastSavedSession.startedAtMs))}, ошибок: ${lastSavedSession.totalDtcCount})"
            } else {
                "нет сохранённых сеансов"
            }
            KeyValue("Последний сохранённый сеанс", lastSavedText)
        }

        Panel(title = "Подключение") {
            val c = connection
            val demo = (c as? DiagnosticRepository.ConnectionState.Connected)?.demo == true ||
                (c as? DiagnosticRepository.ConnectionState.Connecting)?.demo == true
            val stageColor = when {
                c is DiagnosticRepository.ConnectionState.Failed -> Red
                c is DiagnosticRepository.ConnectionState.Connecting -> Amber
                c is DiagnosticRepository.ConnectionState.Reconnecting -> Amber
                demo -> Amber
                pipeline.ecuResponding -> Green
                pipeline.bleConnected || pipeline.elmResponding -> Amber
                else -> TextDim
            }
            val stageText = when (c) {
                is DiagnosticRepository.ConnectionState.Failed -> "ОШИБКА"
                is DiagnosticRepository.ConnectionState.Connecting ->
                    if (c.demo) "ПОДКЛЮЧЕНИЕ (ДЕМО)" else "ПОДКЛЮЧЕНИЕ…"
                is DiagnosticRepository.ConnectionState.Reconnecting -> "ПЕРЕПОДКЛЮЧЕНИЕ ${c.attempt}/3"
                is DiagnosticRepository.ConnectionState.Connected ->
                    if (c.demo) "ДЕМО · ${pipeline.highestStage.labelRu}"
                    else pipeline.highestStage.labelRu.uppercase()
                else -> pipeline.highestStage.labelRu.uppercase()
            }
            StatusChip(stageText, stageColor)
            Spacer(Modifier.height(8.dp))

            val transportLabel = if (pipeline.transportType == BtTransportType.CLASSIC_SPP) {
                "2. Bluetooth Classic (SPP) подключён"
            } else {
                "2. BLE подключён"
            }
            val stages = listOf(
                "1. Адаптер обнаружен" to pipeline.adapterFound,
                transportLabel to pipeline.bleConnected,
                "3. ELM327 отвечает" to pipeline.elmResponding,
                "4. Протокол установлен" to pipeline.protocolEstablished,
                "5. ЭБУ отвечает" to pipeline.ecuResponding,
                "6. Данные получены" to pipeline.dataReceived,
            )
            stages.forEach { (label, done) ->
                Text(
                    "${if (done) "✓" else "○"} $label",
                    color = if (done) Green else TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            pipeline.lastNote?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(
                    note,
                    color = if (c is DiagnosticRepository.ConnectionState.Failed) Red else Amber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (c is DiagnosticRepository.ConnectionState.Connected) {
                Spacer(Modifier.height(6.dp))
                KeyValue("Адаптер", c.deviceName)
                KeyValue("Состояние", c.details)
                elmInfo?.protocol?.let { KeyValue("Протокол", it) }
                elmInfo?.voltage?.let { KeyValue("Напряжение АКБ", "%.1f В".format(it)) }
                stats?.let { KeyValue("Запросов", "${it.sent} (таймаутов ${it.timeouts})") }
            } else if (c is DiagnosticRepository.ConnectionState.Failed) {
                Spacer(Modifier.height(6.dp))
                Text(c.reason, color = Red, style = MaterialTheme.typography.bodyMedium)
            }
            if (c !is DiagnosticRepository.ConnectionState.Connected &&
                c !is DiagnosticRepository.ConnectionState.Connecting &&
                lastAdapter != null
            ) {
                val la = lastAdapter!!
                val transportTxt = if (la.transport == BtTransportType.CLASSIC_SPP.name) "Classic SPP" else "BLE"
                val adapterDf = remember { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()) }
                val tsStr = if (la.lastConnectedAtMs > 0L) {
                    adapterDf.format(java.util.Date(la.lastConnectedAtMs))
                } else "ранее"
                Spacer(Modifier.height(8.dp))
                KeyValue("Последний адаптер", "${la.name} ($transportTxt)")
                KeyValue("MAC / дата", "${la.mac} · $tsStr")
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { vm.quickConnectLastAdapter() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Подключиться к последнему адаптеру")
                }
            }
        }

        Panel(title = "Быстрые показатели") {
            val imperial = settings.units == SettingsRepository.Units.IMPERIAL
            fun pidDisplay(pid: Int, formatInt: Boolean = true): String {
                val v = live[pid]?.reading?.value
                if (v != null) {
                    val converted = when {
                        imperial && pid == 0x0D -> v * 0.621371
                        imperial && pid == 0x05 -> v * 9.0 / 5.0 + 32.0
                        else -> v
                    }
                    return if (formatInt) "%d".format(converted.toInt()) else "%.1f".format(converted)
                }
                return if (pid in unsupportedPids) "нет данных" else "—"
            }
            val voltLive = live[0x42]?.reading?.value
            val voltFallback = if (voltLive == null) elmInfo?.voltage else null
            val voltText = when {
                voltLive != null -> "%.1f".format(voltLive)
                voltFallback != null -> "%.1f".format(voltFallback)
                0x42 in unsupportedPids -> "нет данных"
                else -> "—"
            }
            val voltUnit = if (voltLive == null && voltFallback != null) "В (ATRV)" else "В"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("Обороты", pidDisplay(0x0C), "об/мин", Modifier.weight(1f))
                ValueTile("Скорость", pidDisplay(0x0D), if (imperial) "mph" else "км/ч", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueTile("Темп. ОЖ", pidDisplay(0x05), if (imperial) "°F" else "°C", Modifier.weight(1f), Amber)
                ValueTile("Нагрузка", pidDisplay(0x04), "%", Modifier.weight(1f), Amber)
                ValueTile("Напряжение", voltText, voltUnit, Modifier.weight(1f), Green)
            }
            val connected = connection is DiagnosticRepository.ConnectionState.Connected
            if (connected && pipeline.ecuResponding) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshQuickSnapshot() }) {
                        Text("Обновить")
                    }
                    if (isPolling) {
                        OutlinedButton(onClick = { vm.stopPolling() }) { Text("Стоп") }
                    } else {
                        OutlinedButton(
                            onClick = { vm.startPolling(DiagnosticRepository.HOME_QUICK_PIDS) },
                        ) { Text("Опрос") }
                    }
                }
            }
            if (live.isEmpty() && voltFallback == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Значения появятся после ответа ЭБУ и опроса параметров.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { nav.navigate(Screen.QUICK_DIAG.route) { launchSingleTop = true } },
                modifier = Modifier.weight(1f),
            ) {
                Text("Быстрая диагностика")
            }
            OutlinedButton(
                onClick = { nav.navigate(Screen.EXTENDED_DIAG.route) { launchSingleTop = true } },
                modifier = Modifier.weight(1f),
            ) {
                Text("Расширенная диагностика")
            }
        }

        Button(
            onClick = {
                nav.navigate(Screen.DIAGNOSTICS.route) { launchSingleTop = true }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Начать диагностику", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            "Безопасный режим: только чтение данных (Mode 01/03/07/09/0A). " +
                "Очистка ошибок и запись в ЭБУ в этой версии недоступны.",
            color = TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
