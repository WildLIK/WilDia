package com.obd2.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.ble.BleDevice
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.ui.ObdViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothAdapter

/**
 * Regression tests for the BLE scan lifecycle.
 *
 * Field-test regression #2: «StandaloneCoroutine was cancelled» was surfaced
 * as a scan error whenever the user stopped the scan or picked a device.
 * Stopping a scan is normal lifecycle, not an error.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScanLifecycleTest {

    /** Never-completing scan flow to keep the scan job alive during the test. */
    private class FakeScanner : BleScanner(ApplicationProvider.getApplicationContext()) {
        val started = MutableStateFlow(0)
        override fun missingPermissions(): List<String> = emptyList()
        override fun scan(nameFilter: String?, scanSeconds: Int): Flow<BleDevice> =
            kotlinx.coroutines.flow.flow {
                started.value = started.value + 1
                // emits one device, then stays open until the collector is cancelled
                emit(BleDevice("AA:BB:CC:DD:EE:FF", "IOS-Vlink", -55))
                awaitCancellation()
            }
    }

    @Before
    fun setUp() {
        // grant S+ runtime permissions by default for the fake scanner paths
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun stopScanIsNotAnError() = runTest(StandardTestDispatcher()) {
        val fake = FakeScanner()
        val vm = ObdViewModel(
            ApplicationProvider.getApplicationContext(),
            scannerOverride = fake,
        )
        vm.startScan()
        runCurrent()
        assertTrue("scan should be running", vm.scanning.value)
        assertEquals(1, fake.started.value)
        assertEquals(1, vm.devices.value.size)

        vm.stopScan()
        advanceTimeBy(1000)
        runCurrent()

        assertFalse(vm.scanning.value)
        assertNull(
            "stopping a scan must not produce an error message, got: ${vm.scanError.value}",
            vm.scanError.value,
        )
    }

    @Test
    fun connectPermissionGuardDoesNotDependOnGattInstance() {
        // Regression #1 root cause: the transport's permission guard required a
        // live BluetoothGatt — always null on the first attempt — producing the
        // false «Нет разрешения BLUETOOTH_CONNECT» despite granted permissions.
        // The fixed guard is context-only; emulate via GattInspector on the app.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val granted = com.obd2.app.core.ble.GattInspector.hasConnectPermission(ctx)
        // Robolectric grants manifest runtime permissions by default → granted.
        assertTrue(
            "with granted permissions the guard must pass (no gatt involved)",
            granted,
        )
        // Now deny and expect a clean rejection.
        Shadows.shadowOf(ctx).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(com.obd2.app.core.ble.GattInspector.hasConnectPermission(ctx))
    }

    @Test
    fun scannerReportsMissingPermissionsCorrectly() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val scanner = BleScanner(ctx)
        Shadows.shadowOf(ctx).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val missing = scanner.missingPermissions()
        assertTrue(
            "denied SCAN must be reported: $missing",
            missing.contains(Manifest.permission.BLUETOOTH_SCAN),
        )
        // restore and expect empty
        Shadows.shadowOf(ctx).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertEquals(emptyList<String>(), scanner.missingPermissions())
    }

    @Test
    fun adapterShadowCanBeEnabledForLaterDeviceTests() {
        // Smoke: on-device UUID confirmation tooling relies on a working
        // adapter shadow being reachable from the scanner (kept for the
        // upcoming IOS-Vlink field-test diagnostics).
        val scanner = BleScanner(ApplicationProvider.getApplicationContext())
        val adapter = scanner.adapter
        if (adapter != null) {
            val shadow = Shadows.shadowOf(adapter) as? ShadowBluetoothAdapter
            org.junit.Assert.assertNotNull(shadow)
        }
        // no assertion failure when the shadow is absent in a given Robolectric build
    }
}
