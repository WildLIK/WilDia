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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.obd2.app.Screen
import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidReading
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.TextDim

/** Dashboard PID set shown on the Sensors screen. */
private val dashboardPids = listOf(
    0x0C, 0x0D, 0x05, 0x04, 0x42, 0x0F, 0x2F, 0x11, 0x10, 0x1F, 0x5C, 0x06, 0x07, 0x46, 0x0B, 0x0E,
)
private val dashboardPidRows = dashboardPids.chunked(2)

private val pidTitles = mapOf(
    0x0C to "Обороты двигателя", 0x0D to "Скорость", 0x05 to "Темп. охлаждающей жидкости",
    0x04 to "Нагрузка двигателя", 0x42 to "Напряжение сети", 0x0F to "Темп. впускного воздуха",
    0x2F to "Уровень топлива", 0x11 to "Дроссель", 0x10 to "Расход воздуха (MAF)",
    0x1F to "Время работы двигателя", 0x5C to "Темп. масла", 0x06 to "STFT банк 1",
    0x07 to "LTFT банк 1", 0x46 to "Темп. окружающей среды", 0x0B to "Давление коллектора",
    0x0E to "Угол опережения зажигания",
)

@Composable
fun SensorsScreen(vm: ObdViewModel, nav: NavHostController? = null) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val pipeline by vm.pipelineStatus.collectAsStateWithLifecycle()
    val polling by vm.isPolling.collectAsStateWithLifecycle()
    val live by vm.liveData.collectAsStateWithLifecycle()
    val unsupportedPids by vm.unsupportedPids.collectAsStateWithLifecycle()
    val elmInfo by vm.elmInfo.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val registry by vm.pidRegistry.collectAsStateWithLifecycle()

    val pidDefByPid = remember(registry) {
        registry.filter { it.mode == 1 }.associateBy { it.pid }
    }
    val connected = connection is DiagnosticRepository.ConnectionState.Connected
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected, polling) {
        while (connected) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
    }
    val staleThresholdMs = maxOf(5_000L, settings.refreshMs.toLong() * 4L)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Статус: ${pipeline.highestStage.labelRu}",
            color = if (pipeline.ecuResponding) Green else TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.startPolling(dashboardPids) },
                enabled = connected && !polling,
            ) { Text("Опрос") }
            OutlinedButton(onClick = { vm.stopPolling() }, enabled = polling) {
                Text("Стоп")
            }
            if (nav != null) {
                OutlinedButton(onClick = { nav.navigate(Screen.CHARTS.route) { launchSingleTop = true } }) {
                    Text("Графики")
                }
                OutlinedButton(onClick = { nav.navigate(Screen.RECORDINGS.route) { launchSingleTop = true } }) {
                    Text("Записи")
                }
            }
        }
        if (!connected) {
            Text(
                "Автомобиль не подключён — данные датчиков появятся после подключения и ответа ЭБУ.",
                color = TextDim,
            )
        } else if (!pipeline.ecuResponding) {
            Text(
                "Адаптер подключён, но ЭБУ не отвечает. Проверьте зажигание.",
                color = Amber,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (live.isEmpty()) {
            Text(
                "Данные ещё не считаны — нажмите «Опрос» для чтения параметров реального автомобиля.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Показываются только значения, полученные от ЭБУ с корректным ответом. " +
                "«—» означает «ещё не опрошено», «нет данных» — ЭБУ не поддерживает параметр.",
            color = TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )

        val imperial = settings.units == SettingsRepositoryUnits.IMPERIAL
        dashboardPidRows.forEach { rowPids ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPids.forEach { pid ->
                    SensorPidTile(
                        pid = pid,
                        def = pidDefByPid[pid],
                        reading = live[pid]?.reading,
                        atrvVoltage = if (pid == 0x42) elmInfo?.voltage else null,
                        connected = connected,
                        isUnsupported = pid in unsupportedPids,
                        imperial = imperial,
                        nowMs = nowMs,
                        staleThresholdMs = staleThresholdMs,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowPids.size == 1) androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun SensorPidTile(
    pid: Int,
    def: PidDefinition?,
    reading: PidReading?,
    atrvVoltage: Float?,
    connected: Boolean,
    isUnsupported: Boolean,
    imperial: Boolean,
    nowMs: Long,
    staleThresholdMs: Long,
    modifier: Modifier = Modifier,
) {
    val title = pidTitles[pid] ?: def?.displayName ?: "PID %02X".format(pid)
    val atrvFallback = if (pid == 0x42 && reading?.value == null && connected) atrvVoltage else null
    val freshness = DiagnosticRepository.evaluateSensorFreshness(
        reading = reading,
        isConnected = connected,
        isUnsupported = isUnsupported,
        nowMs = nowMs,
        staleThresholdMs = staleThresholdMs,
    )
    val value = when {
        atrvFallback != null -> "%.1f".format(atrvFallback)
        freshness == DiagnosticRepository.SensorFreshness.UNSUPPORTED -> "Не поддерживается"
        !connected -> "Нет связи"
        reading?.value != null -> {
            var v = reading.value
            if (imperial) {
                when (pid) {
                    0x0D -> v *= 0.621371
                    0x05, 0x0F, 0x46, 0x5C -> v = v * 9.0 / 5.0 + 32.0
                }
            }
            if (v == v.toLong().toDouble()) "%d".format(v.toLong()) else "%.1f".format(v)
        }
        reading?.text != null -> reading.text.take(14)
        else -> "—"
    }
    val baseUnit = when {
        atrvFallback != null -> "В (ATRV)"
        !connected || freshness == DiagnosticRepository.SensorFreshness.UNSUPPORTED -> null
        reading?.value != null -> when {
            imperial && pid == 0x0D -> "mph"
            imperial && pid in setOf(0x05, 0x0F, 0x46, 0x5C) -> "°F"
            else -> reading.unit ?: def?.unit
        }
        else -> null
    }
    val ageSec = if (reading != null) ((nowMs - reading.timestampMs) / 1000L).coerceAtLeast(0L) else 0L
    val unit2 = when {
        freshness == DiagnosticRepository.SensorFreshness.STALE && baseUnit != null -> "$baseUnit · устарело (${ageSec}с)"
        freshness == DiagnosticRepository.SensorFreshness.STALE -> "Устарело (${ageSec}с)"
        else -> baseUnit
    }
    val accent = when {
        freshness == DiagnosticRepository.SensorFreshness.STALE -> Amber
        freshness == DiagnosticRepository.SensorFreshness.NO_CONNECTION -> TextDim
        freshness == DiagnosticRepository.SensorFreshness.UNSUPPORTED -> TextDim
        pid == 0x42 -> Green
        pid == 0x05 || pid == 0x5C -> Amber
        else -> com.obd2.app.ui.theme.Accent
    }
    ValueTile(title, value, unit2, modifier, accent)
}

// Alias to keep the import surface small
typealias SettingsRepositoryUnits = com.obd2.app.core.repo.SettingsRepository.Units
