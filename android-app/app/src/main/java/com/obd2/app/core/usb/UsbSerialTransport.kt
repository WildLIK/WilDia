package com.obd2.app.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicBoolean

enum class UsbDriverKind(val labelRu: String) {
    FTDI("FTDI"),
    CH340("CH340 / CH341"),
    CP210X("CP210x"),
    PL2303("PL2303"),
    CDC_ACM("CDC ACM"),
    UNSUPPORTED("Неподдерживаемое USB-устройство"),
}

data class UsbDeviceDescriptor(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val vendorName: String,
    val productName: String,
    val driverKind: UsbDriverKind,
    val hasPermission: Boolean,
) {
    val isSupportedSerialAdapter: Boolean
        get() = driverKind != UsbDriverKind.UNSUPPORTED

    val vidPidHex: String
        get() = "%04X:%04X".format(vendorId, productId)

    val displayName: String
        get() = productName.ifBlank { "USB-UART ${driverKind.labelRu} ($vidPidHex)" }
}

interface UsbSerialPortHandle {
    fun open(baudRate: Int)
    fun read(dest: ByteArray, timeoutMs: Int): Int
    fun write(src: ByteArray, timeoutMs: Int)
    fun close()
}

interface UsbSerialBackend {
    fun listDevices(): List<UsbDeviceDescriptor>
    fun hasPermission(deviceId: Int): Boolean
    fun requestPermission(deviceId: Int, onResult: (Boolean) -> Unit = {})
    @Throws(IOException::class)
    fun openPort(deviceId: Int, baudRate: Int): UsbSerialPortHandle
}

object UsbBaudPresets {
    val autoProbeRates = listOf(38400, 115200, 9600, 57600, 230400, 500000)
}

class AndroidUsbSerialBackend(private val context: Context) : UsbSerialBackend {
    companion object {
        const val ACTION_USB_PERMISSION = "com.obd2.app.USB_PERMISSION"
    }

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private fun probeDriver(device: UsbDevice): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().probeDevice(device)

    private fun classifyDriver(driver: UsbSerialDriver?): UsbDriverKind = when (driver) {
        is FtdiSerialDriver -> UsbDriverKind.FTDI
        is Ch34xSerialDriver -> UsbDriverKind.CH340
        is Cp21xxSerialDriver -> UsbDriverKind.CP210X
        is ProlificSerialDriver -> UsbDriverKind.PL2303
        is CdcAcmSerialDriver -> UsbDriverKind.CDC_ACM
        else -> UsbDriverKind.UNSUPPORTED
    }

    override fun listDevices(): List<UsbDeviceDescriptor> {
        val mgr = usbManager ?: return emptyList()
        return mgr.deviceList.values.map { dev ->
            val driver = probeDriver(dev)
            val kind = classifyDriver(driver)
            UsbDeviceDescriptor(
                deviceId = dev.deviceId,
                vendorId = dev.vendorId,
                productId = dev.productId,
                vendorName = dev.manufacturerName.orEmpty(),
                productName = dev.productName.orEmpty(),
                driverKind = kind,
                hasPermission = mgr.hasPermission(dev),
            )
        }.sortedByDescending { it.isSupportedSerialAdapter }
    }

    override fun hasPermission(deviceId: Int): Boolean {
        val mgr = usbManager ?: return false
        val dev = mgr.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return false
        return mgr.hasPermission(dev)
    }

