package com.obd2.app

import android.bluetooth.BluetoothGattCharacteristic
import com.obd2.app.core.ble.GattInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Regression tests for the GATT characteristic selection core.
 *
 * Background: the field test showed the adapter connects via **IOS-Vlink**
 * (BLE). We never hardcode a UUID without on-device confirmation; instead the
 * selection is property-driven with ranked hints per known family
 * (Vgate FFF0 / 18F0+2AFx, HM-10 FFE0, Nordic UART). These tests pin the
 * property-driven behaviour so a hint change can never break property-based
 * selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GattInspectorLogicTest {

    private fun svc(uuid: String, vararg chars: GattInspector.CharSpec) =
        GattInspector.ServiceSpec("0000$uuid-0000-1000-8000-00805f9b34fb", chars.toList())

    private fun ch(uuid: String, props: Int, cccd: Boolean = false) =
        GattInspector.CharSpec("0000$uuid-0000-1000-8000-00805f9b34fb", props, cccd)

    private val W = BluetoothGattCharacteristic.PROPERTY_WRITE
    private val WN = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private val N = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val I = BluetoothGattCharacteristic.PROPERTY_INDICATE

    @Test
    fun vgateFff0FamilySelectsFff2WriteAndFff1Notify() {
        val picked = GattInspector.pickFromSpecs(
            listOf(
                svc(
                    "fff0",
                    ch("fff1", N, cccd = true),
                    ch("fff2", WN),
                ),
            ),
        )
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", picked!!.first)
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", picked.second)
    }

    @Test
    fun vgate18f0FamilySelects2af1WriteAnd2af0Notify() {
        val picked = GattInspector.pickFromSpecs(
            listOf(
                svc(
                    "18f0",
                    ch("2af0", N, cccd = true),
                    ch("2af1", WN),
                ),
            ),
        )
        assertTrue(picked!!.first.contains("2af1"))
        assertTrue(picked.second.contains("2af0"))
    }

    @Test
    fun hm10SingleCharacteristicServesBothRoles() {
        val picked = GattInspector.pickFromSpecs(
            listOf(svc("ffe0", ch("ffe1", W or N, cccd = true))),
        )
        assertEquals(picked!!.first, picked.second)
        assertTrue(picked.first.contains("ffe1"))
    }

    @Test
    fun nordicUartFamilyIsSupported() {
        val picked = GattInspector.pickFromSpecs(
            listOf(
                svc(
                    "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                    ch("6e400002", WN),
                    ch("6e400003", N, cccd = true),
                ),
            ),
        )
        assertTrue(picked!!.first.contains("6e400002"))
        assertTrue(picked.second.contains("6e400003"))
    }

    @Test
    fun unknownFamilyStillPicksPropertyValidPair() {
        val picked = GattInspector.pickFromSpecs(
            listOf(
                svc(
                    "abcd",
                    ch("abcd0002", W),
                    ch("abcd0003", N, cccd = true),
                ),
            ),
        )
        assertTrue(picked!!.first.contains("abcd0002"))
        assertTrue(picked.second.contains("abcd0003"))
    }

    @Test
    fun notifyWithCccdBeatsHintedNotifyWithoutCccd() {
        // FFF1 is a notify hint, but here it has no CCCD; an unhinted
        // characteristic with a CCCD must win for reliable notifications.
        val picked = GattInspector.pickFromSpecs(
            listOf(
                svc(
                    "fff0",
                    ch("fff1", N, cccd = false),
                    ch("aaa1", N, cccd = true),
                    ch("fff2", WN),
                ),
            ),
        )
        assertTrue("CCCD-backed notify must win: ${picked!!.second}", picked.second.contains("aaa1"))
    }

    @Test
    fun pairInSameServiceIsPreferred() {
        val picked = GattInspector.pickFromSpecs(
            listOf(
                svc(
                    "fff0",
                    ch("fff1", N, cccd = true),
                    ch("fff2", WN),
                ),
                svc(
                    "180a",
                    ch("2a37", N, cccd = true),
                    ch("2a39", W),
                ),
            ),
        )
        // Both pairs are property-valid; hinted FFF0 pair scores higher.
        assertTrue(picked!!.first.contains("fff2"))
        assertTrue(picked.second.contains("fff1"))
    }

    @Test
    fun noCandidatesReturnsNull() {
        assertNull(GattInspector.pickFromSpecs(listOf(svc("180f", ch("2a19", 0x02)))))
        assertNull(GattInspector.pickFromSpecs(emptyList()))
    }

    @Test
    fun writeTypeFollowsProperties() {
        val wn = BluetoothGattCharacteristic(UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"), WN, 0)
        val w = BluetoothGattCharacteristic(UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"), W, 0)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, GattInspector.writeTypeFor(wn))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, GattInspector.writeTypeFor(w))
    }

    @Test
    fun writeWithResponseSemantics() {
        assertTrue(GattInspector.isWriteWithResponse(W))
        assertFalse(GattInspector.isWriteWithResponse(WN))
        assertFalse("mixed props must prefer no-response fire&forget", GattInspector.isWriteWithResponse(W or WN))
    }

    @Test
    fun gattStatusTextExplainsKnownCodes() {
        assertTrue(GattInspector.gattStatusText(0).contains("SUCCESS"))
        assertTrue(GattInspector.gattStatusText(133).contains("133"))
        assertTrue(GattInspector.gattStatusText(8).contains("timeout"))
        assertTrue(GattInspector.gattStatusText(19).contains("remote"))
        assertEquals("42", GattInspector.gattStatusText(42))
    }
}
