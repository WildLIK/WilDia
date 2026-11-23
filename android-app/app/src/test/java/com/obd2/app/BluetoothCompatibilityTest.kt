package com.obd2.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.ble.GattInspector
import com.obd2.app.core.diag.PhoneInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression tests for Bluetooth / BLE permission & API compatibility across
 * Android API levels (API 26–35: headunits such as URAL AMS STORM 102 on
 * Android 9–11 as well as modern phones such as Galaxy S23+ on Android 15).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BluetoothCompatibilityTest {

    @Test
    fun mergedManifestDeclaresLegacyAndModernPermissions() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()

        // Legacy permissions required on API <= 30 & custom headunit ROMs
        assertTrue("Manifest must declare BLUETOOTH", requested.contains(Manifest.permission.BLUETOOTH))
        assertTrue("Manifest must declare BLUETOOTH_ADMIN", requested.contains(Manifest.permission.BLUETOOTH_ADMIN))
        // Modern Android 12+ (API 31+) BLE permissions
        assertTrue("Manifest must declare BLUETOOTH_SCAN", requested.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue("Manifest must declare BLUETOOTH_CONNECT", requested.contains(Manifest.permission.BLUETOOTH_CONNECT))
        // On Robolectric SDK 34 PackageManager filters maxSdkVersion="30" permissions; verify them explicitly on SDK 30 below
    }

    @Test
    @Config(sdk = [30])
    fun mergedManifestIncludesLocationPermissionsOnApi30() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertTrue("Manifest must declare BLUETOOTH on API 30", requested.contains(Manifest.permission.BLUETOOTH))
        assertTrue("Manifest must declare BLUETOOTH_ADMIN on API 30", requested.contains(Manifest.permission.BLUETOOTH_ADMIN))
        assertTrue("Manifest must declare ACCESS_FINE_LOCATION on API 30", requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue("Manifest must declare ACCESS_COARSE_LOCATION on API 30", requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test
    fun sdkMatrixPermissionRequirementsMatchAndroidContracts() {
        val allGranted: (String) -> Boolean = { true }
        val noneGranted: (String) -> Boolean = { false }

        // API 26..28: requires BLUETOOTH + BLUETOOTH_ADMIN + (FINE or COARSE location)
        for (sdk in 26..28) {
            assertEquals(emptyList<String>(), BleScanner.missingPermissionsForSdk(sdk, allGranted))
            val missingAll = BleScanner.missingPermissionsForSdk(sdk, noneGranted)
            assertTrue("SDK $sdk requires BLUETOOTH", missingAll.contains(Manifest.permission.BLUETOOTH))
            assertTrue("SDK $sdk requires BLUETOOTH_ADMIN", missingAll.contains(Manifest.permission.BLUETOOTH_ADMIN))
            assertTrue("SDK $sdk requires location", missingAll.contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertFalse("SDK $sdk must not ask BLUETOOTH_SCAN", missingAll.contains(Manifest.permission.BLUETOOTH_SCAN))
            assertFalse("SDK $sdk must not ask BLUETOOTH_CONNECT", missingAll.contains(Manifest.permission.BLUETOOTH_CONNECT))

            // With BLUETOOTH + BLUETOOTH_ADMIN + COARSE only, API 26-28 must pass
            val coarseOnly: (String) -> Boolean = { p ->
                p == Manifest.permission.BLUETOOTH ||
                    p == Manifest.permission.BLUETOOTH_ADMIN ||
                    p == Manifest.permission.ACCESS_COARSE_LOCATION
            }
            assertEquals(emptyList<String>(), BleScanner.missingPermissionsForSdk(sdk, coarseOnly))
        }

        // API 29..30 (Android 10-11): requires BLUETOOTH + BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION specifically
        for (sdk in 29..30) {
            val coarseOnly: (String) -> Boolean = { p ->
                p == Manifest.permission.BLUETOOTH ||
                    p == Manifest.permission.BLUETOOTH_ADMIN ||
                    p == Manifest.permission.ACCESS_COARSE_LOCATION
            }
            assertEquals(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                BleScanner.missingPermissionsForSdk(sdk, coarseOnly),
            )
            // Missing legacy BLUETOOTH must be caught cleanly before calling Android BT APIs
            val fineWithoutBt: (String) -> Boolean = { p -> p == Manifest.permission.ACCESS_FINE_LOCATION }
            val missing = BleScanner.missingPermissionsForSdk(sdk, fineWithoutBt)
            assertTrue(missing.contains(Manifest.permission.BLUETOOTH))
            assertTrue(missing.contains(Manifest.permission.BLUETOOTH_ADMIN))
            // Runtime launcher list must NOT include install-time BLUETOOTH permissions
            assertEquals(emptyList<String>(), BleScanner.missingRuntimePermissionsForSdk(sdk) {
                p -> p == Manifest.permission.ACCESS_FINE_LOCATION
            })
        }

        // API 31..35 (Android 12-15): requires BLUETOOTH_SCAN + BLUETOOTH_CONNECT; no location required
        for (sdk in 31..35) {
            val missingAll = BleScanner.missingPermissionsForSdk(sdk, noneGranted)
            assertEquals(
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                missingAll,
            )
            val modernOnly: (String) -> Boolean = { p ->
                p == Manifest.permission.BLUETOOTH_SCAN || p == Manifest.permission.BLUETOOTH_CONNECT
            }
            assertEquals(emptyList<String>(), BleScanner.missingPermissionsForSdk(sdk, modernOnly))
        }
    }

    @Test
    fun gattConnectPermissionChecksLegacyBluetoothOnApiUnder31AndBluetoothConnectOn31Plus() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = Shadows.shadowOf(ctx)

        // Legacy API (28 / 30): must require Manifest.permission.BLUETOOTH
        shadowApp.grantPermissions(Manifest.permission.BLUETOOTH)
        assertTrue(GattInspector.hasConnectPermission(ctx, Build.VERSION_CODES.P))
        assertTrue(GattInspector.hasConnectPermission(ctx, Build.VERSION_CODES.R))

        shadowApp.denyPermissions(Manifest.permission.BLUETOOTH)
        assertFalse(GattInspector.hasConnectPermission(ctx, Build.VERSION_CODES.P))
        assertFalse(GattInspector.hasConnectPermission(ctx, Build.VERSION_CODES.R))

        // Modern API (31 / 34): must require Manifest.permission.BLUETOOTH_CONNECT
        shadowApp.grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(GattInspector.hasConnectPermission(ctx, Build.VERSION_CODES.S))
        assertTrue(GattInspector.hasConnectPermission(ctx, 34))

        shadowApp.denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(GattInspector.hasConnectPermission(ctx, Build.VERSION_CODES.S))
    }

    @Test
    fun phoneInfoAndScannerHandleDeniedPermissionsWithoutSecurityException() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = Shadows.shadowOf(ctx)
        shadowApp.denyPermissions(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val scanner = BleScanner(ctx)
        // Must not throw SecurityException
        scanner.isBluetoothEnabled()
        val facts = PhoneInfo.collect(ctx)
        assertTrue(facts.manifestPermissions[Manifest.permission.BLUETOOTH] == true)
        assertTrue(facts.manifestPermissions[Manifest.permission.BLUETOOTH_ADMIN] == true)
        assertTrue(facts.manifestPermissions[Manifest.permission.BLUETOOTH_SCAN] == true)
        assertTrue(facts.manifestPermissions[Manifest.permission.BLUETOOTH_CONNECT] == true)
    }

    @Test
    @Config(sdk = [28])
    fun legacyApi28ManifestAndScannerWorkInRobolectric() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(ctx).grantPermissions(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val scanner = BleScanner(ctx)
        assertEquals(emptyList<String>(), scanner.missingPermissions())
        assertTrue(GattInspector.hasConnectPermission(ctx))
    }

    @Test
    @Config(sdk = [30])
    fun legacyApi30ManifestAndScannerWorkInRobolectric() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(ctx).grantPermissions(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val scanner = BleScanner(ctx)
        assertEquals(emptyList<String>(), scanner.missingPermissions())
        assertTrue(GattInspector.hasConnectPermission(ctx))
    }
}
