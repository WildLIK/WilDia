package com.obd2.app.core.bt

import android.bluetooth.BluetoothDevice
import com.obd2.app.core.ble.BleDevice
import java.util.UUID

/** Active physical transport chosen or resolved for an adapter. */
enum class BtTransportType(val labelRu: String) {
    BLE("BLE"),
    CLASSIC_SPP("Classic (SPP)"),
    WIFI_TCP("Wi-Fi (TCP)"),
    USB_SERIAL("USB (OTG)"),
}

/** User-selectable scan & connection mode on the Connect/Diag screens. */
enum class BtScanMode(val labelRu: String) {
    AUTO("Авто (BLE → Classic)"),
    BLE("Только BLE"),
    CLASSIC("Только Classic"),
}

/**
 * Confirmation status of Bluetooth Classic Serial Port Profile (00001101)
 * obtained from SDP UUIDs (never inferred from adapter name).
 */
enum class SppProfileStatus(val labelRu: String) {
    CONFIRMED("SPP 00001101 подтверждён (SDP)"),
    UNCONFIRMED("Профиль SPP не подтверждён (SDP пуст)"),
    ABSENT("Профиль SPP отсутствует в SDP"),
    NOT_APPLICABLE("BLE-интерфейс"),
}

/** One Bluetooth Classic device sighting (bonded list, ACTION_FOUND, or ACTION_UUID). */
data class ClassicDeviceInfo(
    val mac: String,
    val name: String,
    val rssi: Int?,
    val deviceType: Int,
    val bondState: Int,
    val deviceClass: Int?,
    val sdpUuids: List<String>,
    val sppStatus: SppProfileStatus,
    val source: String,
    val ts: Long = System.currentTimeMillis(),
)

/** Unified discovered adapter entry combining BLE and Classic observations per MAC. */
data class DiscoveredAdapter(
    val mac: String,
    val name: String,
    val rssi: Int?,
    val seenViaBle: Boolean = false,
    val seenViaClassic: Boolean = false,
    val bluetoothDeviceType: Int = 0,
    val bonded: Boolean = false,
    val sdpUuids: List<String> = emptyList(),
    val sppStatus: SppProfileStatus = SppProfileStatus.UNCONFIRMED,
    val bleDevice: BleDevice? = null,
    val classicInfo: ClassicDeviceInfo? = null,
    val ts: Long = System.currentTimeMillis(),
    val discoveredInCurrentScan: Boolean = false,
)

data class SavedAdapterDisplay(
    val saved: com.obd2.app.core.repo.SavedAdapterInfo,
    val liveAdapter: DiscoveredAdapter? = null,
) {
    val isAvailableNow: Boolean get() = liveAdapter?.discoveredInCurrentScan == true
    val rssi: Int? get() = if (isAvailableNow) liveAdapter?.rssi else null
}

data class BluetoothDeviceGroups(
    val availableObdAdapters: List<DiscoveredAdapter>,
    val lastUsedAdapter: SavedAdapterDisplay?,
    val savedAdapters: List<SavedAdapterDisplay>,
    val otherDevices: List<DiscoveredAdapter>,
)

/** Pure transport classification and resolution logic (no name-based heuristics). */
object TransportResolver {
    const val SPP_UUID_STRING = "00001101-0000-1000-8000-00805f9b34fb"
    val SPP_UUID: UUID = UUID.fromString(SPP_UUID_STRING)

    const val DEVICE_TYPE_UNKNOWN = BluetoothDevice.DEVICE_TYPE_UNKNOWN // 0
    const val DEVICE_TYPE_CLASSIC = BluetoothDevice.DEVICE_TYPE_CLASSIC // 1
    const val DEVICE_TYPE_LE = BluetoothDevice.DEVICE_TYPE_LE           // 2
    const val DEVICE_TYPE_DUAL = BluetoothDevice.DEVICE_TYPE_DUAL       // 3

    data class TransportResolution(
        val transport: BtTransportType?,
        val reasonRu: String,
    )

    fun classifySppFromUuids(uuids: List<String>?, deviceType: Int): SppProfileStatus {
        if (deviceType == DEVICE_TYPE_LE) return SppProfileStatus.NOT_APPLICABLE
        val norm = uuids?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
        if (norm.any { it == SPP_UUID_STRING || it == "1101" || it == "00001101" }) {
            return SppProfileStatus.CONFIRMED
        }
        return if (norm.isNotEmpty()) SppProfileStatus.ABSENT else SppProfileStatus.UNCONFIRMED
    }

