package com.obd2.app.core.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.obd2.app.core.diag.DiagCategory
import com.obd2.app.core.diag.DiagSeverity
import com.obd2.app.core.diag.DiagSession
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth Classic RFCOMM / SPP (UUID 00001101-0000-1000-8000-00805F9B34FB) transport.
 *
 * Verifies device type & SDP profile before opening RFCOMM, then tries
 * Insecure -> Secure -> reflection RFCOMM channel 1, cancels discovery first,
 * and supports clean reconnects.
 */
@SuppressLint("MissingPermission")
class ClassicBluetoothTransport(
    private val context: Context,
    private val mac: String,
    private val deviceName: String,
    private val log: RawLog,
    private val reconnectAttempts: Int = 3,
    private val diag: DiagSession? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectorOverride: ClassicSocketConnector? = null,
    private val allowReflectionFallback: Boolean = false,
    private val connectTimeoutMs: Long = 12_000L,
) : ByteTransport {

    companion object {
        fun defaultAttemptMethods(allowReflection: Boolean = false): List<RfcommAttemptMethod> =
            if (allowReflection) {
                listOf(RfcommAttemptMethod.SECURE_SDP, RfcommAttemptMethod.INSECURE_SDP, RfcommAttemptMethod.REFLECTION_CH1)
            } else {
                listOf(RfcommAttemptMethod.SECURE_SDP, RfcommAttemptMethod.INSECURE_SDP)
            }
    }

    interface ClassicSocketStream {
        val input: InputStream
        val output: OutputStream
        val methodLabel: String
        fun close()
    }

    enum class RfcommAttemptMethod(val label: String) {
        INSECURE_SDP("Insecure RFCOMM (SPP UUID)"),
        SECURE_SDP("Secure RFCOMM (SPP UUID)"),
        REFLECTION_CH1("Reflection RFCOMM ch=1"),
    }

    interface ClassicSocketConnector {
        suspend fun probeProfile(mac: String): ClassicBluetoothScanner.SdpProbeResult
        fun cancelDiscovery()
        @Throws(IOException::class)
        fun connect(mac: String, method: RfcommAttemptMethod): ClassicSocketStream
    }

    override val name: String = "Classic ($deviceName)"
    override val lastWriteUuid: String = TransportResolver.SPP_UUID_STRING

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val userClosed = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)

    @Volatile private var activeStream: ClassicSocketStream? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null

    private val scanner by lazy { ClassicBluetoothScanner(context, log) }
    private val connector: ClassicSocketConnector by lazy {
        connectorOverride ?: AndroidClassicSocketConnector(context, scanner, log)
    }

    override fun isConnected(): Boolean =
        _state.value is TransportState.Connected && activeStream != null

    override suspend fun connect() {
        userClosed.set(false)
        reconnecting.set(false)
        connectInternal(isReconnect = false)
    }

    private suspend fun connectInternal(isReconnect: Boolean) = connectMutex.withLock {
        if (!isReconnect) {
            _state.value = TransportState.Connecting
        }
        diag?.connectAttemptStarted("$deviceName ($mac) [Classic SPP]")
        log.log(RawLog.Dir.INFO, "[CLASSIC] connect($mac, name=$deviceName)")

        connector.cancelDiscovery()
        delay(250)
        val probe = connector.probeProfile(mac)
        probe.error?.let { err ->
            fail(err)
            return
        }
        val methods = defaultAttemptMethods(allowReflectionFallback)
        diag?.event(
            DiagCategory.GATT,
            "classic.preflight",
            resultCode = probe.status.name,
            details = "api=${android.os.Build.VERSION.SDK_INT}, type=${TransportResolver.deviceTypeLabel(probe.deviceType)}, " +
                "bond=${probe.bondState}, methods=${methods.joinToString { it.name }}, uuids=${probe.uuids}",
        )

        if (probe.deviceType == TransportResolver.DEVICE_TYPE_LE) {
            fail("Устройство $deviceName поддерживает только BLE (DEVICE_TYPE_LE); Classic SPP недоступен")
            return
        }
        if (probe.status == SppProfileStatus.ABSENT) {
            fail("Профиль SPP (00001101) отсутствует в SDP устройства (${probe.uuids.joinToString()})")
            return
        }
        if (probe.status == SppProfileStatus.UNCONFIRMED) {
            log.log(RawLog.Dir.INFO, "[CLASSIC] SDP пуст — выполняем контрольную попытку RFCOMM SPP для $mac")
        }

        val errors = ArrayList<String>()
        var opened: ClassicSocketStream? = null
        for ((idx, method) in methods.withIndex()) {
            if (userClosed.get()) return
            if (idx > 0) delay(150)
            try {
                val stream = withTimeout(connectTimeoutMs) {
                    withContext(Dispatchers.IO) {
                        connector.connect(mac, method)
                    }
                }
                opened = stream
                break
            } catch (se: SecurityException) {
                fail("Нет разрешения Bluetooth для RFCOMM: ${se.message}")
                return
            } catch (e: Exception) {
                val msg = "${method.label}: ${e.message ?: e::class.java.simpleName}"
                errors.add(msg)
                log.log(RawLog.Dir.INFO, "[CLASSIC] attempt failed — $msg")
                diag?.event(DiagCategory.GATT, "classic.rfcomm.fail", details = msg, severity = DiagSeverity.WARN)
            }
        }

        if (opened == null) {
            val joined = errors.joinToString("; ")
            val isSocketReadTimeout = joined.contains("read ret: -1", ignoreCase = true) ||
                joined.contains("socket might closed", ignoreCase = true)
            val hint = when {
                probe.bondState == BluetoothDevice.BOND_NONE ->
                    " Возможная причина: требуется сопряжение (PIN 1234/0000) в настройках Bluetooth."
                isSocketReadTimeout ->
                    " Адаптер отклонил RFCOMM-сокет (read ret: -1). Проверьте, не занят ли адаптер другим телефоном/приложением, или используйте BLE (IOS-Vlink)."
                else -> ""
            }
            fail("RFCOMM/SPP подключение не удалось: $joined$hint")
            return
        }

        activeStream = opened
        val details = "Classic SPP (${opened.methodLabel}; ${probe.status.labelRu})"
        _state.value = TransportState.Connected(details)
        diag?.stats?.let { it.successes++ }
        diag?.event(DiagCategory.GATT, "classic.connected", resultCode = opened.methodLabel, details = details)
        log.log(RawLog.Dir.INFO, "[CLASSIC] connected: $details")

        startReader(opened)
    }

    private fun startReader(stream: ClassicSocketStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            try {
                while (isActive && !userClosed.get()) {
                    val n = stream.input.read(buf)
                    if (n < 0) {
                        throw IOException("RFCOMM stream EOF")
                    }
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        log.log(RawLog.Dir.RX, String(copy, Charsets.US_ASCII))
                        _incoming.emit(copy)
                    }
                }
            } catch (e: Exception) {
                if (!userClosed.get() && isActive) {
                    log.log(RawLog.Dir.ERROR, "[CLASSIC] read error: ${e.message}")
                    closeStreamQuietly()
                    val reason = e.message ?: "Разрыв RFCOMM-соединения"
                    reconnectJob = scope.launch { handleUnexpectedDrop(reason) }
                }
            }
        }
    }

    private suspend fun handleUnexpectedDrop(reason: String) {
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            if (reconnectAttempts <= 0 || userClosed.get()) {
                fail(reason)
                return
            }
            for (attempt in 1..reconnectAttempts) {
                if (userClosed.get()) return
                _state.value = TransportState.Reconnecting(attempt)
                log.log(RawLog.Dir.INFO, "[CLASSIC] reconnect attempt $attempt/$reconnectAttempts")
                delay(1000L * attempt)
                connectInternal(isReconnect = true)
                if (isConnected()) return
            }
            fail("Переподключение Classic SPP не удалось после $reconnectAttempts попыток ($reason)")
        } finally {
            reconnecting.set(false)
        }
    }

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock {
            val stream = activeStream ?: throw IOException("Classic SPP не подключён")
            withContext(Dispatchers.IO) {
                stream.output.write(data)
                stream.output.flush()
            }
            log.log(RawLog.Dir.TX, String(data, Charsets.US_ASCII))
        }
    }

    override suspend fun disconnect() {
        userClosed.set(true)
        reconnecting.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        readerJob?.cancel()
        readerJob = null
        closeStreamQuietly()
        _state.value = TransportState.Disconnected
        log.log(RawLog.Dir.INFO, "[CLASSIC] disconnected")
    }

    private fun closeStreamQuietly() {
        val s = activeStream
        activeStream = null
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }

    private fun fail(reason: String) {
        closeStreamQuietly()
        log.log(RawLog.Dir.ERROR, "[CLASSIC] $reason")
        diag?.connectFailed(reason, null)
        _state.value = TransportState.Failed(reason)
    }

    private class AndroidClassicSocketStream(
        private val socket: BluetoothSocket,
        override val methodLabel: String,
    ) : ClassicSocketStream {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override fun close() {
            socket.close()
        }
    }

    private class AndroidClassicSocketConnector(
        private val context: Context,
        private val scanner: ClassicBluetoothScanner,
        private val log: RawLog,
    ) : ClassicSocketConnector {
        private val adapter: BluetoothAdapter?
            get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        override suspend fun probeProfile(mac: String): ClassicBluetoothScanner.SdpProbeResult =
            scanner.probeSdpUuids(mac)

        override fun cancelDiscovery() {
            scanner.cancelDiscoveryIfActive()
        }

        override fun connect(mac: String, method: RfcommAttemptMethod): ClassicSocketStream {
            val a = adapter ?: throw IOException("Bluetooth-адаптер недоступен")
            val dev = a.getRemoteDevice(mac)
            val socket = when (method) {
                RfcommAttemptMethod.INSECURE_SDP ->
                    dev.createInsecureRfcommSocketToServiceRecord(TransportResolver.SPP_UUID)
                RfcommAttemptMethod.SECURE_SDP ->
                    dev.createRfcommSocketToServiceRecord(TransportResolver.SPP_UUID)
                RfcommAttemptMethod.REFLECTION_CH1 -> {
                    val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(dev, 1) as BluetoothSocket
                }
            }
            return try {
                cancelDiscovery()
                socket.connect()
                log.log(RawLog.Dir.INFO, "[CLASSIC] socket connected via ${method.label}")
                AndroidClassicSocketStream(socket, method.label)
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                throw if (e is IOException) e else IOException(e.message ?: "RFCOMM connect failed", e)
            }
        }
    }
}
