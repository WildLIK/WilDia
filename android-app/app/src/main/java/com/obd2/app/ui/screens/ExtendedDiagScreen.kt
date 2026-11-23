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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.universal.EcuStatus
import com.obd2.app.core.universal.VehicleDiagnosticProfileRegistry
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.TextDim

@Composable
fun ExtendedDiagScreen(vm: ObdViewModel) {
    val connection by vm.connection.collectAsState()
    val running by vm.extendedDiagRunning.collectAsState()
    val progress by vm.extendedDiagProgress.collectAsState()
    val report by vm.extendedDiagReport.collectAsState()
    val settings by vm.settingsFlow.collectAsState()
    val catalogStatus by vm.catalogStatus.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val vinTrace by vm.vinIdentificationResult.collectAsState()
    val clipboard = LocalClipboardManager.current

    val activeCar = profiles.firstOrNull { it.id == activeProfileId }
    val matchedDiagProfile = remember(activeCar) {
        VehicleDiagnosticProfileRegistry.matchVehicle(
            maker = activeCar?.maker,
            model = activeCar?.model,
            year = activeCar?.year,
            engine = activeCar?.engine,
        )
    }
    var selectedDiagProfileId by remember(matchedDiagProfile.id) { mutableStateOf(matchedDiagProfile.id) }
    val diagProfile = VehicleDiagnosticProfileRegistry.findById(selectedDiagProfileId) ?: matchedDiagProfile
    var traceCopiedNote by remember { mutableStateOf<String?>(null) }

    val connected = connection is DiagnosticRepository.ConnectionState.Connected

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Расширенная диагностика (обнаружение блоков и возможностей)") {
            Text(
                "Пошагово выполняет идентификацию авто, проверку адаптера, поиск ЭБУ по документированным адресам и оценку подтверждённых функций (только безопасное чтение).",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
            KeyValue("Каталог совместимости", "v${catalogStatus.activeVersion} (${catalogStatus.source})")
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { vm.runExtendedDiagnostics() },
                enabled = connected && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Анализ…" else "Запустить расширенную диагностику")
            }
            if (!connected) {
                Text(
                    "Подключите адаптер на экране «Подключение» для реального опроса ЭБУ.",
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

        Panel(title = "Профильная диагностика (только чтение)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VehicleDiagnosticProfileRegistry.allProfiles().forEach { prof ->
                    FilterChip(
                        selected = prof.id == diagProfile.id,
                        onClick = { selectedDiagProfileId = prof.id },
                        label = { Text("${prof.brand} ${prof.model}") },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            KeyValue("Профиль", diagProfile.displayTitle)
            KeyValue("Трансмиссия", diagProfile.transmissionHint)
            Spacer(Modifier.height(4.dp))
            Text("Протоколы:", style = MaterialTheme.typography.labelMedium)
            diagProfile.protocols.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextDim) }
            Spacer(Modifier.height(4.dp))
            Text("Документированные блоки ЭБУ:", style = MaterialTheme.typography.labelMedium)
            diagProfile.ecuTargets.forEach { ecu ->
                val badgeColor = if (ecu.readOnlySupportedOnElm327) Green else Amber
                Text(
                    "• [${ecu.vagAddressCode}] ${ecu.nameRu} (${ecu.txAddress}->${ecu.rxAddress})",
                    style = MaterialTheme.typography.bodySmall,
                    color = badgeColor,
                )
                Text("  ${ecu.notesRu}", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
            Spacer(Modifier.height(4.dp))
            Text("Ограничения ELM327 / Vgate:", style = MaterialTheme.typography.labelMedium, color = Amber)
            diagProfile.adapterLimitationsRu.forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
            vinTrace?.let { trace ->
                Spacer(Modifier.height(6.dp))
                Text("Последний трассировщик VIN: ${trace.summaryRu}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(vm.exportVinDiagnosticLog(includeSensitive = false)))
                        traceCopiedNote = "Трассировка VIN скопирована в буфер обмена"
                    },
                ) {
                    Text("Экспорт лога VIN / ЭБУ")
                }
                traceCopiedNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Green) }
            }
        }

        val r = report
        if (r != null) {
            Panel(title = "1. Идентификация автомобиля") {
                KeyValue("Статус", r.vehicle.status.labelRu)
                KeyValue("Результат", r.vehicle.summaryRu)
                r.vehicle.manufacturer?.let { KeyValue("Производитель", it) }
                r.vehicle.ecuName?.let { KeyValue("Имя ЭБУ", it) }
                if (r.vehicle.calIds.isNotEmpty()) {
                    KeyValue("Калибровки (CalID)", r.vehicle.calIds.joinToString(", "))
                }
                KeyValue("Активный модуль", r.activeModuleName)
            }

            Panel(title = "2. Возможности адаптера и протоколы") {
                KeyValue("Адаптер", "${r.adapterCapabilities.adapterName} (${r.adapterCapabilities.transportKind.name})")
                KeyValue("Протокол", r.adapterCapabilities.protocolText ?: "не определён")
                KeyValue("Стандартный OBD-II", if (r.adapterCapabilities.supportsStandardObd2) "Поддерживается" else "Недоступен")
                KeyValue("UDS (только чтение)", if (r.adapterCapabilities.supportsUdsReadOnly) "Доступен (ATSH/CAN)" else "Не подтверждён")
                KeyValue("VAG TP 2.0", if (r.adapterCapabilities.supportsVagTp20) "Подтверждён" else "Не подтверждён / отключён")
            }

            Panel(title = "3. Обнаруженные электронные блоки (${r.discoveredEcus.count { it.status == EcuStatus.DETECTED_RESPONDING }})") {
                val visibleEcus = if (settings.devMode) {
                    r.discoveredEcus
                } else {
                    r.discoveredEcus.filter { it.status == EcuStatus.DETECTED_RESPONDING || it.status == EcuStatus.DETECTED_RESTRICTED }
                }
                if (visibleEcus.isEmpty()) {
                    Text(
                        "Подтверждённые электронные блоки не обнаружены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                } else {
                    visibleEcus.forEach { ecu ->
                        val color = when (ecu.status) {
                            EcuStatus.DETECTED_RESPONDING -> Green
                            EcuStatus.DETECTED_RESTRICTED -> Amber
                            else -> TextDim
                        }
                        Text("${ecu.address} · ${ecu.nameRu}", style = MaterialTheme.typography.titleSmall)
                        Text("${ecu.status.labelRu} · ${ecu.protocol}", style = MaterialTheme.typography.bodySmall, color = color)
                        ecu.partNumber?.let { Text("Номер детали: $it", style = MaterialTheme.typography.bodySmall, color = TextDim) }
                        if (ecu.notes.isNotBlank()) Text(ecu.notes, style = MaterialTheme.typography.bodySmall, color = TextDim)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            val visibleCaps = if (settings.devMode) r.evaluatedCapabilities else r.confirmedForNormalUser
            val grouped = visibleCaps.groupBy { it.spec.groupRu }
            Panel(title = "4. Доступные диагностические функции (${r.confirmedForNormalUser.size} подтверждено)") {
                if (grouped.isEmpty()) {
                    Text(
                        "Подтверждённых функций для данного автомобиля пока нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                } else {
                    grouped.forEach { (group, items) ->
                        Text(group, style = MaterialTheme.typography.titleSmall, color = Green)
                        items.forEach { item ->
                            Text("• ${item.spec.titleRu}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "  ${item.status.labelRu}: ${item.reasonRu}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                            )
                            item.riskWarningRu?.let { warn ->
                                Text("  $warn", style = MaterialTheme.typography.bodySmall, color = Amber)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}
