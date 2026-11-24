package com.obd2.app.core.repo

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.obd2.app.core.ble.BleDevice
import com.obd2.app.core.ble.BleTransport
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.ClassicBluetoothTransport
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.DtcRecordEntity
import com.obd2.app.core.db.DtcScanRunEntity
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.db.SessionSensorEntity
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.db.VehicleIdentitySource
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.ElmResponse
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.ClearDtcPrecheck
import com.obd2.app.core.obd.ClearDtcResult
import com.obd2.app.core.obd.DtcModes
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.PidDefinition
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.obd.QuickDiagnosticsResult
import com.obd2.app.core.obd.QuickDiagnosticsRunner
import com.obd2.app.core.telemetry.TelemetryChartBuffer
import com.obd2.app.core.telemetry.TripRecorder
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.DemoElmTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.core.universal.CatalogStatus
import com.obd2.app.core.universal.CompatibilityCatalogManager
import com.obd2.app.core.universal.ExtendedDiagnosticsReport
import com.obd2.app.core.universal.ExtendedDiagnosticsRunner
import com.obd2.app.core.search.PersistentDtcSearchCache
import com.obd2.app.core.search.PublicDtcOnlineSearchProvider
import com.obd2.app.core.updates.ApkUpdateManager
import com.obd2.app.core.updates.DiagnosticDbUpdater
import com.obd2.app.core.updates.UpdateTrack
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central diagnostic orchestrator: connection lifecycle, PID registry, live
 * polling, incremental DTC & sensor autosave, crash recovery, and vehicle
 * identity separation. Read-only OBD operations only.
 */