    fun resolve(adapter: DiscoveredAdapter, preferredMode: BtScanMode): TransportResolution {
        val supportsBle = adapter.seenViaBle ||
            adapter.bluetoothDeviceType == DEVICE_TYPE_LE ||
            adapter.bluetoothDeviceType == DEVICE_TYPE_DUAL
        val supportsClassic = adapter.seenViaClassic ||
            adapter.bluetoothDeviceType == DEVICE_TYPE_CLASSIC ||
            adapter.bluetoothDeviceType == DEVICE_TYPE_DUAL

        return when (preferredMode) {
            BtScanMode.BLE -> {
                if (supportsBle) {
                    TransportResolution(BtTransportType.BLE, "Выбран режим BLE")
                } else {
                    TransportResolution(null, "Устройство не обнаружено по BLE (тип=${deviceTypeLabel(adapter.bluetoothDeviceType)})")
                }
            }

            BtScanMode.CLASSIC -> {
                when {
                    adapter.bluetoothDeviceType == DEVICE_TYPE_LE ->
                        TransportResolution(null, "Устройство только BLE (DEVICE_TYPE_LE); Classic SPP недоступен")
                    adapter.sppStatus == SppProfileStatus.ABSENT ->
                        TransportResolution(null, "Профиль SPP (00001101) отсутствует в SDP устройства")
                    !supportsClassic && !adapter.bonded ->
                        TransportResolution(null, "Устройство не обнаружено по Bluetooth Classic")
                    adapter.sppStatus == SppProfileStatus.CONFIRMED ->
                        TransportResolution(BtTransportType.CLASSIC_SPP, "Bluetooth Classic (SPP подтверждён SDP)")
                    else ->
                        TransportResolution(BtTransportType.CLASSIC_SPP, "Bluetooth Classic (SDP будет проверен при подключении)")
                }
            }

            BtScanMode.AUTO -> {
                when {
                    adapter.seenViaBle && !adapter.seenViaClassic && adapter.bluetoothDeviceType != DEVICE_TYPE_CLASSIC ->
                        TransportResolution(BtTransportType.BLE, "Авто: обнаружен BLE-интерфейс")

                    adapter.bluetoothDeviceType == DEVICE_TYPE_LE ->
                        TransportResolution(BtTransportType.BLE, "Авто: устройство только BLE")

                    adapter.seenViaClassic && !adapter.seenViaBle -> {
                        if (adapter.sppStatus == SppProfileStatus.ABSENT) {
                            TransportResolution(null, "Профиль SPP (00001101) отсутствует в SDP устройства")
                        } else if (adapter.sppStatus == SppProfileStatus.CONFIRMED) {
                            TransportResolution(BtTransportType.CLASSIC_SPP, "Авто: обнаружен Classic (SPP подтверждён SDP)")
                        } else {
                            TransportResolution(BtTransportType.CLASSIC_SPP, "Авто: обнаружен Classic (требуется проверка SDP при подключении)")
                        }
                    }

                    adapter.seenViaBle && (adapter.seenViaClassic || adapter.bluetoothDeviceType == DEVICE_TYPE_DUAL) ->
                        TransportResolution(
                            BtTransportType.BLE,
                            "Авто: dual-mode, приоритет BLE (для Classic переключите режим или выберите Classic)",
                        )

                    else ->
                        TransportResolution(null, "Транспорт не определён: повторите поиск")
                }
            }
        }
    }

