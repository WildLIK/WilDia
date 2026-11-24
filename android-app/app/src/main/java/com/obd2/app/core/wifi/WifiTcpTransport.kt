package com.obd2.app.core.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

data class WifiObdEndpoint(
    val host: String,
    val port: Int,
    val label: String = "$host:$port",
    val verifiedElm: Boolean = false,
)

data class WifiNetworkStatus(
    val wifiNetworkAvailable: Boolean,
    val ssid: String? = null,
    val gatewayIp: String? = null,
    val hasInternet: Boolean = false,
)

object WifiObdPresets {
    val common: List<WifiObdEndpoint> = listOf(
        WifiObdEndpoint("192.168.0.10", 35000, "192.168.0.10:35000 (ELM327 / Kingbolen)"),
        WifiObdEndpoint("192.168.0.10", 23, "192.168.0.10:23 (Telnet ELM327)"),
        WifiObdEndpoint("192.168.0.11", 35000, "192.168.0.11:35000"),
        WifiObdEndpoint("192.168.4.1", 35000, "192.168.4.1:35000 (ESP AP)"),
        WifiObdEndpoint("192.168.1.10", 35000, "192.168.1.10:35000"),
        WifiObdEndpoint("10.0.0.10", 35000, "10.0.0.10:35000"),
    )
}

/**
 * Locates the active Wi-Fi [Network] even when Android keeps mobile cellular data active
 * because the OBD2 Wi-Fi AP has no Internet (`NET_CAPABILITY_INTERNET` is NOT required).
 */
object WifiNetworkBinder {
    @Suppress("DEPRECATION")
    fun findWifiNetwork(context: Context): Network? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun inspectStatus(context: Context): WifiNetworkStatus = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = findWifiNetwork(context)
        val caps = net?.let { cm?.getNetworkCapabilities(it) }
        val linkProps = net?.let { cm?.getLinkProperties(it) }
        val gw = linkProps?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val rawSsid = runCatching { wm?.connectionInfo?.ssid }.getOrNull()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        WifiNetworkStatus(
            wifiNetworkAvailable = net != null,
            ssid = rawSsid,
            gatewayIp = gw,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        )
    }.getOrElse { WifiNetworkStatus(wifiNetworkAvailable = false) }

    fun createBoundSocket(context: Context, timeoutMs: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMs
        val wifiNet = findWifiNetwork(context)
        if (wifiNet != null) {
            runCatching { wifiNet.bindSocket(socket) }
        }
        return socket
    }
}

/**
 * Non-aggressive local Wi-Fi ELM327 probe: tests only the active gateway IP (ports 35000, 23)
 * plus documented common ELM327 presets (~8 endpoints max), never scanning whole subnets.
 */
object WifiEndpointProber {
    suspend fun probeReachable(
        context: Context,
        connectTimeoutMs: Int = 700,
        customHost: String? = null,
        customPort: Int? = null,
    ): List<WifiObdEndpoint> = withContext(Dispatchers.IO) {
        val status = WifiNetworkBinder.inspectStatus(context)
        val candidates = LinkedHashMap<String, WifiObdEndpoint>()

        if (!customHost.isNullOrBlank() && customPort != null && customPort in 1..65535) {
            val h = customHost.trim()
            candidates["$h:$customPort"] = WifiObdEndpoint(h, customPort, "$h:$customPort (вручную)")
        }
        status.gatewayIp?.let { gw ->
            candidates.putIfAbsent("$gw:35000", WifiObdEndpoint(gw, 35000, "$gw:35000 (шлюз Wi-Fi)"))
            candidates.putIfAbsent("$gw:23", WifiObdEndpoint(gw, 23, "$gw:23 (шлюз Telnet)"))
        }
        for (preset in WifiObdPresets.common) {
            candidates.putIfAbsent("${preset.host}:${preset.port}", preset)
        }

        val reachable = mutableListOf<WifiObdEndpoint>()
        for (ep in candidates.values) {
            var sock: Socket? = null
            try {
                sock = WifiNetworkBinder.createBoundSocket(context, connectTimeoutMs)
                sock.connect(InetSocketAddress(ep.host, ep.port), connectTimeoutMs)
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                out.write("ATI\r".toByteArray(Charsets.US_ASCII))
                out.flush()
                val buf = ByteArray(256)
                val n = runCatching { inp.read(buf) }.getOrDefault(0)
                val text = if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
                val isElm = text.contains("ELM", ignoreCase = true) ||
                    text.contains("OBD", ignoreCase = true) ||
                    text.contains(">")
                reachable.add(ep.copy(verifiedElm = isElm))
            } catch (_: Exception) {
                // Skip unreachable endpoint
            } finally {
                runCatching { sock?.close() }
            }
        }
        reachable
    }
}

