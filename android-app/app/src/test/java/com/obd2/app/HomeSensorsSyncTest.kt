package com.obd2.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.transport.ByteTransport
import com.obd2.app.core.transport.TransportState
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.HomeScreen
import com.obd2.app.ui.screens.SensorsScreen
import com.obd2.app.ui.theme.Obd2Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression tests for Stage 6:
 * - 6-stage granular connection status (never claiming vehicle connected on BLE-only / ELM-only).
 * - Shared HomeScreen <-> SensorsScreen live data, polling state, unsupported PIDs & ATRV fallback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeSensorsSyncTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ScriptedTransport(
        private val answers: Map<String, String?>,
        private val defaultReply: String? = null,
    ) : ByteTransport {
        override val name = "SCRIPTED-SYNC"
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ByteArray> = _incoming

        override suspend fun connect() {
            _state.value = TransportState.Connected("scripted")
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        override suspend fun write(data: ByteArray) {
            while (_incoming.subscriptionCount.value == 0) kotlinx.coroutines.delay(5)
            val cmd = String(data, Charsets.US_ASCII).trim('\r', '\n', ' ').uppercase()
            val reply = answers[cmd] ?: defaultReply ?: return
            _incoming.emit((reply + "\r\r>").toByteArray(Charsets.US_ASCII))
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected
    }

    private fun newRepo(): Triple<DiagnosticRepository, AppDb, CoroutineScope> {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = DiagnosticRepository(ctx, ObdLookupDb(ctx), db, SettingsRepository(ctx), RawLog(), scope)
        return Triple(repo, db, scope)
    }

    @Test
    fun `demo transport advances 6-stage pipeline to DATA_RECEIVED and populates HOME_QUICK_PIDS`() = runBlocking {
        val (repo, db, scope) = newRepo()
        try {
            repo.connectDemo(repo.settings.settings.first())
            val p = repo.pipelineStatus.value
            assertTrue(p.adapterFound)
            assertTrue(p.bleConnected)
            assertTrue(p.elmResponding)
            assertTrue(p.protocolEstablished)
            assertTrue(p.ecuResponding)
            assertTrue(p.dataReceived)
            assertEquals(DiagnosticRepository.ConnectionStage.DATA_RECEIVED, p.highestStage)
            assertTrue("RPM (0x0C) must be populated on Home", 0x0C in repo.liveData.value)
            assertTrue("Coolant (0x05) must be populated on Home", 0x05 in repo.liveData.value)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `BLE connected with silent ELM327 stops at BLE_CONNECTED without claiming ECU connected`() = runBlocking {
        val (repo, db, scope) = newRepo()
        try {
            repo.connectTransportForTest(ScriptedTransport(emptyMap()), deviceName = "IOS-Vlink")
            val st = repo.connection.value
            val p = repo.pipelineStatus.value
            assertTrue("Expected Failed connection state, got $st", st is DiagnosticRepository.ConnectionState.Failed)
            assertTrue(p.bleConnected)
            assertFalse(p.elmResponding)
            assertFalse(p.ecuResponding)
            assertEquals(DiagnosticRepository.ConnectionStage.BLE_CONNECTED, p.highestStage)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `ELM327 ready with NO DATA on 0100 stops at ELM_READY and does not claim ECU_RESPONDING`() = runBlocking {
        val t = ScriptedTransport(
            mapOf(
                "ATZ" to "ELM327 v2.3",
                "ATE0" to "OK",
                "ATL0" to "OK",
                "ATS1" to "OK",
                "ATH0" to "OK",
                "ATI" to "ELM327 v2.3",
                "ATSP0" to "OK",
                "ATDP" to "AUTO",
                "ATDPN" to "A0",
                "ATRV" to "12.6V",
                "0100" to "NO DATA",
                "0120" to "NO DATA",
                "0140" to "NO DATA",
                "0160" to "NO DATA",
            ),
        )
        val (repo, db, scope) = newRepo()
        try {
            repo.connectTransportForTest(t, deviceName = "IOS-Vlink")
            val p = repo.pipelineStatus.value
            assertTrue(p.bleConnected)
            assertTrue(p.elmResponding)
            assertFalse(p.protocolEstablished)
            assertFalse(p.ecuResponding)
            assertFalse(p.dataReceived)
            assertEquals(DiagnosticRepository.ConnectionStage.ELM_READY, p.highestStage)
        } finally {
            repo.disconnect()
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `HomeScreen and SensorsScreen share polling state liveData and ATRV fallback across navigation`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val vm = ObdViewModel(ctx)
        val t = ScriptedTransport(
            mapOf(
                "ATZ" to "ELM327 v2.3",
                "ATE0" to "OK",
                "ATL0" to "OK",
                "ATS1" to "OK",
                "ATH0" to "OK",
                "ATI" to "ELM327 v2.3",
                "ATSP0" to "OK",
                "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
                "ATDPN" to "A6",
                "ATRV" to "12.7V",
                // BE 1F A8 13 covers PIDs 01,03,04,05,06,07,0C,0D,0F,10,11,13,1C,1F,20 (does NOT include 0x42)
                "0100" to "41 00 BE 1F A8 13",
                "0120" to "41 20 80 00 00 00",
                "0140" to "NO DATA",
                "0160" to "NO DATA",
                "010C" to "41 0C 0F A0", // 1000 rpm
                "010D" to "41 0D 28",    // 40 km/h
                "0105" to "41 05 5A",    // 50 °C
                "0104" to "41 04 33",    // 20 %
                "0142" to "NO DATA",
            ),
            defaultReply = "NO DATA",
        )
        try {
            runBlocking {
                vm.repo.connectTransportForTest(t, deviceName = "Vgate iCar Pro")
            }
            // ATRV fallback populates voltage while 0x42 is in unsupportedPids
            assertTrue(0x42 in vm.unsupportedPids.value)
            assertEquals(12.7, vm.elmInfo.value?.voltage?.toDouble() ?: 0.0, 0.01)
            assertEquals(1000.0, vm.liveData.value[0x0C]?.reading?.value ?: 0.0, 0.1)

            var showSensors by mutableStateOf(true)
            composeRule.setContent {
                Obd2Theme {
                    if (showSensors) {
                        SensorsScreen(vm)
                    } else {
                        val nav = rememberNavController()
                        HomeScreen(vm, nav)
                    }
                }
            }
            composeRule.waitForIdle()

            // Verify SensorsScreen renders shared live values + ATRV fallback
            composeRule.onNodeWithText("1000").assertExists()
            composeRule.onNodeWithText("12.7").assertExists()
            composeRule.onNodeWithText("В (ATRV)").assertExists()

            // Start polling on SensorsScreen, then switch away to HomeScreen
            composeRule.onNodeWithText("Опрос").performClick()
            composeRule.waitUntil(3000) { vm.isPolling.value }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Опрос").assertIsNotEnabled()
            composeRule.onNodeWithText("Стоп").assertIsEnabled()

            // Switch to HomeScreen: polling MUST continue and shared tiles MUST display the values
            showSensors = false
            composeRule.waitForIdle()
            assertTrue("Polling must survive leaving SensorsScreen", vm.isPolling.value)
            assertTrue(composeRule.onAllNodesWithText("1000").fetchSemanticsNodes().isNotEmpty())
            composeRule.onNodeWithText("12.7").assertExists()
            composeRule.onNodeWithText("В (ATRV)").assertExists()
            composeRule.onNodeWithText("✓ 1. Адаптер обнаружен").assertExists()
            composeRule.onNodeWithText("✓ 6. Данные получены").assertExists()

            // Stop polling from HomeScreen and verify SensorsScreen reflects stopped state too
            composeRule.onNodeWithText("Стоп").performScrollTo().performClick()
            composeRule.waitUntil(3000) { !vm.isPolling.value }
            showSensors = true
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Опрос").assertIsEnabled()
        } finally {
            runBlocking { vm.repo.disconnect() }
        }
    }
}