    fun mergeAdapters(
        existing: DiscoveredAdapter?,
        ble: BleDevice? = null,
        classic: ClassicDeviceInfo? = null,
    ): DiscoveredAdapter {
        val mac = ble?.mac ?: classic?.mac ?: existing?.mac.orEmpty()
        val name = listOfNotNull(
            ble?.name?.takeIf { it.isNotBlank() },
            classic?.name?.takeIf { it.isNotBlank() },
            existing?.name?.takeIf { it.isNotBlank() },
        ).firstOrNull().orEmpty()
        val rssi = ble?.rssi ?: classic?.rssi ?: existing?.rssi
        val seenViaBle = (existing?.seenViaBle == true) || (ble != null)
        val seenViaClassic = (existing?.seenViaClassic == true) || (classic != null)
        val mergedUuids = ((existing?.sdpUuids.orEmpty()) + (classic?.sdpUuids.orEmpty())).distinct()
        val devType = when {
            classic != null && classic.deviceType != DEVICE_TYPE_UNKNOWN -> classic.deviceType
            existing != null && existing.bluetoothDeviceType != DEVICE_TYPE_UNKNOWN -> existing.bluetoothDeviceType
            seenViaBle && seenViaClassic -> DEVICE_TYPE_DUAL
            seenViaBle -> DEVICE_TYPE_LE
            seenViaClassic -> DEVICE_TYPE_CLASSIC
            else -> DEVICE_TYPE_UNKNOWN
        }
        val sppStatus = when {
            mergedUuids.isNotEmpty() -> classifySppFromUuids(mergedUuids, devType)
            classic != null -> classic.sppStatus
            existing != null && existing.seenViaClassic -> existing.sppStatus
            seenViaBle && !seenViaClassic -> SppProfileStatus.NOT_APPLICABLE
            else -> SppProfileStatus.UNCONFIRMED
        }
        val bonded = (existing?.bonded == true) ||
            (classic?.bondState == BluetoothDevice.BOND_BONDED)
        val discoveredNow = (existing?.discoveredInCurrentScan == true) ||
            (ble != null) ||
            (classic != null && !classic.source.equals("bonded", ignoreCase = true))

        return DiscoveredAdapter(
            mac = mac,
            name = name,
            rssi = rssi,
            seenViaBle = seenViaBle,
            seenViaClassic = seenViaClassic,
            bluetoothDeviceType = devType,
            bonded = bonded,
            sdpUuids = mergedUuids,
            sppStatus = sppStatus,
            bleDevice = ble ?: existing?.bleDevice,
            classicInfo = classic ?: existing?.classicInfo,
            ts = maxOf(existing?.ts ?: 0L, ble?.ts ?: 0L, classic?.ts ?: 0L),
            discoveredInCurrentScan = discoveredNow,
        )
    }

    private val obdNameTokens = listOf(
        "vlink", "v-link", "vgate", "obd", "elm", "icar", "carista", "veepeak",
        "konnwei", "bafx", "tonwon", "viecar", "lelink", "thinkdiag", "mucar",
        "nexas", "ancel", "ios-vlink", "android-vlink", "vlinker",
    )

    fun isLikelyObdAdapter(adapter: DiscoveredAdapter): Boolean {
        val lowName = adapter.name.lowercase()
        if (obdNameTokens.any { lowName.contains(it) }) return true
        val uuids = adapter.sdpUuids.map { it.lowercase() }
        if (uuids.any { it.contains("fff0") || it.contains("ffe0") || it.contains("18f0") }) return true
        return false
    }

    fun groupBluetoothDevices(
        adapters: List<DiscoveredAdapter>,
        lastAdapter: com.obd2.app.core.repo.SavedAdapterInfo?,
        savedAdapters: List<com.obd2.app.core.repo.SavedAdapterInfo>,
    ): BluetoothDeviceGroups {
        val byMac = adapters.associateBy { it.mac.uppercase() }
        val lastDisplay = lastAdapter?.let { last ->
            SavedAdapterDisplay(last, byMac[last.mac.uppercase()])
        }
        val savedDisplays = savedAdapters
            .filterNot { lastAdapter != null && it.mac.equals(lastAdapter.mac, ignoreCase = true) }
            .map { s -> SavedAdapterDisplay(s, byMac[s.mac.uppercase()]) }

        val savedMacs = buildSet {
            lastAdapter?.mac?.let { add(it.uppercase()) }
            savedAdapters.forEach { add(it.mac.uppercase()) }
        }

        val availableObd = adapters
            .filter { it.discoveredInCurrentScan && isLikelyObdAdapter(it) }
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }

        val availableMacs = availableObd.map { it.mac.uppercase() }.toSet()
        val other = adapters
            .filter { a ->
                val macU = a.mac.uppercase()
                macU !in availableMacs && macU !in savedMacs
            }
            .sortedWith(
                compareByDescending<DiscoveredAdapter> { it.discoveredInCurrentScan }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )

        return BluetoothDeviceGroups(
            availableObdAdapters = availableObd,
            lastUsedAdapter = lastDisplay,
            savedAdapters = savedDisplays,
            otherDevices = other,
        )
    }

    fun deviceTypeLabel(deviceType: Int): String = when (deviceType) {
        DEVICE_TYPE_CLASSIC -> "Classic"
        DEVICE_TYPE_LE -> "BLE"
        DEVICE_TYPE_DUAL -> "Dual"
        else -> "Unknown"
    }
}