    override fun requestPermission(deviceId: Int, onResult: (Boolean) -> Unit) {
        val mgr = usbManager
        val dev = mgr?.deviceList?.values?.firstOrNull { it.deviceId == deviceId }
        if (mgr == null || dev == null) {
            onResult(false)
            return
        }
        if (mgr.hasPermission(dev)) {
            onResult(true)
            return
        }
        val appCtx = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    runCatching { appCtx.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    onResult(granted)
                }
            }
        }
        ContextCompat.registerReceiver(
            appCtx,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(appCtx, deviceId, Intent(ACTION_USB_PERMISSION), flags)
        mgr.requestPermission(dev, pi)
    }

    override fun openPort(deviceId: Int, baudRate: Int): UsbSerialPortHandle {
        val mgr = usbManager ?: throw IOException("UsbManager недоступен")
        val dev = mgr.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: throw IOException("USB-устройство отключено")
        if (!mgr.hasPermission(dev)) {
            throw SecurityException("Нет разрешения Android на доступ к USB-устройству")
        }
        val driver = probeDriver(dev)
            ?: throw IOException("Устройство не является поддерживаемым USB-UART адаптером (FTDI/CH340/CP210x/PL2303/CDC)")
        val port = driver.ports.firstOrNull()
            ?: throw IOException("У USB-драйвера отсутствуют последовательные порты")
        val conn: UsbDeviceConnection = mgr.openDevice(dev)
            ?: throw IOException("Не удалось открыть USB-соединение с устройством")

        return try {
            port.open(conn)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            AndroidPortHandle(port, conn)
        } catch (e: Exception) {
            runCatching { port.close() }
            runCatching { conn.close() }
            throw if (e is IOException) e else IOException(e.message ?: "Ошибка открытия USB-порта", e)
        }
    }

    private class AndroidPortHandle(
        private val port: UsbSerialPort,
        private val conn: UsbDeviceConnection,
    ) : UsbSerialPortHandle {
        override fun open(baudRate: Int) {
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }

        override fun read(dest: ByteArray, timeoutMs: Int): Int = port.read(dest, timeoutMs)

        override fun write(src: ByteArray, timeoutMs: Int) {
            port.write(src, timeoutMs)
        }

        override fun close() {
            runCatching { port.close() }
            runCatching { conn.close() }
        }
    }
}

/**
 * USB Serial OTG transport for wired ELM327 adapters (FTDI, CH340, CP210x, PL2303, CDC ACM).
 * Handles Android permission checks, auto/manual baud rate detection, and physical cable detach.
 */
