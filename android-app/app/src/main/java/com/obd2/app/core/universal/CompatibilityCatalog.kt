package com.obd2.app.core.universal

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class CatalogVehicleEntry(
    val id: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val years: String,
    val engine: String,
    val wmiPrefixes: List<String> = emptyList(),
    val calIdPrefixes: List<String> = emptyList(),
    val ecuNameKeywords: List<String> = emptyList(),
    val protocols: List<String> = emptyList(),
    val notes: String = "",
)

@Serializable
data class CompatibilityCatalogData(
    val schemaVersion: Int = 1,
    val catalogVersion: String = "0",
    val releasedAt: String = "",
    val vehicles: List<CatalogVehicleEntry> = emptyList(),
    val wmiBrands: Map<String, List<String>> = emptyMap(),
)

data class CatalogStatus(
    val activeVersion: String,
    val schemaVersion: Int,
    val source: String, // "bundled" | "local_override"
    val canRollback: Boolean,
    val vehicleCount: Int,
    val lastError: String? = null,
)

sealed interface CatalogUpdateResult {
    data class Updated(val version: String, val vehicleCount: Int) : CatalogUpdateResult
    data class Rejected(val reason: String) : CatalogUpdateResult
}

/**
 * Offline updatable vehicle/ECU compatibility catalog manager.
 *
 * Security & reliability guarantees:
 * - Pure declarative JSON (no executable code or dynamic dex loading).
 * - Schema version check (`schemaVersion == SUPPORTED_SCHEMA_VERSION`).
 * - Optional/required SHA-256 integrity verification for external updates.
 * - Automatic backup of previous override (`previous_catalog.json`) + instant rollback.
 * - Never marks unconfirmed functions as executable on a vehicle.
 */
class CompatibilityCatalogManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File by lazy { File(context.filesDir, "catalog").apply { mkdirs() } }
    private val activeFile: File get() = File(dir, "active_catalog.json")
    private val prevFile: File get() = File(dir, "previous_catalog.json")

    @Volatile
    private var cached: CompatibilityCatalogData? = null

    @Volatile
    private var lastError: String? = null

    fun load(): CompatibilityCatalogData {
        cached?.let { return it }
        val local = if (activeFile.exists()) {
            runCatching {
                val parsed = json.decodeFromString<CompatibilityCatalogData>(activeFile.readText(Charsets.UTF_8))
                require(parsed.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
                    "Неподдерживаемая версия схемы каталога: ${parsed.schemaVersion}"
                }
                parsed
            }.onFailure {
                lastError = "Локальное обновление каталога повреждено (${it.message}); используется встроенный каталог."
            }.getOrNull()
        } else {
            null
        }
        val resolved = local ?: loadBundled()
        cached = resolved
        return resolved
    }

    fun loadBundled(): CompatibilityCatalogData = runCatching {
        val raw = context.assets.open("compatibility_catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<CompatibilityCatalogData>(raw)
    }.getOrElse {
        CompatibilityCatalogData()
    }

    fun status(): CatalogStatus {
        val data = load()
        return CatalogStatus(
            activeVersion = data.catalogVersion,
            schemaVersion = data.schemaVersion,
            source = if (activeFile.exists() && lastError == null) "local_override" else "bundled",
            canRollback = prevFile.exists() || activeFile.exists(),
            vehicleCount = data.vehicles.size,
            lastError = lastError,
        )
    }

    /**
     * Installs an offline catalog update JSON after checking:
     * 1) SHA-256 integrity against [expectedSha256],
     * 2) schemaVersion == 1,
     * 3) non-empty catalogVersion.
     */
    fun applyUpdate(rawJson: String, expectedSha256: String): CatalogUpdateResult {
        val actualHash = sha256Hex(rawJson)
        if (!actualHash.equals(expectedSha256.trim(), ignoreCase = true)) {
            return CatalogUpdateResult.Rejected("Ошибка целостности SHA-256: контрольная сумма каталога не совпадает.")
        }
        val parsed = runCatching { json.decodeFromString<CompatibilityCatalogData>(rawJson) }
            .getOrElse { return CatalogUpdateResult.Rejected("Некорректный JSON каталога: ${it.message}") }

        if (parsed.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return CatalogUpdateResult.Rejected(
                "Несовместимая версия схемы каталога: ${parsed.schemaVersion} (ожидается $SUPPORTED_SCHEMA_VERSION).",
            )
        }
        if (parsed.catalogVersion.isBlank()) {
            return CatalogUpdateResult.Rejected("В обновлении отсутствует catalogVersion.")
        }

        if (activeFile.exists()) {
            activeFile.copyTo(prevFile, overwrite = true)
        }
        activeFile.writeText(json.encodeToString(parsed), Charsets.UTF_8)
        lastError = null
        cached = parsed
        return CatalogUpdateResult.Updated(parsed.catalogVersion, parsed.vehicles.size)
    }

    fun rollback(): CatalogStatus {
        if (prevFile.exists()) {
            prevFile.copyTo(activeFile, overwrite = true)
            prevFile.delete()
        } else if (activeFile.exists()) {
            activeFile.delete()
        }
        lastError = null
        cached = null
        return status()
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
