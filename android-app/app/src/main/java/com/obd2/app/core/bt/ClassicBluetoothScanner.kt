package com.obd2.app.core.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.obd2.app.core.ble.BleScanner
import com.obd2.app.core.ble.GattInspector
import com.obd2.app.core.log.RawLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Bluetooth Classic (BR/EDR) discovery and SDP UUID probing across Android 8–16 (API 26–36).
 *
 * Uses [BleScanner.missingPermissionsForSdk] and [GattInspector.hasConnectPermission] so
 * legacy headunits (API 26–30) and Android 12–16 (API 31–36) permission rules are enforced
 * before calling any [BluetoothAdapter] method. Never assumes SPP by device name.
 */
@SuppressLint("MissingPermission")
open class ClassicBluetoothScanner(
    private val context: Context,
    private val log: RawLog? = null,
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    data class SdpProbeResult(
        val status: SppProfileStatus,
        val uuids: List<String>,
        val deviceType: Int,
        val bondState: Int,
        val fromCache: Boolean,
        val error: String? = null,
    )

    open fun missingPermissions(): List<String> =
        BleScanner.missingPermissionsForSdk(Build.VERSION.SDK_INT) { perm ->
            ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    open fun hasConnectPermission(): Boolean =
        GattInspector.hasConnectPermission(context, Build.VERSION.SDK_INT)

    open fun isLocationServiceRequiredAndDisabled(): Boolean =
        BleScanner(context, null).isLocationServiceRequiredAndDisabled(Build.VERSION.SDK_INT)

    open fun cancelDiscoveryIfActive() {
        val a = adapter ?: return
        if (!hasConnectPermission() && missingPermissions().isNotEmpty()) return
        try {
            if (a.isDiscovering) {
                a.cancelDiscovery()
                log?.log(RawLog.Dir.INFO, "[CLASSIC] active discovery cancelled")
            }
        } catch (se: SecurityException) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] cancelDiscovery SecurityException: ${se.message}")
        } catch (_: Exception) {
        }
    }

    /** Reads paired/bonded devices passively without triggering discovery. */
    open fun bondedDevices(): List<ClassicDeviceInfo> {
        if (!hasConnectPermission()) {
            log?.log(RawLog.Dir.INFO, "[CLASSIC] bondedDevices skipped: connect permission missing")
            return emptyList()
        }
        val a = adapter ?: return emptyList()
        val bonded = try {
            a.bondedDevices.orEmpty()
        } catch (se: SecurityException) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] bondedDevices SecurityException: ${se.message}")
            return emptyList()
        }
        return bonded.mapNotNull { dev -> deviceToInfo(dev, rssi = null, source = "bonded", overrideUuids = null) }
            .also { list ->
                log?.log(RawLog.Dir.INFO, "[CLASSIC] bondedDevices count=${list.size}")
            }
    }

    /**
     * Runs Bluetooth Classic inquiry via [BluetoothAdapter.startDiscovery].
     * Registers receivers with [ContextCompat.RECEIVER_EXPORTED] for Android 13–16 compatibility.
     */
    open fun discover(scanSeconds: Int = 10): Flow<ClassicDeviceInfo> = callbackFlow {
        val perms = missingPermissions()
        if (perms.isNotEmpty()) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] discover: missing permissions ${perms.joinToString()}")
            close(IllegalStateException("Нет разрешений Bluetooth: ${perms.joinToString()}"))
            return@callbackFlow
        }
        val a = adapter
        val enabled = try {
            a != null && a.isEnabled
        } catch (se: SecurityException) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] discover isEnabled SecurityException: ${se.message}")
            close(IllegalStateException("Нет системного разрешения Bluetooth: ${se.message}"))
            return@callbackFlow
        }
        if (a == null || !enabled) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] discover: Bluetooth off/unavailable")
            close(IllegalStateException("Bluetooth выключен или недоступен"))
            return@callbackFlow
        }
        if (isLocationServiceRequiredAndDisabled()) {
            log?.log(
                RawLog.Dir.INFO,
                "[CLASSIC] discover warning: system Location service is OFF on SDK ${Build.VERSION.SDK_INT} — Classic discovery may miss devices",
            )
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        ) ?: return
                        val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        val rssi = if (rssiShort == Short.MIN_VALUE) null else rssiShort.toInt()
                        val info = deviceToInfo(dev, rssi, source = "discovery", overrideUuids = null) ?: return
                        log?.log(
                            RawLog.Dir.INFO,
                            "[CLASSIC] found: name='${info.name}' mac=${info.mac} type=${TransportResolver.deviceTypeLabel(info.deviceType)} " +
                                "bond=${info.bondState} rssi=$rssi spp=${info.sppStatus.name} uuids=${info.sdpUuids}",
                        )
                        trySend(info)
                    }

                    BluetoothDevice.ACTION_UUID -> {
                        val dev = IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        ) ?: return
                        val uuids = extractIntentUuids(intent)
                        val info = deviceToInfo(dev, rssi = null, source = "sdp", overrideUuids = uuids) ?: return
                        log?.log(
                            RawLog.Dir.INFO,
                            "[CLASSIC] ACTION_UUID: mac=${info.mac} spp=${info.sppStatus.name} uuids=${info.sdpUuids}",
                        )
                        trySend(info)
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        log?.log(RawLog.Dir.INFO, "[CLASSIC] discovery finished broadcast")
                        close()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        cancelDiscoveryIfActive()
        val started = try {
            a.startDiscovery()
        } catch (se: SecurityException) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] startDiscovery SecurityException: ${se.message}")
            false
        } catch (e: Exception) {
            log?.log(RawLog.Dir.ERROR, "[CLASSIC] startDiscovery error: ${e.message}")
            false
        }
        if (!started) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            close(IllegalStateException("Не удалось запустить поиск Bluetooth Classic"))
            return@callbackFlow
        }
        log?.log(RawLog.Dir.INFO, "[CLASSIC] discovery started (${scanSeconds}s)")

        val timeoutJob = launch {
            delay(scanSeconds * 1000L)
            log?.log(RawLog.Dir.INFO, "[CLASSIC] discovery timeout reached (${scanSeconds}s)")
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            cancelDiscoveryIfActive()
            log?.log(RawLog.Dir.INFO, "[CLASSIC] discovery stopped")
        }
    }

    /**
     * Queries SDP UUIDs for [mac] to confirm whether SPP (00001101) is present.
     * Checks cached UUIDs first; if empty and device is not LE-only, triggers [BluetoothDevice.fetchUuidsWithSdp].
     */
    open suspend fun probeSdpUuids(mac: String, timeoutMs: Long = 4500L): SdpProbeResult {
        if (!hasConnectPermission()) {
            return SdpProbeResult(
                status = SppProfileStatus.UNCONFIRMED,
                uuids = emptyList(),
                deviceType = TransportResolver.DEVICE_TYPE_UNKNOWN,
                bondState = BluetoothDevice.BOND_NONE,
                fromCache = false,
                error = "Нет разрешения BLUETOOTH_CONNECT/BLUETOOTH для проверки SDP",
            )
        }
        val a = adapter ?: return SdpProbeResult(
            status = SppProfileStatus.UNCONFIRMED,
            uuids = emptyList(),
            deviceType = TransportResolver.DEVICE_TYPE_UNKNOWN,
            bondState = BluetoothDevice.BOND_NONE,
            fromCache = false,
            error = "Bluetooth-адаптер недоступен",
        )
        val dev = try {
            a.getRemoteDevice(mac)
        } catch (e: Exception) {
            return SdpProbeResult(
                status = SppProfileStatus.UNCONFIRMED,
                uuids = emptyList(),
                deviceType = TransportResolver.DEVICE_TYPE_UNKNOWN,
                bondState = BluetoothDevice.BOND_NONE,
                fromCache = false,
                error = "Некорректный MAC-адрес: ${e.message}",
            )
        }
        val devType = runCatching { dev.type }.getOrDefault(TransportResolver.DEVICE_TYPE_UNKNOWN)
        val bondState = runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        val cached = runCatching { dev.uuids?.map { it.uuid.toString().lowercase() }.orEmpty() }.getOrDefault(emptyList())
        if (devType == TransportResolver.DEVICE_TYPE_LE) {
            return SdpProbeResult(SppProfileStatus.NOT_APPLICABLE, cached, devType, bondState, fromCache = true)
        }
        if (cached.isNotEmpty()) {
            val st = TransportResolver.classifySppFromUuids(cached, devType)
            return SdpProbeResult(st, cached, devType, bondState, fromCache = true)
        }

        val fetched = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<List<String>> { cont ->
                var done = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != BluetoothDevice.ACTION_UUID) return
                        val d = IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        ) ?: return
                        if (!d.address.equals(mac, ignoreCase = true)) return
                        if (done) return
                        done = true
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) cont.resume(extractIntentUuids(intent))
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                cont.invokeOnCancellation {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                }
                val ok = try {
                    dev.fetchUuidsWithSdp()
                } catch (se: SecurityException) {
                    log?.log(RawLog.Dir.ERROR, "[CLASSIC] fetchUuidsWithSdp SecurityException: ${se.message}")
                    false
                } catch (_: Exception) {
                    false
                }
                if (!ok && !done) {
                    done = true
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        } ?: emptyList()

        val status = TransportResolver.classifySppFromUuids(fetched, devType)
        log?.log(
            RawLog.Dir.INFO,
            "[CLASSIC] probeSdpUuids($mac): type=${TransportResolver.deviceTypeLabel(devType)} bond=$bondState " +
                "status=${status.name} uuids=$fetched",
        )
        return SdpProbeResult(status, fetched, devType, bondState, fromCache = false)
    }

    private fun deviceToInfo(
        dev: BluetoothDevice,
        rssi: Int?,
        source: String,
        overrideUuids: List<String>?,
    ): ClassicDeviceInfo? {
        val mac = dev.address ?: return null
        val name = runCatching { dev.name }.getOrNull().orEmpty()
        val type = runCatching { dev.type }.getOrDefault(TransportResolver.DEVICE_TYPE_UNKNOWN)
        val bond = runCatching { dev.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        val devClass = runCatching { dev.bluetoothClass?.deviceClass }.getOrNull()
        val uuids = overrideUuids ?: runCatching {
            dev.uuids?.map { it.uuid.toString().lowercase() }.orEmpty()
        }.getOrDefault(emptyList())
        val spp = TransportResolver.classifySppFromUuids(uuids, type)
        return ClassicDeviceInfo(
            mac = mac,
            name = name,
            rssi = rssi,
            deviceType = type,
            bondState = bond,
            deviceClass = devClass,
            sdpUuids = uuids,
            sppStatus = spp,
            source = source,
        )
    }

    private fun extractIntentUuids(intent: Intent): List<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
        }
        return raw.orEmpty().mapNotNull { (it as? ParcelUuid)?.uuid?.toString()?.lowercase() }
    }
}
