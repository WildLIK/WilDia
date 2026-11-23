package com.obd2.app.core.diag

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * Phone-side facts for the diagnostic report (§3). Everything comes from the
 * public Android API; unavailable values are reported as such, never guessed.
 * No unique identifiers are collected here (privacy §13) — Build fields used
 * are manufacturer/model/release (not serials or Android IDs).
 */
object PhoneInfo {

    data class PhoneFacts(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val sdkInt: Int,
        val appVersionName: String,
        val appVersionCode: Long?,
        val bluetoothApiNote: String,
        val leAvailable: Boolean,
        val bluetoothState: String,
        val bleCapabilities: Map<String, Boolean>,
        val runtimePermissions: Map<String, Boolean>,
        val manifestPermissions: Map<String, Boolean>,
        val powerSaving: Map<String, String>,
        val bluetoothServiceAvailable: Boolean,
        val appForeground: Boolean,
    )

    fun collect(context: Context): PhoneFacts {
        val pm = context.packageManager
        val pkg = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = btManager?.adapter

        val capabilities = LinkedHashMap<String, Boolean>()
        if (adapter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { capabilities["multipleAdvertisement"] = adapter.isMultipleAdvertisementSupported }
            runCatching { capabilities["offloadedFiltering"] = adapter.isOffloadedFilteringSupported }
            runCatching { capabilities["offloadedScanBatching"] = adapter.isOffloadedScanBatchingSupported }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { capabilities["le2MPhy"] = adapter.isLe2MPhySupported }
                runCatching { capabilities["leCodedPhy"] = adapter.isLeCodedPhySupported }
                runCatching { capabilities["leExtendedAdvertising"] = adapter.isLeExtendedAdvertisingSupported }
                runCatching { capabilities["lePeriodicAdvertising"] = adapter.isLePeriodicAdvertisingSupported }
            }
        }

        val runtime = LinkedHashMap<String, Boolean>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runtime[Manifest.permission.BLUETOOTH_SCAN] =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            runtime[Manifest.permission.BLUETOOTH_CONNECT] =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            runtime[Manifest.permission.BLUETOOTH] =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            runtime[Manifest.permission.BLUETOOTH_ADMIN] =
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            runtime[Manifest.permission.ACCESS_FINE_LOCATION] =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            runtime[Manifest.permission.ACCESS_COARSE_LOCATION] =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        val wanted = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        )
        val manifest = LinkedHashMap<String, Boolean>()
        val requested = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toSet()
        }.getOrNull() ?: emptySet()
        wanted.forEach { manifest[it] = it in requested }

        val power = LinkedHashMap<String, String>()
        val pw = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pw != null) {
            power["globalPowerSaveMode"] = pw.isPowerSaveMode.toString()
            runCatching {
                power["ignoringBatteryOptimizations"] =
                    pw.isIgnoringBatteryOptimizations(context.packageName).toString()
            }
        } else {
            power["globalPowerSaveMode"] = "недоступно"
        }
        runCatching {
            val ai: ApplicationInfo = context.applicationInfo
            power["appBackgroundRestricted"] =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                    ai.javaClass.getField("isBackgroundRestricted").get(ai).toString()
                }.getOrDefault("недоступно") else "недоступно (API < 28)"
        }

        val btState = try {
            when (adapter?.state) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> "ON"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                android.bluetooth.BluetoothAdapter.STATE_OFF -> "OFF"
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                null -> "адаптер недоступен"
                else -> "${adapter.state}"
            }
        } catch (se: SecurityException) {
            "нет разрешения (SecurityException: ${se.message})"
        }

        val foreground = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val proc = am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
            proc?.importance?.let { it <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
        }.getOrNull() == true

        val oneUi = oneUiVersion()
        val androidVerText = if (oneUi != "недоступно") {
            "${Build.VERSION.RELEASE} (One UI $oneUi)"
        } else {
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        val locDisabled = com.obd2.app.core.ble.BleScanner(context).isLocationServiceRequiredAndDisabled()
        power["locationService"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "не требуется (API ${Build.VERSION.SDK_INT})"
        } else if (locDisabled) {
            "OFF (требуется для BLE на API <= 30)"
        } else {
            "ON"
        }

        return PhoneFacts(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = androidVerText,
            sdkInt = Build.VERSION.SDK_INT,
            appVersionName = pkg?.versionName ?: "?",
            appVersionCode = pkg?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            },
            // The public API does not expose a "Bluetooth stack version" — say so.
            bluetoothApiNote = "Версия BT-стека публичным API недоступна; SDK ${Build.VERSION.SDK_INT}, " +
                "BLE API: BluetoothLeScanner/BluetoothGatt",
            leAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            bluetoothState = btState,
            bleCapabilities = capabilities,
            runtimePermissions = runtime,
            manifestPermissions = manifest,
            powerSaving = power,
            bluetoothServiceAvailable = btManager != null,
            appForeground = foreground,
        )
    }

    private fun oneUiVersion(): String = runCatching {
        // One UI exposes its version via a Samsung system property; hidden API,
        // so reflection with a graceful fallback (never crash the diagnostics).
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java)
        val v = (get.invoke(null, "ro.build.version.oneui") as? String)?.toIntOrNull()
        if (v != null && v > 0) "${v / 10000}.${(v / 100) % 100}" else "недоступно"
    }.getOrDefault("недоступно")
}
