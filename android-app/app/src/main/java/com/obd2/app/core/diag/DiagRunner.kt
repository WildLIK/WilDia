package com.obd2.app.core.diag

import android.content.Context
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.ble.BleTransport
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.ClassicBluetoothScanner
import com.obd2.app.core.bt.ClassicBluetoothTransport
import com.obd2.app.core.bt.ClassicDeviceInfo
import com.obd2.app.core.bt.SppProfileStatus
import com.obd2.app.core.elm327.DiagExchangeRecorder
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sequential diagnostic scenario runner (§10). Stages run one after another;
 * no second BLE connection is started while one is active — if the app is
 * already connected, scenarios B/C/D reuse that connection instead of making
 * a parallel one.
 *
 * Stage failure keeps the results and skips only dependent stages; independent
 * safe checks continue (§10).
 */
class DiagRunner(
    private val context: Context,
    private val session: DiagSession,
    private val repo: DiagnosticRepository,
    private val log: RawLog,
    private val scanner: BleScanner,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Test/interop seam: when provided, connects through this factory instead of real BLE. */
    private val transportFactory: ((mac: String, name: String) -> com.obd2.app.core.transport.ByteTransport)? = null,
    private val classicScanner: ClassicBluetoothScanner? = null,
    private val classicTransportFactory: ((mac: String, name: String) -> com.obd2.app.core.transport.ByteTransport)? = null,
) {
    enum class Scenario(val id: String, val title: String) {
        A_SCAN("A", "Проверка сканирования"),
        B_CONNECT("B", "Проверка подключения (GATT)"),
        C_ELM("C", "Проверка ELM327"),
        D_FULL("D", "Полная диагностика"),
        E_PASSIVE("E", "Пассивное логирование"),
    }

    data class RunnerState(
        val running: Boolean = false,
        val scenarioId: String = "",
        val stages: List<DiagStageState> = emptyList(),
        val progressPercent: Int = 0,
        val currentOp: String = "",
        val conclusions: List<Conclusion> = emptyList(),
        val finishedMessage: String? = null,
        val cancelled: Boolean = false,
    )

    private val _state = MutableStateFlow(RunnerState())
    val state: StateFlow<RunnerState> = _state

    @Volatile private var cancelled = false
    @Volatile private var running = false

    fun cancel() {
        cancelled = true
    }

    private fun stagesFor(scenario: Scenario): List<DiagStageState> = when (scenario) {
        Scenario.A_SCAN -> listOf(
            DiagStageState("perm", "Разрешения"),
            DiagStageState("scan", "Сканирование и реклама BLE"),
        )
        Scenario.B_CONNECT -> listOf(
            DiagStageState("perm", "Разрешения"),
            DiagStageState("scan", "Поиск IOS-Vlink"),
            DiagStageState("connect", "GATT-подключение и подписка"),
        )
        Scenario.C_ELM -> listOf(
            DiagStageState("perm", "Разрешения"),
            DiagStageState("scan", "Поиск IOS-Vlink"),
            DiagStageState("connect", "GATT-подключение"),
            DiagStageState("elm", "Инициализация ELM327"),
        )
        Scenario.D_FULL -> listOf(
            DiagStageState("perm", "Разрешения"),
            DiagStageState("scan", "Поиск IOS-Vlink"),
            DiagStageState("connect", "GATT-подключение"),
            DiagStageState("elm", "Инициализация ELM327"),
            DiagStageState("obd", "Параметры Mode 01"),
            DiagStageState("dtc", "Коды ошибок Mode 03"),
            DiagStageState("stats", "Статистика и выводы"),
        )
        Scenario.E_PASSIVE -> listOf(
            DiagStageState("perm", "Разрешения (состояние)"),
            DiagStageState("observe", "Наблюдение 30 с без команд"),
        )
    }

    private fun updateState(transform: (RunnerState) -> RunnerState) {
        val s = _state.value
        val percent = if (s.stages.isEmpty()) 0
        else (s.stages.count { it.state != StageState.PENDING && it.state != StageState.RUNNING } * 100) / s.stages.size
        _state.value = transform(s).copy(progressPercent = percent)
    }

    private fun setStage(id: String, mutate: (DiagStageState) -> Unit) {
        updateState { st ->
            val stages = st.stages.map { if (it.id == id) { it.let(mutate); it } else it }
            st.copy(stages = stages, currentOp = stages.firstOrNull { it.state == StageState.RUNNING }?.currentOp ?: st.currentOp)
        }
    }

    private fun markSkippedDependents(stages: List<DiagStageState>, fromId: String, reason: String) {
        val idx = stages.indexOfFirst { it.id == fromId }
        stages.drop(idx + 1).forEach { st ->
            if (st.state == StageState.PENDING) {
                st.state = StageState.SKIP
                st.resultText = reason
            }
        }
    }

    suspend fun run(
        scenario: Scenario,
        targetMacIn: String? = null,
        targetNameIn: String? = null,
        scanMode: BtScanMode = BtScanMode.BLE,
    ) {
        if (running) return
        running = true
        cancelled = false
        var targetMac = targetMacIn
        var targetName = targetNameIn
        var targetTransport: BtTransportType? = null
        val stages = stagesFor(scenario)
        _state.value = RunnerState(running = true, scenarioId = scenario.id, stages = stages)
        session.startSession("${scenario.id} — ${scenario.title} [${scanMode.name}]")
        val analyzer = DiagAnalyzer()

        try {
            val phone = PhoneInfo.collect(context)
            session.event(DiagCategory.SYS, "phone.info",
                details = "${phone.manufacturer} ${phone.model}, Android ${phone.androidVersion}, " +
                    "app ${phone.appVersionName}, BT ${phone.bluetoothState}, LE=${phone.leAvailable}, " +
                    "perm=${phone.runtimePermissions}, foreground=${phone.appForeground}")

            // ---- PERM ----
            runStage("perm") { st ->
                st.currentOp = "Проверка runtime-разрешений и манифеста"
                val missing = scanner.missingPermissions()
                if (missing.isEmpty()) {
                    st.state = StageState.PASS
                    st.resultText = "Разрешения выданы: ${scanner.permissionStateText()}"
                } else {
                    st.state = StageState.FAIL
                    st.resultText = "Отсутствуют: ${missing.joinToString()}"
                    st.errors.add("Bluetooth-разрешения не выданы: ${missing.joinToString()}")
                    session.event(DiagCategory.PERM, "perm.missing", details = missing.joinToString(),
                        severity = DiagSeverity.ERROR)
                    markSkippedDependents(stages, "perm", "пропущено: доступ запрещён")
                    return@runStage
                }
                if (scenario != Scenario.E_PASSIVE && !scanner.isBluetoothEnabled()) {
                    st.state = StageState.FAIL
                    st.errors.add("Bluetooth выключен")
                    markSkippedDependents(stages, "perm", "пропущено: Bluetooth выключен")
                    return@runStage
                }
                st.warnings.addAll(analyzer.warningsFromPhone(phone))
            }

            if (cancelled) return finish("отменено пользователем")

            // ---- SCAN (кроме E) ----
            if (scenario != Scenario.E_PASSIVE) {
                runStage("scan") { st ->
                    val runBle = scanMode == BtScanMode.BLE || scanMode == BtScanMode.AUTO
                    val runClassic = scanMode == BtScanMode.CLASSIC || scanMode == BtScanMode.AUTO
                    var lastCount = 0
                    val classicFound = ArrayList<ClassicDeviceInfo>()

                    if (runBle) {
                        st.currentOp = "Сканирование BLE…"
                        session.scanStarted(if (scanMode == BtScanMode.AUTO) 8 else 12)
                        session.event(
                            DiagCategory.SCAN, "scan.settings",
                            details = "mode=${scanMode.name}; API=${ScanSessionInfo().api}",
                        )
                        val job = scope.launch {
                            runCatching {
                                scanner.scan(null, if (scanMode == BtScanMode.AUTO) 8 else 12).collect { d ->
                                    lastCount++
                                    session.scanSighting(
                                        ScanSighting(
                                            ts = d.ts, mac = d.mac, name = d.name, rssi = d.rssi,
                                            advServiceUuids = d.serviceUuids,
                                            manufacturerDataHex = d.manufacturerDataHex,
                                            serviceDataHex = d.serviceDataHex,
                                            advFlags = d.advFlags,
                                            isConnectable = d.connectable,
                                        ),
                                    )
                                    st.currentOp = "BLE: $lastCount устройств, RSSI=${d.rssi} dBm"
                                    setStage("scan") { it.currentOp = st.currentOp }
                                }
                            }
                        }
                        while (job.isActive && !cancelled) delay(150)
                        job.cancel()
                        delay(150)
                    }

                    if (runClassic && !cancelled && classicScanner != null) {
                        st.currentOp = "Сканирование Bluetooth Classic (SPP)…"
                        setStage("scan") { it.currentOp = st.currentOp }
                        classicScanner.bondedDevices().forEach { c ->
                            classicFound.add(c)
                            session.recordClassicDevice(
                                ClassicScanRecord(c.mac, c.name, c.rssi, c.deviceType, c.bondState, c.sdpUuids, c.sppStatus.name, c.source, c.ts),
                            )
                        }
                        val cJob = scope.launch {
                            runCatching {
                                classicScanner.discover(if (scanMode == BtScanMode.AUTO) 10 else 12).collect { c ->
                                    classicFound.add(c)
                                    session.recordClassicDevice(
                                        ClassicScanRecord(c.mac, c.name, c.rssi, c.deviceType, c.bondState, c.sdpUuids, c.sppStatus.name, c.source, c.ts),
                                    )
                                    st.currentOp = "Classic: найдено ${classicFound.distinctBy { it.mac }.size}"
                                    setStage("scan") { it.currentOp = st.currentOp }
                                }
                            }
                        }
                        while (cJob.isActive && !cancelled) delay(150)
                        cJob.cancel()
                        classicScanner.cancelDiscoveryIfActive()
                        delay(100)
                    }

                    val summaries = session.scanSummaries()
                    val vlink = summaries.filter { it.name.uppercase().contains("VLINK") }
                    val classicDistinct = session.classicSummaries()
                    val vlinkClassic = classicDistinct.filter {
                        it.name.uppercase().contains("VLINK") || it.name.uppercase().contains("VGATE")
                    }
                    session.event(
                        DiagCategory.SCAN, "scan.summary",
                        details = "mode=${scanMode.name}; ble=${summaries.size}; classic=${classicDistinct.size}; " +
                            "vlinkBle=${vlink.size}; vlinkClassic=${vlinkClassic.size}",
                    )
                    if (cancelled && lastCount == 0 && classicDistinct.isEmpty()) {
                        st.state = StageState.FAIL
                        st.resultText = "Отменено пользователем"
                        session.scanFinished("отменено пользователем")
                        return@runStage
                    }
                    when {
                        summaries.isEmpty() && classicDistinct.isEmpty() -> {
                            st.state = StageState.FAIL
                            st.resultText = "Устройства не обнаружены"
                            st.errors.add("Не получено ни одного устройства (${scanMode.labelRu})")
                            session.scanFinished("завершено без устройств")
                        }
                        vlink.isEmpty() && vlinkClassic.isEmpty() -> {
                            st.state = StageState.WARN
                            st.resultText = "Найдено BLE=${summaries.size}, Classic=${classicDistinct.size}, но Vlink не видно"
                            st.warnings.add("Vgate не найден: проверьте зажигание и режим ${scanMode.labelRu}")
                            session.scanFinished("завершено, Vlink не найден")
                        }
                        else -> {
                            st.state = StageState.PASS
                            val parts = buildList {
                                if (vlink.isNotEmpty()) add("BLE: " + vlink.joinToString { "${it.name} (RSSI ${it.rssiLast})" })
                                if (vlinkClassic.isNotEmpty()) add("Classic: " + vlinkClassic.joinToString { "${it.name} [${it.sppStatus}]" })
                            }
                            st.resultText = parts.joinToString("; ")
                            session.scanFinished("завершено, Vlink найден")
                        }
                    }
                    if (targetMac == null && scenario != Scenario.A_SCAN) {
                        val bestBle = if (scanMode != BtScanMode.CLASSIC) vlink.maxByOrNull { it.rssiLast } else null
                        val bestClassic = if (scanMode != BtScanMode.BLE) {
                            vlinkClassic.firstOrNull { it.sppStatus != SppProfileStatus.ABSENT.name }
                        } else null
                        when {
                            bestBle != null -> {
                                targetMac = bestBle.mac
                                targetName = bestBle.name
                                targetTransport = BtTransportType.BLE
                                st.warnings.add("Цель BLE выбрана автоматически: ${bestBle.name}")
                            }
                            bestClassic != null -> {
                                targetMac = bestClassic.mac
                                targetName = bestClassic.name
                                targetTransport = BtTransportType.CLASSIC_SPP
                                st.warnings.add("Цель Classic выбрана автоматически: ${bestClassic.name}")
                            }
                            summaries.isNotEmpty() && scanMode != BtScanMode.CLASSIC -> {
                                val first = summaries.maxByOrNull { it.count }!!
                                targetMac = first.mac
                                targetName = first.name
                                targetTransport = BtTransportType.BLE
                            }
                        }
                    } else if (targetMac != null && targetTransport == null) {
                        targetTransport = if (scanMode == BtScanMode.CLASSIC) BtTransportType.CLASSIC_SPP else BtTransportType.BLE
                    }
                }
            }

            if (cancelled) return finish("отменено пользователем")

            // ---- CONNECT (B/C/D) ----
            var activeTransport: com.obd2.app.core.transport.ByteTransport? = null
            var elm: Elm327Client? = null
            var obd: ObdClient? = null
            var ownConnection = false

            val stageIds = stages.map { it.id }
            if (scenario == Scenario.B_CONNECT || scenario == Scenario.C_ELM || scenario == Scenario.D_FULL) {
                runStage("connect") { st ->
                    // reuse the app's existing real connection when present
                    if (repo.isConnectedNow && !repo.isDemo) {
                        st.state = StageState.SKIP
                        st.resultText = "Приложение уже подключено к ${repo.transport?.name} — подключение переиспользуется (параллельные соединения запрещены)"
                        elm = repo.elm
                        obd = repo.obd
                        session.connectedName = repo.transport?.name
                        session.event(DiagCategory.GATT, "connect.reused",
                            details = "используется существующее подключение приложения")
                        return@runStage
                    }
                    if (repo.isConnectedNow && repo.isDemo) {
                        st.state = StageState.FAIL
                        st.errors.add("Активен демо-режим — результаты не являются проверкой реального адаптера")
                        markSkippedDependents(stages, "connect", "пропущено: демо-режим не является реальным адаптером")
                        return@runStage
                    }
                    val mac = targetMac
                    if (mac == null) {
                        st.state = StageState.FAIL
                        st.errors.add("Целевое устройство не выбрано (Vlink не найден)")
                        markSkippedDependents(stages, "connect", "пропущено: цель не найдена")
                        return@runStage
                    }
                    val useClassic = targetTransport == BtTransportType.CLASSIC_SPP
                    session.transportUsed = (targetTransport ?: BtTransportType.BLE).name
                    st.currentOp = if (useClassic) "SDP → RFCOMM SPP подключение…" else "connectGatt → MTU → сервисы → CCCD…"
                    session.connectAttemptStarted("$targetName ($mac)")
                    val transport = if (useClassic) {
                        classicTransportFactory?.invoke(mac, targetName ?: mac)
                            ?: ClassicBluetoothTransport(
                                context = context,
                                mac = mac,
                                deviceName = targetName ?: mac,
                                log = log,
                                reconnectAttempts = 0,
                                diag = session,
                                scope = scope,
                            )
                    } else {
                        transportFactory?.invoke(mac, targetName ?: mac)
                            ?: BleTransport(
                                context = context,
                                mac = mac,
                                deviceName = targetName ?: mac,
                                log = log,
                                targetMtu = repo.settingsCache.mtu,
                                reconnectAttempts = 0,
                                diag = session,
                            )
                    }
                    activeTransport = transport
                    ownConnection = true
                    elm = Elm327Client(transport, log, exchangeRecorder = DiagExchangeRecorder(session))
                    obd = ObdClient(elm!!)
                    val connectJob = scope.launch { runCatching { transport.connect() } }
                    var ok = false
                    val deadline = System.currentTimeMillis() + 25_000
                    while (System.currentTimeMillis() < deadline && !cancelled) {
                        when (transport.state.value) {
                            is TransportState.Connected -> { ok = true; break }
                            is TransportState.Failed -> break
                            else -> delay(120)
                        }
                    }
                    connectJob.cancel()
                    if (cancelled) {
                        runCatching { transport.disconnect() }
                        st.state = StageState.FAIL
                        st.resultText = "Отменено пользователем"
                        return@runStage
                    }
                    if (!ok) {
                        st.state = StageState.FAIL
                        val reason = (transport.state.value as? TransportState.Failed)?.reason
                            ?: (transport as? BleTransport)?.lastError
                            ?: "неизвестная причина"
                        st.resultText = reason
                        st.errors.add(
                            if (useClassic) "Classic SPP-подключение не удалось: $reason"
                            else "GATT-подключение не удалось: $reason",
                        )
                        if (session.stats.failures == 0) session.connectFailed(reason, null)
                        markSkippedDependents(stages, "connect", "пропущено: подключение не состоялось")
                        return@runStage
                    }
                    val mtuLabel = (transport as? BleTransport)?.mtu?.let { "MTU=$it, " } ?: ""
                    st.state = StageState.PASS
                    st.resultText = "Подключено (${if (useClassic) "Classic SPP" else "BLE"}): ${mtuLabel}RSSI=${session.stats.rssi ?: "н/д"}"
                    st.durationMs = session.stats.connectDurationsMs.lastOrNull()
                }
            }

            if (cancelled) {
                runCatching { activeTransport?.disconnect() }
                return finish("отменено пользователем")
            }

            // ---- ELM init (C/D) ----
            if ("elm" in stageIds) {
                runStage("elm") { st ->
                    val client = elm
                    if (client == null) {
                        st.state = StageState.SKIP
                        st.resultText = "нет подключения"
                        return@runStage
                    }
                    st.currentOp = "ATZ → ATE0 → ATL0 → ATS1 → ATH0 → ATI → ATSP0 → ATDP → ATDPN → ATRV"
                    if (ownConnection) {
                        // the transport is already connected; run the init sequence only
                        val err = client.initializeOnConnected()
                        if (err != null) {
                            st.state = StageState.FAIL
                            st.resultText = err
                            st.errors.add("Инициализация ELM327 не удалась: $err")
                            return@runStage
                        }
                    } else {
                        // reusing app connection: already initialized
                    }
                    val info = client.info.value
                    st.state = StageState.PASS
                    st.resultText = "ELM327: ${info.identity ?: "identity не получен"}; " +
                        "протокол: ${info.protocol ?: "не определён"}; напряжение: ${info.voltage ?: "н/д"}"
                    st.warnings.add("Команды стандартной инициализации; никаких дополнительных AT/CAN-запросов не отправлялось")
                }
            }

            if (cancelled) {
                runCatching { activeTransport?.disconnect() }
                return finish("отменено пользователем")
            }

            // ---- OBD params (D) ----
            if ("obd" in stageIds) {
                runStage("obd") { st ->
                    val o = obd
                    if (o == null || elm == null) {
                        st.state = StageState.SKIP
                        st.resultText = "нет адаптера"
                        return@runStage
                    }
                    st.currentOp = "Маски поддержки 0100/0120/0140/0160…"
                    val support = o.discoverSupportedPids()
                    session.supportedPids.clear()
                    session.supportedPids.addAll(support.supportedPids)
                    session.event(DiagCategory.OBD, "pids.support",
                        details = "поддерживается=${support.supportedPids.size}; ошибки масок=${support.maskErrors}")
                    if (support.supportedPids.isEmpty()) {
                        st.state = StageState.FAIL
                        st.resultText = "ЭБУ не сообщил ни одной поддерживаемой маски"
                        st.errors.addAll(support.maskErrors.ifEmpty { listOf("все маски без ответа") })
                        return@runStage
                    }
                    // read a safe, bounded subset of available standard PIDs
                    val wanted = listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x21, 0x2F, 0x31, 0x42, 0x5C)
                    val defs = repo.pidRegistry.value.filter { it.mode == 1 && it.pid in wanted && it.pid in support.supportedPids }
                    st.currentOp = "Чтение ${defs.size} доступных Mode-01 PID…"
                    var okCount = 0
                    for (def in defs) {
                        if (cancelled) break
                        setStage("obd") { it.currentOp = "Чтение ${def.command} (${def.displayName})…" }
                        val t0 = System.currentTimeMillis()
                        when (val r = o.readPid(def, timeoutMs = 3000)) {
                            is PidResult.Ok -> {
                                okCount++
                                session.obdRecord(
                                    ObdParamResult(
                                        pid = def.command, raw = r.reading.rawHex,
                                        decoded = r.reading.text ?: r.reading.value?.toString(),
                                        unit = r.reading.unit, durationMs = r.reading.durationMs,
                                        supportCheck = "маска подтвердила поддержку",
                                        validity = ObdValidity.OK,
                                    )
                                )
                            }
                            is PidResult.Unsupported -> session.obdRecord(
                                ObdParamResult(def.command, raw = "", decoded = null, unit = def.unit,
                                    durationMs = System.currentTimeMillis() - t0,
                                    supportCheck = r.reason, validity = ObdValidity.NOT_SUPPORTED)
                            )
                            is PidResult.Error -> session.obdRecord(
                                ObdParamResult(def.command, raw = r.raw, decoded = null, unit = def.unit,
                                    durationMs = System.currentTimeMillis() - t0,
                                    supportCheck = "маска подтвердила поддержку",
                                    validity = if (r.kind == "BAD_RESPONSE") ObdValidity.INVALID_RESPONSE else ObdValidity.READ_FAILED,
                                    error = "${r.kind}: ${r.message}")
                            )
                        }
                    }
                    val recs = session.obdResults
                    val notSupported = recs.count { it.validity == ObdValidity.NOT_SUPPORTED }
                    val failed = recs.count { it.validity == ObdValidity.READ_FAILED || it.validity == ObdValidity.INVALID_RESPONSE }
                    st.state = if (okCount > 0) StageState.PASS else StageState.FAIL
                    st.resultText = "прочитано $okCount из ${defs.size}; не поддерживается ЭБУ: $notSupported; ошибок чтения: $failed"
                    if (okCount == 0 && defs.isNotEmpty()) st.errors.add("Ни один PID не прочитан")
                }
            }

            if (cancelled) {
                runCatching { activeTransport?.disconnect() }
                return finish("отменено пользователем")
            }

            // ---- DTC Mode 03 (D) ----
            if ("dtc" in stageIds) {
                runStage("dtc") { st ->
                    val o = obd ?: run {
                        st.state = StageState.SKIP; st.resultText = "нет адаптера"; return@runStage
                    }
                    st.currentOp = "Чтение Mode 03 (сохранённые коды)…"
                    when (val r = o.readDtc(3, timeoutMs = 5000)) {
                        is ObdClient.DtcReadResult.Ok -> {
                            st.state = StageState.PASS
                            st.resultText = "сохранённых кодов: ${r.dtcs.size}"
                        }
                        is ObdClient.DtcReadResult.Unsupported -> {
                            st.state = StageState.WARN
                            st.resultText = r.reason
                            st.warnings.add("Mode 03: ${r.reason}")
                        }
                        is ObdClient.DtcReadResult.Error -> {
                            // Mode 03 может быть не поддержан — это WARN, не сбой
                            st.state = StageState.WARN
                            st.resultText = "${r.kind}: ${r.message}"
                            st.warnings.add("Mode 03: ${r.kind}: ${r.message} (может быть не поддержан ЭБУ/адаптером)")
                        }
                    }
                }
            }

            // ---- STATS (D) ----
            if ("stats" in stageIds) {
                runStage("stats") { st ->
                    st.currentOp = "Сводка задержек и выводов…"
                    val s = session.stats
                    st.state = StageState.PASS
                    st.resultText = "попыток=${s.attempts}, успешных=${s.successes}, разрывов=${s.disconnects}; " +
                        "подключение: ${s.minAvgMax(s.connectDurationsMs)}; обнаружение сервисов: ${s.minAvgMax(s.discoveryDurationsMs)}; " +
                        "MTU=${s.negotiatedMtu ?: "недоступно"}; RSSI=${s.rssi ?: "недоступно"}"
                    st.warnings.add("Потери пакетов через публичный Android API: недоступно")
                }
            }

            // ---- E PASSIVE ----
            if ("observe" in stageIds) {
                runStage("observe") { st ->
                    st.currentOp = "Наблюдение 30 с: только события сеанса, без команд автомобилю"
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < 30_000 && !cancelled) {
                        st.currentOp = "Наблюдение ${((System.currentTimeMillis() - start) / 1000)} / 30 с; " +
                            "состояние: ${repo.connection.value::class.java.simpleName}"
                        setStage("observe") { it.currentOp = st.currentOp }
                        delay(1000)
                    }
                    st.state = StageState.PASS
                    st.resultText = "Зарегистрированы события активного сеанса (см. timeline.json); " +
                        "пассивный перехват всего Bluetooth-трафика Android без HCI-журнала невозможен"
                    st.warnings.add("Режим не перехватывает системный BLE-трафик — только события приложения")
                }
            }

            val conclusions = analyzer.analyze(session, stages)
            updateState { it.copy(conclusions = conclusions) }
            finish(if (cancelled) "отменено пользователем" else "завершено")
        } catch (e: Exception) {
            session.event(DiagCategory.SYS, "runner.error", details = e.message ?: "?",
                severity = DiagSeverity.ERROR)
            finish("ошибка ранера: ${e.message}")
        } finally {
            session.endSession(if (cancelled) "сессия отменена" else "сессия завершена")
            running = false
        }
    }

    private suspend fun finish(msg: String) {
        updateState {
            it.copy(
                running = false,
                finishedMessage = msg,
                cancelled = cancelled,
            )
        }
        _state.value.finishedMessage?.let { session.event(DiagCategory.SYS, "runner.finish", details = it) }
    }

    private suspend fun runStage(id: String, block: suspend (DiagStageState) -> Unit) {
        val stage = _state.value.stages.firstOrNull { it.id == id } ?: return
        if (stage.state == StageState.SKIP) return
        stage.state = StageState.RUNNING
        stage.startedAt = System.currentTimeMillis()
        setStage(id) { it.state = StageState.RUNNING }
        block(stage)
        val started = stage.startedAt
        if (stage.durationMs == null && started != null) {
            stage.durationMs = System.currentTimeMillis() - started
        }
        if (stage.state == StageState.RUNNING) stage.state = StageState.PASS
        setStage(id) {
            it.state = stage.state
            it.resultText = stage.resultText
            it.durationMs = stage.durationMs
        }
    }
}
