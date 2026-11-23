package com.obd2.app.core.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Serializable dump of a GATT tree (adapter research screen + report). */
data class GattSnapshot(
    val services: List<GattServiceInfo>,
    val mtu: Int,
    val selectedWriteUuid: String?,
    val selectedNotifyUuid: String?,
) {
    data class GattServiceInfo(
        val uuid: String,
        val type: Int,
        val characteristics: List<GattCharInfo>,
    )

    data class GattCharInfo(
        val uuid: String,
        val properties: Int,
        val permissions: Int,
        val descriptors: List<String>,
    )

    fun toJson(): String = buildString {
        append("{\"mtu\":$mtu,\"selectedWrite\":\"$selectedWriteUuid\",\"selectedNotify\":\"$selectedNotifyUuid\",\"services\":[")
        services.forEachIndexed { si, s ->
            if (si > 0) append(",")
            append("{\"uuid\":\"${s.uuid}\",\"type\":${s.type},\"characteristics\":[")
            s.characteristics.forEachIndexed { ci, c ->
                if (ci > 0) append(",")
                append("{\"uuid\":\"${c.uuid}\",\"properties\":${c.properties},\"permissions\":${c.permissions},\"descriptors\":[")
                c.descriptors.forEachIndexed { di, d ->
                    if (di > 0) append(",")
                    append("\"$d\"")
                }
                append("]}")
            }
            append("]}")
        }
        append("]}")
    }

    /** Human-readable multi-line dump for the diagnostic log. */
    fun toLogText(): String = buildString {
        appendLine("MTU=$mtu, сервисов=${services.size}")
        services.forEach { s ->
            appendLine("SERVICE ${s.uuid} (type=${s.type})")
            s.characteristics.forEach { c ->
                appendLine(
                    "  CHAR ${c.uuid} props=[${GattPropertyNames.decode(c.properties)}] " +
                        "perms=${c.permissions} desc=${c.descriptors.joinToString(",")}",
                )
            }
        }
        appendLine("SELECTED write=$selectedWriteUuid notify=$selectedNotifyUuid")
    }
}

object GattPropertyNames {
    fun decode(props: Int): String {
        val list = ArrayList<String>()
        fun p(flag: Int, name: String) { if (props and flag != 0) list.add(name) }
        p(BluetoothGattCharacteristic.PROPERTY_BROADCAST, "broadcast")
        p(BluetoothGattCharacteristic.PROPERTY_READ, "read")
        p(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, "writeNoResponse")
        p(BluetoothGattCharacteristic.PROPERTY_WRITE, "write")
        p(BluetoothGattCharacteristic.PROPERTY_NOTIFY, "notify")
        p(BluetoothGattCharacteristic.PROPERTY_INDICATE, "indicate")
        p(BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE, "signedWrite")
        p(BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS, "extendedProps")
        return list.joinToString(",")
    }
}

/**
 * GATT helper: discovers services and picks write/notify characteristics
 * dynamically. No UUID is required or trusted blindly: candidate ranking uses
 * property sets; well-known families (Vgate FFF0 / 18F0+2AFx, HM-10 FFE0,
 * Nordic UART) act as *hints* only and every choice is verified against the
 * real properties and logged for the diagnostic report.
 */
object GattInspector {

    private val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** Known candidate characteristic UUID prefixes, ranked. Hints only. */
    private val knownWriteHints = listOf(
        "0000fff2", "00002af1", "0000fff1", "0000ffe1", "6e400002", "49535343-8841", "49535343-1e4d",
    )
    private val knownNotifyHints = listOf(
        "0000fff1", "00002af0", "0000ffe1", "0000fff2", "6e400003", "49535343-1e4d", "49535343-fe7d",
    )

    /** Public read-only views for the diagnostic report (hints, not requirements). */
    val WRITE_HINTS: List<String> get() = knownWriteHints
    val NOTIFY_HINTS: List<String> get() = knownNotifyHints

    fun isHintMatch(uuidLower: String, hints: List<String>): Boolean =
        hints.any { uuidLower.lowercase().contains(it) }

    // ------- pure scoring core (unit-testable without Android Bluetooth) ----

    data class CharSpec(
        val uuid: String,
        val properties: Int,
        val hasCccd: Boolean,
    )

    data class ServiceSpec(
        val uuid: String,
        val chars: List<CharSpec>,
    )

    /** Writes with-request flag used by the transport for ack semantics. */
    fun isWriteWithResponse(properties: Int): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 &&
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0

