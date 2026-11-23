package com.obd2.app

import com.obd2.app.core.elm327.Elm327Client
import com.obd2.app.core.elm327.classifyLine
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.obd.ObdClient
import com.obd2.app.core.transport.DemoElmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmAndTransportTest {

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `classify adapter messages`() {
        assertEquals(
            com.obd2.app.core.elm327.ElmResponse.AdapterMessage.Kind.NO_DATA,
            classifyLine("NO DATA"),
        )
        assertEquals(
            com.obd2.app.core.elm327.ElmResponse.AdapterMessage.Kind.UNABLE_TO_CONNECT,
            classifyLine("UNABLE TO CONNECT"),
        )
        assertEquals(
            com.obd2.app.core.elm327.ElmResponse.AdapterMessage.Kind.SEARCHING,
            classifyLine("SEARCHING..."),
        )
        assertNull(classifyLine("41 00 BE 3F B8 13"))
    }

    @Test
    fun `voltage parsing`() {
        assertEquals(13.9f, Elm327Client.parseVoltage("13.90V")!!, 0.01f)
        assertEquals(12.1f, Elm327Client.parseVoltage("12,10V")!!, 0.01f)
        assertNull(Elm327Client.parseVoltage("NO DATA"))
    }

    @Test
    fun `demo transport responds to ATZ`() = runBlocking {
        val log = RawLog()
        val demo = DemoElmTransport(scope = scope, latencyMs = 1)
        val elm = Elm327Client(demo, log, scope)
        demo.connect()
        val resp = elm.command("ATZ", timeoutMs = 2000)
        if (resp !is com.obd2.app.core.elm327.ElmResponse.Ok) {
            println("RAWLOG:\n" + log.toPlainText())
        }
        assertTrue(resp is com.obd2.app.core.elm327.ElmResponse.Ok)
        assertEquals("ELM327 v2.3", (resp as com.obd2.app.core.elm327.ElmResponse.Ok).lines.last())
    }

    @Test
    fun `full init sequence works against demo`() = runBlocking {
        val demo = DemoElmTransport(scope = scope, latencyMs = 1)
        val elm = Elm327Client(demo, RawLog(), scope)
        val err = elm.initialize()
        assertNull(err)
        assertEquals("AUTO, ISO 15765-4 (CAN 11 bit ID, 500 kbaud)", elm.info.value.protocol)
        assertEquals(6, elm.info.value.protocolNumber)
        assertNotNull(elm.info.value.voltage)
    }

    @Test
    fun `obd reads rpm from demo with chunked BLE-like responses`() = runBlocking {
        withTimeout(15000) {
            val demo = DemoElmTransport(scope = scope, latencyMs = 1)
            val elm = Elm327Client(demo, RawLog(), scope)
            elm.initialize()
            val obd = ObdClient(elm)
            val def = com.obd2.app.core.obd.PidDefinition(
                mode = 1, pid = 0x0C, nameEn = "Engine RPM", nameRu = "Обороты", unit = "rpm",
                bytes = 2, decoder = "numeric", weight = "AB", mult = 0.25, offset = 0.0,
                formula = null, category = "engine",
            )
            val res = obd.readPid(def, timeoutMs = 3000)
            assertTrue(res is com.obd2.app.core.obd.PidResult.Ok)
            val reading = (res as com.obd2.app.core.obd.PidResult.Ok).reading
            assertEquals(demo.rpm.toDouble(), reading.value!!, 0.5)
        }
    }

    @Test
    fun `obd discovers supported pids from demo`() = runBlocking {
        withTimeout(20000) {
            val demo = DemoElmTransport(scope = scope, latencyMs = 1)
            val log = RawLog()
            val elm = Elm327Client(demo, log, scope)
            elm.initialize()
            val obd = ObdClient(elm)
            val sup = obd.discoverSupportedPids()
            assertTrue(sup.supportedPids.contains(0x0C))
            assertTrue(sup.supportedPids.contains(0x0D))
            assertTrue(sup.supportedPids.contains(0x05))
            assertTrue(sup.maskErrors.isNotEmpty()) // 0160 unsupported in demo
        }
    }

    @Test
    fun `obd reads stored dtcs from demo`() = runBlocking {
        withTimeout(15000) {
            val demo = DemoElmTransport(scope = scope, latencyMs = 1)
            val elm = Elm327Client(demo, RawLog(), scope)
            elm.initialize()
            val obd = ObdClient(elm)
            val res = obd.readDtc(3)
            assertTrue(res is ObdClient.DtcReadResult.Ok)
            assertEquals(listOf("P0300", "P0441"), (res as ObdClient.DtcReadResult.Ok).dtcs.map { it.code })
        }
    }

    @Test
    fun `obd reads VIN from demo`() = runBlocking {
        withTimeout(15000) {
            val demo = DemoElmTransport(scope = scope, latencyMs = 1)
            val elm = Elm327Client(demo, RawLog(), scope)
            elm.initialize()
            val obd = ObdClient(elm)
            val def = com.obd2.app.core.obd.PidDefinition(
                mode = 9, pid = 0x02, nameEn = "VIN", nameRu = "VIN", unit = null,
                bytes = null, decoder = "vin", weight = null, mult = null, offset = null,
                formula = null, category = "vehicle_info",
            )
            val res = obd.readPid(def, timeoutMs = 4000)
            assertTrue(res is com.obd2.app.core.obd.PidResult.Ok)
            assertEquals(demo.vin, (res as com.obd2.app.core.obd.PidResult.Ok).reading.text)
        }
    }

    @Test
    fun `unsupported pid becomes Unsupported`() = runBlocking {
        withTimeout(15000) {
            val demo = DemoElmTransport(scope = scope, latencyMs = 1)
            val elm = Elm327Client(demo, RawLog(), scope)
            elm.initialize()
            val obd = ObdClient(elm)
            val def = com.obd2.app.core.obd.PidDefinition(
                mode = 1, pid = 0x3C, nameEn = "Catalyst", nameRu = null, unit = null,
                bytes = null, decoder = "numeric", weight = "AB", mult = 0.1, offset = -40.0,
                formula = null, category = "catalyst",
            )
            val res = obd.readPid(def, timeoutMs = 3000)
            assertTrue(res is com.obd2.app.core.obd.PidResult.Unsupported)
        }
    }

    @Test
    fun `raw log masks VIN sequences`() {
        val masked = RawLog.maskVin("49 02 01 TMBJJ7NE9C0123456 end")
        assertTrue(masked.contains("XXXXXXXXXXXXXXXXX"))
        assertTrue(!masked.contains("TMBJJ7NE9C0123456"))
    }
}
