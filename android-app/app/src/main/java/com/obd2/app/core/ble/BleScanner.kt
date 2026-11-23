package com.obd2.app.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.obd2.app.core.log.RawLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** One BLE advertising sighting (a device may be seen many times). */
data class BleDevice(
    val mac: String,
    val name: String,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerDataHex: String? = null,
    val serviceDataHex: Map<String, String> = emptyMap(),
    val advFlags: Int? = null,
    val connectable: Boolean? = null,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * BLE scanner with Android permission checks across API 26–35.
 * Does not hardcode adapter names as requirements — it surfaces everything it
 * can see and marks likely OBD adapters (Vgate/Vlink/OBDII/ELM) in UI.
 *
 * The flow self-terminates after [scanSeconds] with a normal completion
 * (stopping a scan is NOT an error; see ObdViewModel scan lifecycle).
 */
@SuppressLint("MissingPermission") // all Bluetooth calls are gated & caught cleanly below
open class BleScanner(
    private val context: Context,
    private val log: RawLog? = null,
) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    /** Missing permissions (both runtime and install-time legacy) for this Android version. */
    open fun missingPermissions(): List<String> =
        missingPermissionsForSdk(Build.VERSION.SDK_INT) { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    /** Missing dangerous runtime permissions only (safe to pass to ActivityResultContracts). */
    open fun missingRuntimePermissions(): List<String> =
        missingRuntimePermissionsForSdk(Build.VERSION.SDK_INT) { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    open fun permissionStateText(): String =
        permissionStateTextForSdk(Build.VERSION.SDK_INT, isLocationEnabled = !isLocationServiceRequiredAndDisabled()) { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    fun hasBluetooth(): Boolean = adapter != null

    fun hasBleFeature(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    open fun isBluetoothEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (se: SecurityException) {
        log?.log(
            RawLog.Dir.ERROR,
            "[BLE] isBluetoothEnabled SecurityException (SDK=${Build.VERSION.SDK_INT}): ${se.message}",
        )
        false
    }

    open fun isLocationServiceRequiredAndDisabled(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt >= Build.VERSION_CODES.S) return false
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm?.isLocationEnabled == false
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF,
                ) == Settings.Secure.LOCATION_MODE_OFF
            }
        }.getOrDefault(false)
    }

    /** Scan stream; emits each found/advertising update. Filters by optional name part. */
    open fun scan(nameFilter: String? = null, scanSeconds: Int = 12): Flow<BleDevice> = callbackFlow {
        // Check permissions BEFORE touching BluetoothAdapter.isEnabled (on API <= 30
        // or custom headunit ROMs even isEnabled() throws SecurityException without BLUETOOTH).
        val perms = missingPermissions()
        if (perms.isNotEmpty()) {
            log?.log(
                RawLog.Dir.ERROR,
                "[BLE] scan: missing permissions ${perms.joinToString()}; ${permissionStateText()}",
            )
            close(IllegalStateException("Нет разрешений Bluetooth: ${perms.joinToString()}"))
            return@callbackFlow
        }
        val a = adapter
        val enabled = try {
            a != null && a.isEnabled
        } catch (se: SecurityException) {
            log?.log(
                RawLog.Dir.ERROR,
                "[BLE] scan: SecurityException on adapter.isEnabled (SDK=${Build.VERSION.SDK_INT}): ${se.message}",
            )
            close(IllegalStateException("Нет системного разрешения Bluetooth (SDK ${Build.VERSION.SDK_INT}): ${se.message}"))
            return@callbackFlow
        }
        if (a == null || !enabled) {
            log?.log(RawLog.Dir.ERROR, "[BLE] scan: bluetooth off/missing (hasAdapter=${a != null}, bleFeature=${hasBleFeature()})")
            close(IllegalStateException("Bluetooth выключен или недоступен"))
            return@callbackFlow
        }
        if (isLocationServiceRequiredAndDisabled()) {
            log?.log(
                RawLog.Dir.INFO,
                "[BLE] scan warning: system Location service is OFF on SDK ${Build.VERSION.SDK_INT} — BLE results may be empty",
            )
        }
        val scanner = try {
            a.bluetoothLeScanner
        } catch (se: SecurityException) {
            log?.log(RawLog.Dir.ERROR, "[BLE] scan: SecurityException getting scanner: ${se.message}")
            close(IllegalStateException("Ошибка доступа к BLE-сканеру: ${se.message}"))
            return@callbackFlow
        } ?: run {
            log?.log(RawLog.Dir.ERROR, "[BLE] scan: BLE scanner unavailable (adapter on?)")
            close(IllegalStateException("BLE-сканер недоступен (возможно, устройство не поддерживает BLE)"))
            return@callbackFlow
        }
        log?.log(RawLog.Dir.INFO, "[BLE] scan start (filter=$nameFilter, ${scanSeconds}s); ${permissionStateText()}")

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val extAdvSupported = runCatching { a.isLeExtendedAdvertisingSupported }.getOrDefault(false)
            if (extAdvSupported) {
                settingsBuilder.setLegacy(false)
            }
        }
        val settings = settingsBuilder.build()
        val filters = ArrayList<ScanFilter>()
        if (!nameFilter.isNullOrBlank()) {
            filters.add(ScanFilter.Builder().setDeviceName(nameFilter).build())
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                val devName = runCatching { result.device.name }.getOrNull()
                val name = rec?.deviceName ?: devName ?: ""
                val mac = result.device.address ?: return
                val uuids = rec?.serviceUuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
                // Manufacturer-specific data: SparseArray<companyId, bytes>.
                val manuf = StringBuilder()
                rec?.manufacturerSpecificData?.let { arr ->
                    for (i in 0 until arr.size()) {
                        val key = arr.keyAt(i)
                        val bytes = arr.valueAt(i)
                        if (bytes != null && bytes.isNotEmpty()) {
                            manuf.append("0x").append(Integer.toHexString(key)).append(":")
                                .append(bytes.toHex()).append(" ")
                        }
                    }
                }
                val serviceData = LinkedHashMap<String, String>()
                rec?.serviceData?.forEach { (uuid, bytes) ->
                    if (bytes != null && bytes.isNotEmpty()) {
                        serviceData[uuid.toString().lowercase()] = bytes.toHex()
                    }
                }
                val isConn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { result.isConnectable }.getOrNull()
                } else {
                    null
                }
                val device = BleDevice(
                    mac = mac,
                    name = name,
                    rssi = result.rssi,
                    serviceUuids = uuids,
                    manufacturerDataHex = manuf.toString().trim().ifEmpty { null },
                    serviceDataHex = serviceData,
                    advFlags = rec?.advertiseFlags,
                    connectable = isConn,
                    ts = System.currentTimeMillis(),
                )
                log?.log(
                    RawLog.Dir.INFO,
                    "[BLE] scan sighting: name='$name' mac=$mac rssi=${result.rssi} " +
                        "advUuids=$uuids flags=${device.advFlags} connectable=$isConn manuf=${device.manufacturerDataHex ?: "-"}",
                )
                trySend(device)
            }

            override fun onScanFailed(errorCode: Int) {
                log?.log(RawLog.Dir.ERROR, "[BLE] scan failed: ${scanErrorText(errorCode)}")
                close(IllegalStateException("Ошибка сканирования: ${scanErrorText(errorCode)}"))
            }
        }
        try {
            if (filters.isEmpty()) {
                runCatching { scanner.startScan(null, settings, callback) }
                    .getOrElse { scanner.startScan(callback) }
            } else {
                scanner.startScan(filters, settings, callback)
            }
        } catch (se: SecurityException) {
            log?.log(
                RawLog.Dir.ERROR,
                "[BLE] startScan SecurityException (SDK=${Build.VERSION.SDK_INT}): ${se.message}",
            )
            close(IllegalStateException("Ошибка доступа при запуске BLE-сканирования (SDK ${Build.VERSION.SDK_INT}): ${se.message}"))
            return@callbackFlow
        } catch (e: Exception) {
            log?.log(RawLog.Dir.ERROR, "[BLE] startScan failed: ${e.message}")
            close(IllegalStateException("Не удалось запустить BLE-сканирование: ${e.message}"))
            return@callbackFlow
        }

        // Self-terminate after scanSeconds with NORMAL completion (not an error).
        val timeoutJob = launch {
            kotlinx.coroutines.delay(scanSeconds * 1000L)
            log?.log(RawLog.Dir.INFO, "[BLE] scan timeout reached — normal stop")
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            log?.log(RawLog.Dir.INFO, "[BLE] scan stopped")
        }
    }

    companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

        fun requiredRuntimePermissionsForSdk(sdkInt: Int): List<String> =
            if (sdkInt >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun requiredLegacyPermissionsForSdk(sdkInt: Int): List<String> =
            if (sdkInt < Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
            } else {
                emptyList()
            }

        fun missingRuntimePermissionsForSdk(sdkInt: Int, isGranted: (String) -> Boolean): List<String> {
            if (sdkInt >= Build.VERSION_CODES.S) {
                return buildList {
                    if (!isGranted(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
                    if (!isGranted(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            // Android 10-11 (API 29-30) requires ACCESS_FINE_LOCATION; Android 8-9 (API 26-28) accepts FINE or COARSE.
            val fine = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            return if (sdkInt >= Build.VERSION_CODES.Q) {
                if (fine) emptyList() else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                if (fine || coarse) emptyList() else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        fun missingPermissionsForSdk(sdkInt: Int, isGranted: (String) -> Boolean): List<String> {
            val missing = ArrayList<String>()
            if (sdkInt < Build.VERSION_CODES.S) {
                if (!isGranted(Manifest.permission.BLUETOOTH)) missing.add(Manifest.permission.BLUETOOTH)
                if (!isGranted(Manifest.permission.BLUETOOTH_ADMIN)) missing.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            missing.addAll(missingRuntimePermissionsForSdk(sdkInt, isGranted))
            return missing
        }

        fun permissionStateTextForSdk(
            sdkInt: Int,
            isLocationEnabled: Boolean = true,
            isGranted: (String) -> Boolean,
        ): String {
            fun flag(p: String) = if (isGranted(p)) "granted" else "denied"
            return if (sdkInt >= Build.VERSION_CODES.S) {
                "SDK $sdkInt: BLUETOOTH_SCAN=${flag(Manifest.permission.BLUETOOTH_SCAN)}, " +
                    "BLUETOOTH_CONNECT=${flag(Manifest.permission.BLUETOOTH_CONNECT)}, " +
                    "legacy_BT=${flag(Manifest.permission.BLUETOOTH)}"
            } else {
                "SDK $sdkInt: BLUETOOTH=${flag(Manifest.permission.BLUETOOTH)}, " +
                    "BLUETOOTH_ADMIN=${flag(Manifest.permission.BLUETOOTH_ADMIN)}, " +
                    "ACCESS_FINE_LOCATION=${flag(Manifest.permission.ACCESS_FINE_LOCATION)}, " +
                    "ACCESS_COARSE_LOCATION=${flag(Manifest.permission.ACCESS_COARSE_LOCATION)}, " +
                    "locationService=${if (isLocationEnabled) "on" else "OFF"}"
            }
        }

        /** Heuristic name check used by UI to badge likely OBD adapters. */
        fun looksLikeObd(name: String): Boolean {
            val n = name.uppercase()
            return listOf("VLINK", "VGATE", "OBD", "ELM", "ICAR", "VEEPEAK", "KONNWEI", "ANCEL", "IEVA").any { n.contains(it) }
        }

        /** Human-readable ScanCallback error codes. */
        fun scanErrorText(code: Int): String = when (code) {
            1 -> "1 (уже запущено другое сканирование)"
            2 -> "2 (ошибка регистрации приложения в стеке)"
            3 -> "3 (внутренняя ошибка Android)"
            4 -> "4 (проверка разрешений не пройдена системой)"
            5 -> "5 (сканирование вне режима окна/фоновое ограничение)"
            6 -> "6 (внутренняя ошибка стека)"
            else -> "код $code"
        }
    }
}
