package com.obd2.app.core.backup

import android.content.Context
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.DtcRecordEntity
import com.obd2.app.core.db.SessionEntity
import com.obd2.app.core.db.SessionSensorEntity
import com.obd2.app.core.db.TripRecordingEntity
import com.obd2.app.core.db.TripSampleEntity
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.repo.VehicleProfile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class BackupSessionDto(
    val id: Long,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long? = null,
    val adapterName: String,
    val transportType: String? = null,
    val demoMode: Boolean,
    val protocol: String? = null,
    val profileId: String? = null,
    val profileName: String? = null,
    val identifiedVin: String? = null,
    val vinMasked: String? = null,
    val ecuIds: String? = null,
    val vehicleIdentitySource: String,
    val storedCount: Int,
    val pendingCount: Int,
    val permanentCount: Int,
    val dtcReadCompleted: Boolean,
    val status: String,
    val endReason: String? = null,
    val rawDtcResponsesJson: String? = null,
    val legacyUnverified: Boolean = false,
    val freezeFrameJson: String? = null,
    val readinessJson: String? = null,
    val discoveredEcusJson: String? = null,
)

@Serializable
data class BackupDtcDto(
    val sessionId: Long,
    val code: String,
    val mode: Int,
    val descriptionRu: String? = null,
    val descriptionEn: String? = null,
    val scope: String? = null,
    val timestampMs: Long,
    val ecuId: String? = null,
    val rawResponse: String? = null,
    val translationStatus: String? = null,
)

@Serializable
data class BackupSensorDto(
    val sessionId: Long,
    val mode: Int,
    val pid: Int,
    val name: String,
    val valueText: String,
    val numericValue: Double? = null,
    val unit: String? = null,
    val rawHex: String? = null,
    val timestampMs: Long,
)

@Serializable
data class BackupTripRecordingDto(
    val id: Long,
    val sessionId: Long? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long? = null,
    val status: String,
    val selectedPidsCsv: String,
    val sampleCount: Int,
    val errorCount: Int,
    val maxDurationSec: Int,
    val maxSamples: Int,
    val vehicleLabel: String,
    val notes: String? = null,
)

@Serializable
data class BackupTripSampleDto(
    val recordingId: Long,
    val timestampMs: Long,
    val pid: Int,
    val value: Double? = null,
    val unit: String? = null,
    val status: String,
    val errorNote: String? = null,
)

@Serializable
data class BackupSettingsDto(
    val unitsImperial: Boolean = false,
    val refreshMs: Int = 700,
    val maskVin: Boolean = true,
    val autoReconnect: Boolean = true,
    val cmdTimeoutSec: Float = 3.0f,
    val mtu: Int = 247,
    val devMode: Boolean = false,
    val pidFavorites: List<String> = emptyList(),
    val autoCheckUpdates: Boolean = false,
    val autoUpdateDbs: Boolean = false,
    val wifiOnlyUpdates: Boolean = true,
    val onlineDtcSearchEnabled: Boolean = false,
    val autoCheckAppUpdates: Boolean = false,
    val notifyAppUpdates: Boolean = true,
    val appUpdateIntervalHours: Int = 24,
)

@Serializable
data class UserBackupPayload(
    val schemaVersion: Int = 1,
    val roomDbVersion: Int = 5,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val activeProfileId: String = "",
    val profiles: List<VehicleProfile> = emptyList(),
    val settings: BackupSettingsDto = BackupSettingsDto(),
    val sessions: List<BackupSessionDto> = emptyList(),
    val dtcs: List<BackupDtcDto> = emptyList(),
    val sensors: List<BackupSensorDto> = emptyList(),
    val tripRecordings: List<BackupTripRecordingDto> = emptyList(),
    val tripSamples: List<BackupTripSampleDto> = emptyList(),
)

@Serializable
data class SealedUserBackup(
    val schemaVersion: Int = 1,
    val roomDbVersion: Int = 5,
    val sha256: String,
    val payloadJson: String,
)

sealed interface BackupVerifyResult {
    data class Valid(val payload: UserBackupPayload) : BackupVerifyResult
    data class Invalid(val reason: String) : BackupVerifyResult
}

data class BackupRestoreSummary(
    val restoredSessions: Int,
    val restoredDtcs: Int,
    val restoredRecordings: Int,
    val restoredProfiles: Int,
)

