package com.obd2.app

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.lpg.omvl.FutureBluetoothSppOmvlTransportStub
import com.obd2.app.core.lpg.omvl.OmvlFieldConfidence
import com.obd2.app.core.lpg.omvl.OmvlFrameDecodeResult
import com.obd2.app.core.lpg.omvl.OmvlSkyCodec
import com.obd2.app.core.lpg.omvl.OmvlSkyReplayController
import com.obd2.app.core.lpg.omvl.OmvlSkyTransport
import com.obd2.app.core.lpg.omvl.OmvlSppAdapterTarget
import com.obd2.app.core.lpg.omvl.OmvlSppConnectionState
import com.obd2.app.core.lpg.omvl.OmvlSppSessionManager
import com.obd2.app.core.lpg.omvl.OmvlStreamReassembler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.OmvlSkyScreen
import com.obd2.app.ui.theme.Obd2Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OmvlSkyModuleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").trim()
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun test01_independentCrc16VectorAndCapturedFrames() {
        // Standard CRC-16/CCITT-FALSE check value for "123456789"
        assertEquals(0x29B1, OmvlSkyCodec.crc16CcittFalse("123456789".toByteArray(Charsets.US_ASCII)))

        val tx04 = hex("77 06 04 2F 3C 71")
        val rx04 = hex("66 11 04 2F 0F 00 00 FF FF 0F 0F 00 0F 01 00 EF 3A")
        assertTrue(OmvlSkyCodec.verifyFrame(tx04) is OmvlFrameDecodeResult.Valid)
        assertTrue(OmvlSkyCodec.verifyFrame(rx04) is OmvlFrameDecodeResult.Valid)

        // 1-bit flip must be rejected
        val bad = rx04.copyOf()
        bad[4] = (bad[4].toInt() xor 0x01).toByte()
        assertTrue(OmvlSkyCodec.verifyFrame(bad) is OmvlFrameDecodeResult.Invalid)
    }

    @Test
    fun test02_reassemblyHandlesFragmentationOrphansAndCorruptedFlood() {
        val orphan = hex("00 00 00 00 00 00 21 00 00 FF FF 00 00 EA 00 00 00 00 00 00 C8 85")
        val part1 = hex(
            "66 7E 09 02 B5 02 BA 02 B5 02 BA 03 C0 03 C7 03 C0 03 C7 02 B7 03 C3 02 E8 7A CA " +
                "00 21 00 71 13 A5 0D D9 00 2B 10 D5 00 00 00 00 02 03 04 37 2D 13 7C FF 05 00 FF 03 " +
                "0F 00 00 FF 00 00 00 00 04 00 00 00 00 00 00 00 00 00 00 00 00 FE FF F5 F6 42 20 00 " +
                "00 00 34 00 00 00 30 00 04 00 00 00 00 92 00 00 00",
        )
        val part2 = hex("FF FF 00 00 00 00 00 00 00 00 21 00 00 FF FF 00 00 EA 00 00 00 00 00 00 D4 9C")

        val reassembler = OmvlStreamReassembler(expectedPrefix = 0x66, maxBufferBytes = 512)
        assertTrue(reassembler.feed(orphan).isEmpty())
        assertTrue(reassembler.feed(part1).isEmpty())
        val frames = reassembler.feed(part2)
        assertEquals(1, frames.size)
        assertEquals(0x09, frames[0].opcode)
        assertEquals(126, frames[0].length)

        // Corrupted flood without 0x66 must stay bounded without OOM or hang
        val flood = ByteArray(2000) { 0xAA.toByte() }
        assertTrue(reassembler.feed(flood).isEmpty())
        assertTrue(reassembler.droppedBytes >= 2000)
    }

    @Test
    fun test03_invalidLengthAndPrefixRejected() {
        assertTrue(OmvlSkyCodec.verifyFrame(hex("55 05 09 00 00")) is OmvlFrameDecodeResult.Invalid)
        assertTrue(OmvlSkyCodec.verifyFrame(hex("66 04 09 00")) is OmvlFrameDecodeResult.Invalid)
        assertTrue(OmvlSkyCodec.verifyFrame(hex("66 20 09 00 00 00")) is OmvlFrameDecodeResult.Invalid)
    }

    @Test
    fun test04_decodeTelemetry0x09_andKeepUndecodedFieldsExplicit() {
        val raw09 = hex(
            "66 7E 09 02 B5 02 BA 02 B5 02 BA 03 C0 03 C7 03 C0 03 C7 02 B7 03 C3 02 E8 7A CA " +
                "00 21 00 71 13 A5 0D D9 00 2B 10 D5 00 00 00 00 02 03 04 37 2D 13 7C FF 05 00 FF 03 " +
                "0F 00 00 FF 00 00 00 00 04 00 00 00 00 00 00 00 00 00 00 00 00 FE FF F5 F6 42 20 00 " +
                "00 00 34 00 00 00 30 00 04 00 00 00 00 92 00 00 00 " +
                "FF FF 00 00 00 00 00 00 00 00 21 00 00 FF FF 00 00 EA 00 00 00 00 00 00 D4 9C",
        )
        val valid = (OmvlSkyCodec.verifyFrame(raw09) as OmvlFrameDecodeResult.Valid).frame
        val tel = OmvlSkyCodec.decodeTelemetry0x09(valid, offsetMs = 100L)
        assertNotNull(tel)
        assertEquals(744, tel!!.rpm.value)
        assertEquals(3.475, tel.petrolInjAvgMs.value!!, 0.001)
        assertEquals(4.815, tel.gasInjAvgMs.value!!, 0.001)
        assertEquals(0.33, tel.mapBar.value!!, 0.01)
        assertEquals(1.13, tel.gasPressureBar.value!!, 0.01)
        assertEquals(14.13, tel.supplyVoltageV.value!!, 0.01)

        // Unconfirmed fields must stay UNDECODED with null values
        assertEquals(OmvlFieldConfidence.UNDECODED, tel.gasTempC.confidence)
        assertNull(tel.gasTempC.value)
        assertEquals(OmvlFieldConfidence.UNDECODED, tel.reducerTempC.confidence)
        assertNull(tel.reducerTempC.value)
        assertEquals(OmvlFieldConfidence.UNDECODED, tel.fuelMode.confidence)
        assertNull(tel.fuelMode.value)
    }

    @Test
    fun test05_decodeDtcTable0x32_andEvents0x35_separateFromObd2() {
        val raw32 = hex(
            "66 68 32 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "01 3F 01 3F 03 84 02 3F 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 08 A5 00 00 00 00 00 00 00 00 03 3F 00 00 00 00 7D 84 00 00 13 9C 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 02 15 00 00 00 00 00 00 00 00 E7 0B",
        )
        val raw35 = hex(
            "66 50 35 " +
                "17 3F 06 BF 0E E2 11 24 2E 56 D0 C7 00 00 FE " +
                "21 00 19 46 00 00 3F 30 22 4A CA C7 00 00 FE " +
                "1F 17 77 05 00 00 4B 10 2A 46 74 C8 00 00 FE " +
                "0B 17 7A A4 1A A9 4B 10 2A 46 7E DB 00 00 FE " +
                "1C 00 02 9D 00 00 4B 0D 16 4A 81 C4 00 00 F9 13 B4",
        )
        val f32 = (OmvlSkyCodec.verifyFrame(raw32) as OmvlFrameDecodeResult.Valid).frame
        val f35 = (OmvlSkyCodec.verifyFrame(raw35) as OmvlFrameDecodeResult.Valid).frame
        val dtcs32 = OmvlSkyCodec.decodeDtcTable0x32(f32)
        val dtcs35 = OmvlSkyCodec.decodeDtcEvents0x35(f35)
        val merged = OmvlSkyCodec.mergeHistoricalDtcs(dtcs32, dtcs35)

        assertEquals(
            listOf("E009", "E010", "E011", "E012", "E023", "E028", "E031", "E033", "E042"),
            merged.map { it.code },
        )
        assertTrue(merged.all { it.code.startsWith("E") && it.isHistorical })
        assertTrue(merged.first { it.code == "E023" }.hasFreezeFrameRecord)
        assertEquals(8, merged.first { it.code == "E023" }.occurrenceCount)
    }

    @Test
    fun test06_offlineReplayController_replaysAssetAndFutureSppStubDisablesTx() {
        runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val ctrl = OmvlSkyReplayController(ctx)
            ctrl.ensureLoaded()
            // Wait briefly for collector coroutine
            var tries = 0
            while (ctrl.uiState.value.currentTelemetry == null && tries < 30) {
                kotlinx.coroutines.delay(50)
                tries++
            }
            val st = ctrl.uiState.value
            assertTrue(st.loaded)
            assertTrue(st.totalTelemetryFrames >= 30)
            assertNotNull(st.currentTelemetry)
            assertEquals(9, st.historicalDtcs.size)

            val stub = FutureBluetoothSppOmvlTransportStub()
            assertFalse(stub.isLiveTransmissionEnabled)
            stub.connect()
            assertTrue(stub.state.value is OmvlSkyTransport.State.Failed)
        }
    }

    @Test
    fun test07_omvlSkyScreenRendersMandatoryWarningAndUndecodedLabels() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(app)
        composeRule.setContent {
            Obd2Theme {
                OmvlSkyScreen(vm)
            }
        }
        composeRule.waitUntil(3000) {
            vm.omvlSkyController.uiState.value.currentTelemetry != null
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Воспроизведение сохранённой записи. Нет подключения к автомобилю").onFirst().assertExists()
        composeRule.onAllNodesWithText("Не расшифровано").onFirst().assertExists()
        composeRule.onAllNodesWithText("E023", substring = true).onFirst().assertExists()
    }

    @Test
    fun test08_sentinel0xFFFF_returnsUndecodedAndNoFabricatedValues() {
        val raw = ByteArray(126)
        raw[0] = 0x66
        raw[1] = 0x7E
        raw[2] = 0x09
        // Fill RPM (23..24) and gas pressure (29..30) with 0xFFFF sentinel
        raw[23] = 0xFF.toByte()
        raw[24] = 0xFF.toByte()
        raw[29] = 0xFF.toByte()
        raw[30] = 0xFF.toByte()
        val crc = OmvlSkyCodec.crc16CcittFalse(raw, 124)
        raw[124] = ((crc ushr 8) and 0xFF).toByte()
        raw[125] = (crc and 0xFF).toByte()

        val valid = (OmvlSkyCodec.verifyFrame(raw) as OmvlFrameDecodeResult.Valid).frame
        val tel = OmvlSkyCodec.decodeTelemetry0x09(valid)!!
        assertNull(tel.rpm.value)
        assertEquals(OmvlFieldConfidence.UNDECODED, tel.rpm.confidence)
        assertNull(tel.gasPressureBar.value)
        assertEquals(OmvlFieldConfidence.UNDECODED, tel.gasPressureBar.confidence)
    }

    @Test
    fun test09_unknownAndNonMatchingOpcodesAreRejectedCleanly() {
        val rx04 = hex("66 11 04 2F 0F 00 00 FF FF 0F 0F 00 0F 01 00 EF 3A")
        val f04 = (OmvlSkyCodec.verifyFrame(rx04) as OmvlFrameDecodeResult.Valid).frame
        assertNull(OmvlSkyCodec.decodeTelemetry0x09(f04))
        assertTrue(OmvlSkyCodec.decodeDtcTable0x32(f04).isEmpty())
        assertTrue(OmvlSkyCodec.decodeDtcEvents0x35(f04).isEmpty())
    }

    @Test
    fun test10_sppSessionManager_adapterSelectionTimeoutCancelDisconnectAndTxLock() {
        runBlocking {
            // 1. Timeout connector
            val slowConnector = object : OmvlSppSessionManager.OmvlSppConnector {
                override suspend fun openPassiveSession(target: OmvlSppAdapterTarget): OmvlSppSessionManager.OmvlSppSession {
                    delay(1000L)
                    error("should have timed out")
                }
            }
            val mgrTimeout = OmvlSppSessionManager(connector = slowConnector)
            assertFalse(mgrTimeout.liveTransmissionAllowed)
            assertTrue(mgrTimeout.attemptSendBlocked(0x09).reason.contains("0x09"))
            mgrTimeout.selectAdapter("00:01:82:00:00:C1", "GBO-3")
            assertTrue(mgrTimeout.state.value is OmvlSppConnectionState.AdapterSelected)

            mgrTimeout.connectPassive(connectTimeoutMs = 80L, readTimeoutMs = 200L)
            delay(200L)
            assertTrue("Expected TimedOut, got ${mgrTimeout.state.value}", mgrTimeout.state.value is OmvlSppConnectionState.TimedOut)

            // 2. Cancel during connect
            val mgrCancel = OmvlSppSessionManager(connector = slowConnector)
            mgrCancel.selectAdapter("00:01:82:00:00:C1", "GBO-3")
            mgrCancel.connectPassive(connectTimeoutMs = 5000L)
            delay(30L)
            mgrCancel.cancelConnect()
            assertTrue("Expected Cancelled, got ${mgrCancel.state.value}", mgrCancel.state.value is OmvlSppConnectionState.Cancelled)

            // 3. Passive RX streaming + adapter re-selection + disconnect
            val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
            var closed = false
            val okConnector = object : OmvlSppSessionManager.OmvlSppConnector {
                override suspend fun openPassiveSession(target: OmvlSppAdapterTarget) = object : OmvlSppSessionManager.OmvlSppSession {
                    override val incomingChunks: Flow<ByteArray> = incoming
                    override suspend fun close() { closed = true }
                }
            }
            val mgrOk = OmvlSppSessionManager(connector = okConnector)
            mgrOk.selectAdapter("00:01:82:00:00:C1", "GBO-3")
            mgrOk.connectPassive(connectTimeoutMs = 500L, readTimeoutMs = 2000L)
            delay(50L)
            assertTrue(mgrOk.state.value is OmvlSppConnectionState.ConnectedPassiveRxOnly)
            incoming.emit(hex("66 11 04 2F 0F 00 00 FF FF 0F 0F 00 0F 01 00 EF 3A"))
            delay(50L)
            assertEquals(1, mgrOk.rxFrames.value.size)
            mgrOk.disconnect()
            delay(50L)
            assertTrue(closed)
            assertTrue(mgrOk.state.value is OmvlSppConnectionState.Disconnected)
            mgrOk.selectAdapter("AA:BB:CC:DD:EE:FF", "GBO-3-ALT")
            assertEquals("AA:BB:CC:DD:EE:FF", mgrOk.selectedAdapter.value?.mac)
        }
    }
}
