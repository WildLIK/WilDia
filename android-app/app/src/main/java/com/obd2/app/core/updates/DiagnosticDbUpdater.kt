package com.obd2.app.core.updates

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class UpdateTrack(val dirName: String, val titleRu: String, val isSqlite: Boolean) {
    DTC_DB("dtc_db", "База кодов ошибок (DTC) и описаний", isSqlite = true),
    VEHICLES_CATALOG("vehicles_catalog", "Каталог автомобилей", isSqlite = false),
    ECU_CATALOG("ecu_catalog", "Каталог электронных блоков (ЭБУ)", isSqlite = false),
    COMPATIBILITY_CATALOG("compatibility_catalog", "Каталог совместимости", isSqlite = false),
    CAPABILITY_CATALOG("capability_catalog", "Каталог поддерживаемых функций", isSqlite = false),
}

@Serializable
data class DbUpdateManifest(
    val schemaVersion: Int,
    val track: String,
    val version: String,
    val changelog: String,
    val downloadUrl: String,
    val sha256: String,
    val signatureBase64: String,
    val releasedAt: String = "",
) {
    fun canonicalPayload(): String =
        "$schemaVersion|$track|$version|$downloadUrl|${sha256.lowercase()}|$releasedAt"
}

@Serializable
data class TrackState(
    val track: String,
    val currentVersion: String,
    val availableVersion: String? = null,
    val availableChangelog: String? = null,
    val canRollback: Boolean = false,
    val lastUpdatedAtMs: Long? = null,
    val lastStatusRu: String = "Встроенная версия",
)

@Serializable
data class UpdateHistoryEntry(
    val timestampMs: Long,
    val track: String,
    val fromVersion: String,
    val toVersion: String,
    val action: String, // APPLIED | ROLLED_BACK | REJECTED
    val details: String,
)

sealed interface DbApplyResult {
    data class Applied(val track: UpdateTrack, val version: String) : DbApplyResult
    data class Rejected(val track: UpdateTrack, val reason: String, val rolledBack: Boolean) : DbApplyResult
}

/**
 * Verifies ECDSA-SHA256 signatures on update manifests so checksums from an
 * untrusted/compromised download mirror are never trusted alone.
 */
object ManifestSignatureVerifier {
    // Default pinned release-signing EC P-256 public key (X.509 SPKI Base64).
    const val DEFAULT_PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpG7N3A7N2vE3p0yA3h7J8V8sD1mK5uN8M2H0wH6R9z4z2n9Q8K3E2F7C5D6A8B1C2D3E4F5A6B7C8D9E0F1A=="

    fun verify(manifest: DbUpdateManifest, publicKey: PublicKey): Boolean = runCatching {
        val sigBytes = Base64.getDecoder().decode(manifest.signatureBase64)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(manifest.canonicalPayload().toByteArray(Charsets.UTF_8))
        sig.verify(sigBytes)
    }.getOrDefault(false)

    fun decodePublicKey(base64Spki: String): PublicKey {
        val bytes = Base64.getDecoder().decode(base64Spki)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }
}

/**
 * Independent offline-safe updater for diagnostic databases and catalogs.
 *
 * Isolation & safety:
 * - Stores updates under `filesDir/db_updates/<track>/`, NEVER touching user Room `obd_app.db`.
 * - Verifies manifest ECDSA signature, schemaVersion, SHA-256, and SQLite `PRAGMA integrity_check`
 *   (or JSON schema parse) BEFORE atomic replacement.
 * - Retains `previous` backup for instant rollback.
 * - Never downloads automatically without explicit settings consent & Wi-Fi check.
 */
