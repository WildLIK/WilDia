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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.obd2.app.Screen
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: ObdViewModel, nav: NavHostController? = null) {
    val settings by vm.settingsFlow.collectAsState()

    var dbMeta by remember { mutableStateOf<com.obd2.app.core.db.ObdLookupDb.DbMeta?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var rawCmd by remember { mutableStateOf("") }
    var devNotice by remember { mutableStateOf<String?>(null) }
    var confirmClearDemo by remember { mutableStateOf(false) }
    val rawRead by vm.rawRead.collectAsState()
    val connection by vm.connection.collectAsState()
    val connectedNow = connection is com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected
    val demoRunning = (connection as? com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected)?.demo == true
    val uiScope = rememberCoroutineScope()
    val importDbLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                val err = vm.repo.lookupDb.importDatabase(uri)
                importError = err
                dbMeta = vm.repo.lookupDb.meta()
            }
        }
    }
    val onImportDb = { importDbLauncher.launch(arrayOf("application/octet-stream", "*/*")) }

    LaunchedEffect(Unit) { dbMeta = vm.repo.lookupDb.meta() }

    if (confirmClearDemo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClearDemo = false },
            title = { Text("Очистить демонстрационные данные?") },
            text = {
                Text("Будут удалены только сеансы режима DEMO и встроенные тестовые профили. Реальная история диагностики и ваши профили останутся нетронутыми.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearDemo = false
                        vm.clearDemoData { removed ->
                            devNotice = "Удалено демонстрационных сеансов: $removed"
                        }
                    },
                ) { Text("Очистить") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmClearDemo = false }) { Text("Отмена") }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        Panel(title = "Единицы измерения") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = settings.units == SettingsRepository.Units.METRIC,
                    onClick = { vm.updateSettings { u -> u.setUnits(SettingsRepository.Units.METRIC) } },
                    label = { Text("Метрические") },
                )
                androidx.compose.material3.FilterChip(
                    selected = settings.units == SettingsRepository.Units.IMPERIAL,
                    onClick = { vm.updateSettings { u -> u.setUnits(SettingsRepository.Units.IMPERIAL) } },
                    label = { Text("Имперские") },
                )
            }
        }

        Panel(title = "Скорость обновления") {
            Text("${settings.refreshMs} мс между опросами", color = TextDim, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.refreshMs.toFloat(),
                onValueChange = { vm.updateSettings { u -> u.setRefreshMs(sliderToInt(it)) } },
                valueRange = 200f..3000f,
                steps = 13,
            )
            Text("Таймаут команды: ${"%.0f".format(settings.cmdTimeoutSec)} с", color = TextDim, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.cmdTimeoutSec,
                onValueChange = { vm.updateSettings { u -> u.setCmdTimeoutSec(it) } },
                valueRange = 1f..10f,
                steps = 8,
            )
        }

        Panel(title = "Подключение") {
            SwitchRow("Автоматическое переподключение", settings.autoReconnect) {
                vm.updateSettings { u -> u.setAutoReconnect(it) }
            }
            Text(
                "MTU BLE: ${settings.mtu} байт (запрашивается при подключении)",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.mtu.toFloat(),
                onValueChange = { vm.updateSettings { u -> u.setMtu(sliderMtu(it)) } },
                valueRange = 64f..512f,
                steps = 7,
            )
        }

        Panel(title = "Приватность") {
            SwitchRow("Маскировать VIN в отчётах", settings.maskVin) { vm.updateSettings { u -> u.setMaskVin(it) } }
        }

        Panel(title = "Для разработчиков") {
            SwitchRow("Режим разработчика", settings.devMode) { vm.updateSettings { u -> u.setDevMode(it) } }
            Text(
                "По умолчанию выключен. Содержит демонстрационный режим ELM327, тестовый профиль Škoda, генератор/имитацию данных, консоль OBD2 и очистку тестовых данных.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (settings.devMode) {
                Spacer(Modifier.height(8.dp))
                Panel(title = "Демо-режим ELM327 и тестовый автомобиль") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (demoRunning) vm.disconnect() else vm.connectDemo()
                            },
                        ) {
                            Text(if (demoRunning) "Отключить демо ELM327" else "Подключить демо ELM327")
                        }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { vm.loadDevTestSkodaProfile { devNotice = it } },
                        ) {
                            Text("Тест-профиль Škoda A5")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                vm.startPolling(com.obd2.app.core.repo.DiagnosticRepository.HOME_QUICK_PIDS)
                                devNotice = "Запущен генератор/опрос тестовых PID"
                            },
                            enabled = demoRunning,
                        ) { Text("Генератор PID") }
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                vm.readDtcs()
                                devNotice = "Имитация чтения ошибок запущена"
                            },
                            enabled = demoRunning,
                        ) { Text("Имитация ошибок") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Записей в техническом журнале Bluetooth: ${vm.container.log.snapshot().size}. Полная отладка и экспорт — в меню «Диагностика подключения».",
                        color = TextDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { confirmClearDemo = true },
                        ) {
                            Text("Очистить демонстрационные данные", color = Red)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Воспроизведение логов и фикстур (тест без авто):", style = MaterialTheme.typography.labelSmall, color = TextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    vm.startDevReplayFixture(com.obd2.app.core.replay.ReplayFixtureKind.ARTIFICIAL_DRIVE)
                                    devNotice = "Запущена фикстура поездки (симуляция)"
                                },
                            ) { Text("Фикстура поездки") }
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    vm.startDevReplayFixture(com.obd2.app.core.replay.ReplayFixtureKind.ARTIFICIAL_DISCONNECT)
                                    devNotice = "Запущена фикстура обрыва связи"
                                },
                            ) { Text("Фикстура обрыва") }
                        }
                    devNotice?.let { msg ->
                        Spacer(Modifier.height(4.dp))
                        Text(msg, color = Amber, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Panel(title = "Консоль OBD2") {
                    Text(
                        "Разрешены только стандартные запросы чтения: 01 xx, 03, 07, 09 xx, 0A. " +
                            "Запись, очистка и нестандартные команды блокируются.",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = rawCmd,
                        onValueChange = { rawCmd = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == ' ' } },
                        label = { Text("Например: 01 0C") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.manualRead(rawCmd.trim()) },
                        enabled = connectedNow && isValidRawCmd(rawCmd.trim()),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Отправить") }
                    rawRead?.let { row ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            (if (row.ok) "OK " else "ОШИБКА ") + row.command + " → " + row.lines.take(200),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.ok) Green else Red,
                        )
                        Text("Время: ${row.durationMs} мс", color = TextDim, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Panel(title = "Офлайн-база") {
            dbMeta?.let { m ->
                KeyValue("Версия", m.version)
                KeyValue("Сборка", m.builtAt)
                KeyValue("PID (с декодерами)", m.pids.toString())
                KeyValue("Определений параметров", m.paramDefinitions.toString())
                KeyValue("DTC всего", "${m.dtcTotal} (общих ${m.dtcGeneric}, фирменных ${m.dtcManufacturer} у ${m.makers} производителей)")
                KeyValue("RU-переводов", "${m.ruTranslations} (проверенных ${m.ruVerified}, автоматических ${m.ruAuto})")
                KeyValue("Автомобилей/ЭБУ", "${m.generations} поколений · ${m.ecus} ЭБУ")
                Spacer(Modifier.height(6.dp))
                Text(
                    "База включена в APK и работает без интернета. Импорт обновления базы — " +
                        "кнопка ниже (SQLite-файл obd_data.db, версия берётся из meta; файл со " +
                        "старой или некорректной версией не принимается).",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = onImportDb) {
                        Text("Импорт базы (SQLite)…")
                    }
                    if (nav != null) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { nav.navigate(Screen.UPDATES.route) { launchSingleTop = true } },
                        ) {
                            Text("Обновления баз")
                        }
                    }
                }
                if (nav != null) {
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { nav.navigate(Screen.APP_UPDATES.route) { launchSingleTop = true } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Обновления приложения (APK)")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Онлайн-поиск причин ошибок (opt-in)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = settings.onlineDtcSearchEnabled,
                        onCheckedChange = { v -> vm.updateSettings { it.setOnlineDtcSearchEnabled(v) } },
                    )
                }
                importError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Источники: ${m.sources}",
                    color = TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Атрибуция: данные PID-сигналов OBDb/SAEJ1979 используются по лицензии " +
                        "CC-BY-SA 4.0 (github.com/OBDb/SAEJ1979). Данные obdex — CC0-1.0; " +
                        "Wal33D/dtc-database — MIT. Русские переводы: «проверенные» — ручные, " +
                        "«автоматические» — правило-ориентированные (не проходили проверку человеком).",
                    color = TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Panel(title = "Развитие") {
            Text(
                "Плавающий виджет поверх других приложений — запланирован: архитектура (репозиторий + сервис опроса) уже позволяет добавить overlay-режим без изменений ядра.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Безопасность: приложение выполняет только стандартные диагностические чтения (Mode 01/03/07/09/0A). " +
                    "Очистка ошибок, кодирование, Security Access и управление механизмами недоступны.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun sliderToInt(v: Float): Int = v.toInt()

private fun sliderMtu(v: Float): Int = v.toInt()

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Only safe read commands are allowed: 01 xx / 03 / 07 / 0A / 09 xx */
fun isValidRawCmd(cmd: String): Boolean {
    val c = cmd.uppercase().replace(" ", "")
    return c.matches(Regex("(01[0-9A-F]{2}|03|07|0A|09[0-9A-F]{2})"))
}