/**
 * Verifiable export/restore of all user diagnostic history, trip recordings,
 * vehicle profiles, and settings. Enables safe migration from debug-signed builds
 * to a permanent release signing key without losing user data.
 */
object UserDataBackupManager {

    const val SUPPORTED_SCHEMA_VERSION = 1
    const val CURRENT_ROOM_VERSION = 5
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportBackup(
        context: Context,
        db: AppDb,
        settingsRepo: SettingsRepository,
    ): File {
        val sessions = db.sessionDao().recentSessions(5000)
        val dtcs = ArrayList<BackupDtcDto>()
        val sensors = ArrayList<BackupSensorDto>()
        for (s in sessions) {
            db.dtcRecordDao().forSession(s.id).forEach { d ->
                dtcs += BackupDtcDto(
                    sessionId = d.sessionId,
                    code = d.code,
                    mode = d.mode,
                    descriptionRu = d.descriptionRu,
                    descriptionEn = d.descriptionEn,
                    scope = d.scope,
                    timestampMs = d.timestampMs,
                    ecuId = d.ecuId,
                    rawResponse = d.rawResponse,
                    translationStatus = d.translationStatus,
                )
            }
            db.sessionSensorDao().forSession(s.id).forEach { ss ->
                sensors += BackupSensorDto(
                    sessionId = ss.sessionId,
                    mode = ss.mode,
                    pid = ss.pid,
                    name = ss.name,
                    valueText = ss.valueText,
                    numericValue = ss.numericValue,
                    unit = ss.unit,
                    rawHex = ss.rawHex,
                    timestampMs = ss.timestampMs,
                )
            }
        }
        val recs = db.tripRecordingDao().recentRecordings(2000)
        val samples = ArrayList<BackupTripSampleDto>()
        for (r in recs) {
            db.tripRecordingDao().samplesForRecording(r.id).forEach { sp ->
                samples += BackupTripSampleDto(
                    recordingId = sp.recordingId,
                    timestampMs = sp.timestampMs,
                    pid = sp.pid,
                    value = sp.value,
                    unit = sp.unit,
                    status = sp.status,
                    errorNote = sp.errorNote,
                )
            }
        }

        val st = settingsRepo.settings.first()
        val profiles = settingsRepo.profilesFlow.first()
        val activeProf = settingsRepo.activeProfileIdFlow.first()

        val payload = UserBackupPayload(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            roomDbVersion = CURRENT_ROOM_VERSION,
            exportedAtMs = System.currentTimeMillis(),
            activeProfileId = activeProf,
            profiles = profiles,
            settings = BackupSettingsDto(
                unitsImperial = st.units == SettingsRepository.Units.IMPERIAL,
                refreshMs = st.refreshMs,
                maskVin = st.maskVin,
                autoReconnect = st.autoReconnect,
                cmdTimeoutSec = st.cmdTimeoutSec,
                mtu = st.mtu,
                devMode = st.devMode,
                pidFavorites = st.pidFavorites.toList(),
                autoCheckUpdates = st.autoCheckUpdates,
                autoUpdateDbs = st.autoUpdateDbs,
                wifiOnlyUpdates = st.wifiOnlyUpdates,
                onlineDtcSearchEnabled = st.onlineDtcSearchEnabled,
                autoCheckAppUpdates = st.autoCheckAppUpdates,
                notifyAppUpdates = st.notifyAppUpdates,
                appUpdateIntervalHours = st.appUpdateIntervalHours,
            ),
            sessions = sessions.map { s ->
                BackupSessionDto(
                    id = s.id, startedAtMs = s.startedAtMs, updatedAtMs = s.updatedAtMs, endedAtMs = s.endedAtMs,
                    adapterName = s.adapterName, transportType = s.transportType, demoMode = s.demoMode,
                    protocol = s.protocol, profileId = s.profileId, profileName = s.profileName,
                    identifiedVin = s.identifiedVin, vinMasked = s.vinMasked, ecuIds = s.ecuIds,
                    vehicleIdentitySource = s.vehicleIdentitySource, storedCount = s.storedCount,
                    pendingCount = s.pendingCount, permanentCount = s.permanentCount,
                    dtcReadCompleted = s.dtcReadCompleted, status = s.status, endReason = s.endReason,
                    rawDtcResponsesJson = s.rawDtcResponsesJson, legacyUnverified = s.legacyUnverified,
                    freezeFrameJson = s.freezeFrameJson, readinessJson = s.readinessJson,
                    discoveredEcusJson = s.discoveredEcusJson,
                )
            },
            dtcs = dtcs,
            sensors = sensors,
            tripRecordings = recs.map { r ->
                BackupTripRecordingDto(
                    id = r.id, sessionId = r.sessionId, startedAtMs = r.startedAtMs, updatedAtMs = r.updatedAtMs,
                    endedAtMs = r.endedAtMs, status = r.status, selectedPidsCsv = r.selectedPidsCsv,
                    sampleCount = r.sampleCount, errorCount = r.errorCount, maxDurationSec = r.maxDurationSec,
                    maxSamples = r.maxSamples, vehicleLabel = r.vehicleLabel, notes = r.notes,
                )
            },
            tripSamples = samples,
        )

        val payloadJson = json.encodeToString(payload)
        val sealed = SealedUserBackup(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            roomDbVersion = CURRENT_ROOM_VERSION,
            sha256 = sha256(payloadJson),
            payloadJson = payloadJson,
        )
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val file = File(dir, "obd2_backup_${payload.exportedAtMs}.json")
        file.writeText(json.encodeToString(sealed), Charsets.UTF_8)
        return file
    }