class UsbSerialTransport(
    private val context: Context,
    private val device: UsbDeviceDescriptor,
    private val manualBaudRate: Int? = null,
    private val backend: UsbSerialBackend = AndroidUsbSerialBackend(context),
    private val log: RawLog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ByteTransport {

    override val name: String = "USB (${device.displayName})"
    override val lastWriteUuid: String = "usb://${device.vidPidHex}"

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incoming: SharedFlow<ByteArray> = _incoming

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val userClosed = AtomicBoolean(false)

    @Volatile private var portHandle: UsbSerialPortHandle? = null
    @Volatile private var activeBaud: Int? = null
    private var readerJob: Job? = null
    private var detachReceiver: BroadcastReceiver? = null

    override fun isConnected(): Boolean =
        _state.value is TransportState.Connected && portHandle != null

    override suspend fun connect() = connectMutex.withLock {
        userClosed.set(false)
        _state.value = TransportState.Connecting

        if (!device.isSupportedSerialAdapter) {
            fail("Устройство ${device.vidPidHex} (${device.productName}) не является поддерживаемым USB-UART адаптером ELM327.")
            return
        }
        if (!backend.hasPermission(device.deviceId)) {
            fail("Нет разрешения Android на доступ к USB-устройству ${device.displayName}. Выдайте разрешение и повторите.")
            return
        }

        val ratesToTry = manualBaudRate?.let { listOf(it) } ?: UsbBaudPresets.autoProbeRates
        var openedPort: UsbSerialPortHandle? = null
        var chosenBaud: Int? = null
        var lastErr: String? = null

        for (baud in ratesToTry) {
            if (userClosed.get()) return
            var handle: UsbSerialPortHandle? = null
            try {
                handle = withContext(Dispatchers.IO) {
                    backend.openPort(device.deviceId, baud)
                }
                if (manualBaudRate != null) {
                    openedPort = handle
                    chosenBaud = baud
                    break
                }
                // Auto-baud: send ATI\r and check for ELM/OK/> response
                val responded = withContext(Dispatchers.IO) {
                    probeAtBaud(handle)
                }
                if (responded) {
                    openedPort = handle
                    chosenBaud = baud
                    break
                } else {
                    handle.close()
                }
            } catch (se: SecurityException) {
                runCatching { handle?.close() }
                fail("Нет разрешения USB: ${se.message}")
                return
            } catch (e: Exception) {
                runCatching { handle?.close() }
                lastErr = e.message ?: e::class.java.simpleName
            }
        }

        if (openedPort == null || chosenBaud == null) {
            fail(
                "Не удалось инициализировать ELM327 по USB (${device.driverKind.labelRu}, ${device.vidPidHex}): " +
                    (lastErr ?: "адаптер не ответил ни на одной скорости (${ratesToTry.joinToString()})"),
            )
            return
        }

        portHandle = openedPort
        activeBaud = chosenBaud
        registerDetachReceiver()
        val details = "USB ${device.driverKind.labelRu} · $chosenBaud bps (${device.vidPidHex})"
        _state.value = TransportState.Connected(details)
        log.log(RawLog.Dir.INFO, "[USB] connected: $details")
        startReader(openedPort)
    }

    private fun probeAtBaud(handle: UsbSerialPortHandle): Boolean {
        val buf = ByteArray(256)
        // Drain any leftover noise
        runCatching { handle.read(buf, 80) }
        handle.write("ATI\r".toByteArray(Charsets.US_ASCII), 400)
        val deadline = System.currentTimeMillis() + 650L
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val n = runCatching { handle.read(buf, 150) }.getOrDefault(0)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                val text = sb.toString().uppercase()
                if (text.contains("ELM") || text.contains("OBD") || text.contains("OK") || text.contains(">")) {
                    return true
                }
            }
        }
        return false
    }

    private fun startReader(handle: UsbSerialPortHandle) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(512)
            try {
                while (isActive && !userClosed.get()) {
                    val n = handle.read(buf, 250)
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        log.log(RawLog.Dir.RX, String(copy, Charsets.US_ASCII))
                        _incoming.emit(copy)
                    }
                }
            } catch (e: Exception) {
                if (!userClosed.get() && isActive) {
                    fail("USB OTG соединение потеряно (кабель отключён?): ${e.message}")
                }
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock {
            val handle = portHandle ?: throw IOException("USB-порт не подключён")
            withContext(Dispatchers.IO) {
                handle.write(data, 1000)
            }
            log.log(RawLog.Dir.TX, String(data, Charsets.US_ASCII))
        }
    }

    override suspend fun disconnect() {
        userClosed.set(true)
        readerJob?.cancel()
        readerJob = null
        unregisterDetachReceiver()
        closePortQuietly()
        _state.value = TransportState.Disconnected
        log.log(RawLog.Dir.INFO, "[USB] disconnected")
    }

    private fun registerDetachReceiver() {
        unregisterDetachReceiver()
        val appCtx = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    val detached = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (detached?.deviceId == device.deviceId) {
                        scope.launch { fail("USB-кабель физически отключён") }
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appCtx,
                r,
                IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            detachReceiver = r
        }
    }

    private fun unregisterDetachReceiver() {
        detachReceiver?.let { r ->
            runCatching { context.applicationContext.unregisterReceiver(r) }
        }
        detachReceiver = null
    }

    private fun closePortQuietly() {
        val h = portHandle
        portHandle = null
        activeBaud = null
        runCatching { h?.close() }
    }

    private fun fail(reason: String) {
        unregisterDetachReceiver()
        closePortQuietly()
        log.log(RawLog.Dir.ERROR, "[USB] $reason")
        _state.value = TransportState.Failed(reason)
    }
}
