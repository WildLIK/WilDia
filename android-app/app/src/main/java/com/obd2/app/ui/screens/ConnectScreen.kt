package com.obd2.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.DiscoveredAdapter
import com.obd2.app.core.bt.SppProfileStatus
import com.obd2.app.core.bt.TransportResolver
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Primary
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

@Composable
fun ConnectScreen(vm: ObdViewModel) {
    val adapters by vm.adapters.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanMode by vm.scanMode.collectAsState()
    val scanPhase by vm.scanPhase.collectAsState()
    val scanError by vm.scanError.collectAsState()
    val connection by vm.connection.collectAsState()
    val pipeline by vm.pipelineStatus.collectAsState()
    val lastAdapter by vm.lastAdapter.collectAsState()
    val savedAdapters by vm.savedAdapters.collectAsState()
    var showOtherDevices by remember { mutableStateOf(false) }

    val openStartMs = remember { android.os.SystemClock.elapsedRealtime() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) vm.startScan() else vm.stopScan()
    }

    LaunchedEffect(Unit) {
        vm.logConnectScreenOpenTiming(android.os.SystemClock.elapsedRealtime() - openStartMs)
        vm.loadBondedDevicesAsync()
    }

    val groups = remember(adapters, lastAdapter, savedAdapters) {
        TransportResolver.groupBluetoothDevices(adapters, lastAdapter, savedAdapters)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(title = "Состояние") {
            when (val c = connection) {
                is DiagnosticRepository.ConnectionState.Connected -> {
                    StatusChip(
                        if (c.demo) "ДЕМО · ${pipeline.highestStage.labelRu}" else pipeline.highestStage.labelRu.uppercase(),
                        if (c.demo) Amber else if (pipeline.ecuResponding) Green else Amber,
                    )
                    Spacer(Modifier.height(6.dp))
                    KeyValue("Адаптер", c.deviceName)
                    KeyValue("Состояние", c.details)
                    pipeline.lastNote?.let {
                        Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { vm.disconnect() }) { Text("Отключиться") }
                    }
                }
                is DiagnosticRepository.ConnectionState.Connecting -> StatusChip("ПОДКЛЮЧЕНИЕ…", Amber)
                is DiagnosticRepository.ConnectionState.Reconnecting ->
                    StatusChip("ПЕРЕПОДКЛЮЧЕНИЕ (попытка ${c.attempt})", Amber)
                is DiagnosticRepository.ConnectionState.Failed -> {
                    StatusChip("ОШИБКА ПОДКЛЮЧЕНИЯ", Red)
                    Spacer(Modifier.height(6.dp))
                    Text(pipeline.lastNote ?: c.reason, color = Red, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Подсказка: вставьте адаптер в разъём OBD2 и включите зажигание. " +
                            "Для BLE-адаптера (IOS-Vlink/Android-Vlink) сопряжение и PIN НЕ требуются — " +
                            "просто выберите устройство из списка. Классическим Bluetooth-адаптерам " +
                            "(SPP) сопряжение может понадобиться.",
                        color = TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    StatusChip(pipeline.highestStage.labelRu.uppercase(), TextDim)
                    pipeline.lastNote?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        val missing = vm.missingPermissions
        val missingRuntime = vm.missingRuntimePermissions
        if (missing.isNotEmpty()) {
            Panel(title = "Разрешения") {
                Text(
                    "Для работы BLE-сканера нужны разрешения: " +
                        missing.joinToString(", ") { p ->
                            when (p) {
                                Manifest.permission.BLUETOOTH_SCAN -> "Сканирование рядом"
                                Manifest.permission.BLUETOOTH_CONNECT -> "Управление Bluetooth"
                                Manifest.permission.BLUETOOTH -> "Bluetooth (установочное)"
                                Manifest.permission.BLUETOOTH_ADMIN -> "Bluetooth Admin (установочное)"
                                else -> "Местоположение"
                            }
                        },
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (missingRuntime.isNotEmpty()) {
                    Button(onClick = { permissionLauncher.launch(missingRuntime.toTypedArray()) }) {
                        Text("Выдать разрешения")
                    }
                } else {
                    Text(
                        "Системное установочное разрешение Bluetooth отсутствует. Переустановите APK или проверьте политику устройства.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            if (vm.locationDisabledWarning) {
                Panel(title = "Включите геолокацию") {
                    Text(
                        "На Android 8–11 (API ≤ 30) поиск BLE и Bluetooth Classic адаптеров требует включённой службы местоположения (GPS/Геолокация) в шторке системы.",
                        color = Amber,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Panel(title = "Режим транспорта") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BtScanMode.entries.forEach { mode ->
                        FilterChip(
                            selected = scanMode == mode,
                            onClick = {
                                if (scanMode != mode) {
                                    vm.setScanMode(mode)
                                    if (scanning) vm.startScan(mode)
                                }
                            },
                            label = { Text(mode.labelRu) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) {
                        Text(if (scanning) "Остановить поиск" else "Искать адаптеры")
                    }
                    scanPhase?.let { phase ->
                        Text(phase, color = Amber, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        scanError?.let {
            Text("Ошибка: $it", color = Red, style = MaterialTheme.typography.bodyMedium)
        }

        groups.lastUsedAdapter?.let { lastDisp ->
            Panel(title = "Последний использованный адаптер") {
                SavedAdapterRow(
                    display = lastDisp,
                    onConnect = { vm.connectSavedAdapter(lastDisp.saved) },
                )
            }
        }

        Panel(title = "Доступные OBD2-адаптеры (${groups.availableObdAdapters.size})") {
            if (groups.availableObdAdapters.isEmpty() && !scanning) {
                Text(
                    "Нажмите «Искать адаптеры» для поиска OBD2-устройств рядом. Ранее сопряжённые сторонние устройства скрыты в разделе ниже.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.availableObdAdapters.forEach { adapter ->
                    AdapterRow(
                        adapter = adapter,
                        scanMode = scanMode,
                        onConnect = { forced -> vm.connectAdapter(adapter, forced) },
                    )
                }
            }
        }

        if (groups.savedAdapters.isNotEmpty()) {
            Panel(title = "Сохранённые адаптеры (${groups.savedAdapters.size})") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.savedAdapters.forEach { savedDisp ->
                        SavedAdapterRow(
                            display = savedDisp,
                            onConnect = { vm.connectSavedAdapter(savedDisp.saved) },
                        )
                    }
                }
            }
        }

        if (groups.otherDevices.isNotEmpty()) {
            Panel(title = "Другие Bluetooth-устройства (${groups.otherDevices.size})") {
                OutlinedButton(
                    onClick = { showOtherDevices = !showOtherDevices },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showOtherDevices) "Скрыть другие устройства" else "Показать (${groups.otherDevices.size})")
                }
                if (showOtherDevices) {
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height((groups.otherDevices.size * 92).coerceAtMost(360).dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(groups.otherDevices, key = { it.mac }) { adapter ->
                            AdapterRow(
                                adapter = adapter,
                                scanMode = scanMode,
                                onConnect = { forced -> vm.connectAdapter(adapter, forced) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAdapterRow(
    display: com.obd2.app.core.bt.SavedAdapterDisplay,
    onConnect: () -> Unit,
) {
    val s = display.saved
    val tsFmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                StatusChip(if (s.transport == BtTransportType.CLASSIC_SPP.name) "Classic" else "BLE", Primary)
                if (display.isAvailableNow) {
                    StatusChip("Найден сейчас", Green)
                }
            }
            Text(
                buildString {
                    append(s.mac)
                    if (display.isAvailableNow && display.rssi != null) {
                        append(" · RSSI ${display.rssi} dBm")
                    } else {
                        append(" · Доступность неизвестна")
                    }
                    if (s.lastConnectedAtMs > 0L) {
                        append(" · Посл.: ${tsFmt.format(java.util.Date(s.lastConnectedAtMs))}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
            Button(onClick = onConnect) {
                Text("Подключиться")
            }
        }
    }
}

@Composable
private fun AdapterRow(
    adapter: DiscoveredAdapter,
    scanMode: BtScanMode,
    onConnect: (BtTransportType?) -> Unit,
) {
    val resolution = TransportResolver.resolve(adapter, scanMode)
    val isDualChoice = adapter.seenViaBle && (adapter.seenViaClassic || adapter.bluetoothDeviceType == TransportResolver.DEVICE_TYPE_DUAL)
    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(adapter.ts))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(adapter.name.ifEmpty { "(без имени)" }, style = MaterialTheme.typography.titleMedium)
                if (BleScanner.looksLikeObd(adapter.name)) {
                    StatusChip("OBD", Primary)
                }
                if (adapter.seenViaBle || adapter.bluetoothDeviceType == TransportResolver.DEVICE_TYPE_LE) {
                    StatusChip("BLE", Green)
                }
                if (adapter.seenViaClassic || adapter.bluetoothDeviceType == TransportResolver.DEVICE_TYPE_CLASSIC) {
                    StatusChip("Classic", Amber)
                }
            }
            Text(
                buildString {
                    append(adapter.mac)
                    if (adapter.discoveredInCurrentScan) {
                        adapter.rssi?.let { append(" · RSSI $it dBm") }
                        append(" · Найден в $timeStr")
                    } else {
                        append(" · Доступность неизвестна")
                    }
                    if (adapter.bonded) append(" · Сопряжено")
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
            if (adapter.seenViaClassic || adapter.bluetoothDeviceType == TransportResolver.DEVICE_TYPE_CLASSIC ||
                adapter.bluetoothDeviceType == TransportResolver.DEVICE_TYPE_DUAL
            ) {
                val sppColor = when (adapter.sppStatus) {
                    SppProfileStatus.CONFIRMED -> Green
                    SppProfileStatus.ABSENT -> Red
                    else -> TextDim
                }
                Text(adapter.sppStatus.labelRu, style = MaterialTheme.typography.labelSmall, color = sppColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isDualChoice) {
                    TextButton(onClick = { onConnect(BtTransportType.BLE) }) { Text("Подкл. BLE") }
                    TextButton(onClick = { onConnect(BtTransportType.CLASSIC_SPP) }) { Text("Подкл. Classic") }
                } else {
                    TextButton(
                        onClick = { onConnect(null) },
                        enabled = resolution.transport != null,
                    ) {
                        Text(
                            resolution.transport?.let { "Подключить (${it.labelRu})" } ?: "Недоступно",
                        )
                    }
                }
            }
        }
    }
}
