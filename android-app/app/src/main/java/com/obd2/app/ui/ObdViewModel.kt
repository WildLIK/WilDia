package com.obd2.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obd2.app.core.ble.BleDevice
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.ClassicBluetoothScanner
import com.obd2.app.core.bt.ClassicDeviceInfo
import com.obd2.app.core.bt.DiscoveredAdapter
import com.obd2.app.core.bt.TransportResolver
import com.obd2.app.core.db.DtcRecordEntity
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.elm327.ElmResponse
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.FullCheckRunner
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.core.report.SessionReportExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single shared ViewModel for all screens (MVVM). Holds the repository flows
 * plus screen-local operation state (scan results, DTC results, full check…).
 */
class ObdViewModel(
    app: Application,
    scannerOverride: BleScanner? = null,
    classicScannerOverride: ClassicBluetoothScanner? = null,
) : AndroidViewModel(app) {

    /** Constructor used by the default ViewModelProvider factory (no override). */
    constructor(app: Application) : this(app, null, null)

    /** Backward-compatible test constructor. */
    constructor(app: Application, scannerOverride: BleScanner?) : this(app, scannerOverride, null)

    val container = (app as com.obd2.app.ObdApp).container

    val repo: DiagnosticRepository = container.repository
    val settings: SettingsRepository = container.settings


    val settingsFlow: StateFlow<SettingsRepository.Settings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.Settings())

    val connection: StateFlow<DiagnosticRepository.ConnectionState> = repo.connection
    val pipelineStatus: StateFlow<DiagnosticRepository.ConnectionPipelineStatus> = repo.pipelineStatus
    val sessionVehicle: StateFlow<DiagnosticRepository.SessionVehicleIdentity> = repo.sessionVehicle
    val vinIdentificationResult: StateFlow<com.obd2.app.core.obd.VinIdentificationResult?> = repo.vinIdentificationResult
    fun exportVinDiagnosticLog(includeSensitive: Boolean = false): String =
        repo.exportVinDiagnosticLog(includeSensitive)
    val currentDtcState: StateFlow<DiagnosticRepository.CurrentDtcState> = repo.currentDtcState
    val dtcScanRuns: StateFlow<List<DiagnosticRepository.DtcScanRunRecord>> = repo.dtcScanRuns
    val dtcAuditLog: StateFlow<List<DiagnosticRepository.DtcCommandAuditEntry>> = repo.dtcAuditLog
    fun exportDtcAuditLogSafe(includeSensitive: Boolean = false): String =
        repo.exportDtcAuditLogSafe(includeSensitive)
    val currentSessionIdFlow: StateFlow<Long> = repo.currentSessionIdFlow
    val isPolling: StateFlow<Boolean> = repo.isPolling
    val supportedPids: StateFlow<Set<Int>?> = repo.supportedPids
    val unsupportedPids: StateFlow<Set<Int>> = repo.unsupportedPids
    val liveData: StateFlow<Map<Int, PidResult.Ok>> = repo.liveData
    val pidRegistry: StateFlow<List<com.obd2.app.core.obd.PidDefinition>> = repo.pidRegistry
    val registryError: StateFlow<String?> = repo.registryError
    val elmInfo: StateFlow<com.obd2.app.core.elm327.Elm327Client.Elm327Info?> = repo.elmInfo
    val elmStats: StateFlow<com.obd2.app.core.elm327.Elm327Client.Stats?> = repo.elmStats
    val profiles: StateFlow<List<VehicleProfile>> =
        settings.profilesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeProfileId: StateFlow<String> =
        settings.activeProfileIdFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val lastAdapter: StateFlow<com.obd2.app.core.repo.SavedAdapterInfo?> =
        settings.lastAdapterFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val savedAdapters: StateFlow<List<com.obd2.app.core.repo.SavedAdapterInfo>> =
        settings.savedAdaptersFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Saved crash report from the previous launch (details in Diagnostics). */
    val lastCrash: StateFlow<String?> = container.lastCrash
    fun acknowledgeCrash() = container.acknowledgeCrash()

    val omvlSkyController: com.obd2.app.core.lpg.omvl.OmvlSkyReplayController by lazy {
        com.obd2.app.core.lpg.omvl.OmvlSkyReplayController(app)
    }

    val omvlSppManager: com.obd2.app.core.lpg.omvl.OmvlSppSessionManager by lazy {
        com.obd2.app.core.lpg.omvl.OmvlSppSessionManager()
    }

    // ---- scanning ----
    val scanner: BleScanner = scannerOverride ?: BleScanner(app, container.log)
    val classicScanner: ClassicBluetoothScanner =
        classicScannerOverride ?: ClassicBluetoothScanner(app, container.log)

    // ---- connection diagnostics ----
    val diagSession = container.diagSession
    val diagRunner = com.obd2.app.core.diag.DiagRunner(
        context = app.applicationContext,
        session = diagSession,
        repo = repo,
        log = container.log,
        scanner = scanner,
        scope = viewModelScope,
        classicScanner = classicScanner,
    )

    fun runDiag(
        scenario: com.obd2.app.core.diag.DiagRunner.Scenario,
        scanMode: BtScanMode = _scanMode.value,
    ) = viewModelScope.launch { diagRunner.run(scenario, scanMode = scanMode) }

    fun cancelDiag() = diagRunner.cancel()

    fun exportDiagZip(maskIds: Boolean, onResult: (Result<java.io.File>) -> Unit) =
        viewModelScope.launch {
            onResult(runCatching { container.diagExporter.export(maskIds) })
        }
    val bleScanner: BleScanner get() = scanner
    val missingPermissions: List<String> get() = scanner.missingPermissions()
    val missingRuntimePermissions: List<String> get() = scanner.missingRuntimePermissions()
    val locationDisabledWarning: Boolean get() = scanner.isLocationServiceRequiredAndDisabled()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _scanMode = MutableStateFlow(BtScanMode.AUTO)
    val scanMode: StateFlow<BtScanMode> = _scanMode
    private val _scanPhase = MutableStateFlow<String?>(null)
    val scanPhase: StateFlow<String?> = _scanPhase
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices
    private val _adapters = MutableStateFlow<List<DiscoveredAdapter>>(emptyList())
    val adapters: StateFlow<List<DiscoveredAdapter>> = _adapters
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError
    private var scanJob: Job? = null

    fun setScanMode(mode: BtScanMode) {
        _scanMode.value = mode
    }

    private fun mergeBleSighting(d: BleDevice) {
        diagSession.scanSighting(
            com.obd2.app.core.diag.ScanSighting(
                ts = d.ts, mac = d.mac, name = d.name, rssi = d.rssi,
                advServiceUuids = d.serviceUuids,
                manufacturerDataHex = d.manufacturerDataHex,
                serviceDataHex = d.serviceDataHex,
                advFlags = d.advFlags,
                isConnectable = d.connectable,
            ),
        )
        if (BleScanner.looksLikeObd(d.name)) {
            repo.markAdapterFound(d.name)
        }
        val existingBle = _devices.value.firstOrNull { it.mac == d.mac }
        if (existingBle != null) {
            _devices.value = (_devices.value - existingBle) +
                d.copy(manufacturerDataHex = d.manufacturerDataHex ?: existingBle.manufacturerDataHex)
        } else {
            _devices.value = _devices.value + d
        }
        _devices.value = _devices.value.sortedByDescending { it.rssi }

        val existingAdapter = _adapters.value.firstOrNull { it.mac.equals(d.mac, ignoreCase = true) }
        val merged = TransportResolver.mergeAdapters(existingAdapter, ble = d)
        _adapters.value = ((_adapters.value.filterNot { it.mac.equals(d.mac, ignoreCase = true) }) + merged)
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
    }

    private fun mergeClassicSighting(c: ClassicDeviceInfo) {
        diagSession.recordClassicDevice(
            com.obd2.app.core.diag.ClassicScanRecord(
                mac = c.mac,
                name = c.name,
                rssi = c.rssi,
                deviceType = c.deviceType,
                bondState = c.bondState,
                sdpUuids = c.sdpUuids,
                sppStatus = c.sppStatus.name,
                source = c.source,
                ts = c.ts,
            ),
        )
        if (BleScanner.looksLikeObd(c.name)) {
            repo.markAdapterFound(c.name)
        }
        val existingAdapter = _adapters.value.firstOrNull { it.mac.equals(c.mac, ignoreCase = true) }
        val merged = TransportResolver.mergeAdapters(existingAdapter, classic = c)
        _adapters.value = ((_adapters.value.filterNot { it.mac.equals(c.mac, ignoreCase = true) }) + merged)
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
    }

    fun startScan(modeOverride: BtScanMode? = null) {
        val mode = modeOverride ?: _scanMode.value
        _scanMode.value = mode
        _devices.value = emptyList()
        _adapters.value = emptyList()
        _scanError.value = null
        _scanning.value = true
        scanJob?.cancel()
        classicScanner.cancelDiscoveryIfActive()
        scanJob = viewModelScope.launch {
            try {
                diagSession.scanStarted(if (mode == BtScanMode.AUTO) 18 else 12)
                when (mode) {
                    BtScanMode.BLE -> {
                        _scanPhase.value = "Поиск BLE…"
                        scanner.scan(nameFilter = null, scanSeconds = 12).collect { d ->
                            mergeBleSighting(d)
                        }
                    }

                    BtScanMode.CLASSIC -> {
                        _scanPhase.value = "Поиск Bluetooth Classic…"
                        val bonded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            classicScanner.bondedDevices()
                        }
                        bonded.forEach { mergeClassicSighting(it) }
                        classicScanner.discover(scanSeconds = 12).collect { c ->
                            mergeClassicSighting(c)
                        }
                    }

                    BtScanMode.AUTO -> {
                        // Strictly sequential: BLE scan -> cooldown -> Classic inquiry (bonded list loaded via loadBondedDevicesAsync).
                        _scanPhase.value = "Поиск BLE…"
                        var bleError: String? = null
                        try {
                            scanner.scan(nameFilter = null, scanSeconds = 8).collect { d ->
                                mergeBleSighting(d)
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            bleError = e.message
                            container.log.log(RawLog.Dir.INFO, "[SCAN] BLE phase skipped/failed in AUTO: ${e.message}")
                        }
                        kotlinx.coroutines.delay(250)
                        _scanPhase.value = "Поиск Bluetooth Classic…"
                        try {
                            classicScanner.discover(scanSeconds = 10).collect { c ->
                                mergeClassicSighting(c)
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            if (bleError != null) throw e
                            container.log.log(RawLog.Dir.INFO, "[SCAN] Classic phase failed in AUTO: ${e.message}")
                        }
                    }
                }
                diagSession.scanFinished("обычный скан приложения завершён (${mode.name})")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal scan stop (user tapped stop / device picked / VM cleared):
                // cancellation is the lifecycle, not a failure — never surface it.
            } catch (e: Exception) {
                _scanError.value = e.message
            } finally {
                _scanPhase.value = null
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        classicScanner.cancelDiscoveryIfActive()
        _scanPhase.value = null
        _scanning.value = false
    }

    fun loadBondedDevicesAsync() = viewModelScope.launch {
        if (missingPermissions.isNotEmpty()) return@launch
        val bonded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { classicScanner.bondedDevices() }.getOrDefault(emptyList())
        }
        bonded.forEach { mergeClassicSighting(it) }
    }

    fun logConnectScreenOpenTiming(durationMs: Long) {
        diagSession.event(
            com.obd2.app.core.diag.DiagCategory.SYS,
            "connect.screen.open_ms",
            details = "${durationMs}ms",
        )
    }

    fun quickConnectLastAdapter() = viewModelScope.launch {
        val saved = lastAdapter.value ?: return@launch
        connectSavedAdapter(saved)
    }

    fun connectSavedAdapter(saved: com.obd2.app.core.repo.SavedAdapterInfo) = viewModelScope.launch {
        stopScan()
        val transportType = runCatching { BtTransportType.valueOf(saved.transport) }.getOrNull()
        when (transportType) {
            BtTransportType.CLASSIC_SPP -> {
                repo.connectClassic(saved.mac, saved.name, settingsFlow.value)
            }
            BtTransportType.WIFI_TCP -> {
                val parts = saved.mac.split(':')
                val host = parts.getOrNull(0)?.trim().takeIf { !it.isNullOrBlank() } ?: settingsFlow.value.lastWifiHost
                val port = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: settingsFlow.value.lastWifiPort
                repo.connectWifi(com.obd2.app.core.wifi.WifiObdEndpoint(host, port), settingsFlow.value)
            }
            BtTransportType.USB_SERIAL -> {
                refreshUsbDevices()
                val targetVidPid = saved.mac.removePrefix("USB:")
                val dev = _usbDevices.value.firstOrNull {
                    it.isSupportedSerialAdapter && it.vidPidHex.equals(targetVidPid, ignoreCase = true)
                } ?: _usbDevices.value.firstOrNull { it.isSupportedSerialAdapter }
                if (dev != null) {
                    val baud = settingsFlow.value.lastUsbBaud.takeIf { it > 0 }
                    repo.connectUsb(dev, baud, usbBackend, settingsFlow.value)
                } else {
                    _usbStatusMessage.value = "Сохранённый USB-адаптер ($targetVidPid) сейчас не подключён"
                }
            }
            else -> {
                repo.connectBle(BleDevice(saved.mac, saved.name, -70), settingsFlow.value)
            }
        }
    }

    // ---- Wi-Fi TCP ELM327 ----
    private val _wifiNetworkStatus = MutableStateFlow(
        com.obd2.app.core.wifi.WifiNetworkBinder.inspectStatus(app.applicationContext),
    )
    val wifiNetworkStatus: StateFlow<com.obd2.app.core.wifi.WifiNetworkStatus> = _wifiNetworkStatus

    private val _wifiProbing = MutableStateFlow(false)
    val wifiProbing: StateFlow<Boolean> = _wifiProbing

    private val _wifiFoundEndpoints = MutableStateFlow<List<com.obd2.app.core.wifi.WifiObdEndpoint>>(emptyList())
    val wifiFoundEndpoints: StateFlow<List<com.obd2.app.core.wifi.WifiObdEndpoint>> = _wifiFoundEndpoints

    fun refreshWifiStatus() {
        _wifiNetworkStatus.value = com.obd2.app.core.wifi.WifiNetworkBinder.inspectStatus(getApplication())
    }

    fun probeWifiAdapters(customHost: String? = null, customPort: Int? = null) =
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _wifiProbing.value = true
            try {
                refreshWifiStatus()
                _wifiFoundEndpoints.value = com.obd2.app.core.wifi.WifiEndpointProber.probeReachable(
                    context = getApplication(),
                    customHost = customHost,
                    customPort = customPort,
                )
            } finally {
                _wifiProbing.value = false
            }
        }

    fun connectWifi(host: String, port: Int) = viewModelScope.launch {
        stopScan()
        repo.connectWifi(com.obd2.app.core.wifi.WifiObdEndpoint(host.trim(), port), settingsFlow.value)
    }

    // ---- USB Serial OTG ELM327 ----
    private val usbBackend: com.obd2.app.core.usb.UsbSerialBackend by lazy {
        com.obd2.app.core.usb.AndroidUsbSerialBackend(getApplication())
    }

    private val _usbDevices = MutableStateFlow<List<com.obd2.app.core.usb.UsbDeviceDescriptor>>(emptyList())
    val usbDevices: StateFlow<List<com.obd2.app.core.usb.UsbDeviceDescriptor>> = _usbDevices

    private val _usbStatusMessage = MutableStateFlow<String?>(null)
    val usbStatusMessage: StateFlow<String?> = _usbStatusMessage

    fun refreshUsbDevices() {
        val list = runCatching { usbBackend.listDevices() }.getOrElse { emptyList() }
        _usbDevices.value = list
        _usbStatusMessage.value = if (list.isEmpty()) {
            "USB-устройства не обнаружены. Подключите адаптер через OTG-кабель."
        } else null
    }

    fun requestUsbPermission(device: com.obd2.app.core.usb.UsbDeviceDescriptor) {
        usbBackend.requestPermission(device.deviceId) { granted ->
            refreshUsbDevices()
            _usbStatusMessage.value = if (granted) {
                "Разрешение USB выдано для ${device.displayName}"
            } else {
                "Пользователь отклонил разрешение USB для ${device.displayName}"
            }
        }
    }

    fun connectUsb(device: com.obd2.app.core.usb.UsbDeviceDescriptor, baudRate: Int?) = viewModelScope.launch {
        stopScan()
        repo.connectUsb(device, baudRate, usbBackend, settingsFlow.value)
    }

    fun connect(device: BleDevice) = viewModelScope.launch {
        stopScan()
        repo.connectBle(device, settingsFlow.value)
    }

    fun connectAdapter(
        adapter: DiscoveredAdapter,
        forcedTransport: BtTransportType? = null,
    ) = viewModelScope.launch {
        stopScan()
        val resolved = if (forcedTransport != null) {
            TransportResolver.TransportResolution(forcedTransport, "Выбран транспорт ${forcedTransport.labelRu}")
        } else {
            TransportResolver.resolve(adapter, _scanMode.value)
        }
        when (resolved.transport) {
            BtTransportType.BLE -> {
                val ble = adapter.bleDevice ?: BleDevice(adapter.mac, adapter.name, adapter.rssi ?: -70)
                repo.connectBle(ble, settingsFlow.value)
            }
            BtTransportType.CLASSIC_SPP -> {
                repo.connectClassic(adapter.mac, adapter.name.ifEmpty { adapter.mac }, settingsFlow.value)
            }
            BtTransportType.WIFI_TCP, BtTransportType.USB_SERIAL -> {
                _scanError.value = "Для Wi-Fi и USB используйте соответствующую вкладку режима подключения"
            }
            null -> {
                _scanError.value = resolved.reasonRu
            }
        }
    }

    fun connectDemo() = viewModelScope.launch {
        repo.connectDemo(settingsFlow.value)
    }

    fun disconnect() = viewModelScope.launch {
        stopScan()
        repo.disconnect()
    }

    // ---- diagnostics & saved session history ----
    private val _dtcResult = MutableStateFlow<DiagnosticRepository.DtcScanResult?>(null)
    val dtcResult: StateFlow<DiagnosticRepository.DtcScanResult?> = _dtcResult
    private val _readingDtcs = MutableStateFlow(false)
    val readingDtcs: StateFlow<Boolean> = _readingDtcs
    private val _openedSavedSession = MutableStateFlow<DiagnosticRepository.SavedSessionDetails?>(null)
    val openedSavedSession: StateFlow<DiagnosticRepository.SavedSessionDetails?> = _openedSavedSession

    val reportExporter = SessionReportExporter(app.applicationContext)

    fun readDtcs() = viewModelScope.launch {
        _readingDtcs.value = true
        try {
            _dtcResult.value = repo.readDtcs(saveHistory = true)
        } finally {
            _readingDtcs.value = false
        }
    }

    fun openSavedSession(sessionId: Long) = viewModelScope.launch {
        _openedSavedSession.value = repo.loadSavedSessionDetails(sessionId)
    }

    fun closeSavedSession() {
        _openedSavedSession.value = null
    }

    fun deleteSavedSession(sessionId: Long) = viewModelScope.launch {
        if (_openedSavedSession.value?.session?.id == sessionId) {
            _openedSavedSession.value = null
        }
        repo.deleteSavedSession(sessionId)
    }

    suspend fun resolveSessionDetailsForExport(sessionId: Long? = null): DiagnosticRepository.SavedSessionDetails? {
        val targetId = sessionId ?: repo.currentSessionIdFlow.value.takeIf { it > 0 }
        if (targetId != null) {
            repo.loadSavedSessionDetails(targetId)?.let { return it }
        }
        _openedSavedSession.value?.let { return it }
        val cur = _dtcResult.value
        val sv = repo.sessionVehicle.value
        val now = System.currentTimeMillis()
        val syntheticSession = SessionEntity(
            id = 0,
            startedAtMs = cur?.timestampMs ?: now,
            updatedAtMs = now,
            endedAtMs = now,
            adapterName = repo.pipelineStatus.value.adapterName ?: "OBD2",
            transportType = repo.pipelineStatus.value.transportType?.name ?: "BLE",
            demoMode = repo.isDemo,
            protocol = repo.elmInfo.value?.protocol,
            identifiedVin = sv.vin,
            vinMasked = sv.vinMasked,
            ecuIds = listOfNotNull(sv.ecuName, sv.calId).joinToString(" / ").ifBlank { null },
            vehicleIdentitySource = sv.source,
            storedCount = cur?.stored?.size ?: 0,
            pendingCount = cur?.pending?.size ?: 0,
            permanentCount = cur?.permanent?.size ?: 0,
            dtcReadCompleted = cur != null && !cur.incomplete,
        )
        return DiagnosticRepository.SavedSessionDetails(
            session = syntheticSession,
            stored = cur?.stored.orEmpty(),
            pending = cur?.pending.orEmpty(),
            permanent = cur?.permanent.orEmpty(),
            sensors = emptyList(),
        )
    }

    fun exportSessionReport(
        sessionId: Long? = null,
        format: String,
        includeIdentifiers: Boolean = false,
        share: Boolean = false,
        onResult: (Result<File>) -> Unit,
    ) = viewModelScope.launch {
        val res = runCatching {
            val details = resolveSessionDetailsForExport(sessionId)
                ?: throw IllegalStateException("Нет данных сеанса для экспорта")
            val (file, mime) = when (format) {
                "PDF" -> reportExporter.exportPdf(details, includeIdentifiers) to "application/pdf"
                "ZIP" -> reportExporter.exportZip(details, container.log.snapshot(), includeIdentifiers) to "application/zip"
                else -> reportExporter.saveTextReport(details, includeIdentifiers) to "text/plain"
            }
            if (share) {
                reportExporter.shareFile(file, mime)
            }
            file
        }
        onResult(res)
    }

    private val _roomError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = kotlinx.coroutines.flow.combine(
        _roomError,
        settings.storageError,
    ) { roomErr, dsErr ->
        listOfNotNull(roomErr, dsErr).joinToString(" · ").takeIf { it.isNotBlank() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sessions: StateFlow<List<SessionEntity>> =
        repo.appDb.sessionDao().allSessions()
            .catch { e ->
                container.log.log(com.obd2.app.core.log.RawLog.Dir.ERROR,
                    "[VM] sessions flow error: ${e.message}")
                _roomError.value = "Ошибка чтения истории сеансов: ${e.message}"
            }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(), emptyList(),
            )
    val recentDtcRecords: StateFlow<List<DtcRecordEntity>> =
        repo.appDb.dtcRecordDao().recent(200)
            .catch { e ->
                container.log.log(com.obd2.app.core.log.RawLog.Dir.ERROR,
                    "[VM] recent DTC flow error: ${e.message}")
                _roomError.value = "Ошибка чтения истории DTC: ${e.message}"
            }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(), emptyList(),
            )

    // ---- all PIDs ----
    private val _support = MutableStateFlow<ObdClient.PidSupportResult?>(null)
    val support: StateFlow<ObdClient.PidSupportResult?> = _support
    private val _rawRead = MutableStateFlow<RawReadRow?>(null)
    val rawRead: StateFlow<RawReadRow?> = _rawRead

    init {
        viewModelScope.launch {
            repo.supportedPids.collect { set ->
                if (set == null) {
                    _support.value = null
                } else if (_support.value == null || _support.value!!.supportedPids != set) {
                    _support.value = ObdClient.PidSupportResult(set.toSortedSet(), emptyList())
                }
            }
        }
        viewModelScope.launch {
            repo.currentDtcState.collect { st ->
                when (st) {
                    is DiagnosticRepository.CurrentDtcState.NotReadYet -> _dtcResult.value = null
                    is DiagnosticRepository.CurrentDtcState.Reading -> _dtcResult.value = st.partial
                    is DiagnosticRepository.CurrentDtcState.Completed -> _dtcResult.value = st.result
                    is DiagnosticRepository.CurrentDtcState.Incomplete -> _dtcResult.value = st.partial
                }
            }
        }
    }

    data class RawReadRow(val command: String, val lines: String, val durationMs: Long, val ok: Boolean)

    fun discoverSupport() = viewModelScope.launch {
        val o = repo.obd ?: return@launch
        _support.value = o.discoverSupportedPids()
    }

    fun togglePidFavorite(key: String, favorite: Boolean) =
        viewModelScope.launch { settings.setPidFavorite(key, favorite) }

    private suspend fun activeOrCreateAutoProfile(suggestedMaker: String? = null): VehicleProfile {
        val list = settings.profilesFlow.first()
        val id = settings.activeProfileIdFlow.first()
        list.firstOrNull { it.id == id }?.let { return it }
        val newId = "auto-${System.currentTimeMillis()}"
        val created = VehicleProfile(
            id = newId,
            name = suggestedMaker?.let { "$it (авто)" } ?: "Определённый автомобиль",
            maker = suggestedMaker.orEmpty(),
            identityStatus = VehicleProfile.ID_AUTO,
        )
        settings.addProfile(created)
        settings.setActiveProfile(newId)
        return created
    }

    /** Developer-only: explicitly loads the test Škoda Octavia A5 profile. */
    fun loadDevTestSkodaProfile(onDone: (String) -> Unit = {}) = viewModelScope.launch {
        val skoda = settings.devTestSkodaProfile()
        settings.addProfile(skoda)
        settings.setActiveProfile(skoda.id)
        onDone("Загружен тестовый профиль Škoda Octavia A5")
    }

    /** Confirms partially/auto identified session vehicle as an explicit user profile. */
    fun confirmSessionVehicleAsProfile(
        name: String,
        maker: String,
        model: String,
        year: String,
    ) = viewModelScope.launch {
        val sv = repo.sessionVehicle.value
        val existing = settings.profilesFlow.first().firstOrNull { it.id == settings.activeProfileIdFlow.first() }
        val ecuList = listOfNotNull(sv.ecuName, sv.calId)
        if (existing != null && !existing.builtIn) {
            settings.updateProfile(
                existing.copy(
                    name = name.ifBlank { existing.name },
                    maker = maker.ifBlank { existing.maker },
                    model = model.ifBlank { existing.model },
                    year = year.ifBlank { existing.year },
                    vin = sv.vin ?: existing.vin,
                    vinStatus = if (!sv.vin.isNullOrBlank()) VehicleProfile.ID_AUTO else existing.vinStatus,
                    ecuIds = if (ecuList.isNotEmpty()) ecuList else existing.ecuIds,
                    identityStatus = VehicleProfile.ID_MANUAL,
                ),
            )
        } else {
            val newId = "user-${System.currentTimeMillis()}"
            settings.addProfile(
                VehicleProfile(
                    id = newId,
                    name = name.ifBlank { listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Мой автомобиль" } },
                    maker = maker.trim(),
                    model = model.trim(),
                    year = year.trim(),
                    vin = sv.vin.orEmpty(),
                    vinStatus = if (!sv.vin.isNullOrBlank()) VehicleProfile.ID_AUTO else VehicleProfile.ID_UNKNOWN,
                    ecuIds = ecuList,
                    identityStatus = VehicleProfile.ID_MANUAL,
                ),
            )
            settings.setActiveProfile(newId)
        }
    }

    /** Clears demo sessions and test profiles without touching real user history. */
    fun clearDemoData(onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        if (repo.isDemo && repo.isConnectedNow) {
            repo.disconnect()
        }
        val removed = repo.clearDemoData()
        onDone(removed)
    }

    /** Reads VIN via Mode 09 and stores it in the ACTIVE profile (auto status). */
    fun identifyVehicle(onDone: (String) -> Unit) = viewModelScope.launch {
        if (repo.isDemo) { onDone("Демо-режим не является проверкой реального автомобиля"); return@launch }
        val vin = repo.readVin(masked = false)
        if (vin.isNullOrBlank() || vin.length != 17) {
            onDone("VIN не получен: адаптер или ЭБУ не поддержали Mode 09 02")
            return@launch
        }
        val maker = DiagnosticRepository.inferMakerFromVin(vin)
        val profile = activeOrCreateAutoProfile(maker)
        settings.updateProfile(
            profile.copy(
                maker = profile.maker.ifBlank { maker.orEmpty() },
                vin = vin,
                vinStatus = VehicleProfile.ID_AUTO,
                identityStatus = VehicleProfile.ID_AUTO,
            ),
        )
        onDone("VIN определён автоматически и сохранён в профиле «${profile.name}»")
    }

    /** Reads Mode 09 identifiers (ECU name, Calibration ID) into the active profile. */
    fun readEcuIdentifiers(onDone: (String) -> Unit) = viewModelScope.launch {
        if (repo.isDemo) { onDone("Демо-режим не является проверкой реального автомобиля"); return@launch }
        val o = repo.obd
        if (o == null) { onDone("Нет подключения к адаптеру"); return@launch }
        val registry = pidRegistry.value
        val ids = ArrayList<String>()
        for (pid in listOf(0x04, 0x0A)) {
            val def = registry.firstOrNull { it.mode == 9 && it.pid == pid } ?: continue
            when (val r = o.readPid(def, timeoutMs = 4000)) {
                is com.obd2.app.core.obd.PidResult.Ok -> {
                    val text = r.reading.text ?: r.reading.rawHex
                    if (!text.isNullOrBlank()) ids.add("${def.nameRu ?: def.nameEn}: ${text.take(80)}")
                }
                else -> {}
            }
        }
        if (ids.isEmpty()) {
            onDone("Идентификаторы ЭБУ недоступны (Mode 09 не поддержан)")
            return@launch
        }
        val profile = activeOrCreateAutoProfile()
        settings.updateProfile(
            profile.copy(ecuIds = ids, identityStatus = VehicleProfile.ID_AUTO)
        )
        onDone("Сохранено ${ids.size} идентификаторов ЭБУ в профиле «${profile.name}»")
    }

    fun manualRead(command: String) = viewModelScope.launch {
        val resp = repo.readRaw(command, 4000)
        _rawRead.value = when (resp) {
            is ElmResponse.Ok -> RawReadRow(command, resp.lines.joinToString("\n"), resp.durationMs, true)
            is ElmResponse.AdapterMessage -> RawReadRow(command, resp.text, resp.durationMs, false)
            is ElmResponse.Timeout -> RawReadRow(command, "Таймаут. Частично: " + resp.partial, resp.durationMs, false)
        }
    }

    /** Applies an async settings change inside the ViewModel scope. */
    fun updateSettings(block: suspend (SettingsRepository) -> Unit) = viewModelScope.launch {
        block(settings)
    }

    // ---- polling ----
    fun startPolling(pids: List<Int>) = viewModelScope.launch {
        repo.startPolling(pids)
    }

    fun stopPolling() {
        repo.stopPolling()
    }

    fun refreshQuickSnapshot() = viewModelScope.launch {
        repo.refreshQuickSnapshot()
    }

    // ---- full check ----
    private val _fullCheckSteps = MutableStateFlow<List<FullCheckRunner.Step>>(emptyList())
    val fullCheckSteps: StateFlow<List<FullCheckRunner.Step>> = _fullCheckSteps
    private val _fullCheckRunning = MutableStateFlow(false)
    val fullCheckRunning: StateFlow<Boolean> = _fullCheckRunning
    private val _fullCheckResult = MutableStateFlow<FullCheckRunner.Result?>(null)
    val fullCheckResult: StateFlow<FullCheckRunner.Result?> = _fullCheckResult

    fun runFullCheck() = viewModelScope.launch {
        _fullCheckRunning.value = true
        _fullCheckSteps.value = emptyList()
        try {
            val runner = container.fullCheckRunner()
            val res = runner.run(settingsFlow.value.demoMode || repo.isDemo) { id, title, status ->
                // progress callback: update the running marker
                val steps = _fullCheckSteps.value.toMutableList()
                val existing = steps.indexOfFirst { it.id == id }
                val step = FullCheckRunner.Step(id, title, status, 0, "…")
                if (existing >= 0) steps[existing] = step else steps.add(step)
                _fullCheckSteps.value = steps
            }
            _fullCheckSteps.value = res.steps
            _fullCheckResult.value = res
        } finally {
            _fullCheckRunning.value = false
        }
    }

    // ---- report ----
    private val _reportFile = MutableStateFlow<File?>(null)
    val reportFile: StateFlow<File?> = _reportFile
    private val _reportError = MutableStateFlow<String?>(null)
    val reportError: StateFlow<String?> = _reportError

    fun exportReport() = viewModelScope.launch {
        val res = _fullCheckResult.value
        if (res == null) {
            _reportError.value = "Сначала выполните полную проверку"
            return@launch
        }
        try {
            val file = container.reportBuilder.build(
                result = res,
                repo = repo,
                maskVins = settingsFlow.value.maskVin,
                demoMode = settingsFlow.value.demoMode || repo.isDemo,
            )
            _reportFile.value = file
            _reportError.value = null
        } catch (e: Exception) {
            _reportError.value = e.message
        }
    }

    // ---- v0.5.0 Quick & Extended Diagnostics ----
    val quickDiagResult = repo.quickDiagResult
    val quickDiagRunning = repo.quickDiagRunning
    val quickDiagProgress = repo.quickDiagProgress

    val extendedDiagReport = repo.extendedDiagReport
    val extendedDiagRunning = repo.extendedDiagRunning
    val extendedDiagProgress = repo.extendedDiagProgress

    val catalogStatus = repo.catalogStatus

    private val _clearDtcFeedback = MutableStateFlow<com.obd2.app.core.obd.ClearDtcResult?>(null)
    val clearDtcFeedback: StateFlow<com.obd2.app.core.obd.ClearDtcResult?> = _clearDtcFeedback

    fun runQuickDiagnostics() = viewModelScope.launch {
        _clearDtcFeedback.value = null
        repo.runQuickDiagnostics()
    }

    fun runExtendedDiagnostics() = viewModelScope.launch {
        repo.runExtendedDiagnostics()
    }

    fun clearDtcsGuarded(
        userConfirmed: Boolean,
        warningsAcknowledged: Boolean,
    ) = viewModelScope.launch {
        val quick = quickDiagResult.value
        val precheck = com.obd2.app.core.obd.ClearDtcPrecheck(
            dtcSaved = quick?.dtcScan != null && quick.sessionId > 0,
            freezeFrameSaved = quick?.freezeFrame != null,
            connectionStable = repo.isConnectedNow && !repo.isPolling.value,
            userConfirmed = userConfirmed,
            warningsAcknowledged = warningsAcknowledged,
            realTransmissionEnabled = false,
        )
        _clearDtcFeedback.value = repo.clearDtcsGuarded(precheck)
    }

    fun dismissClearDtcFeedback() {
        _clearDtcFeedback.value = null
    }

    // ---- v0.5.1 Charts, Trip Recording, DB Updates, Log Replay ----
    val chartBuffer = repo.chartBuffer
    val chartTick = repo.chartTick
    val tripRecordingState = repo.tripRecorder.state
    val tripRecordings = repo.tripRecordings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedChartPids = MutableStateFlow(com.obd2.app.core.telemetry.TelemetryChartBuffer.DEFAULT_CHART_PIDS)
    val selectedChartPids: StateFlow<List<Int>> = _selectedChartPids

    private val _chartViewingRecordingId = MutableStateFlow<Long?>(null)
    val chartViewingRecordingId: StateFlow<Long?> = _chartViewingRecordingId

    private val _dbTrackStates = MutableStateFlow(repo.dbUpdater.getAllStates())
    val dbTrackStates: StateFlow<List<com.obd2.app.core.updates.TrackState>> = _dbTrackStates

    private val _dbUpdateHistory = MutableStateFlow(repo.dbUpdater.getHistory())
    val dbUpdateHistory: StateFlow<List<com.obd2.app.core.updates.UpdateHistoryEntry>> = _dbUpdateHistory

    private val _updateCheckMessage = MutableStateFlow<String?>(null)
    val updateCheckMessage: StateFlow<String?> = _updateCheckMessage

    fun toggleChartPid(pid: Int) {
        val cur = _selectedChartPids.value
        _selectedChartPids.value = if (pid in cur) {
            (cur - pid).ifEmpty { listOf(pid) }
        } else {
            cur + pid
        }
    }

    fun applyChartPreset(pids: List<Int>) {
        if (pids.isNotEmpty()) _selectedChartPids.value = pids
    }

    fun saveCurrentChartPreset(name: String) = viewModelScope.launch {
        val trimmed = name.trim().ifBlank { "Набор ${_selectedChartPids.value.size} PID" }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val existing = runCatching {
            val raw = settingsFlow.value.chartPresetsJson
            if (raw.isBlank()) emptyList<com.obd2.app.core.telemetry.ChartPreset>()
            else json.decodeFromString<List<com.obd2.app.core.telemetry.ChartPreset>>(raw)
        }.getOrDefault(emptyList())
        val updated = existing + com.obd2.app.core.telemetry.ChartPreset(
            id = "preset_${System.currentTimeMillis()}",
            name = trimmed,
            pids = _selectedChartPids.value,
        )
        settings.setChartPresetsJson(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.obd2.app.core.telemetry.ChartPreset.serializer()), updated))
    }

    fun startTripRecording(pids: List<Int>) = viewModelScope.launch {
        val label = if (repo.sessionVehicle.value.identified) repo.sessionVehicle.value.displayTitle else "Автомобиль не определён"
        repo.tripRecorder.startRecording(
            sessionId = repo.currentSessionIdFlow.value.takeIf { it > 0 },
            selectedPids = pids.ifEmpty { _selectedChartPids.value },
            vehicleLabel = label,
        )
    }

    fun stopTripRecording() = viewModelScope.launch {
        repo.tripRecorder.stopRecording()
    }

    fun loadRecordingIntoCharts(recordingId: Long) = viewModelScope.launch {
        val samples = repo.appDb.tripRecordingDao().samplesForRecording(recordingId)
        val rec = repo.appDb.tripRecordingDao().getRecording(recordingId) ?: return@launch
        val reg = pidRegistry.value
        val byPid = LinkedHashMap<Int, MutableList<com.obd2.app.core.telemetry.ChartSample>>()
        val units = HashMap<Int, String>()
        val names = HashMap<Int, String>()
        for (s in samples) {
            val def = reg.firstOrNull { it.mode == 1 && it.pid == s.pid }
            names[s.pid] = def?.displayName ?: "PID %02X".format(s.pid)
            units[s.pid] = s.unit ?: def?.unit ?: ""
            if (s.value != null) {
                byPid.getOrPut(s.pid) { ArrayList() }.add(
                    com.obd2.app.core.telemetry.ChartSample(s.timestampMs, s.value),
                )
            }
        }
        val pids = rec.selectedPidsCsv.split(",").mapNotNull { it.trim().toIntOrNull(16) }.ifEmpty { byPid.keys.toList() }
        _selectedChartPids.value = pids
        _chartViewingRecordingId.value = recordingId
        chartBuffer.loadHistoricalSamples(byPid, units, names)
    }

    fun clearHistoricalChartMode() {
        _chartViewingRecordingId.value = null
        chartBuffer.clear()
    }

    fun exportTripCsv(
        recordingId: Long,
        share: Boolean = false,
        onResult: (Result<File>) -> Unit,
    ) = viewModelScope.launch {
        val res = runCatching {
            val rec = repo.appDb.tripRecordingDao().getRecording(recordingId)
                ?: throw IllegalStateException("Запись #$recordingId не найдена")
            val samples = repo.appDb.tripRecordingDao().samplesForRecording(recordingId)
            val file = com.obd2.app.core.telemetry.TripCsvExporter.exportCsv(
                context = getApplication(),
                recording = rec,
                samples = samples,
                registry = pidRegistry.value,
            )
            if (share) {
                val intent = com.obd2.app.core.telemetry.TripCsvExporter.buildShareIntent(getApplication(), file)
                val chooser = android.content.Intent.createChooser(intent, "Поделиться CSV").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>().startActivity(chooser)
            }
            file
        }
        onResult(res)
    }

    fun deleteTripRecording(recordingId: Long) = viewModelScope.launch {
        repo.appDb.tripRecordingDao().deleteRecordingCompletely(recordingId)
        if (_chartViewingRecordingId.value == recordingId) {
            clearHistoricalChartMode()
        }
    }

    fun checkDatabaseUpdates(userInitiated: Boolean = true) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val s = settingsFlow.value
        val (allowed, reason) = repo.dbUpdater.canDownload(s.autoUpdateDbs, s.wifiOnlyUpdates, userInitiated)
        if (!allowed && !userInitiated) {
            _updateCheckMessage.value = reason
            return@launch
        }
        val res = repo.dbUpdater.checkGitHubLatestRelease()
        _updateCheckMessage.value = res.fold(
            onSuccess = { (tag, notes) -> "Доступный релиз GitHub: $tag${if (notes.isNotBlank()) " — ${notes.take(120)}" else ""}" },
            onFailure = { "Проверка недоступна офлайн (${it.message ?: "нет сети"})" },
        )
        _dbTrackStates.value = repo.dbUpdater.getAllStates()
        _dbUpdateHistory.value = repo.dbUpdater.getHistory()
    }

    fun rollbackDatabaseTrack(track: com.obd2.app.core.updates.UpdateTrack) {
        repo.dbUpdater.rollback(track)
        _dbTrackStates.value = repo.dbUpdater.getAllStates()
        _dbUpdateHistory.value = repo.dbUpdater.getHistory()
        _updateCheckMessage.value = "Выполнен откат: ${track.titleRu}"
    }

    fun startDevReplayFixture(kind: com.obd2.app.core.replay.ReplayFixtureKind) = viewModelScope.launch {
        val rawText = if (kind == com.obd2.app.core.replay.ReplayFixtureKind.RECORDED_LOG) {
            repo.log.toPlainText(maskVins = false)
        } else null
        val t = com.obd2.app.core.replay.SessionLogReplayer.buildFixture(kind, rawText)
        repo.connectTransportForTest(t, deviceName = kind.labelRu, demo = true)
    }

    // ---- v0.5.2 Online DTC Search & APK Updates ----
    private val _dtcSearchCards = MutableStateFlow<List<com.obd2.app.core.search.DtcDiagnosticCard>>(
        repo.dtcSearchCache.allCached(),
    )
    val dtcSearchCards: StateFlow<List<com.obd2.app.core.search.DtcDiagnosticCard>> = _dtcSearchCards

    private val _dtcSearchRunning = MutableStateFlow(false)
    val dtcSearchRunning: StateFlow<Boolean> = _dtcSearchRunning

    private val _dtcSearchNotice = MutableStateFlow<String?>(null)
    val dtcSearchNotice: StateFlow<String?> = _dtcSearchNotice

    fun searchDtcOnline(
        code: String,
        dtcMode: Int = 3,
        relatedCodes: List<String> = emptyList(),
        isLegacyUnverified: Boolean = false,
    ) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _dtcSearchRunning.value = true
        _dtcSearchNotice.value = null
        try {
            val sv = repo.sessionVehicle.value
            val ext = repo.extendedDiagReport.value?.vehicle
            val req = com.obd2.app.core.search.DtcSearchRequest(
                code = code,
                manufacturer = ext?.manufacturer ?: if (sv.identified && !sv.demo) DiagnosticRepository.inferMakerFromVin(sv.vin) else null,
                model = ext?.model,
                year = ext?.generationOrYears,
                engine = ext?.engine,
                ecuName = ext?.ecuName ?: sv.ecuName,
                dtcMode = dtcMode,
                relatedCodes = relatedCodes.filterNot { it.equals(code, ignoreCase = true) },
                isLegacyUnverified = isLegacyUnverified,
                includeVinConsent = false,
            )
            when (val res = repo.dtcSearchProvider.search(req, settingsFlow.value.onlineDtcSearchEnabled)) {
                is com.obd2.app.core.search.DtcOnlineSearchResponse.Found -> {
                    _dtcSearchCards.value = listOf(res.card) + _dtcSearchCards.value.filterNot { it.code == res.card.code }
                }
                is com.obd2.app.core.search.DtcOnlineSearchResponse.NotFound -> {
                    res.card?.let { card ->
                        _dtcSearchCards.value = listOf(card) + _dtcSearchCards.value.filterNot { it.code == card.code }
                    }
                    _dtcSearchNotice.value = res.reason
                }
                is com.obd2.app.core.search.DtcOnlineSearchResponse.Disabled -> _dtcSearchNotice.value = res.reason
                is com.obd2.app.core.search.DtcOnlineSearchResponse.Blocked -> _dtcSearchNotice.value = res.reason
            }
        } finally {
            _dtcSearchRunning.value = false
        }
    }

    fun searchAllSessionDtcsOnline() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val codes = currentReadDtcRequests()
        if (codes.isEmpty()) {
            _dtcSearchNotice.value = "Нет реально считанных ошибок в текущем или выбранном сеансе."
            return@launch
        }
        val allCodeNames = codes.map { it.first }
        for ((code, mode, legacy) in codes) {
            searchDtcOnline(code, mode, allCodeNames, legacy).join()
        }
    }

    fun currentReadDtcRequests(): List<Triple<String, Int, Boolean>> {
        val cur = repo.currentDtcState.value
        val activeScan = when (cur) {
            is DiagnosticRepository.CurrentDtcState.Completed -> cur.result
            is DiagnosticRepository.CurrentDtcState.Incomplete -> cur.partial
            else -> null
        }
        if (activeScan != null) {
            val all = activeScan.stored + activeScan.pending + activeScan.permanent
            if (all.isNotEmpty()) {
                return all.map { Triple(it.code, it.mode, false) }.distinctBy { it.first }
            }
        }
        val opened = openedSavedSession.value
        if (opened != null && !opened.session.legacyUnverified) {
            val all = opened.stored + opened.pending + opened.permanent
            return all.map { Triple(it.code, it.mode, false) }.distinctBy { it.first }
        }
        return emptyList()
    }

    private val _apkCheckResult = MutableStateFlow<com.obd2.app.core.updates.ApkCheckResult?>(null)
    val apkCheckResult: StateFlow<com.obd2.app.core.updates.ApkCheckResult?> = _apkCheckResult

    private val _apkDownloadProgress = MutableStateFlow<com.obd2.app.core.updates.ApkDownloadProgress?>(null)
    val apkDownloadProgress: StateFlow<com.obd2.app.core.updates.ApkDownloadProgress?> = _apkDownloadProgress

    private val _apkVerifyResult = MutableStateFlow<com.obd2.app.core.updates.ApkVerificationResult?>(null)
    val apkVerifyResult: StateFlow<com.obd2.app.core.updates.ApkVerificationResult?> = _apkVerifyResult

    fun refreshPersistedApkUpdate() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (_apkVerifyResult.value == null) {
            repo.apkUpdateManager.loadPersistedReadyUpdate()?.let { ready ->
                _apkVerifyResult.value = ready
            }
        }
    }

    fun checkApkUpdates() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _apkCheckResult.value = repo.apkUpdateManager.checkForUpdate()
        refreshPersistedApkUpdate()
    }

    fun downloadAndVerifyApk(manifest: com.obd2.app.core.updates.ApkReleaseManifest) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _apkVerifyResult.value = null
        val res = repo.apkUpdateManager.downloadAndVerify(
            manifest = manifest,
            wifiOnly = settingsFlow.value.wifiOnlyUpdates,
            onProgress = { p -> _apkDownloadProgress.value = p },
        )
        _apkDownloadProgress.value = null
        _apkVerifyResult.value = res
    }

    fun canRequestPackageInstalls(): Boolean = repo.apkUpdateManager.canRequestPackageInstalls()
    fun buildUnknownSourcesIntent() = repo.apkUpdateManager.buildUnknownSourcesSettingsIntent()
    fun buildApkInstallIntent(apkFile: File) = repo.apkUpdateManager.buildInstallIntent(apkFile)

    private val _backupNotice = MutableStateFlow<String?>(null)
    val backupNotice: StateFlow<String?> = _backupNotice
    private val _lastBackupFile = MutableStateFlow<File?>(null)
    val lastBackupFile: StateFlow<File?> = _lastBackupFile

    fun exportUserBackup() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val file = com.obd2.app.core.backup.UserDataBackupManager.exportBackup(getApplication(), repo.appDb, settings)
            val verified = com.obd2.app.core.backup.UserDataBackupManager.verifyBackup(file)
            require(verified is com.obd2.app.core.backup.BackupVerifyResult.Valid) { "Ошибка проверки резервной копии" }
            _lastBackupFile.value = file
            _backupNotice.value = "Резервная копия создана и проверена (SHA-256): ${file.name} (сеансов: ${verified.payload.sessions.size})"
        }.onFailure {
            _backupNotice.value = "Ошибка резервного копирования: ${it.message}"
        }
    }

    fun verifyAndRestoreBackup(file: File) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        com.obd2.app.core.backup.UserDataBackupManager.restoreBackup(file, repo.appDb, settings)
            .onSuccess { s ->
                _backupNotice.value = "Восстановлено: сеансов ${s.restoredSessions}, DTC ${s.restoredDtcs}, записей ${s.restoredRecordings}, профилей ${s.restoredProfiles}"
            }
            .onFailure {
                _backupNotice.value = "Ошибка восстановления: ${it.message}"
            }
    }
}

/** Re-export for UI type references. */
typealias ObdClientPidSupport = com.obd2.app.core.obd.ObdClient.PidSupportResult