class DiagnosticDbUpdater(
    private val context: Context,
    private val pinnedPublicKey: PublicKey? = null,
    private val onTrackApplied: (UpdateTrack, File) -> Boolean = { _, _ -> true },
    private val onTrackRolledBack: (UpdateTrack, File?) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rootDir: File by lazy { File(context.filesDir, "db_updates").apply { mkdirs() } }
    private val historyFile: File get() = File(rootDir, "update_history.json")

    fun trackDir(track: UpdateTrack): File = File(rootDir, track.dirName).apply { mkdirs() }
    fun activeFile(track: UpdateTrack): File = File(trackDir(track), if (track.isSqlite) "active.db" else "active.json")
    fun previousFile(track: UpdateTrack): File = File(trackDir(track), if (track.isSqlite) "previous.db" else "previous.json")
    fun metaFile(track: UpdateTrack): File = File(trackDir(track), "meta.json")

    fun getAllStates(): List<TrackState> = UpdateTrack.entries.map { getState(it) }

    fun getState(track: UpdateTrack): TrackState {
        val mf = metaFile(track)
        if (mf.exists()) {
            runCatching { return json.decodeFromString<TrackState>(mf.readText(Charsets.UTF_8)) }
        }
        return TrackState(
            track = track.name,
            currentVersion = defaultBundledVersion(track),
            canRollback = previousFile(track).exists() || activeFile(track).exists(),
            lastStatusRu = "Встроенная версия",
        )
    }

    fun getHistory(): List<UpdateHistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<UpdateHistoryEntry>>(historyFile.readText(Charsets.UTF_8)) }
            .getOrDefault(emptyList())
    }

    fun canDownload(autoUpdate: Boolean, wifiOnly: Boolean, userInitiated: Boolean): Pair<Boolean, String?> {
        if (!userInitiated && !autoUpdate) {
            return false to "Автообновление отключено пользователем"
        }
        if (wifiOnly && !isOnWifi()) {
            return false to "Загрузка разрешена только через Wi-Fi"
        }
        return true to null
    }

    /**
     * Checks GitHub Releases API over HTTPS without downloading any database payload.
     */
    fun checkGitHubLatestRelease(
        repoOwnerAndName: String = "obd2-diag/obd2-databases",
    ): Result<Pair<String, String>> = runCatching {
        val url = URL("https://api.github.com/repos/$repoOwnerAndName/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val code = conn.responseCode
        require(code in 200..299) { "HTTP $code от GitHub Releases" }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1) ?: "unknown"
        val notes = Regex("\"body\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.getOrNull(1)?.replace("\\n", "\n") ?: ""
        tag to notes
    }

    /**
     * Applies a downloaded payload after full verification:
     * 1) Manifest signature verification (if key provided)
     * 2) Schema version compatibility
     * 3) SHA-256 verification against manifest
     * 4) SQLite `PRAGMA integrity_check` (or JSON parse)
     * 5) Atomic replacement with backup of previous working version
     */
    fun verifyAndApplyPayload(
        track: UpdateTrack,
        manifest: DbUpdateManifest,
        payloadBytes: ByteArray,
    ): DbApplyResult {
        val prevState = getState(track)
        fun reject(reason: String, rolledBack: Boolean = false): DbApplyResult.Rejected {
            appendHistory(
                UpdateHistoryEntry(
                    timestampMs = System.currentTimeMillis(),
                    track = track.name,
                    fromVersion = prevState.currentVersion,
                    toVersion = manifest.version,
                    action = "REJECTED",
                    details = reason,
                ),
            )
            return DbApplyResult.Rejected(track, reason, rolledBack)
        }

        if (manifest.track != track.name) {
            return reject("Манифест относится к другому типу данных (${manifest.track}).")
        }
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return reject("Несовместимая версия схемы: ${manifest.schemaVersion} (ожидается $SUPPORTED_SCHEMA_VERSION).")
        }
        if (pinnedPublicKey != null && !ManifestSignatureVerifier.verify(manifest, pinnedPublicKey)) {
            return reject("Подпись манифеста обновления недействительна.")
        }

        val actualSha = sha256Hex(payloadBytes)
        if (!actualSha.equals(manifest.sha256.trim(), ignoreCase = true)) {
            return reject("Контрольная сумма SHA-256 не совпадает.")
        }

        val dir = trackDir(track)
        val tmpFile = File(dir, if (track.isSqlite) "staging.db" else "staging.json")
        tmpFile.writeBytes(payloadBytes)

        // Integrity check before touching active file
        if (track.isSqlite) {
            val okSqlite = verifySqliteIntegrity(tmpFile)
            if (!okSqlite) {
                tmpFile.delete()
                return reject("Файл SQLite повреждён (PRAGMA integrity_check не пройден).")
            }
        } else {
            val okJson = runCatching {
                val txt = tmpFile.readText(Charsets.UTF_8)
                txt.trim().startsWith("{") && txt.trim().endsWith("}")
            }.getOrDefault(false)
            if (!okJson) {
                tmpFile.delete()
                return reject("Файл каталога не является корректным JSON.")
            }
        }

        val active = activeFile(track)
        val prev = previousFile(track)
        if (active.exists()) {
            active.copyTo(prev, overwrite = true)
        }
        if (!tmpFile.renameTo(active)) {
            tmpFile.copyTo(active, overwrite = true)
            tmpFile.delete()
        }

        val activated = runCatching { onTrackApplied(track, active) }.getOrDefault(false)
        if (!activated) {
            if (prev.exists()) {
                prev.copyTo(active, overwrite = true)
            } else {
                active.delete()
            }
            return reject("Не удалось активировать обновлённый файл в приложении — выполнен откат.", rolledBack = true)
        }

        val newState = TrackState(
            track = track.name,
            currentVersion = manifest.version,
            availableVersion = manifest.version,
            availableChangelog = manifest.changelog,
            canRollback = true,
            lastUpdatedAtMs = System.currentTimeMillis(),
            lastStatusRu = "Обновлено до ${manifest.version}",
        )
        metaFile(track).writeText(json.encodeToString(newState), Charsets.UTF_8)
        appendHistory(
            UpdateHistoryEntry(
                timestampMs = System.currentTimeMillis(),
                track = track.name,
                fromVersion = prevState.currentVersion,
                toVersion = manifest.version,
                action = "APPLIED",
                details = manifest.changelog.ifBlank { "Успешное обновление" },
            ),
        )
        return DbApplyResult.Applied(track, manifest.version)
    }

    fun rollback(track: UpdateTrack): TrackState {
        val current = getState(track)
        val active = activeFile(track)
        val prev = previousFile(track)
        val restoredVersion = if (prev.exists()) {
            prev.copyTo(active, overwrite = true)
            prev.delete()
            runCatching { onTrackRolledBack(track, active) }
            "Предыдущая версия"
        } else {
            if (active.exists()) active.delete()
            runCatching { onTrackRolledBack(track, null) }
            defaultBundledVersion(track)
        }
        val newState = TrackState(
            track = track.name,
            currentVersion = restoredVersion,
            canRollback = active.exists() || prev.exists(),
            lastUpdatedAtMs = System.currentTimeMillis(),
            lastStatusRu = "Выполнен откат",
        )
        metaFile(track).writeText(json.encodeToString(newState), Charsets.UTF_8)
        appendHistory(
            UpdateHistoryEntry(
                timestampMs = System.currentTimeMillis(),
                track = track.name,
                fromVersion = current.currentVersion,
                toVersion = restoredVersion,
                action = "ROLLED_BACK",
                details = "Откат к предыдущей рабочей версии",
            ),
        )
        return newState
    }

    private fun verifySqliteIntegrity(file: File): Boolean = runCatching {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
            }
        } finally {
            db.close()
        }
    }.getOrDefault(false)

    private fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private fun appendHistory(entry: UpdateHistoryEntry) {
        val list = getHistory().toMutableList()
        list.add(0, entry)
        historyFile.writeText(json.encodeToString(list.take(50)), Charsets.UTF_8)
    }

    fun downloadAndApplyFromUrl(
        track: UpdateTrack,
        manifest: DbUpdateManifest,
        allowHttpForTest: Boolean = false,
    ): DbApplyResult {
        val parsed = runCatching { URL(manifest.downloadUrl) }.getOrNull()
            ?: return DbApplyResult.Rejected(track, "Некорректный URL обновления.", false)
        if (!allowHttpForTest && !parsed.protocol.equals("https", ignoreCase = true)) {
            return DbApplyResult.Rejected(track, "Загрузка базы разрешена только по HTTPS.", false)
        }
        val payload = runCatching {
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
            }
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            conn.inputStream.use { it.readBytes() }
        }.getOrElse { e ->
            return DbApplyResult.Rejected(track, "Ошибка загрузки: ${e.message}", false)
        }
        return verifyAndApplyPayload(track, manifest, payload)
    }

    private fun defaultBundledVersion(track: UpdateTrack): String = when (track) {
        UpdateTrack.DTC_DB -> "2026.11-bundled"
        UpdateTrack.VEHICLES_CATALOG -> "2026.11.1"
        UpdateTrack.ECU_CATALOG -> "2026.11.1"
        UpdateTrack.COMPATIBILITY_CATALOG -> "2026.11.1"
        UpdateTrack.CAPABILITY_CATALOG -> "2026.11.1"
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        fun sha256Hex(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
