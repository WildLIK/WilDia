package com.obd2.app

import com.obd2.app.core.obd.PidDecoder
import com.obd2.app.core.obd.PidDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PidDecoderTest {

    private fun def(pid: Int, weight: String?, mult: Double?, offset: Double?) = PidDefinition(
        mode = 1, pid = pid, nameEn = "test", nameRu = null, unit = null, bytes = null,
        decoder = "numeric", weight = weight, mult = mult, offset = offset, formula = null, category = "other",
    )

    @Test
    fun `rpm pid 0C decodes per SAE formula`() {
        // (A*256+B)/4; 0x0F 0xA0 = 4000 -> 1000 rpm
        val v = PidDecoder.numeric(def(0x0C, "AB", 0.25, 0.0), byteArrayOf(0x0F.toByte(), 0xA0.toByte()))
        assertEquals(1000.0, v, 0.001)
    }

    @Test
    fun `rpm max value 16383_75`() {
        val v = PidDecoder.numeric(def(0x0C, "AB", 0.25, 0.0), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertEquals(16383.75, v, 0.001)
    }

    @Test
    fun `coolant pid 05 decodes A-40`() {
        // 90 C
        val v = PidDecoder.numeric(def(0x05, "A", 1.0, -40.0), byteArrayOf(0x82.toByte()))
        assertEquals(90.0, v, 0.001)
    }

    @Test
    fun `load pid 04 decodes A*100_255`() {
        val v = PidDecoder.numeric(def(0x04, "A", 100.0 / 255.0, 0.0), byteArrayOf(0xFF.toByte()))
        assertEquals(100.0, v, 0.01)
    }

    @Test
    fun `voltage pid 42 decodes AB_1000`() {
        // 13.962 V -> 13962 -> 0x36 0x6A
        val v = PidDecoder.numeric(def(0x42, "AB", 0.001, 0.0), byteArrayOf(0x36, 0x8A.toByte()))
        assertEquals(13.962, v, 0.001)
    }

    @Test
    fun `fuel level pid 2F`() {
        val v = PidDecoder.numeric(def(0x2F, "A", 100.0 / 255.0, 0.0), byteArrayOf(0x64.toByte()))
        assertEquals(39.2, v, 0.1)
    }

    @Test
    fun `catalyst temp 3C decodes AB_10-40`() {
        // 0x1698 = 5784 -> 5784/10 - 40 = 538.4 C
        val v = PidDecoder.numeric(def(0x3C, "AB", 0.1, -40.0), byteArrayOf(0x16, 0x98.toByte()))
        assertEquals(538.4, v, 0.001)
    }

    @Test
    fun `support mask 0100 classic example BE3FB813`() {
        // Wiki example: BE 3F B8 13 -> A0..? verify a few known bits
        val pids = PidDecoder.supportMask(0x00, byteArrayOf(0xBE.toByte(), 0x3F, 0xB8.toByte(), 0x13))
        assertTrue(pids.contains(0x01))
        assertTrue(pids.contains(0x04))
        assertTrue(pids.contains(0x05))
        assertTrue(pids.contains(0x0C))
        assertTrue(pids.contains(0x0D))
        assertTrue(pids.contains(0x11))
        assertTrue(!pids.contains(0x02))
    }

    @Test
    fun `monitor status MIL and count`() {
        val st = PidDecoder.monitorStatus(byteArrayOf(0x81.toByte(), 0x00, 0x07.toByte(), 0x00))
        assertTrue(st.milOn)
        assertEquals(1, st.dtcCount)
    }

    @Test
    fun `fuel status closed loop`() {
        val s = PidDecoder.fuelSystemStatus(byteArrayOf(2, 0))
        assertTrue(s.contains("closed loop"))
    }

    @Test
    fun `obd standard 17 is EOBD phase 2`() {
        assertEquals("EOBD (Европа, фаза 2)", PidDecoder.obdStandard(17))
    }

    @Test
    fun `fuel type 1 is petrol`() {
        assertEquals("Бензин", PidDecoder.fuelType(1))
    }
}
