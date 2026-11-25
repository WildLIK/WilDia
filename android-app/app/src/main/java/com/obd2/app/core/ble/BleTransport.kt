package com.obd2.app.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE GATT transport for ELM327 adapters (Vgate iCar Pro BLE 4.0 "IOS-Vlink"
 * and other LE adapters).
 *
 * Connect sequence (no bonding/PIN is ever requested for BLE):
 *   connectGatt(TRANSPORT_LE) → STATE_CONNECTED → requestMtu(target)
 *   → onMtuChanged → discoverServices → pick write/notify chars by properties
 *   → CCCD subscribe → Connected. A 2 s fallback timer proceeds to service
 *   discovery even if the stack never calls onMtuChanged.
 *
 * Every step is written to the diagnostic log ("[BLE]" lines land in the
 * exported ZIP); see [bleReport] for the permission/GATT summary.
 */
@SuppressLint("MissingPermission") // permission gating is explicit below and logged
class BleTransport(
    private val context: Context,
    private val mac: String,
    private val deviceName: String,
    private val log: RawLog,
    private val targetMtu: Int = 247,
    private val reconnectAttempts: Int = 3,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val diag: com.obd2.app.core.diag.DiagSession? = null,
) : ByteTransport {

    override val name: String get() = "BLE $deviceName ($mac)"

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val userDisconnected = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var mtuJob: kotlinx.coroutines.Job? = null
    private var discoverFallbackJob: kotlinx.coroutines.Job? = null
    private var writeWorkerJob: kotlinx.coroutines.Job? = null
    private val connectOutcomes = Channel<Boolean>(Channel.CONFLATED)
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var writeWorkerStarted = false
    private var lastSnapshot: GattSnapshot? = null
    private val discoverRequested = AtomicBoolean(false)
    private var connectRequestedAt: Long = 0
    private var discoveryStartedAt: Long = 0
    private var subscribeStartedAt: Long = 0

    /** State-transition history for the diagnostic report. */
    private val stateHistory = ArrayDeque<String>()

    @Volatile var mtu: Int = 20
        private set

    @Volatile var lastError: String? = null
        private set

    val gattInfo: GattSnapshot? get() = lastSnapshot

    private fun trackState(s: TransportState) {
        stateHistory.addLast("${System.currentTimeMillis()} ${s::class.java.simpleName} ${s.detailsIfAny()}")
        while (stateHistory.size > 60) stateHistory.removeFirst()
        _state.value = s
        if (s is TransportState.Failed) {
            connectOutcomes.trySend(false)
        }
    }

    private fun TransportState.detailsIfAny(): String = when (this) {
        is TransportState.Connected -> details
        is TransportState.Failed -> reason
        is TransportState.Reconnecting -> "attempt=$attempt"
        else -> ""
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log.log(
                RawLog.Dir.INFO,
                "[BLE] onConnectionStateChange: newState=$newState " +
                    "(1=connecting,2=connected,0=disconnected) status=${GattInspector.gattStatusText(status)}",
            )
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {}
                8, 19, 22 -> lastError = "Соединение потеряно (${GattInspector.gattStatusText(status)})"
                133 -> lastError = "Ошибка BLE (status=133)"
                else -> lastError = "GATT status=${GattInspector.gattStatusText(status)}"
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    diag?.event(
                        com.obd2.app.core.diag.DiagCategory.GATT,
                        "gatt.stateConnected",
                        resultCode = GattInspector.gattStatusText(status),
                        prevState = "CONNECTING", newState = "CONNECTED",
                        durationMs = if (connectRequestedAt > 0) System.currentTimeMillis() - connectRequestedAt else null,
                    )
                    // Fresh physical connection: MTU must be (re)negotiated for
                    // every GATT instance — Android resets it to 23 on reconnect.
                    discoverRequested.set(false)
                    mtuJob?.cancel()
                    mtuJob = scope.launch {
                        delay(600)
                        if (!safePermissions()) {
                            log.log(RawLog.Dir.ERROR, "[BLE] CONNECT permission lost mid-connect")
                            diag?.event(com.obd2.app.core.diag.DiagCategory.PERM, "gatt.permissionLost",
                                details = "BLUETOOTH_CONNECT отсутствует при GATT-операции",
                                severity = com.obd2.app.core.diag.DiagSeverity.ERROR)
                            return@launch
                        }
                        diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.requestMtu",
                            details = "target=$targetMtu (проверка разрешения выполнена перед вызовом)")
                        runCatching { g.requestMtu(targetMtu) }
                            .onFailure { ex ->
                                log.log(RawLog.Dir.ERROR, "[BLE] requestMtu threw (${ex::class.java.simpleName}, SDK=${Build.VERSION.SDK_INT}): ${ex.message}")
                            }
                    }
                    // Fallback: proceed even if onMtuChanged never arrives.
                    discoverFallbackJob?.cancel()
                    discoverFallbackJob = scope.launch {
                        delay(2000)
                        if (discoverRequested.compareAndSet(false, true)) {
                            log.log(RawLog.Dir.INFO, "[BLE] MTU callback missing — fallback to discoverServices")
                            discoveryStartedAt = System.currentTimeMillis()
                            runCatching { g.discoverServices() }
                                .onFailure { ex ->
                                    lastError = "discoverServices threw: ${ex.message}"
                                    log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
                                    trackState(TransportState.Failed(lastError!!))
                                }
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    diag?.event(
                        com.obd2.app.core.diag.DiagCategory.GATT,
                        "gatt.stateDisconnected",
                        resultCode = GattInspector.gattStatusText(status),
                        prevState = "CONNECTED/CONNECTING", newState = "DISCONNECTED",
                        details = if (userDisconnected.get()) "инициировано пользователем" else (lastError ?: "без деталей"),
                        severity = if (userDisconnected.get()) com.obd2.app.core.diag.DiagSeverity.INFO
                                   else com.obd2.app.core.diag.DiagSeverity.WARN,
                    )
                    if (!userDisconnected.get()) {
                        diag?.disconnected(GattInspector.gattStatusText(status) + ": " + (lastError ?: "без деталей"))
                    }
                    mtuJob?.cancel()
                    mtuJob = null
                    discoverFallbackJob?.cancel()
                    discoverFallbackJob = null
                    stopWriteWorker()
                    drainQueuesOnDisconnect()
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    writeChar = null
                    notifyChar = null
                    connectOutcomes.trySend(false)
                    if (userDisconnected.get()) {
                        trackState(TransportState.Disconnected)
                    } else if (!reconnecting.get()) {
                        attemptReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log.log(RawLog.Dir.INFO, "[BLE] onMtuChanged: mtu=$mtu status=${GattInspector.gattStatusText(status)}")
            diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.onMtuChanged",
                resultCode = GattInspector.gattStatusText(status), newState = "mtu=$mtu",
                severity = if (status == BluetoothGatt.GATT_SUCCESS) com.obd2.app.core.diag.DiagSeverity.INFO
                           else com.obd2.app.core.diag.DiagSeverity.WARN)
            this@BleTransport.mtu = mtu
            // Proceed to service discovery regardless of negotiation outcome.
            if (discoverRequested.compareAndSet(false, true)) {
                discoveryStartedAt = System.currentTimeMillis()
                runCatching { g.discoverServices() }
                    .onFailure { log.log(RawLog.Dir.ERROR, "[BLE] discoverServices threw: ${it.message}") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log.log(RawLog.Dir.INFO, "[BLE] onServicesDiscovered: status=${GattInspector.gattStatusText(status)}")
            diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.onServicesDiscovered",
                resultCode = GattInspector.gattStatusText(status),
                durationMs = if (discoveryStartedAt > 0) System.currentTimeMillis() - discoveryStartedAt else null,
                severity = if (status == BluetoothGatt.GATT_SUCCESS) com.obd2.app.core.diag.DiagSeverity.INFO
                           else com.obd2.app.core.diag.DiagSeverity.ERROR)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError = "Обнаружение сервисов не удалось (status=${GattInspector.gattStatusText(status)})"
                trackState(TransportState.Failed(lastError!!))
                return
            }
            val snap = GattInspector.dump(g, mtu)
            // Full tree goes to the diagnostic log — this is what confirms the
            // real IOS-Vlink UUID family on the device (no blind hardcoding).
            log.log(RawLog.Dir.INFO, "[BLE] GATT tree:\n" + snap.toLogText())
            diag?.gattTreeJson = snap.toJson()
            lastSnapshot = snap.copy(selectedWriteUuid = null, selectedNotifyUuid = null)
            val pair = GattInspector.pickCharacteristics(g)
            if (pair == null) {
                lastError = "Не найдены характеристики записи/уведомлений (сервисов: ${snap.services.size})"
                log.log(RawLog.Dir.ERROR, "[BLE] char pick failed: $lastError")
                trackState(TransportState.Failed(lastError!!))
                return
            }
            val (w, n) = pair
            writeChar = w
            notifyChar = n
            lastSnapshot = snap.copy(
                selectedWriteUuid = w.uuid.toString(),
                selectedNotifyUuid = n.uuid.toString(),
            )
            val pickReason = buildString {
                append("write=${w.uuid}: свойства [${GattPropertyNames.decode(w.properties)}]")
                if (GattInspector.isHintMatch(w.uuid.toString(), GattInspector.WRITE_HINTS)) {
                    append("; совпадение с подсказкой-семейством UUID")
                }
                append("; notify=${n.uuid}: свойства [${GattPropertyNames.decode(n.properties)}], CCCD есть")
                append("; выбор по фактическим свойствам; наличие UUID не доказывает работоспособность соединения")
            }
            log.log(
                RawLog.Dir.INFO,
                "[BLE] chars picked: write=${w.uuid} props=[${GattPropertyNames.decode(w.properties)}], " +
                    "notify=${n.uuid} props=[${GattPropertyNames.decode(n.properties)}]",
            )
            diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.charsPicked",
                details = pickReason, newState = "SUBSCRIBING")
            if (!safePermissions()) {
                lastError = realPermissionError()
                log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
                trackState(TransportState.Failed(lastError!!))
                return
            }
            // Subscribe to notifications via CCCD
            val localNotify = n
            val notifSet = try {
                g.setCharacteristicNotification(localNotify, true)
            } catch (se: SecurityException) {
                lastError = "SecurityException при подписке на уведомления (SDK ${Build.VERSION.SDK_INT}): ${se.message}"
                log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
                trackState(TransportState.Failed(lastError!!))
                return
            }
            if (!notifSet) {
                lastError = "Не удалось включить уведомления (setCharacteristicNotification=false)"
                log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
                trackState(TransportState.Failed(lastError!!))
                return
            }
            val cccd = localNotify.getDescriptor(UUID.fromString(GattInspector.cccdUuid()))
            subscribeStartedAt = System.currentTimeMillis()
            if (cccd != null) {
                val value =
                    if (localNotify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                        localNotify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                    ) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: the deprecated setValue/writeDescriptor pair is
                    // unreliable — pass the value through the new overload (Int status).
                    runCatching { g.writeDescriptor(cccd, value) }
                        .map { it == BluetoothGatt.GATT_SUCCESS }
                        .getOrDefault(false)
                } else {
                    @Suppress("DEPRECATION")
                    runCatching {
                        cccd.setValue(value)
                        g.writeDescriptor(cccd)
                    }.getOrDefault(false)
                }
                if (!submitted) {
                    lastError = "Не удалось подписаться (CCCD write не отправлен)"
                    log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
                    diag?.let { d -> d.stats.notifyErrors += 1 }
                    diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.cccdSubmitFail",
                        details = lastError, severity = com.obd2.app.core.diag.DiagSeverity.ERROR)
                    trackState(TransportState.Failed(lastError!!))
                    connectOutcomes.trySend(false)
                } else {
                    log.log(RawLog.Dir.INFO, "[BLE] CCCD write submitted (${cccd.uuid})")
                }
            } else {
                // No CCCD: treat as connected anyway (some clones still deliver).
                log.log(RawLog.Dir.INFO, "[BLE] no CCCD on notify char — proceeding without subscription")
                trackState(TransportState.Connected("$deviceName ($mac), MTU=$mtu"))
                connectOutcomes.trySend(true)
                startWriteWorker()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log.log(
                RawLog.Dir.INFO,
                "[BLE] onDescriptorWrite: ${d.uuid} status=${GattInspector.gattStatusText(status)}",
            )
            if (status == BluetoothGatt.GATT_SUCCESS &&
                d.uuid.toString().lowercase() == GattInspector.cccdUuid()
            ) {
                log.log(RawLog.Dir.INFO, "[BLE] CCCD subscribed — notifications active")
                val subMs = if (subscribeStartedAt > 0) System.currentTimeMillis() - subscribeStartedAt else null
                val totalMs = if (connectRequestedAt > 0) System.currentTimeMillis() - connectRequestedAt else null
                diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.cccdSubscribed",
                    resultCode = "GATT_SUCCESS", durationMs = subMs,
                    newState = "NOTIFICATIONS_ACTIVE", details = "полное время подключения=$totalMs мс")
                subMs?.let { ms -> diag?.let { d -> d.stats.subscribeDurationsMs.add(ms) } }
                diag?.connectSucceeded(totalMs ?: 0, mtu, readRssiOnce(g))
                trackState(TransportState.Connected("$deviceName ($mac), MTU=$mtu"))
                connectOutcomes.trySend(true)
                startWriteWorker()
            } else if (status != BluetoothGatt.GATT_SUCCESS &&
                d.uuid.toString().lowercase() == GattInspector.cccdUuid()
            ) {
                lastError = "Подписка на уведомления не принята (CCCD status=${GattInspector.gattStatusText(status)})"
                log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
                diag?.let { d -> d.stats.notifyErrors += 1 }
                diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.cccdRejected",
                    resultCode = GattInspector.gattStatusText(status),
                    details = lastError, severity = com.obd2.app.core.diag.DiagSeverity.ERROR)
                trackState(TransportState.Failed(lastError!!))
                connectOutcomes.trySend(false)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log.log(
                RawLog.Dir.INFO,
                "[BLE] onCharacteristicWrite: ${c.uuid} status=${GattInspector.gattStatusText(status)}",
            )
            if (status != BluetoothGatt.GATT_SUCCESS) diag?.let { d -> d.stats.writeErrors += 1 }
            writeAck.trySend(status)
        }

        // API < 33 delivery path
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = c.value
            if (bytes != null && bytes.isNotEmpty()) {
                log.log(RawLog.Dir.INFO, "[BLE] notify chunk ${bytes.size}B (legacy)")
                _incoming.tryEmit(bytes)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingRssi = rssi
                diag?.let { d -> d.stats.rssi = rssi }
                diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.rssi", details = "rssi=$rssi")
            }
        }

        // API 33+ delivery path
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isNotEmpty()) {
                log.log(RawLog.Dir.INFO, "[BLE] notify chunk ${value.size}B")
                _incoming.tryEmit(value)
            }
        }
    }

    private val writeAck = Channel<Int>(Channel.UNLIMITED)

    @Volatile private var pendingRssi: Int? = null

    /** Best-effort RSSI read right after subscription (public API, optional). */
    private fun readRssiOnce(g: BluetoothGatt): Int? {
        pendingRssi = null
        if (safePermissions()) runCatching { g.readRemoteRssi() }
        // the value arrives via onReadRemoteRssi shortly after; report what we have
        return pendingRssi
    }

    private fun safePermissions(): Boolean = GattInspector.hasConnectPermission(context)

    private fun realPermissionError(): String {
        val scanner = BleScanner(context, log)
        val missing = scanner.missingPermissions()
        val state = scanner.permissionStateText()
        return if (missing.isEmpty()) {
            "Система отклонила GATT-операцию; разрешения формально выданы ($state)"
        } else {
            "Нет разрешений: ${missing.joinToString()} ($state)"
        }
    }

    private fun drainQueuesOnDisconnect() {
        while (writeQueue.tryReceive().isSuccess) { /* discard stale queued commands */ }
        writeAck.trySend(-1)
    }

    private fun attemptReconnect() {
        if (userDisconnected.get() || reconnectAttempts <= 0) {
            if (userDisconnected.get()) trackState(TransportState.Disconnected)
            else trackState(TransportState.Failed(lastError ?: "Соединение потеряно"))
            return
        }
        if (!reconnecting.compareAndSet(false, true)) {
            log.log(RawLog.Dir.INFO, "[BLE] reconnect already in progress — skipping duplicate trigger")
            return
        }
        reconnectJob = scope.launch {
            try {
                for (attempt in 1..reconnectAttempts) {
                    if (userDisconnected.get()) return@launch
                    trackState(TransportState.Reconnecting(attempt))
                    log.log(RawLog.Dir.INFO, "[BLE] reconnect attempt $attempt/$reconnectAttempts")
                    delay(1000L * attempt)
                    if (userDisconnected.get()) return@launch
                    if (connectInternal()) return@launch
                }
                if (!userDisconnected.get()) {
                    trackState(TransportState.Failed(lastError ?: "Не удалось переподключиться"))
                }
            } finally {
                reconnecting.set(false)
            }
        }
    }

    /** Blocking-ish internal connect used by connect() and reconnects. */
    private suspend fun connectInternal(): Boolean {
        // NOTE: the permission guard MUST NOT depend on a live gatt instance —
        // at first connect there is none. The old null-gatt guard produced the
        // false «Нет разрешения BLUETOOTH_CONNECT» on every first attempt.
        if (!safePermissions()) {
            lastError = realPermissionError()
            log.log(RawLog.Dir.ERROR, "[BLE] connectInternal: $lastError")
            return false
        }
        log.log(RawLog.Dir.INFO, "[BLE] connectInternal: permissions ok (${BleScanner(context, log).permissionStateText()})")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val enabled = try {
            adapter != null && adapter.isEnabled
        } catch (se: SecurityException) {
            lastError = "Нет системного разрешения Bluetooth при проверке адаптера (SDK ${Build.VERSION.SDK_INT}): ${se.message}"
            log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
            return false
        }
        if (adapter == null || !enabled) {
            lastError = "Bluetooth выключен"
            log.log(RawLog.Dir.ERROR, "[BLE] connectInternal: adapter off")
            return false
        }
        val bondedNote = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && adapter.bondedDevices.isNotEmpty()) {
                "bonded=${adapter.bondedDevices.joinToString(",") { it.address }}"
            } else ""
        }.getOrDefault("")
        if (bondedNote.isNotEmpty()) log.log(RawLog.Dir.INFO, "[BLE] $bondedNote (bonding is NOT required for BLE)")
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            lastError = "Некорректный MAC или ошибка getRemoteDevice: ${e.message}"
            log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
            return false
        }
        connectRequestedAt = System.currentTimeMillis()
        return suspendCancellableConnect(device)
    }

    private suspend fun suspendCancellableConnect(device: BluetoothDevice): Boolean {
        runCatching { gatt?.close() }
        gatt = null
        while (connectOutcomes.tryReceive().isSuccess) { /* clear previous signal */ }
        log.log(RawLog.Dir.INFO, "[BLE] connectGatt(TRANSPORT_LE, autoConnect=false) to $mac")
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (se: SecurityException) {
            lastError = "SecurityException в connectGatt (SDK ${Build.VERSION.SDK_INT}): ${se.message}"
            log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
            return false
        } catch (e: Exception) {
            lastError = "connectGatt threw: ${e.message}"
            log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
            return false
        }
        gatt = g
        if (g == null) {
            lastError = "connectGatt вернул null"
            log.log(RawLog.Dir.ERROR, "[BLE] $lastError")
            return false
        }
        // Wait for Connected state (CCCD subscription included) or failure/disconnect signal.
        val ok = kotlinx.coroutines.withTimeoutOrNull(10000) {
            connectOutcomes.receive()
        }
        if (ok == null) {
            lastError = "Таймаут подключения (10 с) без ответа стека"
            log.log(RawLog.Dir.ERROR, "[BLE] $lastError — closing stale GATT")
            diag?.event(com.obd2.app.core.diag.DiagCategory.GATT, "gatt.connectTimeout",
                resultCode = "TIMEOUT", durationMs = 10000,
                details = "стек не вернул колбэк за 10 с", severity = com.obd2.app.core.diag.DiagSeverity.ERROR)
            runCatching { g.close() }
            if (gatt === g) gatt = null
            return false
        }
        return ok
    }

    override suspend fun connect() {
        userDisconnected.set(false)
        reconnecting.set(false)
        reconnectJob?.cancel()
        trackState(TransportState.Connecting)
        log.log(RawLog.Dir.INFO, "[BLE] connect() requested for $deviceName ($mac)")
        if (!connectInternal()) {
            trackState(TransportState.Failed(lastError ?: "Подключение не удалось"))
            diag?.connectFailed(lastError ?: "Подключение не удалось", lastError)
        }
    }

    override suspend fun disconnect() {
        userDisconnected.set(true)
        reconnecting.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        mtuJob?.cancel()
        mtuJob = null
        discoverFallbackJob?.cancel()
        discoverFallbackJob = null
        stopWriteWorker()
        drainQueuesOnDisconnect()
        connectOutcomes.trySend(false)
        log.log(RawLog.Dir.INFO, "[BLE] disconnect() requested by user")
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        notifyChar = null
        trackState(TransportState.Disconnected)
    }

    private fun stopWriteWorker() {
        writeWorkerStarted = false
        writeWorkerJob?.cancel()
        writeWorkerJob = null
    }

    private fun startWriteWorker() {
        if (writeWorkerStarted && writeWorkerJob?.isActive == true) return
        writeWorkerStarted = true
        writeWorkerJob?.cancel()
        writeWorkerJob = scope.launch {
            try {
                for (data in writeQueue) {
                val g = gatt ?: continue
                val ch = writeChar ?: continue
                if (!safePermissions()) {
                    log.log(RawLog.Dir.ERROR, "[BLE] write skipped: permission lost")
                    continue
                }
                if (GattInspector.isWriteWithResponse(ch.properties)) {
                    // With-response write: wait for the ack before the next command
                    val submitted = kotlinx.coroutines.withTimeoutOrNull(3000) {
                        var ok = false
                        while (!ok) {
                            ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching {
                                    g.writeCharacteristic(ch, data, GattInspector.writeTypeFor(ch))
                                }.getOrDefault(0x101) == BluetoothGatt.GATT_SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                runCatching {
                                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    ch.value = data
                                    g.writeCharacteristic(ch)
                                }.getOrDefault(false)
                            }
                            if (!ok) delay(10)
                        }
                        true
                    }
                    if (submitted == null) {
                        log.log(RawLog.Dir.ERROR, "[BLE] write submit busy/timeout (${data.size}B)")
                        continue
                    }
                    val ack = kotlinx.coroutines.withTimeoutOrNull(4000) { writeAck.receive() }
                    if (ack != null && ack != BluetoothGatt.GATT_SUCCESS) {
                        log.log(RawLog.Dir.ERROR, "[BLE] write ack status=${GattInspector.gattStatusText(ack)}")
                    }
                } else {
                    // writeNoResponse: fire and forget
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching {
                            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        }.onFailure { log.log(RawLog.Dir.ERROR, "[BLE] write threw: ${it.message}") }
                    } else {
                        @Suppress("DEPRECATION")
                        runCatching {
                            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            ch.value = data
                            g.writeCharacteristic(ch)
                        }.onFailure { log.log(RawLog.Dir.ERROR, "[BLE] write threw: ${it.message}") }
                    }
                }
                }
            } finally {
                writeWorkerStarted = false
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        log.log(RawLog.Dir.INFO, "[BLE] tx queue +${data.size}B")
        writeQueue.send(data)
    }

    override fun isConnected(): Boolean =
        _state.value is TransportState.Connected

    override val lastWriteUuid: String?
        get() = writeChar?.uuid?.toString()

    /**
     * Diagnostic summary for the exported ZIP (permissions, GATT tree,
     * selected characteristics, state history, last error).
     */
    fun bleReport(): String = buildString {
        appendLine("# BLE-диагностика")
        appendLine()
        appendLine("## Разрешения (фактические на момент отчёта)")
        appendLine("- ${BleScanner(context, log).permissionStateText()}")
        appendLine("- AndroidManifest объявляет BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN (neverForLocation), BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION (maxSdk=30), ACCESS_COARSE_LOCATION (maxSdk=30)")
        appendLine("- Сопряжение/PIN для BLE не требуется и не запрашивается приложением")
        appendLine()
        appendLine("## Устройство")
        appendLine("- name=$deviceName mac=$mac")
        appendLine("- negotiated MTU=$mtu (target=$targetMtu)")
        appendLine()
        appendLine("## GATT-дерево (обнаружено на устройстве)")
        lastSnapshot?.toLogText()?.lines()?.forEach { appendLine(it) }
            ?: appendLine("(GATT не обнаружен — подключение не состоялось)")
        appendLine()
        appendLine("## Последняя ошибка")
        appendLine(lastError ?: "(нет)")
        appendLine()
        appendLine("## История состояний")
        stateHistory.forEach { appendLine("- $it") }
        if (stateHistory.isEmpty()) appendLine("(пусто)")
        appendLine()
        appendLine("## События [BLE] из журнала обмена")
        log.snapshot()
            .filter { it.text.contains("[BLE]") }
            .takeLast(200)
            .forEach { e ->
                appendLine("- ${e.timestampMs} [${e.dir}] ${e.text.replace("\n", " | ")}")
            }
        if (log.snapshot().none { it.text.contains("[BLE]") }) appendLine("(нет событий)")
    }
}
