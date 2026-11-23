package com.obd2.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.ble.BleDevice
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.bt.BtScanMode
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.bt.ClassicBluetoothScanner
import com.obd2.app.core.bt.ClassicBluetoothTransport
import com.obd2.app.core.bt.ClassicDeviceInfo
import com.obd2.app.core.bt.DiscoveredAdapter
import com.obd2.app.core.bt.SppProfileStatus
import com.obd2.app.core.bt.TransportResolver
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.diag.ClassicScanRecord
import com.obd2.app.core.diag.DiagExporter
import com.obd2.app.core.diag.DiagSession
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.ui.ObdViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Tests for Bluetooth Classic (SPP/RFCOMM), SDP verification, transport resolution,
 * sequential AUTO scanning (BLE -> Classic), reconnects, Android 8-16 permissions,
 * and diagnostic ZIP export.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClassicBluetoothTest {

    @Test
    fun `TransportResolver never infers SPP by name and respects SDP and device type`() {
        // 1. Named "Android-Vlink" but actually LE-only -> rejected for CLASSIC, uses BLE in AUTO
        val fakeLeNamedAndroid = DiscoveredAdapter(
            mac = "13:E0:2F:8D:56:CA",
            name = "Android-Vlink",
            rssi = -55,
            seenViaBle = true,
            bluetoothDeviceType = TransportResolver.DEVICE_TYPE_LE,
            sppStatus = SppProfileStatus.NOT_APPLICABLE,
        )
        assertNull(TransportResolver.resolve(fakeLeNamedAndroid, BtScanMode.CLASSIC).transport)
        assertEquals(BtTransportType.BLE, TransportResolver.resolve(fakeLeNamedAndroid, BtScanMode.AUTO).transport)

        // 2. Classic device with confirmed SPP UUID -> resolves to CLASSIC_SPP in AUTO and CLASSIC
        val realClassicSpp = DiscoveredAdapter(
            mac = "13:E0:2F:8D:56:CA",
            name = "Android-Vlink",
            rssi = -60,
            seenViaClassic = true,
            bluetoothDeviceType = TransportResolver.DEVICE_TYPE_CLASSIC,
            bonded = true,
            sdpUuids = listOf(TransportResolver.SPP_UUID_STRING),
            sppStatus = SppProfileStatus.CONFIRMED,
        )
        assertEquals(BtTransportType.CLASSIC_SPP, TransportResolver.resolve(realClassicSpp, BtScanMode.AUTO).transport)
        assertEquals(BtTransportType.CLASSIC_SPP, TransportResolver.resolve(realClassicSpp, BtScanMode.CLASSIC).transport)
        assertNull(TransportResolver.resolve(realClassicSpp, BtScanMode.BLE).transport)

        // 3. Classic device where SDP returned other UUIDs WITHOUT 00001101 -> ABSENT -> rejected
        val absentSpp = realClassicSpp.copy(
            sdpUuids = listOf("0000110a-0000-1000-8000-00805f9b34fb"),
            sppStatus = TransportResolver.classifySppFromUuids(
                listOf("0000110a-0000-1000-8000-00805f9b34fb"),
                TransportResolver.DEVICE_TYPE_CLASSIC,
            ),
        )
        assertEquals(SppProfileStatus.ABSENT, absentSpp.sppStatus)
        assertNull(TransportResolver.resolve(absentSpp, BtScanMode.AUTO).transport)
        assertNull(TransportResolver.resolve(absentSpp, BtScanMode.CLASSIC).transport)

        // 4. Dual-mode seen via both BLE & Classic -> AUTO prefers BLE (preserves IOS-Vlink), CLASSIC selects SPP
        val dualAdapter = DiscoveredAdapter(
            mac = "D2:E0:2F:8D:56:CA",
            name = "IOS-Vlink",
            rssi = -52,
            seenViaBle = true,
            seenViaClassic = true,
            bluetoothDeviceType = TransportResolver.DEVICE_TYPE_DUAL,
            sppStatus = SppProfileStatus.UNCONFIRMED,
        )
        assertEquals(BtTransportType.BLE, TransportResolver.resolve(dualAdapter, BtScanMode.AUTO).transport)
        assertEquals(BtTransportType.CLASSIC_SPP, TransportResolver.resolve(dualAdapter, BtScanMode.CLASSIC).transport)
    }

    @Test
    fun `AUTO scan runs BLE then Classic sequentially without overlap and surfaces both IOS-Vlink and Android-Vlink`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val bleActive = AtomicBoolean(false)
        val classicActive = AtomicBoolean(false)
        val overlapDetected = AtomicBoolean(false)
        val events = ArrayList<String>()

        val fakeBle = object : BleScanner(ctx, null) {
            override fun missingPermissions(): List<String> = emptyList()
            override fun scan(nameFilter: String?, scanSeconds: Int): Flow<BleDevice> = flow {
                if (classicActive.get()) overlapDetected.set(true)
                bleActive.set(true)
                events.add("ble_start")
                delay(40)
                emit(BleDevice(mac = "D2:E0:2F:8D:56:CA", name = "IOS-Vlink", rssi = -54))
                delay(40)
                events.add("ble_end")
                bleActive.set(false)
            }
        }
        val fakeClassic = object : ClassicBluetoothScanner(ctx, null) {
            override fun missingPermissions(): List<String> = emptyList()
            override fun hasConnectPermission(): Boolean = true
            override fun bondedDevices(): List<ClassicDeviceInfo> = emptyList()
            override fun cancelDiscoveryIfActive() {}
            override fun discover(scanSeconds: Int): Flow<ClassicDeviceInfo> = flow {
                if (bleActive.get()) overlapDetected.set(true)
                classicActive.set(true)
                events.add("classic_start")
                delay(40)
                emit(
                    ClassicDeviceInfo(
                        mac = "13:E0:2F:8D:56:CA",
                        name = "Android-Vlink",
                        rssi = -61,
                        deviceType = TransportResolver.DEVICE_TYPE_CLASSIC,
                        bondState = BluetoothDevice.BOND_BONDED,
                        deviceClass = 0x1F00,
                        sdpUuids = listOf(TransportResolver.SPP_UUID_STRING),
                        sppStatus = SppProfileStatus.CONFIRMED,
                        source = "discovery",
                    ),
                )
                events.add("classic_end")
                classicActive.set(false)
            }
        }

        val vm = ObdViewModel(ctx, fakeBle, fakeClassic)
        vm.startScan(BtScanMode.AUTO)

        val deadline = System.currentTimeMillis() + 5000
        while (vm.scanning.value && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(50))
            delay(10)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("BLE and Classic scans must never run concurrently", overlapDetected.get())
        assertEquals(listOf("ble_start", "ble_end", "classic_start", "classic_end"), events)

        val list = vm.adapters.value
        assertEquals(2, list.size)
        val ios = list.first { it.name == "IOS-Vlink" }
        val android = list.first { it.name == "Android-Vlink" }
        assertTrue(ios.seenViaBle && !ios.seenViaClassic)
        assertTrue(android.seenViaClassic && !android.seenViaBle)
        assertEquals(SppProfileStatus.CONFIRMED, android.sppStatus)
    }

    @Test
    fun `ClassicBluetoothTransport validates SDP profile falls back RFCOMM methods exchanges ELM327 and reconnects`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // 1. LE-only rejection
            val leConnector = object : ClassicBluetoothTransport.ClassicSocketConnector {
                override suspend fun probeProfile(mac: String) = ClassicBluetoothScanner.SdpProbeResult(
                    SppProfileStatus.NOT_APPLICABLE, emptyList(), TransportResolver.DEVICE_TYPE_LE, BluetoothDevice.BOND_NONE, true,
                )
                override fun cancelDiscovery() {}
                override fun connect(mac: String, method: ClassicBluetoothTransport.RfcommAttemptMethod) =
                    throw AssertionError("Must not open RFCOMM for DEVICE_TYPE_LE")
            }
            val tLe = ClassicBluetoothTransport(ctx, "D2:E0:2F:8D:56:CA", "IOS-Vlink", RawLog(), reconnectAttempts = 0, scope = scope, connectorOverride = leConnector)
            tLe.connect()
            assertTrue(tLe.state.value is TransportState.Failed)

            // 2. ABSENT SPP rejection
            val noSppConnector = object : ClassicBluetoothTransport.ClassicSocketConnector {
                override suspend fun probeProfile(mac: String) = ClassicBluetoothScanner.SdpProbeResult(
                    SppProfileStatus.ABSENT, listOf("0000110a-0000-1000-8000-00805f9b34fb"), TransportResolver.DEVICE_TYPE_CLASSIC, BluetoothDevice.BOND_BONDED, true,
                )
                override fun cancelDiscovery() {}
                override fun connect(mac: String, method: ClassicBluetoothTransport.RfcommAttemptMethod) =
                    throw AssertionError("Must not open RFCOMM when SPP is absent")
            }
            val tAbsent = ClassicBluetoothTransport(ctx, "13:E0:2F:8D:56:CA", "AudioOnly", RawLog(), reconnectAttempts = 0, scope = scope, connectorOverride = noSppConnector)
            tAbsent.connect()
            assertTrue(tAbsent.state.value is TransportState.Failed)

            // 3. Fallback INSECURE_SDP -> SECURE_SDP -> REFLECTION_CH1 + ELM327 ATZ + Reconnect on EOF
            var connectCycle = 0
            var firstStreamIn: PipedOutputStream? = null
            val triedMethods = ArrayList<ClassicBluetoothTransport.RfcommAttemptMethod>()

            val workingConnector = object : ClassicBluetoothTransport.ClassicSocketConnector {
                override suspend fun probeProfile(mac: String) = ClassicBluetoothScanner.SdpProbeResult(
                    SppProfileStatus.CONFIRMED, listOf(TransportResolver.SPP_UUID_STRING), TransportResolver.DEVICE_TYPE_CLASSIC, BluetoothDevice.BOND_BONDED, true,
                )
                override fun cancelDiscovery() {}
                override fun connect(mac: String, method: ClassicBluetoothTransport.RfcommAttemptMethod): ClassicBluetoothTransport.ClassicSocketStream {
                    triedMethods.add(method)
                    if (connectCycle == 0 && method != ClassicBluetoothTransport.RfcommAttemptMethod.REFLECTION_CH1) {
                        throw IOException("simulated failure for $method")
                    }
                    connectCycle++
                    val appIn = PipedInputStream()
                    val simOut = PipedOutputStream(appIn)
                    if (connectCycle == 1) firstStreamIn = simOut
                    val simIn = PipedInputStream()
                    val appOut = PipedOutputStream(simIn)
                    // Echo ELM327 replies asynchronously
                    Thread {
                        val buf = ByteArray(256)
                        while (true) {
                            val n = try { simIn.read(buf) } catch (_: Exception) { -1 }
                            if (n <= 0) break
                            val cmd = String(buf, 0, n, Charsets.US_ASCII).trim()
                            val reply = when {
                                cmd.startsWith("ATZ") || cmd.startsWith("ATI") -> "ELM327 v1.5\r\r>"
                                cmd.startsWith("ATDPN") -> "A6\r\r>"
                                cmd.startsWith("ATDP") -> "AUTO, ISO 15765-4 (CAN 11/500)\r\r>"
                                cmd.startsWith("ATRV") -> "12.4V\r\r>"
                                else -> "OK\r\r>"
                            }
                            try {
                                simOut.write(reply.toByteArray(Charsets.US_ASCII))
                                simOut.flush()
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }.start()
                    return object : ClassicBluetoothTransport.ClassicSocketStream {
                        override val input: InputStream = appIn
                        override val output = appOut
                        override val methodLabel = method.label
                        override fun close() {
                            runCatching { appIn.close() }
                            runCatching { appOut.close() }
                            runCatching { simOut.close() }
                            runCatching { simIn.close() }
                        }
                    }
                }
            }

            val t = ClassicBluetoothTransport(
                ctx,
                "13:E0:2F:8D:56:CA",
                "Android-Vlink",
                RawLog(),
                reconnectAttempts = 2,
                scope = scope,
                connectorOverride = workingConnector,
                allowReflectionFallback = true,
            )
            t.connect()
            assertTrue("Expected Connected, got ${t.state.value}", t.state.value is TransportState.Connected)
            assertEquals(
                listOf(
                    ClassicBluetoothTransport.RfcommAttemptMethod.SECURE_SDP,
                    ClassicBluetoothTransport.RfcommAttemptMethod.INSECURE_SDP,
                    ClassicBluetoothTransport.RfcommAttemptMethod.REFLECTION_CH1,
                ),
                triedMethods,
            )

            val elm = Elm327Client(t, RawLog(), scope)
            assertNull(elm.initialize())
            assertEquals("ELM327 v1.5", elm.info.value.identity)

            // Simulate RFCOMM drop -> auto-reconnect should bring it back to Connected
            firstStreamIn?.close()
            val deadline = System.currentTimeMillis() + 5000
            while (connectCycle < 2 && System.currentTimeMillis() < deadline) {
                delay(50)
            }
            assertTrue("Expected reconnect cycle >= 2, got $connectCycle", connectCycle >= 2)
            val deadline2 = System.currentTimeMillis() + 3000
            while (t.state.value !is TransportState.Connected && System.currentTimeMillis() < deadline2) {
                delay(30)
            }
            assertTrue("Expected reconnected state, got ${t.state.value}", t.state.value is TransportState.Connected)
            t.disconnect()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `DiagnosticRepository connectTransportForTest supports CLASSIC_SPP in 6-stage pipeline`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        val scriptedClassic = object : ByteTransport {
            override val name = "Classic (Android-Vlink)"
            private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
            override val state: StateFlow<TransportState> = _state
            private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
            override val incoming: SharedFlow<ByteArray> = _incoming
            override suspend fun connect() { _state.value = TransportState.Connected("Classic SPP") }
            override suspend fun disconnect() { _state.value = TransportState.Disconnected }
            override suspend fun write(data: ByteArray) {
                while (_incoming.subscriptionCount.value == 0) delay(5)
                val cmd = String(data, Charsets.US_ASCII).trim().uppercase()
                val reply = when (cmd) {
                    "ATZ", "ATI" -> "ELM327 v1.5"
                    "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
                    "ATDPN" -> "A6"
                    "ATRV" -> "12.5V"
                    "0100" -> "41 00 BE 1F A8 13"
                    "010C" -> "41 0C 0F A0"
                    "0120", "0140", "0160" -> "NO DATA"
                    else -> "OK"
                }
                _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
            }
            override fun isConnected() = _state.value is TransportState.Connected
        }
        try {
            repo.connectTransportForTest(scriptedClassic, "Android-Vlink", transportType = BtTransportType.CLASSIC_SPP)
            val p = repo.pipelineStatus.value
            assertEquals(BtTransportType.CLASSIC_SPP, p.transportType)
            assertEquals(DiagnosticRepository.ConnectionStage.DATA_RECEIVED, p.highestStage)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `ClassicBluetoothScanner handles denied permissions and DiagExporter exports classic_scan json`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scanner = ClassicBluetoothScanner(ctx, RawLog())
        // Without permissions on Robolectric, bondedDevices and cancelDiscovery must never crash
        assertTrue(scanner.bondedDevices().isEmpty())
        scanner.cancelDiscoveryIfActive()

        val session = DiagSession(ctx.filesDir, RawLog())
        session.recordClassicDevice(
            ClassicScanRecord(
                mac = "13:E0:2F:8D:56:CA",
                name = "Android-Vlink",
                rssi = -62,
                deviceType = TransportResolver.DEVICE_TYPE_CLASSIC,
                bondState = BluetoothDevice.BOND_BONDED,
                sdpUuids = listOf(TransportResolver.SPP_UUID_STRING),
                sppStatus = SppProfileStatus.CONFIRMED.name,
                source = "discovery",
            ),
        )
        val zipFile = DiagExporter(ctx, session).export(maskIds = true)
        assertNotNull(zipFile)
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("classic_scan.json")
            assertNotNull("classic_scan.json must exist in diagnostic ZIP", entry)
            val json = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(json.contains("Android-Vlink"))
            assertTrue(json.contains("CONFIRMED"))
            assertFalse("MAC must be masked when maskIds=true", json.contains("13:E0:2F:8D:56:CA"))
            assertTrue(json.contains("13:E0:**:**:**:**"))
        }
    }
}
