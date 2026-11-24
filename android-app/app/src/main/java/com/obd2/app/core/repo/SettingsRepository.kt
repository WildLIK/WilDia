package com.obd2.app.core.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.WeakHashMap

private val dataStoreLock = Any()
private val dataStoreCache = WeakHashMap<Context, DataStore<Preferences>>()

private val Context.dataStore: DataStore<Preferences>
    get() {
        val appCtx = applicationContext ?: this
        synchronized(dataStoreLock) {
            return dataStoreCache.getOrPut(appCtx) {
                PreferenceDataStoreFactory.create(
                    produceFile = { appCtx.preferencesDataStoreFile("obd_settings") },
                )
            }
        }
    }

/**
 * Saved car profile. The built-in Skoda profile holds the owner's VCDS facts;
 * user profiles contain only what the user or the adapter actually provided.
 * Nothing here is guessed: absent fields stay empty and the UI reports them
 * as «не определено».
 */
@Serializable
data class VehicleProfile(
    val id: String,
    val name: String,
    val maker: String = "",
    val model: String = "",
    val year: String = "",
    val engine: String = "",
    val vin: String = "",
    val vinStatus: String = ID_UNKNOWN,      // unknown | auto | manual
    val ecuIds: List<String> = emptyList(),  // captured via Mode 09 (Cal ID / ECU name)
    val identityStatus: String = ID_UNKNOWN, // unknown | auto | manual
    val builtIn: Boolean = false,
) {
    companion object {
        const val ID_UNKNOWN = "unknown"
        const val ID_AUTO = "auto"
        const val ID_MANUAL = "manual"
        const val BUILTIN_SKODA_ID = "skoda-octavia-a5-1z"
    }
}

@Serializable
data class SavedAdapterInfo(
    val mac: String,
    val name: String,
    val transport: String, // "BLE" | "CLASSIC_SPP"
    val lastConnectedAtMs: Long = 0L,
    val verifiedMethod: String? = null,
)