class DiagnosticRepository(
    private val context: Context,
    val lookupDb: ObdLookupDb,
    val appDb: AppDb,
    val settings: SettingsRepository,
    val log: RawLog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class ConnectionStage(val labelRu: String) {
        DISCONNECTED("Отключено"),
        ADAPTER_FOUND("Адаптер обнаружен"),
        BLE_CONNECTED("BLE подключён"),
        ELM_READY("ELM327 отвечает"),
        PROTOCOL_SET("Протокол автомобиля установлен"),
        ECU_RESPONDING("ЭБУ отвечает"),
        DATA_RECEIVED("Данные получены"),
    }

    data class ConnectionPipelineStatus(
        val adapterFound: Boolean = false,
        val bleConnected: Boolean = false,
        val elmResponding: Boolean = false,
        val protocolEstablished: Boolean = false,
        val ecuResponding: Boolean = false,
        val dataReceived: Boolean = false,
        val adapterName: String? = null,
        val protocolText: String? = null,
        val lastNote: String? = null,
        val transportType: BtTransportType? = null,
        val lastResponseAtMs: Long? = null,
    ) {
        val highestStage: ConnectionStage
            get() = when {
                dataReceived && ecuResponding -> ConnectionStage.DATA_RECEIVED
                ecuResponding -> ConnectionStage.ECU_RESPONDING
                protocolEstablished -> ConnectionStage.PROTOCOL_SET
                elmResponding -> ConnectionStage.ELM_READY
                bleConnected -> ConnectionStage.BLE_CONNECTED
                adapterFound -> ConnectionStage.ADAPTER_FOUND
                else -> ConnectionStage.DISCONNECTED
            }
    }

    /**
     * Vehicle actually identified in the current session (via Mode 09 VIN / ECU ID).
     * Never populated from the manual reference profile (e.g. Škoda Octavia A5).
     */
    data class SessionVehicleIdentity(
        val identified: Boolean = false,
        val displayTitle: String = "Автомобиль не определён",
        val vin: String? = null,
        val vinMasked: String? = null,
        val ecuName: String? = null,
        val calId: String? = null,
        val rawMode09: String? = null,
        val source: String = VehicleIdentitySource.NONE,
        val sourceLabelRu: String = "Автомобиль не определён",
        val confidenceLabelRu: String = "Автомобиль не определён",
        val needsUserConfirmation: Boolean = false,
        val demo: Boolean = false,
    )

    enum class DtcScanStatus(val labelRu: String) {
        DTC_FOUND("Найдены ошибки"),
        DTC_NONE_CONFIRMED("Ошибки отсутствуют (подтверждено ЭБУ)"),
        DTC_READ_FAILED("Чтение не удалось"),
        DTC_NOT_SCANNED("Диагностика не выполнялась"),
    }

    enum class SensorFreshness(val labelRu: String) {
        FRESH("Актуально"),
        STALE("Устарело"),
        NO_CONNECTION("Нет связи"),
        UNSUPPORTED("Не поддерживается"),
        READ_ERROR("Ошибка чтения"),
    }

    data class DtcCommandAuditEntry(
        val timestampMs: Long,
        val runId: String,
        val mode: Int,
        val command: String,
        val rawResponse: String,
        val normalizedHex: String,
        val transportState: String,
        val decodeResult: String,
        val dtcCodes: List<String>,
        val status: DtcScanStatus,
        val failureReason: String? = null,
    )

    data class DtcScanRunRecord(
        val runId: String,
        val sessionId: Long,
        val timestampMs: Long,
        val status: DtcScanStatus,
        val storedCodes: List<String>,
        val pendingCodes: List<String>,
        val permanentCodes: List<String>,
        val errors: List<String>,
        val auditEntries: List<DtcCommandAuditEntry>,
    )

    /** State of DTC reading strictly within the CURRENT connection session. */
    sealed interface CurrentDtcState {
        data object NotReadYet : CurrentDtcState
        data class Reading(val stageLabelRu: String, val partial: DtcScanResult? = null) : CurrentDtcState
        data class Completed(val result: DtcScanResult) : CurrentDtcState
        data class Incomplete(val partial: DtcScanResult?, val reason: String) : CurrentDtcState
    }

    /** Full reconstructed details of any saved session from Room (works offline). */
    data class SavedSessionDetails(
        val session: SessionEntity,
        val stored: List<DtcEntryInfo>,
        val pending: List<DtcEntryInfo>,
        val permanent: List<DtcEntryInfo>,
        val sensors: List<SessionSensorEntity>,
    )

    companion object {
        val HOME_QUICK_PIDS = listOf(0x0C, 0x0D, 0x05, 0x04, 0x42)
        private val DASHBOARD_PROBE_PIDS = listOf(
            0x0C, 0x0D, 0x05, 0x04, 0x42, 0x0F, 0x2F, 0x11, 0x10, 0x1F, 0x5C, 0x06, 0x07, 0x46, 0x0B, 0x0E,
        )
        private const val SENSOR_AUTOSAVE_INTERVAL_MS = 2500L
        const val DEFAULT_STALE_THRESHOLD_MS = 6000L

        fun evaluateSensorFreshness(
            reading: com.obd2.app.core.obd.PidReading?,
            isConnected: Boolean,
            isUnsupported: Boolean,
            nowMs: Long,
            staleThresholdMs: Long = DEFAULT_STALE_THRESHOLD_MS,
            hasReadError: Boolean = false,
        ): SensorFreshness = when {
            isUnsupported -> SensorFreshness.UNSUPPORTED
            !isConnected -> SensorFreshness.NO_CONNECTION
            hasReadError -> SensorFreshness.READ_ERROR
            reading == null -> SensorFreshness.NO_CONNECTION
            nowMs - reading.timestampMs > staleThresholdMs -> SensorFreshness.STALE
            else -> SensorFreshness.FRESH
        }

        fun inferMakerFromVin(vin: String?): String? {
            if (vin.isNullOrBlank() || vin.length < 3) return null
            return when (vin.take(3).uppercase()) {
                "TMB", "TMP" -> "Škoda"
                "WVW", "WVG", "1VW", "3VW" -> "Volkswagen"
                "WAU", "TRU", "WA1" -> "Audi"
                "VSS" -> "SEAT"
                "XTA" -> "Lada"
                "Z94", "KMH", "KNA" -> "Hyundai/Kia"
                "JT1", "JT2", "JTD", "JTE", "JTM", "SB1" -> "Toyota"
                "VF1", "X7L" -> "Renault"
                else -> null
            }
        }
    }

    sealed interface ConnectionState {
        data object Idle : ConnectionState
        data class Connecting(val demo: Boolean, val deviceName: String) : ConnectionState
        data class Connected(val demo: Boolean, val deviceName: String, val details: String) : ConnectionState
        data class Reconnecting(val demo: Boolean, val deviceName: String, val attempt: Int) : ConnectionState
        data class Failed(val demo: Boolean, val deviceName: String, val reason: String) : ConnectionState
        data object Disconnected : ConnectionState
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection

    private val _pipelineStatus = MutableStateFlow(ConnectionPipelineStatus())
    val pipelineStatus: StateFlow<ConnectionPipelineStatus> = _pipelineStatus

    private val _sessionVehicle = MutableStateFlow(SessionVehicleIdentity())
    val sessionVehicle: StateFlow<SessionVehicleIdentity> = _sessionVehicle

    private val _vinIdentificationResult = MutableStateFlow<com.obd2.app.core.obd.VinIdentificationResult?>(null)
    val vinIdentificationResult: StateFlow<com.obd2.app.core.obd.VinIdentificationResult?> = _vinIdentificationResult

    fun exportVinDiagnosticLog(includeSensitive: Boolean = false): String {
        val res = _vinIdentificationResult.value
            ?: return "Журнал идентификации VIN ещё не сформирован. Выполните подключение и «Определить VIN»."
        return com.obd2.app.core.obd.VinIdentificationEngine.formatDiagnosticLog(
            result = res,
            maskVin = !includeSensitive,
        )
    }

    private val _currentDtcState = MutableStateFlow<CurrentDtcState>(CurrentDtcState.NotReadYet)
    val currentDtcState: StateFlow<CurrentDtcState> = _currentDtcState

    private val _dtcScanRuns = MutableStateFlow<List<DtcScanRunRecord>>(emptyList())
    val dtcScanRuns: StateFlow<List<DtcScanRunRecord>> = _dtcScanRuns

    private val _dtcAuditLog = MutableStateFlow<List<DtcCommandAuditEntry>>(emptyList())
    val dtcAuditLog: StateFlow<List<DtcCommandAuditEntry>> = _dtcAuditLog

    private val runCounter = java.util.concurrent.atomic.AtomicLong(0L)

    private val _currentSessionIdFlow = MutableStateFlow(-1L)
    val currentSessionIdFlow: StateFlow<Long> = _currentSessionIdFlow

    private val _lastSuccessfulResponseAtMs = MutableStateFlow<Long?>(null)
    val lastSuccessfulResponseAtMs: StateFlow<Long?> = _lastSuccessfulResponseAtMs

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling

    private val _supportedPids = MutableStateFlow<Set<Int>?>(null)
    val supportedPids: StateFlow<Set<Int>?> = _supportedPids

    private val _unsupportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val unsupportedPids: StateFlow<Set<Int>> = _unsupportedPids

    private var activePollingPids: List<Int> = emptyList()

    private val _pidRegistry = MutableStateFlow<List<PidDefinition>>(emptyList())
    val pidRegistry: StateFlow<List<PidDefinition>> = _pidRegistry

    /** Non-null when the bundled DB failed to load — surfaced in the UI. */
    private val _registryError = MutableStateFlow<String?>(null)
    val registryError: StateFlow<String?> = _registryError

    private val initErrorHandler = CoroutineExceptionHandler { _, t ->
        log.log(RawLog.Dir.ERROR, "init error: ${t.message}")
        _registryError.value = t.message ?: t::class.java.simpleName
    }

    private val _liveData = MutableStateFlow<Map<Int, PidResult.Ok>>(emptyMap())
    val liveData: StateFlow<Map<Int, PidResult.Ok>> = _liveData

    private val _lastVin = MutableStateFlow<String?>(null)
    val lastVin: StateFlow<String?> = _lastVin

    /** Stable mirrors of the current ELM client state (survive reconnects). */
    private val _elmInfo = MutableStateFlow<Elm327Client.Elm327Info?>(null)
    val elmInfo: StateFlow<Elm327Client.Elm327Info?> = _elmInfo
    private val _elmStats = MutableStateFlow<Elm327Client.Stats?>(null)
    val elmStats: StateFlow<Elm327Client.Stats?> = _elmStats

    val catalogManager = CompatibilityCatalogManager(context)
    private val _catalogStatus = MutableStateFlow(catalogManager.status())
    val catalogStatus: StateFlow<CatalogStatus> = _catalogStatus

    private val _quickDiagResult = MutableStateFlow<QuickDiagnosticsResult?>(null)
    val quickDiagResult: StateFlow<QuickDiagnosticsResult?> = _quickDiagResult
    private val _quickDiagRunning = MutableStateFlow(false)
    val quickDiagRunning: StateFlow<Boolean> = _quickDiagRunning
    private val _quickDiagProgress = MutableStateFlow<String?>(null)
    val quickDiagProgress: StateFlow<String?> = _quickDiagProgress

    private val _extendedDiagReport = MutableStateFlow<ExtendedDiagnosticsReport?>(null)
    val extendedDiagReport: StateFlow<ExtendedDiagnosticsReport?> = _extendedDiagReport
    private val _extendedDiagRunning = MutableStateFlow(false)
    val extendedDiagRunning: StateFlow<Boolean> = _extendedDiagRunning
    private val _extendedDiagProgress = MutableStateFlow<String?>(null)
    val extendedDiagProgress: StateFlow<String?> = _extendedDiagProgress

    val chartBuffer = TelemetryChartBuffer()
    val tripRecorder = TripRecorder(appDb)
    val dtcSearchCache = PersistentDtcSearchCache(context)
    val dtcSearchProvider = PublicDtcOnlineSearchProvider(lookupDb, dtcSearchCache)
    val apkUpdateManager = ApkUpdateManager(context)
    val dbUpdater = DiagnosticDbUpdater(
        context = context,
        onTrackApplied = { track, file ->
            when (track) {
                UpdateTrack.DTC_DB -> lookupDb.installVerifiedDatabase(file)
                UpdateTrack.COMPATIBILITY_CATALOG -> {
                    val txt = file.readText(Charsets.UTF_8)
                    val sha = com.obd2.app.core.universal.CompatibilityCatalogManager.sha256Hex(txt)
                    val ok = catalogManager.applyUpdate(txt, sha) is com.obd2.app.core.universal.CatalogUpdateResult.Updated
                    if (ok) _catalogStatus.value = catalogManager.status()
                    ok
                }
                else -> true
            }
        },
        onTrackRolledBack = { track, prevFile ->
            when (track) {
                UpdateTrack.DTC_DB -> {
                    if (prevFile != null && prevFile.exists()) lookupDb.installVerifiedDatabase(prevFile)
                    else lookupDb.revertToBundled()
                }
                UpdateTrack.COMPATIBILITY_CATALOG -> {
                    catalogManager.rollback()
                    _catalogStatus.value = catalogManager.status()
                }
                else -> {}
            }
        },
    )
    val tripRecordings = appDb.tripRecordingDao().allRecordings()

    private val _chartTick = MutableStateFlow(0L)
    val chartTick: StateFlow<Long> = _chartTick

    var transport: ByteTransport? = null
        private set
    var elm: Elm327Client? = null
        private set
    var obd: ObdClient? = null
        private set

    private var pollingJob: Job? = null
    private val pollingMutex = Mutex()
    private val sessionCloseMutex = Mutex()
    private var currentSessionId: Long = -1
        set(value) {
            field = value
            _currentSessionIdFlow.value = value
        }
    private var lastSensorAutosaveAtMs: Long = 0L

    /** Collects ELM client flows into stable mirrors; restarted per connection. */
    private var mirrorJob: Job? = null

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                if (transport != null && !isDemo) {
                    val devName = _pipelineStatus.value.adapterName ?: "Bluetooth-адаптер"
                    log.log(RawLog.Dir.ERROR, "[BT] Bluetooth adapter turned OFF — closing session")
                    scope.launch {
                        handleFatalConnectionLoss(
                            demo = false,
                            deviceName = devName,
                            reason = "Bluetooth на устройстве был выключен",
                        )
                    }
                }
            }
        }
    }

    private fun startMirrors(demo: Boolean, deviceName: String) {
        mirrorJob?.cancel()
        _elmInfo.value = null
        _elmStats.value = null
        val client = elm ?: return
        val t = transport
        mirrorJob = scope.launch {
            launch { client.info.collect { _elmInfo.value = it } }
            launch { client.stats.collect { _elmStats.value = it } }
            launch {
                client.lastSuccessfulResponseAtMs.collect { ts ->
                    _lastSuccessfulResponseAtMs.value = ts
                    if (ts != null) {
                        _pipelineStatus.value = _pipelineStatus.value.copy(lastResponseAtMs = ts)
                    }
                }
            }
            if (t != null) {
                launch {
                    var sawConnected = t.isConnected()
                    t.state.collect { st ->
                        when (st) {
                            is TransportState.Connected -> {
                                sawConnected = true
                                _pipelineStatus.value = _pipelineStatus.value.copy(bleConnected = true)
                            }
                            is TransportState.Reconnecting -> {
                                stopPolling()
                                _pipelineStatus.value = _pipelineStatus.value.copy(
                                    bleConnected = false,
                                    elmResponding = false,
                                    ecuResponding = false,
                                    lastNote = "Потеря связи Bluetooth — переподключение (попытка ${st.attempt})…",
                                )
                                _connection.value = ConnectionState.Reconnecting(demo, deviceName, st.attempt)
                            }
                            is TransportState.Failed -> {
                                if (sawConnected) {
                                    stopPolling()
                                    closeActiveSessionInDb(
                                        status = SessionStatus.CONNECTION_LOST,
                                        reason = st.reason,
                                    )
                                    _pipelineStatus.value = _pipelineStatus.value.copy(
                                        bleConnected = false,
                                        elmResponding = false,
                                        ecuResponding = false,
                                        lastNote = st.reason,
                                    )
                                    _connection.value = ConnectionState.Failed(demo, deviceName, st.reason)
                                }
                            }
                            is TransportState.Disconnected -> {
                                if (sawConnected) {
                                    stopPolling()
                                    closeActiveSessionInDb(
                                        status = SessionStatus.COMPLETED,
                                        reason = "Соединение закрыто",
                                    )
                                    _pipelineStatus.value = ConnectionPipelineStatus()
                                    _connection.value = ConnectionState.Disconnected
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    fun markAdapterFound(name: String) {
        val cur = _pipelineStatus.value
        if (!cur.adapterFound || cur.adapterName != name) {
            _pipelineStatus.value = cur.copy(
                adapterFound = true,
                adapterName = name.ifBlank { cur.adapterName },
            )
        }
    }

    /** Marks any orphan sessions from a previous crash/freeze as INTERRUPTED on startup, and tags legacy unverified DTCs. */
    suspend fun recoverOrphanSessions(): Int = try {
        val count = appDb.sessionDao().markOrphanSessionsInterrupted(
            nowMs = System.currentTimeMillis(),
            reason = "Прерван (приложение было закрыто или потеряло питание до завершения сеанса)",
        )
        runCatching { appDb.tripRecordingDao().markOrphanRecordingsInterrupted(System.currentTimeMillis()) }
        runCatching { appDb.sessionDao().markLegacyUnverifiedSessions() }
        if (count > 0) {
            log.log(RawLog.Dir.INFO, "[RECOVERY] Помечено прерванных сеансов после перезапуска: $count")
        }
        count
    } catch (e: Exception) {
        log.log(RawLog.Dir.ERROR, "[RECOVERY] recoverOrphanSessions failed: ${e.message}")
        0
    }

    /** Deletes ONLY demo-mode sessions and built-in test profiles; never touches real user history. */
    suspend fun clearDemoData(): Int {
        val deletedSessions = runCatching { appDb.deleteAllDemoSessions() }.getOrDefault(0)
        runCatching { settings.clearDemoProfiles() }
        return deletedSessions
    }

    init {
        scope.launch(initErrorHandler) {
            recoverOrphanSessions()
            _pidRegistry.value = lookupDb.allPids()
        }
        // Keep a current snapshot of settings for synchronous use in protocol code.
        scope.launch(initErrorHandler) {
            settings.settings.collect { settingsCache = it }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    @Volatile
    var settingsCache: SettingsRepository.Settings = SettingsRepository.Settings()
        private set

    val isDemo: Boolean get() = transport is DemoElmTransport
    val isConnectedNow: Boolean get() = transport?.isConnected() == true

    // ---------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------

    suspend fun connectBle(device: BleDevice, settingsSnapshot: SettingsRepository.Settings) {
        val label = device.name.ifEmpty { device.mac }
        markAdapterFound(label)
        log.log(RawLog.Dir.INFO, "Подключение к ${device.name} (${device.mac}), MTU=${settingsSnapshot.mtu}")
        val ble = BleTransport(
            context = context,
            mac = device.mac,
            deviceName = label,
            log = log,
            targetMtu = settingsSnapshot.mtu,
            reconnectAttempts = if (settingsSnapshot.autoReconnect) 2 else 0,
        )
        connectWithTransport(ble, demo = false, deviceName = label, transportType = BtTransportType.BLE, adapterMac = device.mac)
    }

    suspend fun connectClassic(
        mac: String,
        deviceName: String,
        settingsSnapshot: SettingsRepository.Settings,
    ) {
        val label = deviceName.ifEmpty { mac }
        markAdapterFound(label)
        log.log(RawLog.Dir.INFO, "Подключение Classic SPP к $label ($mac)")
        val classic = ClassicBluetoothTransport(
            context = context,
            mac = mac,
            deviceName = label,
            log = log,
            reconnectAttempts = if (settingsSnapshot.autoReconnect) 2 else 0,
            scope = scope,
        )
        connectWithTransport(classic, demo = false, deviceName = label, transportType = BtTransportType.CLASSIC_SPP, adapterMac = mac)
    }

    suspend fun connectWifi(
        endpoint: com.obd2.app.core.wifi.WifiObdEndpoint,
        settingsSnapshot: SettingsRepository.Settings,
    ) {
        val label = "Wi-Fi ${endpoint.host}:${endpoint.port}"
        markAdapterFound(label)
        log.log(RawLog.Dir.INFO, "Подключение Wi-Fi TCP к ${endpoint.host}:${endpoint.port}")
        runCatching { settings.setWifiEndpoint(endpoint.host, endpoint.port) }
        val wifi = com.obd2.app.core.wifi.WifiTcpTransport(
            context = context,
            host = endpoint.host,
            port = endpoint.port,
            log = log,
            reconnectAttempts = if (settingsSnapshot.autoReconnect) 2 else 0,
            scope = scope,
        )
        connectWithTransport(
            wifi,
            demo = false,
            deviceName = label,
            transportType = BtTransportType.WIFI_TCP,
            adapterMac = "${endpoint.host}:${endpoint.port}",
        )
    }

    suspend fun connectUsb(
        device: com.obd2.app.core.usb.UsbDeviceDescriptor,
        baudRate: Int?,
        backend: com.obd2.app.core.usb.UsbSerialBackend,
        settingsSnapshot: SettingsRepository.Settings,
    ) {
        val label = device.displayName
        markAdapterFound(label)
        log.log(
            RawLog.Dir.INFO,
            "Подключение USB OTG к $label (${device.vidPidHex}, driver=${device.driverKind.name}, baud=${baudRate ?: "AUTO"})",
        )
        runCatching { settings.setUsbBaud(baudRate ?: 0) }
        val usb = com.obd2.app.core.usb.UsbSerialTransport(
            context = context,
            device = device,
            manualBaudRate = baudRate,
            backend = backend,
            log = log,
            scope = scope,
        )
        connectWithTransport(
            usb,
            demo = false,
            deviceName = label,
            transportType = BtTransportType.USB_SERIAL,
            adapterMac = "USB:${device.vidPidHex}",
        )
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun connectDemo(settingsSnapshot: SettingsRepository.Settings) {
        val demo = DemoElmTransport(scope = scope)
        connectWithTransport(demo, demo = true, deviceName = "Демо")
    }

    internal suspend fun connectTransportForTest(
        customTransport: ByteTransport,
        deviceName: String,
        demo: Boolean = false,
        transportType: BtTransportType? = null,
    ) {
        connectWithTransport(customTransport, demo = demo, deviceName = deviceName, transportType = transportType)
    }

    private suspend fun connectWithTransport(
        targetTransport: ByteTransport,
        demo: Boolean,
        deviceName: String,
        transportType: BtTransportType? = null,
        adapterMac: String? = null,
    ) {
        disconnectInternal(endStatus = SessionStatus.COMPLETED, endReason = "Переключение на новое соединение")
        _currentDtcState.value = CurrentDtcState.NotReadYet
        _dtcScanRuns.value = emptyList()
        _sessionVehicle.value = if (demo) {
            SessionVehicleIdentity(
                identified = true,
                displayTitle = "Демо-симулятор (1.6 MPI)",
                source = VehicleIdentitySource.DEMO,
                sourceLabelRu = VehicleIdentitySource.labelRu(VehicleIdentitySource.DEMO),
                demo = true,
            )
        } else {
            SessionVehicleIdentity()
        }
        _pipelineStatus.value = ConnectionPipelineStatus(
            adapterFound = true,
            adapterName = deviceName,
            transportType = transportType,
        )
        _connection.value = ConnectionState.Connecting(demo, deviceName)
        transport = targetTransport
        elm = Elm327Client(targetTransport, log, scope)
        obd = ObdClient(elm!!)
        startMirrors(demo, deviceName)

        val err = elm!!.initialize()
        val transportConnected = targetTransport.isConnected()
        _pipelineStatus.value = _pipelineStatus.value.copy(bleConnected = transportConnected)

        if (err != null || targetTransport.state.value is TransportState.Failed || !transportConnected) {
            val reason = err ?: (targetTransport.state.value as? TransportState.Failed)?.reason ?: "неизвестная ошибка"
            _pipelineStatus.value = _pipelineStatus.value.copy(
                elmResponding = false,
                lastNote = if (transportConnected) "BLE подключён, но $reason" else reason,
            )
            _connection.value = ConnectionState.Failed(demo, deviceName, reason)
            log.log(RawLog.Dir.ERROR, "Инициализация не удалась: $reason")
            return
        }

        _pipelineStatus.value = _pipelineStatus.value.copy(elmResponding = true)
        if (!demo && !adapterMac.isNullOrBlank()) {
            val verifiedMethod = (targetTransport.state.value as? TransportState.Connected)?.details
            settings.recordSuccessfulAdapter(
                mac = adapterMac,
                name = deviceName,
                transport = (transportType ?: BtTransportType.BLE).name,
                verifiedMethod = verifiedMethod,
            )
        }

        // Probe ECU (0100..0160) then re-query ATDP/ATDPN so ATSP0 resolves the real bus protocol.
        val support = obd!!.discoverSupportedPids()
        elm!!.refreshProtocolAndVoltage()
        val info = elm!!.info.value
        val proto = info.protocol
        val protoEstablished = !proto.isNullOrBlank() &&
            !proto.equals("AUTO", ignoreCase = true) &&
            (info.protocolNumber ?: 0) > 0
        val ecuOk = support.supportedPids.isNotEmpty()
        _supportedPids.value = if (ecuOk) support.supportedPids else null
        if (ecuOk) {
            val probedUnsupported = DASHBOARD_PROBE_PIDS.filterTo(HashSet()) { it !in support.supportedPids }
            _unsupportedPids.value = probedUnsupported
        }

        _pipelineStatus.value = _pipelineStatus.value.copy(
            protocolEstablished = protoEstablished,
            ecuResponding = ecuOk,
            protocolText = proto,
            lastNote = if (ecuOk) null else "ELM327 отвечает, но ЭБУ не отвечает (проверьте зажигание/подключение к авто)",
        )

        startSession(
            adapterName = if (demo) "Демо (симулятор)" else deviceName,
            demo = demo,
            transportType = if (demo) "DEMO" else (transportType?.name ?: "BLE"),
            protocol = proto,
        )
        val details = if (demo) {
            "Симулятор ELM327 v2.3 + ЭБУ 1.6 MPI"
        } else if (ecuOk) {
            targetTransport.state.value.details().ifBlank { "ЭБУ отвечает" }
        } else {
            "ELM327 готов; ЭБУ не отвечает"
        }
        _connection.value = ConnectionState.Connected(demo, deviceName, details)

        if (ecuOk) {
            if (!demo) {
                tryAutoIdentifyVehicleInSession()
            }
            refreshQuickSnapshot()
        }
    }

    private fun TransportState.details(): String = when (this) {
        is TransportState.Connected -> this.details
        else -> ""
    }

    /**
     * Multi-stage, read-only VIN & per-ECU identification for the current session.
     * Uses ATH1 + direct 7E0->7E8 + UDS 22 F1 90 + KWP2000 1A 90 fallbacks and records
     * a complete diagnostic trace. Never falls back to any manual profile VIN.
     */
    private suspend fun tryAutoIdentifyVehicleInSession() {
        val o = obd ?: return
        val activeProfileKey = runCatching {
            val activeId = settings.activeProfileIdFlow.first()
            settings.profilesFlow.first().firstOrNull { it.id == activeId }?.name
        }.getOrNull()

        val detailed = o.identifyVehicleDetailed(activeProfileKey)
        _vinIdentificationResult.value = detailed
        log.log(RawLog.Dir.INFO, "VIN/ECU trace: ${detailed.summaryRu}")

        val vin = detailed.vin?.takeIf { com.obd2.app.core.obd.Mode09Parser.isValidVin(it) }
        val ecuName = detailed.ecuNamesByEcu.entries
            .joinToString(" | ") { "${it.key}: ${it.value}" }
            .takeIf { it.isNotBlank() }
        val calId = detailed.calIdsByEcu.entries
            .joinToString(" | ") { "${it.key}: ${it.value}" }
            .takeIf { it.isNotBlank() }
        val rawMode09 = detailed.steps
            .joinToString(" || ") { "${it.command}: ${it.rawLines.joinToString(" ")}" }
            .takeIf { it.isNotBlank() }

        if (vin != null) {
            _lastVin.value = vin
        }
        if (vin != null || ecuName != null || calId != null || rawMode09 != null) {
            val maker = inferMakerFromVin(vin)
            val masked = vin?.let { RawLog.maskVin(it) }
            val title = buildString {
                if (maker != null) append("$maker · ")
                when {
                    vin != null -> append("VIN $masked")
                    ecuName != null -> append("ЭБУ $ecuName")
                    calId != null -> append("CalID $calId")
                    else -> append("Автомобиль не определён")
                }
            }
            val isFull = vin != null && maker != null
            val identifiedAny = vin != null || ecuName != null || calId != null
            val src = when {
                isFull -> VehicleIdentitySource.AUTO_FULL
                identifiedAny -> VehicleIdentitySource.AUTO_PARTIAL
                else -> VehicleIdentitySource.NONE
            }
            val conf = when {
                isFull -> "Автоматически (VIN + WMI: $maker)"
                vin != null -> "Частично (VIN считан, модель требует подтверждения)"
                identifiedAny -> "Частично (только ID/калибровка ЭБУ, модель требует подтверждения)"
                else -> "Автомобиль не определён"
            }
            _sessionVehicle.value = SessionVehicleIdentity(
                identified = identifiedAny,
                displayTitle = title,
                vin = vin,
                vinMasked = masked,
                ecuName = ecuName,
                calId = calId,
                rawMode09 = rawMode09,
                source = src,
                sourceLabelRu = VehicleIdentitySource.labelRu(src),
                confidenceLabelRu = conf,
                needsUserConfirmation = !isFull,
                demo = false,
            )
            persistTelemetryMetaNow()
        }
    }

    private suspend fun startSession(
        adapterName: String,
        demo: Boolean,
        transportType: String?,
        protocol: String?,
    ) {
        try {
            val manualProfile = runCatching {
                val profiles = settings.profilesFlow.first()
                val id = settings.activeProfileIdFlow.first()
                profiles.firstOrNull { it.id == id }
            }.getOrNull()
            val now = System.currentTimeMillis()
            val sv = _sessionVehicle.value
            currentSessionId = appDb.sessionDao().insertSession(
                SessionEntity(
                    startedAtMs = now,
                    updatedAtMs = now,
                    adapterName = adapterName,
                    transportType = transportType,
                    demoMode = demo,
                    protocol = protocol,
                    profileId = manualProfile?.id,
                    profileName = manualProfile?.name,
                    identifiedVin = sv.vin,
                    vinMasked = sv.vinMasked,
                    ecuIds = listOfNotNull(sv.ecuName, sv.calId).joinToString(" / ").ifBlank { null },
                    vehicleIdentitySource = sv.source,
                    status = SessionStatus.ACTIVE,
                ),
            )
        } catch (e: Exception) {
            log.log(RawLog.Dir.ERROR, "session insert failed: ${e.message}")
        }
    }

    suspend fun disconnect() {
        disconnectInternal(endStatus = SessionStatus.COMPLETED, endReason = "Отключено пользователем")
        _pipelineStatus.value = ConnectionPipelineStatus()
        _sessionVehicle.value = SessionVehicleIdentity()
        _connection.value = ConnectionState.Disconnected
    }

    private suspend fun handleFatalConnectionLoss(
        demo: Boolean,
        deviceName: String,
        reason: String,
    ) {
        stopPolling()
        persistLiveSensorsNow(force = true)
        closeActiveSessionInDb(status = SessionStatus.CONNECTION_LOST, reason = reason)
        mirrorJob?.cancel()
        mirrorJob = null
        runCatching { elm?.shutdown() }
        transport = null
        elm = null
        obd = null
        _pipelineStatus.value = _pipelineStatus.value.copy(
            bleConnected = false,
            elmResponding = false,
            ecuResponding = false,
            dataReceived = false,
            lastNote = reason,
        )
        _connection.value = ConnectionState.Failed(demo, deviceName, reason)
    }

    private suspend fun closeActiveSessionInDb(status: String, reason: String?) {
        sessionCloseMutex.withLock {
            if (currentSessionId > 0) {
                val sid = currentSessionId
                currentSessionId = -1
                try {
                    appDb.sessionDao().closeSessionWithStatus(
                        id = sid,
                        ended = System.currentTimeMillis(),
                        status = status,
                        reason = reason,
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun disconnectInternal(
        endStatus: String = SessionStatus.COMPLETED,
        endReason: String? = null,
    ) {
        stopPolling()
        persistLiveSensorsNow(force = true)
        mirrorJob?.cancel()
        mirrorJob = null
        _elmInfo.value = null
        _elmStats.value = null
        _liveData.value = emptyMap()
        _supportedPids.value = null
        _unsupportedPids.value = emptySet()
        _sessionVehicle.value = SessionVehicleIdentity()
        runCatching { elm?.shutdown() }
        transport = null
        elm = null
        obd = null
        closeActiveSessionInDb(status = endStatus, reason = endReason)
    }

    private suspend fun ensureRegistryLoaded(): List<PidDefinition> =
        _pidRegistry.value.ifEmpty {
            runCatching { lookupDb.allPids() }.getOrDefault(emptyList()).also { _pidRegistry.value = it }
        }

    // ---------------------------------------------------------------------
    // PID operations & incremental sensor persistence
    // ---------------------------------------------------------------------

    suspend fun readPid(pid: Int, mode: Int = 1): PidResult {
        val def = ensureRegistryLoaded().firstOrNull { it.mode == mode && it.pid == pid }
            ?: return PidResult.Error(mode, pid, "NODB", "PID отсутствует в базе")
        val o = obd ?: return PidResult.Error(mode, pid, "OFFLINE", "Нет подключения")
        return o.readPid(def, timeoutMs = (settingsCache.cmdTimeoutSec * 1000).toLong())
    }

    suspend fun readRaw(command: String, timeoutMs: Long = 3000): ElmResponse {
        val o = obd ?: return ElmResponse.AdapterMessage(ElmResponse.AdapterMessage.Kind.ERROR, "Нет подключения", 0)
        return o.readRaw(command, timeoutMs)
    }

    private suspend fun persistTelemetryMetaNow() {
        val sid = currentSessionId
        if (sid <= 0) return
        val sv = _sessionVehicle.value
        try {
            appDb.sessionDao().updateSessionTelemetryMeta(
                id = sid,
                updatedAtMs = System.currentTimeMillis(),
                protocol = elm?.info?.value?.protocol,
                identifiedVin = sv.vin,
                vinMasked = sv.vinMasked,
                ecuIds = listOfNotNull(sv.ecuName, sv.calId).joinToString(" / ").ifBlank { null },
                identitySource = sv.source,
            )
        } catch (e: Exception) {
            log.log(RawLog.Dir.ERROR, "updateSessionTelemetryMeta failed: ${e.message}")
        }
    }

    private suspend fun persistLiveSensorsNow(force: Boolean = false) {
        val sid = currentSessionId
        if (sid <= 0) return
        val snapshot = _liveData.value
        if (snapshot.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSensorAutosaveAtMs < SENSOR_AUTOSAVE_INTERVAL_MS) return
        lastSensorAutosaveAtMs = now
        val defs = ensureRegistryLoaded()
        val entities = snapshot.values.map { ok ->
            val r = ok.reading
            val def = defs.firstOrNull { it.mode == r.mode && it.pid == r.pid }
            val valText = when {
                r.value != null -> if (r.value == r.value.toLong().toDouble()) "%d".format(r.value.toLong()) else "%.2f".format(r.value)
                !r.text.isNullOrBlank() -> r.text
                else -> r.rawHex
            }
            SessionSensorEntity(
                sessionId = sid,
                mode = r.mode,
                pid = r.pid,
                name = def?.displayName ?: "PID %02X".format(r.pid),
                valueText = valText,
                numericValue = r.value,
                unit = r.unit ?: def?.unit,
                rawHex = r.rawHex,
                timestampMs = r.timestampMs,
            )
        }
        try {
            appDb.sessionSensorDao().upsertAll(entities)
            persistTelemetryMetaNow()
        } catch (e: Exception) {
            log.log(RawLog.Dir.ERROR, "sensor autosave failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // DTC reading with immediate per-mode incremental autosave
    // ---------------------------------------------------------------------

    data class DtcScanResult(
        val stored: List<DtcEntryInfo>,
        val pending: List<DtcEntryInfo>,
        val permanent: List<DtcEntryInfo>,
        val errors: List<String>,
        val durationMs: Long,
        val timestampMs: Long,
        val rawStored: List<String>,
        val rawPending: List<String> = emptyList(),
        val rawPermanent: List<String> = emptyList(),
        val incomplete: Boolean = false,
        val sessionId: Long = -1L,
        val status: DtcScanStatus = DtcScanStatus.DTC_NOT_SCANNED,
        val runId: String = "",
        val auditEntries: List<DtcCommandAuditEntry> = emptyList(),
        val modeStatuses: Map<Int, DtcScanStatus> = emptyMap(),
    )

    private data class ModeReadOutcome(
        val dtcs: List<com.obd2.app.core.obd.DtcEntry>,
        val rawLines: List<String>,
        val fatal: Boolean,
        val status: DtcScanStatus,
        val failureReason: String?,
        val auditEntry: DtcCommandAuditEntry,
    )

    fun exportDtcAuditLogSafe(includeSensitive: Boolean = false): String {
        val entries = _dtcAuditLog.value
        if (entries.isEmpty()) return "Журнал команд Mode 03/07/0A пуст."
        val macRegex = Regex("\\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")
        val vinRegex = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
        fun sanitize(text: String): String {
            if (includeSensitive) return text
            var s = macRegex.replace(text) { m -> "XX:XX:XX:XX:" + m.value.takeLast(5) }
            s = vinRegex.replace(s) { m -> RawLog.maskVin(m.value) }
            return s
        }
        return buildString {
            appendLine("=== OBD2 DTC AUDIT LOG (${entries.size} entries) ===")
            entries.forEach { e ->
                appendLine(
                    "[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.timestampMs))}] " +
                        "run=${e.runId} cmd=${e.command} status=${e.status.name} " +
                        "transport=${sanitize(e.transportState)} decode=${e.decodeResult} codes=${e.dtcCodes.joinToString(",")}",
                )
                appendLine("  raw=${sanitize(e.rawResponse)} hex=${sanitize(e.normalizedHex)}")
                if (!e.failureReason.isNullOrBlank()) {
                    appendLine("  reason=${sanitize(e.failureReason)}")
                }
            }
        }.trimEnd()
    }

    data class DtcEntryInfo(
        val code: String,
        val mode: Int,
        val ru: String?,
        val en: String?,
        val scopeLabel: String?,
        val causes: List<String>,
        val causeStatuses: List<String> = emptyList(),
        val symptoms: List<String> = emptyList(),
        val symptomStatuses: List<String> = emptyList(),
        val recommendationsRu: String? = null,
        val detailRu: String? = null,
        val translationStatus: String? = null,
        val severity: String? = null,
        val severityReason: String? = null,
        val relatedCodes: List<String> = emptyList(),
        val source: String,
        val ecuId: String? = null,
        val rawResponse: String? = null,
    )

    suspend fun readDtcs(saveHistory: Boolean = true, vehicleMaker: String? = null): DtcScanResult {
        val runId = "run_${System.currentTimeMillis()}_${runCounter.incrementAndGet()}"
        val o = obd
        if (o == null || !isConnectedNow) {
            val now = System.currentTimeMillis()
            val audit = DtcCommandAuditEntry(
                timestampMs = now,
                runId = runId,
                mode = DtcModes.STORED,
                command = "03",
                rawResponse = "",
                normalizedHex = "",
                transportState = _connection.value::class.java.simpleName,
                decodeResult = "NOT_CONNECTED",
                dtcCodes = emptyList(),
                status = DtcScanStatus.DTC_READ_FAILED,
                failureReason = "Нет подключения к адаптеру",
            )
            recordDtcAuditEntries(listOf(audit))
            val failRes = DtcScanResult(
                emptyList(), emptyList(), emptyList(),
                listOf("Нет подключения к адаптеру"), 0, now, emptyList(),
                incomplete = true,
                status = DtcScanStatus.DTC_READ_FAILED,
                runId = runId,
                auditEntries = listOf(audit),
                modeStatuses = mapOf(DtcModes.STORED to DtcScanStatus.DTC_READ_FAILED),
            )
            _currentDtcState.value = CurrentDtcState.Incomplete(failRes, "Нет подключения к адаптеру")
            return failRes
        }
        val wasPolling = _isPolling.value
        val resumePids = activePollingPids
        if (wasPolling) stopPolling()

        if (saveHistory && currentSessionId <= 0) {
            startSession(
                adapterName = _pipelineStatus.value.adapterName ?: (transport?.name ?: "OBD2"),
                demo = isDemo,
                transportType = if (isDemo) "DEMO" else (_pipelineStatus.value.transportType?.name ?: "BLE"),
                protocol = elm?.info?.value?.protocol,
            )
        }
        val sid = currentSessionId

        try {
            val started = System.currentTimeMillis()
            val errors = ArrayList<String>()
            val auditEntries = ArrayList<DtcCommandAuditEntry>()
            val modeStatuses = LinkedHashMap<Int, DtcScanStatus>()
            val resolvedMaker = vehicleMaker ?: runCatching { resolveSessionVehicleMaker() }.getOrNull()

            _currentDtcState.value = CurrentDtcState.Reading("Чтение сохранённых ошибок (Mode 03)…")

            // 1. Mode 03 (Stored) — read and save immediately ONLY when read succeeded!
            val m03 = readDtcMode(o, DtcModes.STORED, runId, errors)
            auditEntries += m03.auditEntry
            modeStatuses[DtcModes.STORED] = m03.status
            val storedInfos = enrich(m03.dtcs, DtcModes.STORED, resolvedMaker, m03.rawLines.joinToString(" | "))
            if (saveHistory && sid > 0 && m03.status != DtcScanStatus.DTC_READ_FAILED) {
                persistModeDtcImmediately(
                    sid = sid,
                    mode = DtcModes.STORED,
                    modeInfos = storedInfos,
                    storedCount = storedInfos.size,
                    pendingCount = 0,
                    permanentCount = 0,
                    dtcReadCompleted = true,
                    rawStored = m03.rawLines,
                    rawPending = emptyList(),
                    rawPermanent = emptyList(),
                )
            }
            val partialAfter03 = DtcScanResult(
                stored = storedInfos,
                pending = emptyList(),
                permanent = emptyList(),
                errors = errors.toList(),
                durationMs = System.currentTimeMillis() - started,
                timestampMs = System.currentTimeMillis(),
                rawStored = m03.rawLines,
                incomplete = m03.fatal || m03.status == DtcScanStatus.DTC_READ_FAILED,
                sessionId = sid,
                status = if (storedInfos.isNotEmpty()) DtcScanStatus.DTC_FOUND else m03.status,
                runId = runId,
                auditEntries = auditEntries.toList(),
                modeStatuses = modeStatuses.toMap(),
            )
            if (m03.fatal || !isConnectedNow) {
                val reason = errors.lastOrNull() ?: "Потеряна связь во время чтения Mode 03"
                recordDtcAuditEntries(auditEntries)
                recordScanRun(sid, partialAfter03)
                _currentDtcState.value = CurrentDtcState.Incomplete(partialAfter03, reason)
                handleMidScanAbort(reason)
                return partialAfter03
            }

            _currentDtcState.value = CurrentDtcState.Reading("Чтение ожидающих ошибок (Mode 07)…", partialAfter03)

            // 2. Mode 07 (Pending) — read and save immediately ONLY when read succeeded!
            val m07 = readDtcMode(o, DtcModes.PENDING, runId, errors)
            auditEntries += m07.auditEntry
            modeStatuses[DtcModes.PENDING] = m07.status
            val pendingInfos = enrich(m07.dtcs, DtcModes.PENDING, resolvedMaker, m07.rawLines.joinToString(" | "))
            if (saveHistory && sid > 0 && m07.status != DtcScanStatus.DTC_READ_FAILED) {
                persistModeDtcImmediately(
                    sid = sid,
                    mode = DtcModes.PENDING,
                    modeInfos = pendingInfos,
                    storedCount = storedInfos.size,
                    pendingCount = pendingInfos.size,
                    permanentCount = 0,
                    dtcReadCompleted = m03.status != DtcScanStatus.DTC_READ_FAILED,
                    rawStored = m03.rawLines,
                    rawPending = m07.rawLines,
                    rawPermanent = emptyList(),
                )
            }
            val partialAfter07 = partialAfter03.copy(
                pending = pendingInfos,
                errors = errors.toList(),
                durationMs = System.currentTimeMillis() - started,
                rawPending = m07.rawLines,
                incomplete = m03.status == DtcScanStatus.DTC_READ_FAILED || m07.fatal || m07.status == DtcScanStatus.DTC_READ_FAILED,
                status = when {
                    (storedInfos + pendingInfos).isNotEmpty() -> DtcScanStatus.DTC_FOUND
                    m03.status == DtcScanStatus.DTC_READ_FAILED -> DtcScanStatus.DTC_READ_FAILED
                    else -> m07.status
                },
                auditEntries = auditEntries.toList(),
                modeStatuses = modeStatuses.toMap(),
            )
            if (m07.fatal || !isConnectedNow) {
                val reason = errors.lastOrNull() ?: "Потеряна связь во время чтения Mode 07"
                recordDtcAuditEntries(auditEntries)
                recordScanRun(sid, partialAfter07)
                _currentDtcState.value = CurrentDtcState.Incomplete(partialAfter07, reason)
                handleMidScanAbort(reason)
                return partialAfter07
            }

            _currentDtcState.value = CurrentDtcState.Reading("Чтение постоянных ошибок (Mode 0A)…", partialAfter07)

            // 3. Mode 0A (Permanent) — read and save immediately ONLY when read succeeded!
            val m0A = readDtcMode(o, DtcModes.PERMANENT, runId, errors)
            auditEntries += m0A.auditEntry
            modeStatuses[DtcModes.PERMANENT] = m0A.status
            val permanentInfos = enrich(m0A.dtcs, DtcModes.PERMANENT, resolvedMaker, m0A.rawLines.joinToString(" | "))
            if (saveHistory && sid > 0 && m0A.status != DtcScanStatus.DTC_READ_FAILED) {
                persistModeDtcImmediately(
                    sid = sid,
                    mode = DtcModes.PERMANENT,
                    modeInfos = permanentInfos,
                    storedCount = storedInfos.size,
                    pendingCount = pendingInfos.size,
                    permanentCount = permanentInfos.size,
                    dtcReadCompleted = m03.status != DtcScanStatus.DTC_READ_FAILED,
                    rawStored = m03.rawLines,
                    rawPending = m07.rawLines,
                    rawPermanent = m0A.rawLines,
                )
            }

            val anyFound = storedInfos.isNotEmpty() || pendingInfos.isNotEmpty() || permanentInfos.isNotEmpty()
            val mode03ConfirmedEmpty = m03.status == DtcScanStatus.DTC_NONE_CONFIRMED
            val mode07Failed = m07.status == DtcScanStatus.DTC_READ_FAILED
            val overallStatus = when {
                anyFound -> DtcScanStatus.DTC_FOUND
                mode03ConfirmedEmpty && !mode07Failed -> DtcScanStatus.DTC_NONE_CONFIRMED
                else -> DtcScanStatus.DTC_READ_FAILED
            }
            val incomplete = overallStatus == DtcScanStatus.DTC_READ_FAILED ||
                m03.status == DtcScanStatus.DTC_READ_FAILED ||
                m07.status == DtcScanStatus.DTC_READ_FAILED

            val dur = System.currentTimeMillis() - started
            val result = DtcScanResult(
                stored = storedInfos,
                pending = pendingInfos,
                permanent = permanentInfos,
                errors = errors,
                durationMs = dur,
                timestampMs = System.currentTimeMillis(),
                rawStored = m03.rawLines,
                rawPending = m07.rawLines,
                rawPermanent = m0A.rawLines,
                incomplete = incomplete,
                sessionId = sid,
                status = overallStatus,
                runId = runId,
                auditEntries = auditEntries.toList(),
                modeStatuses = modeStatuses.toMap(),
            )
            recordDtcAuditEntries(auditEntries)
            recordScanRun(sid, result)

            if (overallStatus == DtcScanStatus.DTC_READ_FAILED) {
                val reason = errors.firstOrNull() ?: "Нет подтверждённого ответа ЭБУ на чтение ошибок"
                _currentDtcState.value = CurrentDtcState.Incomplete(result, reason)
            } else {
                _pipelineStatus.value = _pipelineStatus.value.copy(
                    ecuResponding = true,
                    dataReceived = true,
                    lastResponseAtMs = System.currentTimeMillis(),
                )
                _currentDtcState.value = CurrentDtcState.Completed(result)
            }
            return result
        } finally {
            if (wasPolling && resumePids.isNotEmpty() && isConnectedNow && _pipelineStatus.value.ecuResponding) {
                startPolling(resumePids)
            }
        }
    }

    private suspend fun handleMidScanAbort(reason: String) {
        if (!isConnectedNow || (elm?.consecutiveTimeouts?.value ?: 0) >= 2) {
            val dev = _pipelineStatus.value.adapterName ?: (transport?.name ?: "OBD2")
            handleFatalConnectionLoss(isDemo, dev, reason)
        } else {
            _pipelineStatus.value = _pipelineStatus.value.copy(
                ecuResponding = false,
                lastNote = reason,
            )
        }
    }

    private suspend fun persistModeDtcImmediately(
        sid: Long,
        mode: Int,
        modeInfos: List<DtcEntryInfo>,
        storedCount: Int,
        pendingCount: Int,
        permanentCount: Int,
        dtcReadCompleted: Boolean,
        rawStored: List<String>,
        rawPending: List<String>,
        rawPermanent: List<String>,
    ) {
        try {
            val now = System.currentTimeMillis()
            val entities = modeInfos.map {
                DtcRecordEntity(
                    sessionId = sid,
                    code = it.code,
                    mode = mode,
                    descriptionRu = it.ru,
                    descriptionEn = it.en,
                    scope = it.scopeLabel,
                    timestampMs = now,
                    ecuId = it.ecuId,
                    rawResponse = it.rawResponse,
                    translationStatus = it.translationStatus,
                )
            }
            appDb.dtcRecordDao().replaceModeRecords(sid, mode, entities)
            val rawJson = buildString {
                append("{\"03\":${rawStored.joinToString(",", "[", "]") { "\"${it.replace("\"", "'")}\"" }},")
                append("\"07\":${rawPending.joinToString(",", "[", "]") { "\"${it.replace("\"", "'")}\"" }},")
                append("\"0A\":${rawPermanent.joinToString(",", "[", "]") { "\"${it.replace("\"", "'")}\"" }}}")
            }
            appDb.sessionDao().updateSessionDtcProgress(
                id = sid,
                updatedAtMs = now,
                stored = storedCount,
                pending = pendingCount,
                permanent = permanentCount,
                dtcReadCompleted = dtcReadCompleted,
                protocol = elm?.info?.value?.protocol,
                vinMasked = _sessionVehicle.value.vinMasked ?: _lastVin.value?.let { RawLog.maskVin(it) },
                rawDtcJson = rawJson,
            )
        } catch (e: Exception) {
            log.log(RawLog.Dir.ERROR, "immediate DTC persist failed (mode=$mode): ${e.message}")
        }
    }

    private fun recordDtcAuditEntries(entries: List<DtcCommandAuditEntry>) {
        if (entries.isEmpty()) return
        _dtcAuditLog.value = (_dtcAuditLog.value + entries).takeLast(200)
    }

    private suspend fun recordScanRun(sessionId: Long, result: DtcScanResult) {
        val rec = DtcScanRunRecord(
            runId = result.runId,
            sessionId = sessionId,
            timestampMs = result.timestampMs,
            status = result.status,
            storedCodes = result.stored.map { it.code },
            pendingCodes = result.pending.map { it.code },
            permanentCodes = result.permanent.map { it.code },
            errors = result.errors,
            auditEntries = result.auditEntries,
        )
        _dtcScanRuns.value = (listOf(rec) + _dtcScanRuns.value).take(30)
        if (sessionId > 0) {
            runCatching {
                fun List<String>.toJsonArray(): String =
                    joinToString(",", "[", "]") { "\"${it.replace("\"", "'")}\"" }
                val auditJson = result.auditEntries.joinToString(",", "[", "]") { e ->
                    "{\"ts\":${e.timestampMs},\"cmd\":\"${e.command}\",\"status\":\"${e.status.name}\"," +
                        "\"raw\":\"${e.rawResponse.replace("\"", "'")}\",\"decode\":\"${e.decodeResult.replace("\"", "'")}\"}"
                }
                appDb.dtcScanRunDao().insert(
                    DtcScanRunEntity(
                        runId = result.runId,
                        sessionId = sessionId,
                        timestampMs = result.timestampMs,
                        status = result.status.name,
                        storedCodesJson = rec.storedCodes.toJsonArray(),
                        pendingCodesJson = rec.pendingCodes.toJsonArray(),
                        permanentCodesJson = rec.permanentCodes.toJsonArray(),
                        errorsJson = result.errors.toJsonArray(),
                        auditJson = auditJson,
                    ),
                )
            }.onFailure { e ->
                log.log(RawLog.Dir.ERROR, "dtcScanRun insert failed: ${e.message}")
            }
        }
    }

    private suspend fun readDtcMode(
        o: ObdClient,
        mode: Int,
        runId: String,
        errors: MutableList<String>,
    ): ModeReadOutcome {
        val cmd = "%02X".format(mode)
        val ts = System.currentTimeMillis()
        val tState = _connection.value::class.java.simpleName
        return when (val r = o.readDtc(mode, timeoutMs = 4500)) {
            is ObdClient.DtcReadResult.Ok -> {
                r.warnings.forEach { w ->
                    errors.add("Mode %02X: %s".format(mode, w))
                }
                val st = if (r.dtcs.isNotEmpty()) DtcScanStatus.DTC_FOUND else DtcScanStatus.DTC_NONE_CONFIRMED
                val rawJoined = r.rawLines.joinToString(" | ")
                val normHex = r.rawLines.joinToString(" ") { it.replace(Regex("[^0-9A-Fa-f]"), "").uppercase() }
                val entry = DtcCommandAuditEntry(
                    timestampMs = ts,
                    runId = runId,
                    mode = mode,
                    command = cmd,
                    rawResponse = rawJoined,
                    normalizedHex = normHex,
                    transportState = tState,
                    decodeResult = if (r.dtcs.isNotEmpty()) "OK_${r.dtcs.size}_DTC" else "OK_CONFIRMED_ZERO",
                    dtcCodes = r.dtcs.map { it.code },
                    status = st,
                    failureReason = r.warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
                )
                ModeReadOutcome(r.dtcs, r.rawLines, fatal = false, status = st, failureReason = null, auditEntry = entry)
            }
            is ObdClient.DtcReadResult.Unsupported -> {
                val reason = "Mode %02X: не поддерживается ЭБУ (%s)".format(mode, r.reason)
                errors.add(reason)
                val entry = DtcCommandAuditEntry(
                    timestampMs = ts,
                    runId = runId,
                    mode = mode,
                    command = cmd,
                    rawResponse = r.rawLines.joinToString(" | "),
                    normalizedHex = r.rawLines.joinToString(" ") { it.replace(Regex("[^0-9A-Fa-f]"), "").uppercase() },
                    transportState = tState,
                    decodeResult = "UNSUPPORTED",
                    dtcCodes = emptyList(),
                    status = DtcScanStatus.DTC_NOT_SCANNED,
                    failureReason = reason,
                )
                ModeReadOutcome(emptyList(), r.rawLines, fatal = false, status = DtcScanStatus.DTC_NOT_SCANNED, failureReason = reason, auditEntry = entry)
            }
            is ObdClient.DtcReadResult.Error -> {
                val reason = "Mode %02X: %s (%s)".format(mode, r.kind, r.message)
                if (mode == DtcModes.PERMANENT) {
                    errors.add("Mode 0A (постоянные): ${r.message}")
                } else {
                    errors.add(reason)
                }
                // Transport-level errors are fatal; NO_DATA / PARSE_ERROR on Mode 03/07 mean read failed (not fatal drop, but NEVER "no errors"!)
                val fatal = (mode != DtcModes.PERMANENT) && (r.kind in setOf("TIMEOUT", "UNABLE_TO_CONNECT", "ERROR") || !isConnectedNow)
                val rawJoined = r.rawLines.joinToString(" | ").ifBlank { r.message }
                val entry = DtcCommandAuditEntry(
                    timestampMs = ts,
                    runId = runId,
                    mode = mode,
                    command = cmd,
                    rawResponse = rawJoined,
                    normalizedHex = r.rawLines.joinToString(" ") { it.replace(Regex("[^0-9A-Fa-f]"), "").uppercase() },
                    transportState = tState,
                    decodeResult = "ERROR_${r.kind}",
                    dtcCodes = emptyList(),
                    status = DtcScanStatus.DTC_READ_FAILED,
                    failureReason = reason,
                )
                ModeReadOutcome(emptyList(), r.rawLines, fatal = fatal, status = DtcScanStatus.DTC_READ_FAILED, failureReason = reason, auditEntry = entry)
            }
        }
    }

    /**
     * Resolves vehicle maker for OEM DTC lookup:
     * 1. If the session identified a VIN, uses the WMI maker from that VIN (never mixing previous profile data!).
     * 2. Otherwise, only uses a user-selected profile if it is NOT the built-in reference Škoda profile,
     *    or if its `identityStatus` was explicitly confirmed.
     */
    private suspend fun resolveSessionVehicleMaker(): String? {
        val sv = _sessionVehicle.value
        inferMakerFromVin(sv.vin)?.let { return it }
        val profiles = settings.profilesFlow.first()
        val id = settings.activeProfileIdFlow.first()
        val active = profiles.firstOrNull { it.id == id } ?: return null
        if (active.builtIn && !sv.identified) {
            return null
        }
        return active.maker.takeIf { it.isNotBlank() }
    }

    private fun matchLookupForMaker(
        lookups: List<ObdLookupDb.DtcLookup>,
        vehicleMaker: String?,
    ): ObdLookupDb.DtcLookup? {
        val generic = lookups.firstOrNull { it.scope == "GENERIC" }
        if (generic != null) return generic
        if (lookups.isEmpty()) return null
        val m = vehicleMaker?.trim()?.uppercase().orEmpty()
        if (m.isEmpty()) return null
        if (m in setOf("SKODA", "ŠKODA", "VOLKSWAGEN", "VW", "AUDI", "SEAT", "VAG")) {
            lookups.firstOrNull { it.groupTag == "VAG" }?.let { return it }
        }
        return lookups.firstOrNull { it.scope.uppercase() == m || it.scope.uppercase().contains(m) }
    }

    internal suspend fun readDtcEnrichPreviewForTest(code: String, vehicleMaker: String?): DtcEntryInfo =
        enrich(listOf(com.obd2.app.core.obd.DtcEntry(code, DtcModes.STORED)), DtcModes.STORED, vehicleMaker).first()

    private suspend fun enrich(
        entries: List<com.obd2.app.core.obd.DtcEntry>,
        mode: Int,
        vehicleMaker: String?,
        rawResponse: String? = null,
    ): List<DtcEntryInfo> =
        entries.map { entry ->
            val code = entry.code
            val lookups = try { lookupDb.lookupDtc(code) } catch (_: Exception) { emptyList() }
            val matched = matchLookupForMaker(lookups, vehicleMaker)
            if (matched != null) {
                DtcEntryInfo(
                    code = code,
                    mode = mode,
                    ru = matched.descriptionRu,
                    en = matched.description,
                    scopeLabel = if (matched.scope == "GENERIC") "SAE (общий)"
                    else matched.scope.lowercase().replaceFirstChar { c -> c.uppercaseChar() },
                    causes = matched.causes,
                    causeStatuses = matched.causeStatuses,
                    symptoms = matched.symptoms,
                    symptomStatuses = matched.symptomStatuses,
                    recommendationsRu = matched.recommendationsRu,
                    detailRu = matched.detailRu,
                    translationStatus = matched.translationStatus,
                    severity = matched.severity,
                    severityReason = matched.severityReason,
                    relatedCodes = matched.relatedCodes,
                    source = matched.source,
                    ecuId = entry.ecuId,
                    rawResponse = rawResponse,
                )
            } else {
                val availableScopes = lookups.map {
                    it.scope.lowercase().replaceFirstChar { c -> c.uppercaseChar() }
                }.distinct()
                val note = if (availableScopes.isEmpty()) {
                    "Фирменный или нестандартный код (расшифровка отсутствует в базе)"
                } else {
                    "Фирменный код — расшифровка зависит от марки (в базе есть варианты для: ${availableScopes.take(4).joinToString(", ")})"
                }
                DtcEntryInfo(
                    code = code,
                    mode = mode,
                    ru = note,
                    en = lookups.firstOrNull()?.description,
                    scopeLabel = if (availableScopes.isEmpty()) null else "Не сопоставлено с маркой",
                    causes = emptyList(),
                    source = if (availableScopes.isEmpty()) "нет в базе" else "требуется уточнение марки",
                    ecuId = entry.ecuId,
                    rawResponse = rawResponse,
                )
            }
        }

    /** Reconstructs full rich details for a saved session from Room (100% offline). */
    suspend fun loadSavedSessionDetails(sessionId: Long): SavedSessionDetails? {
        val session = appDb.sessionDao().getById(sessionId) ?: return null
        val records = appDb.dtcRecordDao().forSession(sessionId)
        val sensors = appDb.sessionSensorDao().forSession(sessionId)
        val maker = inferMakerFromVin(session.identifiedVin)
        val stored = ArrayList<DtcEntryInfo>()
        val pending = ArrayList<DtcEntryInfo>()
        val permanent = ArrayList<DtcEntryInfo>()

        for (rec in records) {
            val enriched = enrich(
                listOf(com.obd2.app.core.obd.DtcEntry(code = rec.code, mode = rec.mode, ecuId = rec.ecuId)),
                rec.mode,
                maker,
                rec.rawResponse,
            ).first().let { info ->
                info.copy(
                    ru = info.ru ?: rec.descriptionRu,
                    en = info.en ?: rec.descriptionEn,
                    scopeLabel = info.scopeLabel ?: rec.scope,
                    translationStatus = info.translationStatus ?: rec.translationStatus,
                )
            }
            when (rec.mode) {
                DtcModes.STORED -> stored.add(enriched)
                DtcModes.PENDING -> pending.add(enriched)
                DtcModes.PERMANENT -> permanent.add(enriched)
                else -> stored.add(enriched)
            }
        }
        return SavedSessionDetails(
            session = session,
            stored = stored,
            pending = pending,
            permanent = permanent,
            sensors = sensors,
        )
    }

    suspend fun deleteSavedSession(sessionId: Long) {
        if (sessionId == currentSessionId) return
        appDb.deleteSessionCompletely(sessionId)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun readVin(masked: Boolean): String? {
        if (obd == null) return null
        tryAutoIdentifyVehicleInSession()
        val vin = _vinIdentificationResult.value?.vin
        if (vin != null) {
            _sessionVehicle.value = _sessionVehicle.value.copy(
                source = VehicleIdentitySource.AUTO_VIN,
                sourceLabelRu = VehicleIdentitySource.labelRu(VehicleIdentitySource.AUTO_VIN),
            )
            persistTelemetryMetaNow()
        }
        return vin
    }

    // ---------------------------------------------------------------------
    // Live polling & ECU / Adapter liveness checking
    // ---------------------------------------------------------------------

    suspend fun publishLiveReading(pid: Int, result: PidResult.Ok) {
        _liveData.value = _liveData.value + (pid to result)
        _pipelineStatus.value = _pipelineStatus.value.copy(
            ecuResponding = true,
            dataReceived = true,
            lastNote = null,
            lastResponseAtMs = System.currentTimeMillis(),
        )
        ingestTelemetryStreams()
    }

    /** Reads the Home quick-dashboard PIDs once (+ ATRV fallback) and autosaves immediately. */
    suspend fun refreshQuickSnapshot() {
        val o = obd ?: return
        val e = elm ?: return
        val defs = ensureRegistryLoaded().filter { it.mode == 1 && it.pid in HOME_QUICK_PIDS }
        val knownSupported = _supportedPids.value.orEmpty()
        var failures = 0
        for (def in defs) {
            if (!isConnectedNow) break
            if (def.pid in _unsupportedPids.value) continue
            when (val r = o.readPid(def, timeoutMs = (settingsCache.cmdTimeoutSec * 1000).toLong())) {
                is PidResult.Ok -> {
                    failures = 0
                    publishLiveReading(def.pid, r)
                }
                is PidResult.Unsupported -> {
                    if (def.pid !in knownSupported) {
                        _unsupportedPids.value = _unsupportedPids.value + def.pid
                        _liveData.value = _liveData.value - def.pid
                    } else {
                        failures++
                    }
                }
                is PidResult.Error -> {
                    failures++
                }
            }
        }
        if (0x42 !in _liveData.value && isConnectedNow) {
            e.refreshProtocolAndVoltage()
        }
        persistLiveSensorsNow(force = true)
        if (failures >= 2) {
            verifyEcuAndAdapterLiveness()
        }
    }

    /**
     * Serialized liveness check called ONLY after consecutive read failures
     * (never runs in parallel with normal ELM327 commands).
     */
    private suspend fun verifyEcuAndAdapterLiveness(): Boolean {
        val dev = _pipelineStatus.value.adapterName ?: (transport?.name ?: "OBD2")
        val e = elm
        if (!isConnectedNow || e == null) {
            handleFatalConnectionLoss(isDemo, dev, "Соединение с Bluetooth-адаптером разорвано")
            return false
        }
        if (e.consecutiveTimeouts.value >= 2) {
            // Confirm whether ELM327 itself is unreachable (walked away from vehicle)
            val ping = e.command("ATI", timeoutMs = 1500, allowRetries = false)
            if (ping is ElmResponse.Timeout || !isConnectedNow) {
                handleFatalConnectionLoss(
                    isDemo,
                    dev,
                    "Потеряна связь с адаптером (нет ответа от ELM327)",
                )
                return false
            }
        }
        // Adapter answered, now check if ECU is still awake (0100)
        val probe = e.command("0100", timeoutMs = 2200, allowRetries = false)
        val ecuAlive = probe is ElmResponse.Ok && probe.lines.any { it.replace(" ", "").uppercase().contains("4100") }
        if (!ecuAlive) {
            val note = "Связь с ЭБУ потеряна (двигатель заглушен или выключено зажигание)"
            log.log(RawLog.Dir.INFO, "[LIVENESS] $note")
            _pipelineStatus.value = _pipelineStatus.value.copy(
                ecuResponding = false,
                dataReceived = false,
                lastNote = note,
            )
            _connection.value = ConnectionState.Connected(
                demo = isDemo,
                deviceName = dev,
                details = "Адаптер на связи; ЭБУ перестал отвечать",
            )
            persistLiveSensorsNow(force = true)
            return false
        }
        return true
    }

    suspend fun startPolling(pids: List<Int>) = pollingMutex.withLock {
        pollingJob?.cancel()
        if (pids.isEmpty()) {
            _isPolling.value = false
            activePollingPids = emptyList()
            return@withLock
        }
        activePollingPids = pids
        _isPolling.value = true
        val interval = settingsCache.refreshMs.toLong()
        val registrySnapshot = ensureRegistryLoaded()
        val defs = registrySnapshot.filter { it.mode == 1 && pids.contains(it.pid) }
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var consecutiveFailures = 0
            try {
                while (isActive) {
                    if (!isConnectedNow) {
                        val dev = _pipelineStatus.value.adapterName ?: "OBD2"
                        handleFatalConnectionLoss(isDemo, dev, "Соединение с адаптером потеряно")
                        break
                    }
                    val t0 = System.currentTimeMillis()
                    val knownSupported = _supportedPids.value.orEmpty()
                    for (def in defs) {
                        if (!isActive || !isConnectedNow) break
                        if (def.pid in _unsupportedPids.value) continue
                        val o = obd ?: break
                        when (val r = o.readPid(def, timeoutMs = (settingsCache.cmdTimeoutSec * 1000).toLong())) {
                            is PidResult.Ok -> {
                                consecutiveFailures = 0
                                publishLiveReading(def.pid, r)
                                persistLiveSensorsNow(force = false)
                            }
                            is PidResult.Unsupported -> {
                                if (def.pid !in knownSupported) {
                                    _unsupportedPids.value = _unsupportedPids.value + def.pid
                                    _liveData.value = _liveData.value - def.pid
                                } else {
                                    consecutiveFailures++
                                }
                            }
                            is PidResult.Error -> {
                                consecutiveFailures++
                            }
                        }
                        if (consecutiveFailures >= 2) {
                            val stillAlive = verifyEcuAndAdapterLiveness()
                            if (!stillAlive) {
                                return@launch
                            }
                            consecutiveFailures = 0
                        }
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    delay(maxOf(0, interval - elapsed))
                }
            } finally {
                if (pollingJob === job) {
                    _isPolling.value = false
                }
                scope.launch { persistLiveSensorsNow(force = true) }
            }
        }
        pollingJob = job
        job.start()
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        activePollingPids = emptyList()
        _isPolling.value = false
    }

    suspend fun saveExtendedSessionArtifacts(
        sessionId: Long,
        freezeFrameJson: String?,
        readinessJson: String?,
        discoveredEcusJson: String?,
    ) {
        if (sessionId <= 0) return
        runCatching {
            appDb.sessionDao().updateSessionExtendedArtifacts(
                id = sessionId,
                updatedAtMs = System.currentTimeMillis(),
                freezeFrameJson = freezeFrameJson,
                readinessJson = readinessJson,
                discoveredEcusJson = discoveredEcusJson,
            )
        }
    }

    suspend fun runQuickDiagnostics(): QuickDiagnosticsResult {
        _quickDiagRunning.value = true
        _quickDiagProgress.value = "Запуск быстрой диагностики…"
        return try {
            val res = QuickDiagnosticsRunner(this).run { step -> _quickDiagProgress.value = step }
            if (res.incomplete && !isConnectedNow) {
                val failReason = (transport?.state?.value as? TransportState.Failed)?.reason
                    ?: "Потеря связи во время быстрой диагностики"
                closeActiveSessionInDb(status = SessionStatus.CONNECTION_LOST, reason = failReason)
            }
            _quickDiagResult.value = res
            res
        } finally {
            _quickDiagRunning.value = false
            _quickDiagProgress.value = null
        }
    }

    suspend fun runExtendedDiagnostics(): ExtendedDiagnosticsReport {
        _extendedDiagRunning.value = true
        _extendedDiagProgress.value = "Запуск расширенной диагностики…"
        return try {
            val res = ExtendedDiagnosticsRunner(this, catalogManager).run { step -> _extendedDiagProgress.value = step }
            _extendedDiagReport.value = res
            res
        } finally {
            _extendedDiagRunning.value = false
            _extendedDiagProgress.value = null
        }
    }

    suspend fun clearDtcsGuarded(precheck: ClearDtcPrecheck): ClearDtcResult {
        val o = obd ?: return ClearDtcResult.Blocked(listOf("Нет активного подключения к адаптеру/ЭБУ."))
        val res = o.clearDtcsGuarded(precheck)
        if (res is ClearDtcResult.DryRunSimulated) {
            log.log(RawLog.Dir.INFO, "[MODE 04 DRY-RUN] ${res.summary}")
        } else if (res is ClearDtcResult.SentOk) {
            log.log(RawLog.Dir.INFO, "[MODE 04] Выполнено по явному подтверждению")
        }
        return res
    }

    fun rollbackCatalog(): CatalogStatus {
        val st = catalogManager.rollback()
        _catalogStatus.value = st
        return st
    }

    suspend fun ingestTelemetryStreams() {
        val live = _liveData.value
        val unsup = _unsupportedPids.value
        val reg = _pidRegistry.value
        chartBuffer.ingestLiveSnapshot(live, unsup, reg)
        _chartTick.value = System.currentTimeMillis()
        tripRecorder.ingestSnapshot(live, unsup, reg)
    }
}
