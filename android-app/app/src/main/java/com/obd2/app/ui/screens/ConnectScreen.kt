package com.obd2.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.DiscoveredAdapter
import com.obd2.app.core.bt.SppProfileStatus
import com.obd2.app.core.bt.TransportResolver
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.usb.UsbBaudPresets
import com.obd2.app.core.wifi.WifiObdPresets
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.theme.Amber
import com.obd2.app.ui.theme.Green
import com.obd2.app.ui.theme.Primary
import com.obd2.app.ui.theme.Red
import com.obd2.app.ui.theme.TextDim

private enum class ConnectModeTab {
    BLUETOOTH,
    WIFI,
    USB,
}

@Composable
fun ConnectScreen(vm: ObdViewModel) {
    val ctx = LocalContext.current
    val adapters by vm.adapters.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanMode by vm.scanMode.collectAsStateWithLifecycle()
    val scanPhase by vm.scanPhase.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val pipeline by vm.pipelineStatus.collectAsStateWithLifecycle()
    val lastAdapter by vm.lastAdapter.collectAsStateWithLifecycle()
    val savedAdapters by vm.savedAdapters.collectAsStateWithLifecycle()
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val wifiStatus by vm.wifiNetworkStatus.collectAsStateWithLifecycle()
    val wifiProbing by vm.wifiProbing.collectAsStateWithLifecycle()
    val wifiEndpoints by vm.wifiFoundEndpoints.collectAsStateWithLifecycle()
    val usbDevices by vm.usbDevices.collectAsStateWithLifecycle()
    val usbStatusMsg by vm.usbStatusMessage.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(ConnectModeTab.BLUETOOTH) }
    var wifiHost by remember(settings.lastWifiHost) { mutableStateOf(settings.lastWifiHost) }
    var wifiPortText by remember(settings.lastWifiPort) { mutableStateOf(settings.lastWifiPort.toString()) }
    var selectedUsbBaud by remember(settings.lastUsbBaud) {
        mutableStateOf(settings.lastUsbBaud.takeIf { it > 0 })
    }
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
        Panel(title = "Режим транспорта") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BtScanMode.entries.forEach { mode ->
                    FilterChip(
                        selected = activeTab == ConnectModeTab.BLUETOOTH && scanMode == mode,
                        onClick = {
                            activeTab = ConnectModeTab.BLUETOOTH
                            if (scanMode != mode) {
                                vm.setScanMode(mode)
                                if (scanning) vm.startScan(mode)
                            }
                        },
                        label = { Text(mode.labelRu) },
                    )
                }
                FilterChip(
                    selected = activeTab == ConnectModeTab.WIFI,
                    onClick = {
                        activeTab = ConnectModeTab.WIFI
                        vm.refreshWifiStatus()
                    },
                    label = { Text("Wi-Fi") },
                )
                FilterChip(
                    selected = activeTab == ConnectModeTab.USB,
                    onClick = {
                        activeTab = ConnectModeTab.USB
                        vm.refreshUsbDevices()
                    },
                    label = { Text("USB (OTG)") },
                )
            }

            if (activeTab == ConnectModeTab.BLUETOOTH) {
                if (missing.isEmpty()) {
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
        }

        when (activeTab) {
            ConnectModeTab.WIFI -> {
                Panel(title = "Wi-Fi ELM327 (TCP)") {
                    KeyValue(
                        "Wi-Fi сеть",
                        if (wifiStatus.wifiNetworkAvailable) {
                            buildString {
                                append(wifiStatus.ssid ?: "подключена")
                                wifiStatus.gatewayIp?.let { append(" · шлюз $it") }
                                if (!wifiStatus.hasInternet) append(" (без интернета — ОК)")
                            }
                        } else {
                            "не подключена"
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        }) {
                            Text("Настройки Wi-Fi")
                        }
                        OutlinedButton(
                            onClick = {
                                val p = wifiPortText.toIntOrNull() ?: 35000
                                vm.probeWifiAdapters(wifiHost, p)
                            },
                            enabled = !wifiProbing,
                        ) {
                            Text(if (wifiProbing) "Поиск…" else "Найти адаптер в сети")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Предустановки:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WifiObdPresets.common.forEach { preset ->
                            FilterChip(
                                selected = wifiHost == preset.host && wifiPortText == preset.port.toString(),
                                onClick = {
                                    wifiHost = preset.host
                                    wifiPortText = preset.port.toString()
                                },
                                label = { Text("${preset.host}:${preset.port}") },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = wifiHost,
                            onValueChange = { wifiHost = it },
                            label = { Text("IP-адрес") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = wifiPortText,
                            onValueChange = { wifiPortText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Порт") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val portNum = wifiPortText.toIntOrNull()
                    Button(
                        onClick = { if (portNum != null) vm.connectWifi(wifiHost, portNum) },
                        enabled = wifiHost.isNotBlank() && portNum != null && portNum in 1..65535,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Подключиться по Wi-Fi")
                    }
                    if (wifiEndpoints.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Найденные в сети адреса:", style = MaterialTheme.typography.labelLarge)
                        wifiEndpoints.forEach { ep ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${ep.label}${if (ep.verifiedElm) " · ELM327 OK" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ep.verifiedElm) Green else Amber,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    wifiHost = ep.host
                                    wifiPortText = ep.port.toString()
                                    vm.connectWifi(ep.host, ep.port)
                                }) {
                                    Text("Подкл.")
                                }
                            }
                        }
                    }
                }
            }

            ConnectModeTab.USB -> {
                Panel(title = "USB ELM327 (OTG Serial)") {
                    Button(onClick = { vm.refreshUsbDevices() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Обнаружить USB-устройства")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Скорость порта (бод):", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = selectedUsbBaud == null,
                            onClick = { selectedUsbBaud = null },
                            label = { Text("Авто") },
                        )
                        UsbBaudPresets.autoProbeRates.forEach { rate ->
                            FilterChip(
                                selected = selectedUsbBaud == rate,
                                onClick = { selectedUsbBaud = rate },
                                label = { Text(rate.toString()) },
                            )
                        }
                    }
                    usbStatusMsg?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(msg, color = Amber, style = MaterialTheme.typography.bodySmall)
                    }
                    if (usbDevices.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        usbDevices.forEach { dev ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(dev.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${dev.driverKind.labelRu} · VID:PID ${dev.vidPidHex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (dev.isSupportedSerialAdapter) Green else Red,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (dev.isSupportedSerialAdapter && !dev.hasPermission) {
                                            OutlinedButton(onClick = { vm.requestUsbPermission(dev) }) {
                                                Text("Выдать разрешение USB")
                                            }
                                        }
                                        Button(
                                            onClick = { vm.connectUsb(dev, selectedUsbBaud) },
                                            enabled = dev.isSupportedSerialAdapter && dev.hasPermission,
                                        ) {
                                            Text("Подключиться")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ConnectModeTab.BLUETOOTH -> {
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
                }

                scanError?.let {
                    Text("Ошибка: $it", color = Red, style = MaterialTheme.typography.bodyMedium)
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

        groups.lastUsedAdapter?.let { lastDisp ->
            Panel(title = "Последний использованный адаптер") {
                SavedAdapterRow(
                    display = lastDisp,
                    onConnect = { vm.connectSavedAdapter(lastDisp.saved) },
                )
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
                val badge = when (s.transport) {
                    BtTransportType.CLASSIC_SPP.name -> "Classic"
                    BtTransportType.WIFI_TCP.name -> "Wi-Fi"
                    BtTransportType.USB_SERIAL.name -> "USB"
                    else -> "BLE"
                }
                StatusChip(badge, Primary)
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