    /** Pure selection over plain specs: returns (writeUuid, notifyUuid) or null. */
    fun pickFromSpecs(services: List<ServiceSpec>): Pair<String, String>? {
        data class Cand(val svc: String, val ch: CharSpec, val score: Int)

        val writes = ArrayList<Cand>()
        val notifiers = ArrayList<Cand>()

        for (svc in services) {
            for (ch in svc.chars) {
                val u = ch.uuid.lowercase()
                if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    var score = 0
                    knownWriteHints.forEachIndexed { i, h -> if (u.contains(h)) score = maxOf(score, 100 - i) }
                    writes.add(Cand(svc.uuid, ch, score))
                }
                if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    var score = 0
                    knownNotifyHints.forEachIndexed { i, h -> if (u.contains(h)) score = maxOf(score, 100 - i) }
                    if (ch.hasCccd) score += 4
                    notifiers.add(Cand(svc.uuid, ch, score))
                }
            }
        }
        // A notify characteristic without a CCCD cannot deliver reliable
        // subscriptions; when any CCCD-backed candidate exists it wins over
        // even hinted candidates without a descriptor.
        if (notifiers.any { it.ch.hasCccd }) {
            notifiers.removeAll { !it.ch.hasCccd }
        }

        var bestPair: Pair<String, String>? = null
        var bestScore = -1
        for (w in writes) {
            for (n in notifiers) {
                val sameService = w.svc == n.svc
                val score = w.score + n.score + (if (sameService) 50 else 0)
                if (score > bestScore) {
                    bestScore = score
                    bestPair = w.ch.uuid to n.ch.uuid
                }
            }
        }
        return bestPair
    }

    // ------- Android-bound wrappers ----

    fun dump(gatt: BluetoothGatt, negotiatedMtu: Int): GattSnapshot {
        val services = gatt.services.orEmpty().map { svc ->
            GattSnapshot.GattServiceInfo(
                uuid = svc.uuid.toString(),
                type = svc.type,
                characteristics = svc.characteristics.orEmpty().map { ch ->
                    GattSnapshot.GattCharInfo(
                        uuid = ch.uuid.toString(),
                        properties = ch.properties,
                        permissions = ch.permissions,
                        descriptors = ch.descriptors.orEmpty().map { d -> d.uuid.toString() },
                    )
                },
            )
        }
        return GattSnapshot(services, negotiatedMtu, null, null)
    }

    fun toSpecs(gatt: BluetoothGatt): List<ServiceSpec> =
        gatt.services.orEmpty().map { svc ->
            ServiceSpec(
                uuid = svc.uuid.toString(),
                chars = svc.characteristics.orEmpty().map { ch ->
                    CharSpec(
                        uuid = ch.uuid.toString(),
                        properties = ch.properties,
                        hasCccd = ch.descriptors.orEmpty()
                            .any { it.uuid.toString().lowercase() == CCCD },
                    )
                },
            )
        }

    /** Chooses the best write + notify characteristics from discovered services. */
    fun pickCharacteristics(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val picked = pickFromSpecs(toSpecs(gatt)) ?: return null
        val (wu, nu) = picked
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null
        for (svc in gatt.services.orEmpty()) {
            for (ch in svc.characteristics.orEmpty()) {
                val u = ch.uuid.toString()
                if (u == wu) write = ch
                if (u == nu) notify = ch
            }
        }
        return if (write != null && notify != null) write to notify else null
    }

    fun cccdUuid(): String = CCCD

    fun writeTypeFor(ch: BluetoothGattCharacteristic): Int =
        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

    /**
     * Runtime BLUETOOTH_CONNECT guard for API 31+; on API <= 30 Android requires
     * legacy [android.Manifest.permission.BLUETOOTH] at manifest/install time.
     */
    fun hasConnectPermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        if (sdkInt >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Backwards-compatible single-arg wrapper. */
    fun hasConnectPermission(context: Context): Boolean =
        hasConnectPermission(context, Build.VERSION.SDK_INT)

    /**
     * Checks all permissions needed to perform BLE scanning for the given SDK level.
     */
    fun hasScanPermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        BleScanner.missingPermissionsForSdk(sdkInt) { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }.isEmpty()

    /** Human-readable GATT status for the diagnostic log and UI. */
    fun gattStatusText(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
        8 -> "8 (connection timeout)"
        19 -> "19 (remote device terminated)"
        22 -> "22 (local host terminated)"
        62 -> "62 (failed to establish)"
        133 -> "133 (GATT_ERROR — частый транзиентный сбой стека)"
        else -> "$status"
    }
}
