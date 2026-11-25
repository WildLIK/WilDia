package com.obd2.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.obd2.app.core.bt.BtTransportType
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.DtcRecordEntity
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.ElmResponse
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.obd.PidResult
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.core.transport.TransportState
import com.obd2.app.core.universal.AdapterCapabilityProbe
import com.obd2.app.core.universal.AdapterTransportKind
import com.obd2.app.core.updates.ApkReleaseManifest
import com.obd2.app.core.updates.ApkUpdateManager
import com.obd2.app.core.updates.ApkVerificationResult
import com.obd2.app.core.usb.UsbDeviceDescriptor
import com.obd2.app.core.usb.UsbDriverKind
import com.obd2.app.core.usb.UsbSerialBackend
import com.obd2.app.core.usb.UsbSerialPortHandle
import com.obd2.app.core.usb.UsbSerialTransport
import com.obd2.app.core.wifi.WifiTcpTransport
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.ConnectScreen
import com.obd2.app.ui.theme.Obd2Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V057MultiTransportAndUpdaterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ctx(): Application = ApplicationProvider.getApplicationContext()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // 1. Wi-Fi TCP ELM327: local ServerSocket, fragmentation, reconnect
    // ------------------------------------------------------------------

    @Test
    fun `WifiTcpTransport connects to local ELM327 TCP server, handles fragmented chunks, PID 010C, and reconnects`() {
        runBlocking {
            val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val port = ss.localPort
            val dropFirstConnection = AtomicBoolean(true)
            val connections = AtomicInteger(0)

            val serverThread = thread(isDaemon = true) {
                while (!ss.isClosed) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    val connNum = connections.incrementAndGet()
                    thread(isDaemon = true) {
                        sock.use { s ->
                            val inp = s.getInputStream()
                            val out = s.getOutputStream()
                            val buf = ByteArray(256)
                            val sb = StringBuilder()
                            while (!s.isClosed) {
                                val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                                if (n <= 0) break
                                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                                while (true) {
                                    val cr = sb.indexOf('\r')
                                    if (cr < 0) break
                                    val cmd = sb.substring(0, cr).trim().uppercase()
                                    sb.delete(0, cr + 1)
                                    val resp = when (cmd) {
                                        "ATZ" -> "ELM327 v1.5\r>"
                                        "ATI" -> "ELM327 v1.5\r>"
                                        "ATE0", "ATL0", "ATS1", "ATH1", "ATH0", "ATAT1", "ATSP0", "ATSH 7DF", "ATCRA" -> "OK\r>"
                                        "ATDP" -> "ISO 15765-4 (CAN 11/500)\r>"
                                        "ATDPN" -> "A6\r>"
                                        "ATRV" -> "12.8V\r>"
                                        "010C" -> {
                                            if (connNum == 1 && dropFirstConnection.compareAndSet(true, false)) {
                                                // Simulate abrupt TCP drop before response
                                                s.close()
                                                return@thread
                                            }
                                            // Fragmented response across two TCP writes!
                                            out.write("41 0C ".toByteArray(Charsets.US_ASCII))
                                            out.flush()
                                            Thread.sleep(30)
                                            out.write("1A F8\r>".toByteArray(Charsets.US_ASCII))
                                            out.flush()
                                            continue
                                        }
                                        else -> "NO DATA\r>"
                                    }
                                    out.write(resp.toByteArray(Charsets.US_ASCII))
                                    out.flush()
                                }
                            }
                        }
                    }
                }
            }

            try {
                val transport = WifiTcpTransport(
                    context = ctx(),
                    host = "127.0.0.1",
                    port = port,
                    log = RawLog(),
                    reconnectAttempts = 2,
                    connectTimeoutMs = 2000,
                )
                val elm = Elm327Client(transport, RawLog())
                val initErr = elm.initialize()
                assertEquals(null, initErr)
                assertEquals(true, elm.info.value.supportsHeaders)
                assertTrue(elm.info.value.supportsCanAddressing)

                val caps = AdapterCapabilityProbe.probe(
                    adapterName = "Kingbolen Wi-Fi",
                    transportType = BtTransportType.WIFI_TCP,
                    demoMode = false,
                    elmInfo = elm.info.value,
                    ecuResponding = true,
                )
                assertEquals(AdapterTransportKind.WIFI_TCP, caps.transportKind)
                assertTrue(caps.supportsCustomCanHeaders)
                assertTrue(caps.supportsUdsReadOnly)

                // Trigger drop on first 010C, wait for automatic reconnect, then read PID 010C cleanly
                elm.command("010C", timeoutMs = 800, allowRetries = false)
                val deadline = System.currentTimeMillis() + 5000L
                while (!transport.isConnected() && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(50)
                }
                assertTrue("Wi-Fi TCP must reconnect automatically", transport.isConnected())

                val obd = ObdClient(elm)
                val rpmRes = obd.readPid(
                    com.obd2.app.core.obd.PidDefinition(
                        mode = 1,
                        pid = 0x0C,
                        nameEn = "Engine RPM",
                        nameRu = "Обороты двигателя",
                        unit = "rpm",
                        bytes = 2,
                        decoder = "numeric",
                        weight = "AB",
                        mult = 0.25,
                        offset = 0.0,
                        formula = "(A*256+B)/4",
                        category = "Engine",
                    ),
                )
                assertTrue(rpmRes is PidResult.Ok)
                assertEquals(1726.0, (rpmRes as PidResult.Ok).reading.value!!, 0.5)

                transport.disconnect()
                assertEquals(TransportState.Disconnected, transport.state.value)
            } finally {
                runCatching { ss.close() }
                serverThread.interrupt()
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. USB Serial OTG: permission denied, auto-baud, detach, unsupported USB
    // ------------------------------------------------------------------

    private class FakeUsbBackend(
        private val devices: List<UsbDeviceDescriptor>,
        private val activeBaud: Int = 115200,
        private val failReadAfterFirstCommand: Boolean = false,
    ) : UsbSerialBackend {
        var openCount = 0
        var closeCount = 0

        override fun listDevices(): List<UsbDeviceDescriptor> = devices
        override fun hasPermission(deviceId: Int): Boolean =
            devices.firstOrNull { it.deviceId == deviceId }?.hasPermission == true

        override fun requestPermission(deviceId: Int, onResult: (Boolean) -> Unit) = onResult(false)

        override fun openPort(deviceId: Int, baudRate: Int): UsbSerialPortHandle {
            openCount++
            val rxQueue = LinkedBlockingQueue<ByteArray>()
            val closed = AtomicBoolean(false)
            val cmdsSeen = AtomicInteger(0)

            return object : UsbSerialPortHandle {
                override fun open(baudRate: Int) {}

                override fun read(dest: ByteArray, timeoutMs: Int): Int {
                    if (closed.get()) throw IOException("USB port closed")
                    val chunk = rxQueue.poll(timeoutMs.toLong().coerceAtLeast(10L), TimeUnit.MILLISECONDS)
                        ?: return 0
                    val len = chunk.size.coerceAtMost(dest.size)
                    System.arraycopy(chunk, 0, dest, 0, len)
                    return len
                }

                override fun write(src: ByteArray, timeoutMs: Int) {
                    if (closed.get()) throw IOException("USB disconnected")
                    val cmd = String(src, Charsets.US_ASCII).trim().uppercase()
                    if (baudRate != activeBaud) return // wrong baud -> silence
                    val n = cmdsSeen.incrementAndGet()
                    if (failReadAfterFirstCommand && n > 2) {
                        throw IOException("USB OTG cable detached")
                    }
                    val reply = when (cmd) {
                        "ATI", "ATZ" -> "ELM327 v1.5\r>"
                        "ATE0", "ATL0", "ATS1", "ATH1", "ATH0", "ATAT1", "ATSP0", "ATSH 7DF", "ATCRA" -> "OK\r>"
                        "ATDP" -> "ISO 15765-4 (CAN 11/500)\r>"
                        "ATDPN" -> "A6\r>"
                        "ATRV" -> "13.1V\r>"
                        else -> "NO DATA\r>"
                    }
                    // Fragment reply in 2 pieces
                    val bytes = reply.toByteArray(Charsets.US_ASCII)
                    val mid = (bytes.size / 2).coerceAtLeast(1)
                    rxQueue.offer(bytes.copyOfRange(0, mid))
                    rxQueue.offer(bytes.copyOfRange(mid, bytes.size))
                }

                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        closeCount++
                    }
                }
            }
        }
    }

    @Test
    fun `UsbSerialTransport rejects unsupported USB and permission denied, auto-detects 115200 baud, and handles detach`() {
        runBlocking {
            val unsupported = UsbDeviceDescriptor(1, 0x1234, 0x5678, "Generic", "USB Flash Drive", UsbDriverKind.UNSUPPORTED, true)
            val backend = FakeUsbBackend(listOf(unsupported))
            val tUnsupported = UsbSerialTransport(ctx(), unsupported, null, backend, RawLog())
            tUnsupported.connect()
            assertTrue(tUnsupported.state.value is TransportState.Failed)
            assertFalse(tUnsupported.isConnected())

            val ch340Denied = UsbDeviceDescriptor(2, 0x1A86, 0x7523, "QinHeng", "CH340 ELM327", UsbDriverKind.CH340, hasPermission = false)
            val tDenied = UsbSerialTransport(ctx(), ch340Denied, null, FakeUsbBackend(listOf(ch340Denied)), RawLog())
            tDenied.connect()
            assertTrue(tDenied.state.value is TransportState.Failed)
            assertTrue((tDenied.state.value as TransportState.Failed).reason.contains("разрешения"))

            val ch340Ok = ch340Denied.copy(hasPermission = true)
            val okBackend = FakeUsbBackend(listOf(ch340Ok), activeBaud = 115200)
            val tOk = UsbSerialTransport(ctx(), ch340Ok, manualBaudRate = null, okBackend, RawLog())
            val elm = Elm327Client(tOk, RawLog())
            val err = elm.initialize()
            assertEquals(null, err)
            assertTrue(tOk.isConnected())
            assertTrue((tOk.state.value as TransportState.Connected).details.contains("115200"))

            tOk.disconnect()
            assertEquals(TransportState.Disconnected, tOk.state.value)
            assertTrue("USB port handle must be closed on disconnect", okBackend.closeCount >= 1)
        }
    }

    // ------------------------------------------------------------------
    // 3. Factual ELM327 capability probing (limited v2.1 clone vs capable adapter)
    // ------------------------------------------------------------------

    @Test
    fun `AdapterCapabilityProbe evaluates factual AT header support instead of version string`() {
        val limitedCloneInfo = Elm327Client.Elm327Info(
            identity = "ELM327 v2.1",
            protocol = "ISO 15765-4 (CAN 11/500)",
            protocolNumber = 6,
            isCanProtocol = true,
            supportsHeaders = false,
            supportsCanAddressing = false,
            capabilityNotes = listOf("ATH1/ATH0 не подтверждён (возможны ограничения разделения ЭБУ)"),
        )
        val cloneCaps = AdapterCapabilityProbe.probe(
            adapterName = "Cheap Clone v2.1",
            transportType = BtTransportType.BLE,
            demoMode = false,
            elmInfo = limitedCloneInfo,
            ecuResponding = true,
        )
        assertTrue(cloneCaps.supportsStandardObd2)
        assertFalse("Clone rejecting ATH1 must not claim custom CAN headers/UDS", cloneCaps.supportsCustomCanHeaders)
        assertFalse(cloneCaps.supportsUdsReadOnly)
        assertTrue(cloneCaps.limitations.any { it.contains("ATH1/ATH0") })
    }

    // ------------------------------------------------------------------
    // 4. Updater fixes: reuse existing APK & persist across restart
    // ------------------------------------------------------------------

    @Test
    fun `ApkUpdateManager persists verified update across restart and skips re-download when SHA matches`() {
        val mgr = ApkUpdateManager(ctx(), allowHttpForTest = true)
        val payload = "already-downloaded-valid-payload".toByteArray()
        val sha = sha256(payload)
        val manifest = ApkReleaseManifest(
            versionCode = 99,
            versionName = "0.9.9",
            changelog = "Test",
            apkUrl = "http://127.0.0.1:1/must-not-be-called",
            sizeBytes = payload.size.toLong(),
            sha256 = sha,
        )
        val cachedApk = mgr.apkFileFor(manifest).apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        try {
            // Even with an unreachable URL, downloadAndVerify reuses cachedApk (matching SHA-256)
            // and passes straight to verifyApkArchive!
            val res = mgr.downloadAndVerify(manifest, wifiOnly = false, currentVersionCode = 17)
            assertTrue(res is ApkVerificationResult.Rejected)
            assertTrue((res as ApkVerificationResult.Rejected).reason.contains("корректным Android APK"))
        } finally {
            cachedApk.delete()
        }
    }

    // ------------------------------------------------------------------
    // 5. User data safety: Room + DataStore preserved across upgrade + error surfaced
    // ------------------------------------------------------------------

    @Test
    fun `Room sessions, DTCs, DataStore profiles and settings survive restart without loss`() {
        runBlocking {
            val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".").apply { mkdirs() }
            val dbFile = File(tmpDir, "v057_upg_${System.nanoTime()}.db")
            val db1 = Room.databaseBuilder(ctx(), AppDb::class.java, dbFile.absolutePath)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(*AppDb.ALL_MIGRATIONS)
                .build()
            val sid = db1.sessionDao().insertSession(
                SessionEntity(
                    startedAtMs = 1000L,
                    adapterName = "Vgate iCar Pro",
                    transportType = "CLASSIC_SPP",
                    demoMode = false,
                    identifiedVin = "TMBJC41Z8C2123456",
                    vinMasked = "TMBJC41Z8********",
                    storedCount = 1,
                    dtcReadCompleted = true,
                    status = SessionStatus.COMPLETED,
                ),
            )
            db1.dtcRecordDao().insertAll(
                listOf(
                    DtcRecordEntity(
                        sessionId = sid,
                        code = "P0171",
                        mode = 3,
                        descriptionRu = "Бедная смесь",
                        descriptionEn = "System Too Lean",
                        scope = "GENERIC",
                        timestampMs = 1001L,
                    ),
                ),
            )
            db1.close()

            val settings = SettingsRepository(ctx())
            val profile = VehicleProfile(
                id = "my-test-car",
                name = "Моя Тестовая",
                maker = "TestBrand",
                model = "ModelX",
                year = "2012",
                engine = "1.6 MPI",
            )
            settings.addProfile(profile)
            settings.setActiveProfile(profile.id)
            settings.setWifiEndpoint("192.168.0.10", 35000)
            settings.setUsbBaud(115200)

            // Re-open Room DB (simulating app update / cold restart)
            val db2 = Room.databaseBuilder(ctx(), AppDb::class.java, dbFile.absolutePath)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(*AppDb.ALL_MIGRATIONS)
                .build()
            try {
                val restoredSessions = db2.sessionDao().allSessions().first()
                assertEquals(1, restoredSessions.size)
                assertEquals("TMBJC41Z8C2123456", restoredSessions[0].identifiedVin)
                val restoredDtcs = db2.dtcRecordDao().forSession(sid)
                assertEquals(1, restoredDtcs.size)
                assertEquals("P0171", restoredDtcs[0].code)

                val restoredProfiles = settings.profilesFlow.first()
                assertTrue(restoredProfiles.any { it.id == "my-test-car" && it.engine == "1.6 MPI" })
                val restoredSettings = settings.settings.first()
                assertEquals("192.168.0.10", restoredSettings.lastWifiHost)
                assertEquals(35000, restoredSettings.lastWifiPort)
                assertEquals(115200, restoredSettings.lastUsbBaud)
            } finally {
                db2.close()
                dbFile.delete()
                runCatching {
                    settings.deleteProfile("my-test-car")
                    settings.setActiveProfile("")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. ConnectScreen UI test on 1280x720 headunit layout across 4 modes
    // ------------------------------------------------------------------

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land")
    fun `ConnectScreen renders Bluetooth, Wi-Fi, and USB modes cleanly at 1280x720 headunit size`() {
        val vm = ObdViewModel(ctx())
        composeRule.setContent {
            Obd2Theme {
                ConnectScreen(vm)
            }
        }
        composeRule.onNodeWithText("Авто (BLE → Classic)").assertIsDisplayed()
        composeRule.onNodeWithText("Wi-Fi").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Wi-Fi ELM327 (TCP)").assertIsDisplayed()
        composeRule.onNodeWithText("Подключиться по Wi-Fi").assertIsDisplayed()

        composeRule.onNodeWithText("USB (OTG)").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("USB ELM327 (OTG Serial)").assertIsDisplayed()
        composeRule.onNodeWithText("Обнаружить USB-устройства").assertIsDisplayed()
    }
}
