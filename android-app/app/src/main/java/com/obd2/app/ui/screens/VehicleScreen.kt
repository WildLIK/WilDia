package com.obd2.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.TextDim

/**
 * «Автомобиль» — universal screen. Shows the ACTIVE profile; identification
 * runs through standard Mode 09 when the adapter is connected. The built-in
 * Skoda profile is a saved test profile with the owner's VCDS facts and is
 * NEVER applied to another car automatically. Anything the app could not
 * determine is reported honestly as «не определено».
 */
@Composable
fun VehicleScreen(vm: ObdViewModel) {
    val profiles by vm.settings.profilesFlow.collectAsState(initial = emptyList())
    val activeId by vm.settings.activeProfileIdFlow.collectAsState(initial = "")
    val connection by vm.connection.collectAsState()
    val sessionVehicle by vm.sessionVehicle.collectAsState()
    val vinTrace by vm.vinIdentificationResult.collectAsState()
    val settings by vm.settingsFlow.collectAsState()
    val clipboard = LocalClipboardManager.current

    var message by remember { mutableStateOf<String?>(null) }
    var showNewProfile by remember { mutableStateOf(false) }
    var showManualEdit by remember { mutableStateOf(false) }
    var showConfirmModel by remember { mutableStateOf(false) }
    var showReference by remember { mutableStateOf(false) }

    val connected = connection is com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected
        && !(connection as com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected).demo
    val active = profiles.firstOrNull { it.id == activeId }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(title = "Определение автомобиля в текущем сеансе") {
                KeyValue(
                    "Статус",
                    when {
                        sessionVehicle.demo -> "ДЕМО — данные симулированы"
                        sessionVehicle.identified -> sessionVehicle.confidenceLabelRu
                        else -> "Автомобиль не определён"
                    },
                )
                KeyValue(
                    "Идентификатор",
                    if (sessionVehicle.identified) sessionVehicle.displayTitle else "Автомобиль не определён",
                )
                if (sessionVehicle.needsUserConfirmation && !sessionVehicle.demo) {
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { showConfirmModel = true }) {
                        Text("Подтвердить модель")
                    }
                }
            }
        }

        item {
            Panel(title = "Профили автомобилей") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    profiles.take(4).forEach { p ->
                        FilterChip(
                            selected = p.id == activeId,
                            onClick = { vm.updateSettings { s -> s.setActiveProfile(p.id) } },
                            label = { Text(p.name.ifEmpty { "Профиль" }) },
                        )
                    }
                    TextButton(onClick = { showNewProfile = true }) { Text("＋ Новый") }
                }
                if (profiles.size > 4) Text("и ещё ${profiles.size - 4}…", color = TextDim, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (active == null) {
            item {
                Panel(title = "Активный профиль") {
                    Text("Автомобиль не определён", style = MaterialTheme.typography.titleMedium, color = Amber)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Профиль автомобиля не выбран и не задаётся автоматически. " +
                            "Подключитесь к автомобилю для чтения VIN/ЭБУ (Mode 09) или добавьте профиль вручную.",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showNewProfile = true }) { Text("＋ Добавить автомобиль вручную") }
                        if (connected) {
                            OutlinedButton(onClick = { vm.identifyVehicle { message = it } }) { Text("Определить VIN") }
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium)
                    }
                    vinTrace?.let { trace ->
                        Spacer(Modifier.height(8.dp))
                        Text("Диагностика чтения VIN / ЭБУ:", style = MaterialTheme.typography.labelLarge)
                        Text(trace.summaryRu, color = TextDim, style = MaterialTheme.typography.bodySmall)
                        trace.protocolText?.let { proto ->
                            Text("Протокол: $proto", color = TextDim, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(vm.exportVinDiagnosticLog(includeSensitive = false)))
                                message = "Лог диагностики VIN (с маскировкой VIN) скопирован в буфер обмена"
                            },
                        ) {
                            Text("Скопировать лог диагностики VIN")
                        }
                    }
                }
            }
        } else {
            item {
                Panel(title = "Активный профиль") {
                    Text(active.name, style = MaterialTheme.typography.titleLarge)
                    val info = listOf(
                        "Марка" to active.maker,
                        "Модель" to active.model,
                        "Год" to active.year,
                        "Двигатель" to active.engine,
                    )
                    info.forEach { (k, v) ->
                        KeyValue(k, v.ifBlank { "не определено" })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (active.identityStatus) {
                            VehicleProfile.ID_AUTO -> "Идентификация: автоматически определена адаптером"
                            VehicleProfile.ID_MANUAL -> "Идентификация: выбрано вручную"
                            else -> "Идентификация: не выполнена"
                        },
                        color = when (active.identityStatus) {
                            VehicleProfile.ID_AUTO -> Green
                            VehicleProfile.ID_MANUAL -> Amber
                            else -> TextDim
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                Panel(title = "Идентификация через адаптер (Mode 09)") {
                    Text(
                        "Стандартные запросы чтения: VIN (09 02), имена/калибровки ЭБУ (09 04, 09 0A). " +
                            "Другие данные автоматически не запрашиваются.",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.identifyVehicle { message = it } }, enabled = connected) {
                            Text("Определить VIN")
                        }
                        OutlinedButton(onClick = { vm.readEcuIdentifiers { message = it } }, enabled = connected) {
                            Text("Идентификаторы ЭБУ")
                        }
                    }
                    if (!connected) {
                        Text(
                            "Нужен подключённый адаптер и включённое зажигание (демо-режим не выдаёт реальные данные).",
                            color = TextDim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValue("VIN", if (active.vin.isBlank()) "не определён" else active.vin)
                    Text(
                        when (active.vinStatus) {
                            VehicleProfile.ID_AUTO -> "VIN получен через Mode 09 автоматически"
                            VehicleProfile.ID_MANUAL -> "VIN введён вручную"
                            else -> "VIN не считывался. Расшифровка модели по VIN офлайн недоступна — " +
                                "комплектация и блоки НЕ выдумываются."
                        },
                        color = TextDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (active.ecuIds.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Идентификаторы ЭБУ (из ответов адаптера):", style = MaterialTheme.typography.labelLarge)
                        active.ecuIds.forEach { Text("• $it", color = TextDim, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "Сопоставление с локальной базой: если парт-номер найден в базе, он будет " +
                                "подсвечен при обновлении базы; совпадения не выдумываются.",
                            color = TextDim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text("Идентификаторы ЭБУ: не определены", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium)
                    }
                    vinTrace?.let { trace ->
                        Spacer(Modifier.height(8.dp))
                        Text("Диагностика чтения VIN / ЭБУ:", style = MaterialTheme.typography.labelLarge)
                        Text(trace.summaryRu, color = TextDim, style = MaterialTheme.typography.bodySmall)
                        trace.protocolText?.let { proto ->
                            Text("Протокол: $proto", color = TextDim, style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(vm.exportVinDiagnosticLog(includeSensitive = false)))
                                message = "Лог диагностики VIN (с маскировкой VIN) скопирован в буфер обмена"
                            },
                        ) {
                            Text("Скопировать лог диагностики VIN")
                        }
                    }
                }
            }

            // VCDS facts belong ONLY to developer mode when inspecting the test Skoda profile
            if (settings.devMode && active.builtIn) {
                item {
                    Panel(title = "Сохранённые данные тестового профиля (скан VCDS владельца)") {
                        val ecus = listOf(
                            "06A 906 033 KD" to "ЭБУ двигателя — Siemens Simos 7.1",
                            "7N0 907 530 R" to "Шлюз CAN",
                            "1Z0 920 803 Q" to "Приборная панель",
                            "1K0 907 379 AK" to "ABS/ESP (Bosch MK60)",
                            "5J0 919 475 A" to "Парктроник",
                            "09G 927 750 JA" to "АКПП (Aisin TF-60SN)",
                        )
                        ecus.forEach { (pn, what) ->
                            KeyValue(pn, what)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Эти сведения привязаны исключительно к профилю «${active.name}» и не " +
                                "применяются к другим автомобилям автоматически.",
                            color = TextDim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showManualEdit = true }) { Text("Изменить вручную") }
                    if (!active.builtIn && profiles.size > 1) {
                        OutlinedButton(onClick = { vm.updateSettings { s -> s.deleteProfile(active.id) } }) {
                            Text("Удалить профиль")
                        }
                    }
                    TextButton(onClick = { showReference = !showReference }) {
                        Text(if (showReference) "Скрыть справку" else "Как это работает")
                    }
                }
            }

            if (showReference) {
                item {
                    Panel(title = "Как работает идентификация") {
                        Text(
                            "1. VIN считывается стандартным запросом Mode 09 02, если ЭБУ его отдаёт.\n" +
                                "2. Имена/калибровки ЭБУ — Mode 09 04 и 09 0A, если поддержаны.\n" +
                                "3. Полученные значения сохраняются в активный профиль.\n" +
                                "4. Расшифровка модели/двигателя по VIN без интернета недоступна: " +
                                "приложение не выдумывает комплектацию. Данные можно ввести вручную.\n" +
                                "5. Тестовый профиль Škoda Octavia A5 существует только как сохранённый " +
                                "пример и к другим автомобилям не применяется.",
                            color = TextDim,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    if (showNewProfile) {
        EditProfileDialog(
            title = "Новый профиль",
            initial = VehicleProfile(id = "", name = ""),
            onDismiss = { showNewProfile = false },
            onSave = { p ->
                val newId = "user-${System.currentTimeMillis()}"
                vm.updateSettings { s ->
                    s.addProfile(
                        p.copy(id = newId, identityStatus = VehicleProfile.ID_MANUAL)
                    )
                    s.setActiveProfile(newId)
                }
                showNewProfile = false
            },
        )
    }
    if (showManualEdit && active != null) {
        EditProfileDialog(
            title = "Изменить профиль",
            initial = active,
            onDismiss = { showManualEdit = false },
            onSave = { p ->
                vm.updateSettings { s ->
                    s.updateProfile(
                        p.copy(id = active.id, builtIn = active.builtIn, identityStatus = VehicleProfile.ID_MANUAL)
                    )
                }
                showManualEdit = false
            },
        )
    }
    if (showConfirmModel) {
        EditProfileDialog(
            title = "Подтвердить модель автомобиля",
            initial = VehicleProfile(
                id = "",
                name = sessionVehicle.displayTitle,
                maker = com.obd2.app.core.repo.DiagnosticRepository.inferMakerFromVin(sessionVehicle.vin).orEmpty(),
                vin = sessionVehicle.vin.orEmpty(),
            ),
            onDismiss = { showConfirmModel = false },
            onSave = { p ->
                vm.confirmSessionVehicleAsProfile(p.name, p.maker, p.model, p.year)
                showConfirmModel = false
            },
        )
    }
}

@Composable
private fun EditProfileDialog(
    title: String,
    initial: VehicleProfile,
    onDismiss: () -> Unit,
    onSave: (VehicleProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var maker by remember { mutableStateOf(initial.maker) }
    var model by remember { mutableStateOf(initial.model) }
    var year by remember { mutableStateOf(initial.year) }
    var engine by remember { mutableStateOf(initial.engine) }
    var vin by remember { mutableStateOf(initial.vin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название профиля") }, singleLine = true)
                OutlinedTextField(maker, { maker = it }, label = { Text("Марка") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Модель") }, singleLine = true)
                OutlinedTextField(year, { year = it }, label = { Text("Год") }, singleLine = true)
                OutlinedTextField(engine, { engine = it }, label = { Text("Двигатель") }, singleLine = true)
                OutlinedTextField(vin, { vin = it }, label = { Text("VIN (необязательно)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    initial.copy(
                        name = name.ifBlank { "Мой автомобиль" },
                        maker = maker.trim(),
                        model = model.trim(),
                        year = year.trim(),
                        engine = engine.trim(),
                        vin = vin.trim().uppercase(),
                        vinStatus = if (vin.isBlank()) VehicleProfile.ID_UNKNOWN else VehicleProfile.ID_MANUAL,
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