/** App settings (units, polling, connection defaults, privacy, developer mode). */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val units = stringPreferencesKey("units")                 // metric | imperial
        val refreshMs = intPreferencesKey("refresh_ms")           // live data polling
        val demoMode = booleanPreferencesKey("demo_mode")
        val lastAdapterMac = stringPreferencesKey("last_adapter_mac")
        val lastAdapterName = stringPreferencesKey("last_adapter_name")
        val lastAdapterTransport = stringPreferencesKey("last_adapter_transport")
        val lastAdapterConnectedAtMs = longPreferencesKey("last_adapter_connected_at_ms")
        val lastAdapterVerifiedMethod = stringPreferencesKey("last_adapter_verified_method")
        val savedAdaptersJson = stringPreferencesKey("saved_adapters_json")
        val maskVin = booleanPreferencesKey("mask_vin")
        val vehicleMaker = stringPreferencesKey("vehicle_maker")
        val vehicleModel = stringPreferencesKey("vehicle_model")
        val vehicleYear = stringPreferencesKey("vehicle_year")
        val vehicleEngine = stringPreferencesKey("vehicle_engine")
        val vehicleEcu = stringPreferencesKey("vehicle_ecu")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val cmdTimeoutSec = floatPreferencesKey("cmd_timeout_sec")
        val mtu = intPreferencesKey("mtu")
        val devMode = booleanPreferencesKey("dev_mode")
        val pidFavorites = stringSetPreferencesKey("pid_favorites")
        val profiles = stringPreferencesKey("vehicle_profiles_json")
        val activeProfileId = stringPreferencesKey("active_profile_id")
        val autoCheckUpdates = booleanPreferencesKey("auto_check_updates")
        val autoUpdateDbs = booleanPreferencesKey("auto_update_dbs")
        val wifiOnlyUpdates = booleanPreferencesKey("wifi_only_updates")
        val onlineDtcSearchEnabled = booleanPreferencesKey("online_dtc_search_enabled")
        val chartPresetsJson = stringPreferencesKey("chart_presets_json")
        val autoCheckAppUpdates = booleanPreferencesKey("auto_check_app_updates")
        val notifyAppUpdates = booleanPreferencesKey("notify_app_updates")
        val appUpdateIntervalHours = intPreferencesKey("app_update_interval_hours")
        val lastWifiHost = stringPreferencesKey("last_wifi_host")
        val lastWifiPort = intPreferencesKey("last_wifi_port")
        val lastUsbBaud = intPreferencesKey("last_usb_baud")
    }

    data class Settings(
        val units: Units = Units.METRIC,
        val refreshMs: Int = 700,
        val demoMode: Boolean = false,
        val lastAdapterMac: String = "",
        val lastAdapterName: String = "",
        val maskVin: Boolean = true,
        val vehicleMaker: String = "",
        val vehicleModel: String = "",
        val vehicleYear: String = "",
        val vehicleEngine: String = "",
        val vehicleEcu: String = "",
        val autoReconnect: Boolean = true,
        val cmdTimeoutSec: Float = 3.0f,
        val mtu: Int = 247,
        val devMode: Boolean = false,
        val pidFavorites: Set<String> = emptySet(),
        val autoCheckUpdates: Boolean = false,
        val autoUpdateDbs: Boolean = false,
        val wifiOnlyUpdates: Boolean = true,
        val onlineDtcSearchEnabled: Boolean = false,
        val chartPresetsJson: String = "",
        val autoCheckAppUpdates: Boolean = false,
        val notifyAppUpdates: Boolean = true,
        val appUpdateIntervalHours: Int = 24,
        val lastWifiHost: String = "192.168.0.10",
        val lastWifiPort: Int = 35000,
        val lastUsbBaud: Int = 0, // 0 = Auto
    )

    enum class Units { METRIC, IMPERIAL }

    private val json = Json { ignoreUnknownKeys = true }

    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError

    /** Explicit developer/test fixture (owner's VCDS scan). Never auto-applied on fresh install. */
    fun devTestSkodaProfile(): VehicleProfile = VehicleProfile(
        id = VehicleProfile.BUILTIN_SKODA_ID,
        name = "Škoda Octavia A5 (тестовый)",
        maker = "Škoda",
        model = "Octavia A5 (1Z)",
        year = "2012",
        engine = "1.6 MPI BSE",
        identityStatus = VehicleProfile.ID_MANUAL,
        builtIn = true,
    )

    /** Alias kept for isolated developer tools and unit tests. */
    fun builtinProfile(): VehicleProfile = devTestSkodaProfile()

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            units = if (p[Keys.units] == "imperial") Units.IMPERIAL else Units.METRIC,
            refreshMs = p[Keys.refreshMs] ?: 700,
            demoMode = p[Keys.demoMode] ?: false,
            lastAdapterMac = p[Keys.lastAdapterMac] ?: "",
            lastAdapterName = p[Keys.lastAdapterName] ?: "",
            maskVin = p[Keys.maskVin] ?: true,
            vehicleMaker = p[Keys.vehicleMaker] ?: "",
            vehicleModel = p[Keys.vehicleModel] ?: "",
            vehicleYear = p[Keys.vehicleYear] ?: "",
            vehicleEngine = p[Keys.vehicleEngine] ?: "",
            vehicleEcu = p[Keys.vehicleEcu] ?: "",
            autoReconnect = p[Keys.autoReconnect] ?: true,
            cmdTimeoutSec = p[Keys.cmdTimeoutSec] ?: 3.0f,
            mtu = p[Keys.mtu] ?: 247,
            devMode = p[Keys.devMode] ?: false,
            pidFavorites = p[Keys.pidFavorites] ?: emptySet(),
            autoCheckUpdates = p[Keys.autoCheckUpdates] ?: false,
            autoUpdateDbs = p[Keys.autoUpdateDbs] ?: false,
            wifiOnlyUpdates = p[Keys.wifiOnlyUpdates] ?: true,
            onlineDtcSearchEnabled = p[Keys.onlineDtcSearchEnabled] ?: false,
            chartPresetsJson = p[Keys.chartPresetsJson] ?: "",
            autoCheckAppUpdates = p[Keys.autoCheckAppUpdates] ?: false,
            notifyAppUpdates = p[Keys.notifyAppUpdates] ?: true,
            appUpdateIntervalHours = p[Keys.appUpdateIntervalHours] ?: 24,
            lastWifiHost = p[Keys.lastWifiHost]?.takeIf { it.isNotBlank() } ?: "192.168.0.10",
            lastWifiPort = p[Keys.lastWifiPort]?.takeIf { it in 1..65535 } ?: 35000,
            lastUsbBaud = p[Keys.lastUsbBaud] ?: 0,
        )
    }.catch { e ->
        _storageError.value = "Ошибка чтения настроек DataStore: ${e.message ?: "неизвестная ошибка"}"
    }

    /** Saved user profiles (empty on clean install — no default test car). */
    val profilesFlow: Flow<List<VehicleProfile>> = context.dataStore.data.map { p ->
        val raw = p[Keys.profiles] ?: "[]"
        runCatching {
            json.decodeFromString(
                ListSerializer(VehicleProfile.serializer()),
                raw,
            )
        }.getOrElse { e ->
            _storageError.value = "Ошибка чтения сохранённых профилей автомобилей: ${e.message}"
            emptyList()
        }
    }.catch { e ->
        _storageError.value = "Ошибка доступа к хранилищу профилей: ${e.message}"
        emit(emptyList())
    }

    val activeProfileIdFlow: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.activeProfileId] ?: ""
    }

    val lastAdapterFlow: Flow<SavedAdapterInfo?> = context.dataStore.data.map { p ->
        val mac = p[Keys.lastAdapterMac]?.trim().orEmpty()
        if (mac.isEmpty()) null
        else SavedAdapterInfo(
            mac = mac,
            name = p[Keys.lastAdapterName]?.takeIf { it.isNotBlank() } ?: "OBD2 ($mac)",
            transport = p[Keys.lastAdapterTransport] ?: "BLE",
            lastConnectedAtMs = p[Keys.lastAdapterConnectedAtMs] ?: 0L,
            verifiedMethod = p[Keys.lastAdapterVerifiedMethod],
        )
    }

    val savedAdaptersFlow: Flow<List<SavedAdapterInfo>> = context.dataStore.data.map { p ->
        runCatching {
            json.decodeFromString(
                ListSerializer(SavedAdapterInfo.serializer()),
                p[Keys.savedAdaptersJson] ?: "[]",
            )
        }.getOrElse { e ->
            _storageError.value = "Ошибка чтения списка сохранённых адаптеров: ${e.message}"
            emptyList()
        }
    }.catch { e ->
        _storageError.value = "Ошибка доступа к списку адаптеров: ${e.message}"
        emit(emptyList())
    }

    suspend fun setWifiEndpoint(host: String, port: Int) = context.dataStore.edit { p ->
        p[Keys.lastWifiHost] = host.trim()
        p[Keys.lastWifiPort] = port
    }

    suspend fun setUsbBaud(baud: Int) = context.dataStore.edit { p ->
        p[Keys.lastUsbBaud] = baud
    }

    suspend fun recordSuccessfulAdapter(
        mac: String,
        name: String,
        transport: String,
        verifiedMethod: String? = null,
        connectedAtMs: Long = System.currentTimeMillis(),
    ) = context.dataStore.edit { p ->
        val cleanMac = mac.trim().uppercase()
        if (cleanMac.isEmpty()) return@edit
        val cleanName = name.ifBlank { "OBD2 ($cleanMac)" }
        p[Keys.lastAdapterMac] = cleanMac
        p[Keys.lastAdapterName] = cleanName
        p[Keys.lastAdapterTransport] = transport
        p[Keys.lastAdapterConnectedAtMs] = connectedAtMs
        if (verifiedMethod != null) {
            p[Keys.lastAdapterVerifiedMethod] = verifiedMethod
        } else {
            p.remove(Keys.lastAdapterVerifiedMethod)
        }
        val existing = runCatching {
            json.decodeFromString(
                ListSerializer(SavedAdapterInfo.serializer()),
                p[Keys.savedAdaptersJson] ?: "[]",
            )
        }.getOrDefault(emptyList())
        val entry = SavedAdapterInfo(
            mac = cleanMac,
            name = cleanName,
            transport = transport,
            lastConnectedAtMs = connectedAtMs,
            verifiedMethod = verifiedMethod,
        )
        val updated = (listOf(entry) + existing.filterNot { it.mac.equals(cleanMac, ignoreCase = true) }).take(10)
        p[Keys.savedAdaptersJson] = json.encodeToString(ListSerializer(SavedAdapterInfo.serializer()), updated)
    }

    // ---------------- profiles ----------------

    suspend fun addProfile(profile: VehicleProfile) = context.dataStore.edit { p ->
        val list = runCatching {
            json.decodeFromString(ListSerializer(VehicleProfile.serializer()), p[Keys.profiles] ?: "[]")
        }.getOrDefault(emptyList())
        val updated = list.filterNot { it.id == profile.id } + profile
        p[Keys.profiles] = json.encodeToString(ListSerializer(VehicleProfile.serializer()), updated)
    }

    suspend fun updateProfile(profile: VehicleProfile) = context.dataStore.edit { p ->
        val list = runCatching {
            json.decodeFromString(ListSerializer(VehicleProfile.serializer()), p[Keys.profiles] ?: "[]")
        }.getOrDefault(emptyList())
        val updated = if (list.any { it.id == profile.id }) {
            list.map { if (it.id == profile.id) profile else it }
        } else {
            list + profile
        }
        p[Keys.profiles] = json.encodeToString(ListSerializer(VehicleProfile.serializer()), updated)
        if (p[Keys.activeProfileId] == profile.id) {
            p.syncLegacy(profile)
        }
    }

    suspend fun deleteProfile(id: String) = context.dataStore.edit { p ->
        val list = runCatching {
            json.decodeFromString(ListSerializer(VehicleProfile.serializer()), p[Keys.profiles] ?: "[]")
        }.getOrDefault(emptyList())
        val updated = list.filter { it.id != id }
        p[Keys.profiles] = json.encodeToString(ListSerializer(VehicleProfile.serializer()), updated)
        if (p[Keys.activeProfileId] == id) {
            p[Keys.activeProfileId] = updated.firstOrNull()?.id ?: ""
        }
    }

    /** Removes developer/test built-in profiles without touching real user profiles. */
    suspend fun clearDemoProfiles() = context.dataStore.edit { p ->
        val list = runCatching {
            json.decodeFromString(ListSerializer(VehicleProfile.serializer()), p[Keys.profiles] ?: "[]")
        }.getOrDefault(emptyList())
        val removedIds = list.filter { it.builtIn }.map { it.id }.toSet()
        val updated = list.filterNot { it.builtIn }
        p[Keys.profiles] = json.encodeToString(ListSerializer(VehicleProfile.serializer()), updated)
        if (p[Keys.activeProfileId] in removedIds) {
            p[Keys.activeProfileId] = updated.firstOrNull()?.id ?: ""
        }
        p[Keys.demoMode] = false
    }

    suspend fun setActiveProfile(id: String) = context.dataStore.edit { p ->
        p[Keys.activeProfileId] = id
        val list = runCatching {
            json.decodeFromString(ListSerializer(VehicleProfile.serializer()), p[Keys.profiles] ?: "[]")
        }.getOrDefault(emptyList())
        list.firstOrNull { prof -> prof.id == id }?.let { prof -> p.syncLegacy(prof) }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.syncLegacy(profile: VehicleProfile) {
        if (profile.maker.isNotEmpty()) this[Keys.vehicleMaker] = profile.maker
        if (profile.model.isNotEmpty()) this[Keys.vehicleModel] = profile.model
        if (profile.year.isNotEmpty()) this[Keys.vehicleYear] = profile.year
        if (profile.engine.isNotEmpty()) this[Keys.vehicleEngine] = profile.engine
    }

    // ---------------- basic settings ----------------

    suspend fun setUnits(u: Units) = context.dataStore.edit { it[Keys.units] = if (u == Units.IMPERIAL) "imperial" else "metric" }
    suspend fun setRefreshMs(v: Int) = context.dataStore.edit { it[Keys.refreshMs] = v }
    suspend fun setDemoMode(v: Boolean) = context.dataStore.edit { it[Keys.demoMode] = v }
    suspend fun setLastAdapter(mac: String, name: String) = context.dataStore.edit {
        it[Keys.lastAdapterMac] = mac; it[Keys.lastAdapterName] = name
    }
    suspend fun setMaskVin(v: Boolean) = context.dataStore.edit { it[Keys.maskVin] = v }
    suspend fun setVehicle(maker: String, model: String, year: String, engine: String, ecu: String) =
        context.dataStore.edit {
            it[Keys.vehicleMaker] = maker; it[Keys.vehicleModel] = model; it[Keys.vehicleYear] = year
            it[Keys.vehicleEngine] = engine; it[Keys.vehicleEcu] = ecu
        }
    suspend fun setAutoReconnect(v: Boolean) = context.dataStore.edit { it[Keys.autoReconnect] = v }
    suspend fun setCmdTimeoutSec(v: Float) = context.dataStore.edit { it[Keys.cmdTimeoutSec] = v }
    suspend fun setMtu(v: Int) = context.dataStore.edit { it[Keys.mtu] = v }
    suspend fun setDevMode(v: Boolean) = context.dataStore.edit { it[Keys.devMode] = v }
    suspend fun setPidFavorite(key: String, favorite: Boolean) = context.dataStore.edit {
        val cur = it[Keys.pidFavorites] ?: emptySet()
        it[Keys.pidFavorites] = if (favorite) cur + key else cur - key
    }
    suspend fun setAutoCheckUpdates(v: Boolean) = context.dataStore.edit { it[Keys.autoCheckUpdates] = v }
    suspend fun setAutoUpdateDbs(v: Boolean) = context.dataStore.edit { it[Keys.autoUpdateDbs] = v }
    suspend fun setWifiOnlyUpdates(v: Boolean) = context.dataStore.edit { it[Keys.wifiOnlyUpdates] = v }
    suspend fun setOnlineDtcSearchEnabled(v: Boolean) = context.dataStore.edit { it[Keys.onlineDtcSearchEnabled] = v }
    suspend fun setChartPresetsJson(v: String) = context.dataStore.edit { it[Keys.chartPresetsJson] = v }
    suspend fun setAutoCheckAppUpdates(v: Boolean) = context.dataStore.edit { it[Keys.autoCheckAppUpdates] = v }
    suspend fun setNotifyAppUpdates(v: Boolean) = context.dataStore.edit { it[Keys.notifyAppUpdates] = v }
    suspend fun setAppUpdateIntervalHours(h: Int) = context.dataStore.edit { it[Keys.appUpdateIntervalHours] = h }
}