    fun verifyBackup(file: File): BackupVerifyResult {
        if (!file.exists()) return BackupVerifyResult.Invalid("Файл резервной копии не найден.")
        val sealed = runCatching { json.decodeFromString<SealedUserBackup>(file.readText(Charsets.UTF_8)) }
            .getOrElse { return BackupVerifyResult.Invalid("Некорректный формат резервной копии: ${it.message}") }
        if (sealed.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return BackupVerifyResult.Invalid("Несовместимая версия схемы резервной копии: ${sealed.schemaVersion}.")
        }
        if (sealed.roomDbVersion > CURRENT_ROOM_VERSION) {
            return BackupVerifyResult.Invalid("Копия создана более новой версией БД (${sealed.roomDbVersion} > $CURRENT_ROOM_VERSION).")
        }
        val actual = sha256(sealed.payloadJson)
        if (!actual.equals(sealed.sha256, ignoreCase = true)) {
            return BackupVerifyResult.Invalid("Контрольная сумма SHA-256 резервной копии не совпадает — файл повреждён.")
        }
        val payload = runCatching { json.decodeFromString<UserBackupPayload>(sealed.payloadJson) }
            .getOrElse { return BackupVerifyResult.Invalid("Не удалось прочитать содержимое резервной копии: ${it.message}") }
        return BackupVerifyResult.Valid(payload)
    }