/**
 * Wi-Fi TCP socket transport for ELM327 adapters (e.g. Kingbolen OBD SCAN Wi-Fi v1.5).
 * Feeds the shared [com.obd2.app.core.elm327.Elm327Client] via [ByteTransport].
 */
class WifiTcpTransport(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val log: RawLog,
    private val reconnectAttempts: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Int = 5000,
    private val socketFactory: ((Context, Int) -> Socket)? = null,
) : ByteTransport {

    override val name: String = "Wi-Fi ($host:$port)"
    override val lastWriteUuid: String = "tcp://$host:$port"

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val userClosed = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    private var readerJob: Job? = null

    override fun isConnected(): Boolean =
        _state.value is TransportState.Connected && socket?.isClosed == false && output != null

    override suspend fun connect() {
        userClosed.set(false)
        reconnecting.set(false)
        connectInternal(isReconnect = false)
    }

    private suspend fun connectInternal(isReconnect: Boolean) = connectMutex.withLock {
        if (host.isBlank() || port !in 1..65535) {
            fail("Некорректный адрес Wi-Fi адаптера ($host:$port)")
            return
        }
        if (!isReconnect) {
            _state.value = TransportState.Connecting
        }
        val netStatus = WifiNetworkBinder.inspectStatus(context)
        log.log(
            RawLog.Dir.INFO,
            "[WIFI] connect $host:$port (wifiNet=${netStatus.wifiNetworkAvailable}, ssid=${netStatus.ssid ?: "?"}, hasInternet=${netStatus.hasInternet})",
        )

        val opened = try {
            withContext(Dispatchers.IO) {
                val s = socketFactory?.invoke(context, connectTimeoutMs)
                    ?: WifiNetworkBinder.createBoundSocket(context, connectTimeoutMs)
                s.soTimeout = 0 // non-timeout blocking read in reader loop until close/EOF
                s.connect(InetSocketAddress(host.trim(), port), connectTimeoutMs)
                s
            }
        } catch (e: Exception) {
            val hint = if (!netStatus.wifiNetworkAvailable) {
                " Подключите устройство к Wi-Fi сети OBD2-адаптера."
            } else {
                " Проверьте IP/порт ($host:$port) и питание адаптера."
            }
            fail("Wi-Fi TCP ошибка подключения к $host:$port: ${e.message ?: e::class.java.simpleName}.$hint")
            return
        }

        socket = opened
        input = opened.getInputStream()
        output = opened.getOutputStream()
        val ssidLabel = netStatus.ssid?.let { "SSID: $it · " } ?: ""
        val details = "Wi-Fi TCP ($ssidLabel$host:$port)"
        _state.value = TransportState.Connected(details)
        log.log(RawLog.Dir.INFO, "[WIFI] connected: $details")
        startReader(opened, input!!)
    }

    private fun startReader(sock: Socket, inp: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            try {
                while (isActive && !userClosed.get() && !sock.isClosed) {
                    val n = inp.read(buf)
                    if (n < 0) throw IOException("Wi-Fi TCP EOF")
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        log.log(RawLog.Dir.RX, String(copy, Charsets.US_ASCII))
                        _incoming.emit(copy)
                    }
                }
            } catch (e: Exception) {
                if (!userClosed.get() && isActive) {
                    log.log(RawLog.Dir.ERROR, "[WIFI] read error: ${e.message}")
                    closeQuietly()
                    handleUnexpectedDrop(e.message ?: "Разрыв Wi-Fi TCP соединения")
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
                log.log(RawLog.Dir.INFO, "[WIFI] reconnect attempt $attempt/$reconnectAttempts")
                delay(1000L * attempt)
                connectInternal(isReconnect = true)
                if (isConnected()) return
            }
            fail("Переподключение Wi-Fi TCP не удалось после $reconnectAttempts попыток ($reason)")
        } finally {
            reconnecting.set(false)
        }
    }

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock {
            val out = output ?: throw IOException("Wi-Fi TCP не подключён")
            withContext(Dispatchers.IO) {
                out.write(data)
                out.flush()
            }
            log.log(RawLog.Dir.TX, String(data, Charsets.US_ASCII))
        }
    }

    override suspend fun disconnect() {
        userClosed.set(true)
        readerJob?.cancel()
        readerJob = null
        closeQuietly()
        _state.value = TransportState.Disconnected
        log.log(RawLog.Dir.INFO, "[WIFI] disconnected")
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun fail(reason: String) {
        closeQuietly()
        log.log(RawLog.Dir.ERROR, "[WIFI] $reason")
        _state.value = TransportState.Failed(reason)
    }
}
