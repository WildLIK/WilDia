package com.obd2.app

import com.obd2.app.core.obd.DtcParser
import com.obd2.app.core.obd.Mode09Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcParserTest {

    @Test
    fun `encode P0300`() {
        val (b1, b2) = DtcParser.codeToBytes("P0300")
        assertEquals(0x03, b1)
        assertEquals(0x00, b2)
    }

    @Test
    fun `encode P0420`() {
        val (b1, b2) = DtcParser.codeToBytes("P0420")
        assertEquals(0x04, b1)
        assertEquals(0x20, b2)
    }

    @Test
    fun `encode U0100 uses U letter bits`() {
        val (b1, _) = DtcParser.codeToBytes("U0100")
        assertEquals(0xC1, b1)
    }

    @Test
    fun `roundtrip codes`() {
        listOf("P0300", "P0171", "P0420", "U0100", "C0035", "B0001", "P2096", "U3FFF").forEach { code ->
            val (b1, b2) = DtcParser.codeToBytes(code)
            assertEquals(code, DtcParser.bytesToCode(b1, b2))
        }
    }

    @Test
    fun `parse mode03 with count and padding (J1979 CAN format)`() {
        // CAN Mode 03: 43 <count> <pairs> <padding 00 00>; count-first per
        // J1979 / python-OBD ("remove the mode and DTC_count bytes").
        // 1 DTC P0133 + padding:
        val data = byteArrayOf(0x01, 0x01, 0x33, 0x00, 0x00, 0x00)
        val dtcs = DtcParser.parseDtcPayload(data, 3)
        assertEquals(listOf("P0133"), dtcs.map { it.code })
    }

    @Test
    fun `parse mode03 with count=0 and zero padding returns empty`() {
        // Explicit count=0 followed by zero padding must NOT invent codes
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val dtcs = DtcParser.parseDtcPayload(data, 3)
        assertEquals(emptyList<String>(), dtcs.map { it.code })
    }

    @Test
    fun `parse mode03 two codes`() {
        // 43 02 04 20 01 33 00 00 -> P0420, P0133
        val data = byteArrayOf(0x02, 0x04, 0x20, 0x01, 0x33, 0x00, 0x00)
        val dtcs = DtcParser.parseDtcPayload(data, 3)
        assertEquals(listOf("P0420", "P0133"), dtcs.map { it.code })
    }

    @Test
    fun `parse no codes`() {
        val dtcs = DtcParser.parseDtcPayload(byteArrayOf(0x00), 3)
        assertEquals(0, dtcs.size)
    }

    @Test
    fun `tolerates missing count byte`() {
        // Some clones send pairs directly: 03 00 01 71 -> P0300 P0171
        val data = byteArrayOf(0x03, 0x00, 0x01, 0x71)
        val dtcs = DtcParser.parseDtcPayload(data, 3)
        assertEquals(listOf("P0300", "P0171"), dtcs.map { it.code })
    }

    @Test
    fun `collectModeResponseBytes skips header token and handles wrapped lines`() {
        val lines = listOf("43 02 01 71 01", "74")
        val bytes = DtcParser.collectModeResponseBytes(lines, 0x43)
        // tokens after 43: 02 01 71 01 74
        assertEquals(5, bytes.size)
        val dtcs = DtcParser.parseDtcPayload(bytes, 3)
        assertEquals(listOf("P0171", "P0174"), dtcs.map { it.code })
    }

    @Test
    fun `vin extraction from hex stream`() {
        val vin = "TMBJJ7NE9C0123456"
        val lines = listOf("49 02 01 " + vin.toByteArray().joinToString(" ") { "%02X".format(it) })
        assertEquals(vin, Mode09Parser.extractVin(lines))
    }

    @Test
    fun `vin extraction rejects hex-only garbage without 17-char sequence`() {
        assertNull(Mode09Parser.extractVin(listOf("49 02 01 02 03 04")))
    }

    @Test
    fun `vin extraction from multi-message format`() {
        val vin = "WVWZZZ3CZWE569102"
        // split in three messages with indexes 01/02/03
        val b = vin.toByteArray()
        val l1 = "49 02 01 " + b.slice(0..6).joinToString(" ") { "%02X".format(it) }
        val l2 = "49 02 02 " + b.slice(7..13).joinToString(" ") { "%02X".format(it) }
        val l3 = "49 02 03 " + b.slice(14..16).joinToString(" ") { "%02X".format(it) }
        assertEquals(vin, Mode09Parser.extractVin(listOf(l1, l2, l3)))
    }
}