    suspend fun restoreBackup(
        file: File,
        db: AppDb,
        settingsRepo: SettingsRepository,
    ): Result<BackupRestoreSummary> = runCatching {
        val valid = when (val v = verifyBackup(file)) {
            is BackupVerifyResult.Valid -> v.payload
            is BackupVerifyResult.Invalid -> throw IllegalStateException(v.reason)
        }

        val sessionMap = HashMap<Long, Long>()
        for (s in valid.sessions) {
            val newId = db.sessionDao().insertSession(
                SessionEntity(
                    startedAtMs = s.startedAtMs, updatedAtMs = s.updatedAtMs, endedAtMs = s.endedAtMs,
                    adapterName = s.adapterName, transportType = s.transportType, demoMode = s.demoMode,
                    protocol = s.protocol, profileId = s.profileId, profileName = s.profileName,
                    identifiedVin = s.identifiedVin, vinMasked = s.vinMasked, ecuIds = s.ecuIds,
                    vehicleIdentitySource = s.vehicleIdentitySource, storedCount = s.storedCount,
                    pendingCount = s.pendingCount, permanentCount = s.permanentCount,
                    dtcReadCompleted = s.dtcReadCompleted, status = s.status, endReason = s.endReason,
                    rawDtcResponsesJson = s.rawDtcResponsesJson, legacyUnverified = s.legacyUnverified,
                    freezeFrameJson = s.freezeFrameJson, readinessJson = s.readinessJson,
                    discoveredEcusJson = s.discoveredEcusJson,
                ),
            )
            sessionMap[s.id] = newId
        }

        val dtcEntities = valid.dtcs.mapNotNull { d ->
            val sid = sessionMap[d.sessionId] ?: return@mapNotNull null
            DtcRecordEntity(
                sessionId = sid, code = d.code, mode = d.mode, descriptionRu = d.descriptionRu,
                descriptionEn = d.descriptionEn, scope = d.scope, timestampMs = d.timestampMs,
                ecuId = d.ecuId, rawResponse = d.rawResponse, translationStatus = d.translationStatus,
            )
        }
        if (dtcEntities.isNotEmpty()) db.dtcRecordDao().insertAll(dtcEntities)

        val sensorEntities = valid.sensors.mapNotNull { ss ->
            val sid = sessionMap[ss.sessionId] ?: return@mapNotNull null
            SessionSensorEntity(
                sessionId = sid, mode = ss.mode, pid = ss.pid, name = ss.name,
                valueText = ss.valueText, numericValue = ss.numericValue, unit = ss.unit,
                rawHex = ss.rawHex, timestampMs = ss.timestampMs,
            )
        }
        if (sensorEntities.isNotEmpty()) db.sessionSensorDao().upsertAll(sensorEntities)

        val recMap = HashMap<Long, Long>()
        for (r in valid.tripRecordings) {
            val newRecId = db.tripRecordingDao().insertRecording(
                TripRecordingEntity(
                    sessionId = r.sessionId?.let { sessionMap[it] },
                    startedAtMs = r.startedAtMs, updatedAtMs = r.updatedAtMs, endedAtMs = r.endedAtMs,
                    status = r.status, selectedPidsCsv = r.selectedPidsCsv, sampleCount = r.sampleCount,
                    errorCount = r.errorCount, maxDurationSec = r.maxDurationSec, maxSamples = r.maxSamples,
                    vehicleLabel = r.vehicleLabel, notes = r.notes,
                ),
            )
            recMap[r.id] = newRecId
        }
        val sampleEntities = valid.tripSamples.mapNotNull { sp ->
            val rid = recMap[sp.recordingId] ?: return@mapNotNull null
            TripSampleEntity(
                recordingId = rid, timestampMs = sp.timestampMs, pid = sp.pid,
                value = sp.value, unit = sp.unit, status = sp.status, errorNote = sp.errorNote,
            )
        }
        if (sampleEntities.isNotEmpty()) db.tripRecordingDao().insertSamples(sampleEntities)

        for (p in valid.profiles) {
            settingsRepo.addProfile(p)
        }
        if (valid.activeProfileId.isNotBlank()) {
            settingsRepo.setActiveProfile(valid.activeProfileId)
        }
        settingsRepo.setUnits(if (valid.settings.unitsImperial) SettingsRepository.Units.IMPERIAL else SettingsRepository.Units.METRIC)
        settingsRepo.setRefreshMs(valid.settings.refreshMs)
        settingsRepo.setMaskVin(valid.settings.maskVin)
        settingsRepo.setAutoReconnect(valid.settings.autoReconnect)
        settingsRepo.setCmdTimeoutSec(valid.settings.cmdTimeoutSec)
        settingsRepo.setMtu(valid.settings.mtu)
        settingsRepo.setAutoCheckUpdates(valid.settings.autoCheckUpdates)
        settingsRepo.setAutoUpdateDbs(valid.settings.autoUpdateDbs)
        settingsRepo.setWifiOnlyUpdates(valid.settings.wifiOnlyUpdates)
        settingsRepo.setOnlineDtcSearchEnabled(valid.settings.onlineDtcSearchEnabled)
        settingsRepo.setAutoCheckAppUpdates(valid.settings.autoCheckAppUpdates)
        settingsRepo.setNotifyAppUpdates(valid.settings.notifyAppUpdates)
        settingsRepo.setAppUpdateIntervalHours(valid.settings.appUpdateIntervalHours)

        BackupRestoreSummary(
            restoredSessions = valid.sessions.size,
            restoredDtcs = dtcEntities.size,
            restoredRecordings = valid.tripRecordings.size,
            restoredProfiles = valid.profiles.size,
        )
    }

    private fun sha256(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
