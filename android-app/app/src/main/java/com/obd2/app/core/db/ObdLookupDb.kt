package com.obd2.app.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.obd2.app.core.obd.PidDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only bundled lookup database (assets/obd_data.db, schema v2).
 * Copied to filesDir on first access; replaced automatically when the asset
 * carries a different `meta.version` (so a database update arrives without a
 * full app reinstall, and the legacy marker bug is fixed).
 *
 * v2 tables (see tools/schema.sql): data_sources, manufacturers, vehicles,
 * ecus, protocols, dtc_definitions (+translations/causes/symptoms/checks),
 * parameter_definitions, vehicle_parameter_support — plus the legacy v1
 * tables pids/dtc/dtc_categories kept for backwards compatibility.
 */
class ObdLookupDb(private val context: Context) {

    private var open: SQLiteDatabase? = null

    data class DbMeta(
        val version: String,
        val builtAt: String,
        val pids: Int,
        val dtcTotal: Int,
        val dtcGeneric: Int,
        val dtcManufacturer: Int,
        val makers: Int,
        val ruTranslations: Int,
        val ruVerified: Int,
        val ruAuto: Int,
        val paramDefinitions: Int,
        val didDefinitions: Int,
        val manufacturers: Int,
        val generations: Int,
        val ecus: Int,
        val sources: String,
    )

    /**
     * The bundled database is fully built offline; it is opened read-only via
     * SQLiteDatabase.openDatabase (not SQLiteOpenHelper) so version metadata
     * never triggers runtime "upgrades" on a prebuilt file.
     *
     * Versioning is pointer-based: every database version is a separate file
     * and the tiny marker text file (obd_data.version) names the active one.
     * Files are never overwritten or renamed after being opened — SQLite on
     * Windows/Robolectric releases file handles lazily, so open-then-replace
     * of the same file is not reliable.
     */
    private fun ensure(): SQLiteDatabase {
        val dir = context.getDatabasePath("lookup").parentFile ?: context.filesDir
        synchronized(ensureLock) {
            val markerName = readMarker(dir)
            if (markerName != null) {
                val f = java.io.File(dir, markerName)
                if (f.exists()) {
                    val db = runCatching { openReadOnly(f) }.getOrNull()
                    if (db != null && tableExists(db, "dtc_definitions")) {
                        return db
                    }
                    runCatching { db?.close() }
                }
            }
            return copyAssetAndOpen(dir)
        }
    }

    /** Copies the bundled asset under a fresh, never-existing name and opens it. */
    private fun copyAssetAndOpen(dir: java.io.File): SQLiteDatabase {
        dir.mkdirs()
        for (attempt in 1..5) {
            val f = java.io.File(dir, "obd_data_${System.currentTimeMillis()}_$attempt.db")
            if (f.exists()) continue
            context.assets.open("obd_data.db").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            val db = runCatching { openReadOnly(f) }.getOrNull()
            if (db != null && tableExists(db, "dtc_definitions")) {
                writeMarker(dir, f.name)
                cleanupStaleFiles(dir, f.name)
                return db
            }
            runCatching { db?.close() }
            runCatching { f.delete() }
        }
        throw IllegalStateException("Не удалось развернуть офлайн-базу данных")
    }

    private fun readMarker(dir: java.io.File): String? =
        java.io.File(dir, "obd_data.version").readTextOrNull()?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }

    private fun writeMarker(dir: java.io.File, name: String) {
        val marker = java.io.File(dir, "obd_data.version")
        runCatching { marker.delete() }
        marker.writeText(name)
    }

    /** Best-effort removal of non-active obd_data files (may fail if locked). */
    private fun cleanupStaleFiles(dir: java.io.File, activeName: String) {
        dir.listFiles { f -> f.name.startsWith("obd_data") && f.name.endsWith(".db") && f.name != activeName }
            ?.forEach { f ->
                runCatching { f.delete() }
            }
    }

    private fun openReadOnly(file: java.io.File): SQLiteDatabase {
        open?.let { existing ->
            if (existing.isOpen && existing.path == file.absolutePath) {
                return existing
            }
            if (existing.isOpen) {
                runCatching { existing.close() }
            }
            open = null
        }
        sharedConnection?.let { (sharedPath, sharedDb) ->
            if (sharedPath == file.absolutePath && sharedDb.isOpen) {
                open = sharedDb
                return sharedDb
            }
        }
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        open = db
        sharedConnection = file.absolutePath to db
        return db
    }

    fun close() {
        synchronized(ensureLock) {
            open?.let { runCatching { it.close() } }
            open = null
            sharedConnection?.second?.let { runCatching { it.close() } }
            sharedConnection = null
        }
    }

    private fun closeSharedConnection(path: String) {
        sharedConnection?.let { (p, db) ->
            if (p == path) {
                runCatching { db.close() }
                sharedConnection = null
                open = null
            }
        }
    }

    /**
     * Atomically installs a pre-verified external SQLite database (`DiagnosticDbUpdater`)
     * by copying it to a fresh versioned filename, updating `obd_data.version`, and
     * closing any previously open handle so subsequent reads use the new database immediately.
     */
    fun installVerifiedDatabase(sourceFile: java.io.File): Boolean {
        if (!sourceFile.exists() || !sourceFile.isFile) return false
        val dir = context.getDatabasePath("lookup").parentFile ?: context.filesDir
        synchronized(ensureLock) {
            dir.mkdirs()
            val target = java.io.File(dir, "obd_data_upd_${System.currentTimeMillis()}.db")
            return try {
                sourceFile.copyTo(target, overwrite = true)
                val probe = SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val valid = try {
                    tableExists(probe, "dtc_definitions")
                } finally {
                    probe.close()
                }
                if (!valid) {
                    target.delete()
                    return false
                }
                sharedConnection?.first?.let { closeSharedConnection(it) }
                writeMarker(dir, target.name)
                cleanupStaleFiles(dir, target.name)
                true
            } catch (_: Exception) {
                runCatching { target.delete() }
                false
            }
        }
    }

    /** Reverts the active lookup database back to the bundled APK asset. */
    fun revertToBundled(): Boolean {
        val dir = context.getDatabasePath("lookup").parentFile ?: context.filesDir
        synchronized(ensureLock) {
            sharedConnection?.first?.let { closeSharedConnection(it) }
            runCatching { java.io.File(dir, "obd_data.version").delete() }
            return runCatching {
                copyAssetAndOpen(dir)
                true
            }.getOrDefault(false)
        }
    }

    @Volatile
    private var sharedConnection: Pair<String, SQLiteDatabase>? = null

    private val ensureLock = Any()

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name),
        ).use { it.moveToFirst() }

    /** Opens a standalone (possibly foreign) SQLite file and reads meta.version. */
    private fun readDbVersion(file: java.io.File): String? = try {
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            readDbVersion(db)
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        null
    }

    private fun readDbVersion(db: SQLiteDatabase): String? = try {
        db.rawQuery("SELECT value FROM meta WHERE key='version'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun java.io.File.readTextOrNull(): String? = try {
        if (exists()) readText() else null
    } catch (e: Exception) {
        null
    }

    /**
     * Imports an external SQLite data package (Settings screen, SAF uri).
     * Returns null on success or a human-readable error. The imported file is
     * stored under a fresh name and becomes active via the marker; an older
     * or invalid file is rejected.
     */
    suspend fun importDatabase(uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        val dir = context.getDatabasePath("lookup").parentFile ?: context.filesDir
        synchronized(ensureLock) {
            val current = readDbVersion(ensure()) ?: "0"
            for (attempt in 1..5) {
                val f = java.io.File(dir, "obd_data_import_${System.currentTimeMillis()}_$attempt.db")
                if (f.exists()) continue
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext "Не удалось открыть файл"
                    val db = runCatching {
                        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    }.getOrNull()
                    val version = db?.use { readDbVersion(it) }
                    if (version == null) {
                        runCatching { db?.close() }
                        f.delete()
                        return@withContext "Файл не является базой данных OBD2 (нет meta.version)"
                    }
                    if (compareVersions(version, current) < 0) {
                        runCatching { db?.close() }
                        f.delete()
                        return@withContext "Версия файла ($version) старше текущей ($current)"
                    }
                    if (!tableExists(db, "dtc_definitions")) {
                        runCatching { db?.close() }
                        f.delete()
                        return@withContext "Импортированный файл повреждён (нет ожидаемых таблиц)"
                    }
                    // Valid and current: repoint the marker and reopen lazily.
                    runCatching { db.close() }
                    closeSharedConnection(activeDatabasePathSafe(dir))
                    writeMarker(dir, f.name)
                    cleanupStaleFiles(dir, f.name)
                    return@withContext null
                } catch (e: Exception) {
                    runCatching { f.delete() }
                    return@withContext "Ошибка импорта: ${e.message}"
                }
            }
            "Не удалось сохранить импортированный файл"
        }
    }

    private fun activeDatabasePathSafe(dir: java.io.File): String {
        val name = readMarker(dir) ?: "obd_data.db"
        return java.io.File(dir, name).absolutePath
    }

    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    suspend fun meta(): DbMeta = withContext(Dispatchers.IO) {
        val db = ensure()
        val m = HashMap<String, String>()
        db.rawQuery("SELECT key, value FROM meta", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
        }
        DbMeta(
            version = m["version"] ?: "?",
            builtAt = m["built_at"] ?: "?",
            pids = m["pids"]?.toIntOrNull() ?: 0,
            dtcTotal = m["dtc_total"]?.toIntOrNull() ?: 0,
            dtcGeneric = m["dtc_generic"]?.toIntOrNull() ?: 0,
            dtcManufacturer = m["dtc_manufacturer"]?.toIntOrNull() ?: 0,
            makers = m["dtc_manufacturers"]?.toIntOrNull() ?: 0,
            ruTranslations = m["dtc_ru_translations"]?.toIntOrNull() ?: 0,
            ruVerified = m["dtc_ru_verified"]?.toIntOrNull() ?: 0,
            ruAuto = m["dtc_ru_auto"]?.toIntOrNull() ?: 0,
            paramDefinitions = m["param_definitions"]?.toIntOrNull() ?: 0,
            didDefinitions = m["param_dids"]?.toIntOrNull() ?: 0,
            manufacturers = m["manufacturers"]?.toIntOrNull() ?: 0,
            generations = m["vehicle_generations"]?.toIntOrNull() ?: 0,
            ecus = m["ecus"]?.toIntOrNull() ?: 0,
            sources = listOfNotNull(
                m["source_pid"], m["source_dtc_generic"], m["source_dtc_manufacturer"],
                m["translation_attribution"],
            ).joinToString("; "),
        )
    }

    /** integrity_check for diagnostics. */
    suspend fun integrityCheck(): String = withContext(Dispatchers.IO) {
        ensure().rawQuery("PRAGMA integrity_check", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else "unknown"
        }
    }

    suspend fun allPids(): List<PidDefinition> = withContext(Dispatchers.IO) {
        val db = ensure()
        val out = ArrayList<PidDefinition>(200)
        db.rawQuery(
            "SELECT mode, pid, name_en, name_ru, unit, bytes, decoder, weight, mult, \"offset\", formula, category FROM pids ORDER BY mode, pid",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PidDefinition(
                        mode = c.getInt(0),
                        pid = c.getInt(1),
                        nameEn = c.getString(2),
                        nameRu = if (c.isNull(3)) null else c.getString(3),
                        unit = if (c.isNull(4)) null else c.getString(4),
                        bytes = if (c.isNull(5)) null else c.getInt(5),
                        decoder = c.getString(6),
                        weight = if (c.isNull(7)) null else c.getString(7),
                        mult = if (c.isNull(8)) null else c.getDouble(8),
                        offset = if (c.isNull(9)) null else c.getDouble(9),
                        formula = if (c.isNull(10)) null else c.getString(10),
                        category = c.getString(11),
                    ),
                )
            }
        }
        out
    }

    /** Catalog of parameter definitions (PIDs, DID, VAG info) for display. */
    data class ParamCatalogEntry(
        val id: String,
        val kind: String,
        val scope: String,
        val mode: Int?,
        val pidHex: String?,
        val nameEn: String,
        val nameRu: String?,
        val unit: String?,
        val formula: String?,
        val notes: String?,
    )

    suspend fun parameterCatalog(limit: Int = 400): List<ParamCatalogEntry> =
        withContext(Dispatchers.IO) {
            val db = ensure()
            val out = ArrayList<ParamCatalogEntry>()
            db.rawQuery(
                "SELECT id, kind, scope, mode, pid_hex, name_en, name_ru, unit, formula, notes " +
                    "FROM parameter_definitions ORDER BY kind, mode, pid_hex LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ParamCatalogEntry(
                            id = c.getString(0),
                            kind = c.getString(1),
                            scope = c.getString(2),
                            mode = if (c.isNull(3)) null else c.getInt(3),
                            pidHex = if (c.isNull(4)) null else c.getString(4),
                            nameEn = c.getString(5),
                            nameRu = if (c.isNull(6)) null else c.getString(6),
                            unit = if (c.isNull(7)) null else c.getString(7),
                            formula = if (c.isNull(8)) null else c.getString(8),
                            notes = if (c.isNull(9)) null else c.getString(9),
                        ),
                    )
                }
            }
            out
        }

    /** Rich DTC lookup over the v2 tables (RU-first, with provenance). */
    data class DtcLookup(
        val code: String,
        val scope: String,
        val standard: String,
        val titleEn: String,
        val titleRu: String?,
        val translationStatus: String?,  // verified | auto | null
        val detailEn: String?,
        val detailRu: String?,
        val recommendationsRu: String?,
        val severity: String?,
        val severityReason: String?,
        val mil: Int?,
        val limpPossible: Int?,
        val causes: List<String>,
        val causeStatuses: List<String>,
        val symptoms: List<String>,
        val symptomStatuses: List<String>,
        val checks: List<String>,
        val relatedCodes: List<String>,
        val source: String,
        val groupTag: String,
    ) {
        /** v1-compat alias used by the repository and screens. */
        val description: String get() = titleEn
        val descriptionRu: String? get() = titleRu
        val longDescription: String? get() = detailEn
    }

    private fun queryDtcs(db: SQLiteDatabase, where: String, args: Array<String>, limit: Int?): List<DtcLookup> {
        val lim = limit?.toString() ?: "-1"
        val out = ArrayList<DtcLookup>()
            db.rawQuery(
                "SELECT d.code, d.manufacturer_id, d.standard, d.title_en, d.long_desc_en, " +
                    "d.severity, d.severity_reason, d.mil, d.limp_mode_possible, " +
                    "t.title_ru, t.translation_status, t.detail_ru, " +
                    "t.recommendations_ru, d.related_codes, d.source_id " +
                    "FROM dtc_definitions d " +
                    "LEFT JOIN dtc_translations t ON t.code=d.code AND t.manufacturer_id=d.manufacturer_id " +
                    "AND t.standard=d.standard AND t.lang='ru' " +
                    "WHERE $where ORDER BY (d.manufacturer_id='GENERIC') DESC, d.manufacturer_id " +
                    "LIMIT $lim",
                args,
            ).use { c ->
            while (c.moveToNext()) {
                val code = c.getString(0)
                val maker = c.getString(1)
                val standard = c.getString(2)
                val causes = ArrayList<String>()
                val causeStatuses = ArrayList<String>()
                db.rawQuery(
                    "SELECT ct.text_ru, ct.translation_status, pc.text_en, pc.likelihood " +
                        "FROM dtc_probable_causes pc " +
                        "LEFT JOIN dtc_cause_translations ct ON ct.code=pc.code AND ct.manufacturer_id=pc.manufacturer_id " +
                        "AND ct.standard=pc.standard AND ct.ord=pc.ord AND ct.lang='ru' " +
                        "WHERE pc.code=? AND pc.manufacturer_id=? AND pc.standard=? " +
                        "ORDER BY pc.ord LIMIT 8",
                    arrayOf(code, maker, standard),
                ).use { cc ->
                    while (cc.moveToNext()) {
                        val ru = if (cc.isNull(0)) null else cc.getString(0)
                        val en = cc.getString(2)
                        val lik = if (cc.isNull(3)) "" else cc.getString(3)
                        val text = ru ?: en ?: continue
                        val likLabel = when (lik.trim().lowercase()) {
                            "high" -> "справочная причина: частая"
                            "medium" -> "справочная причина: возможная"
                            "low" -> "справочная причина: редкая"
                            "" -> ""
                            else -> "справочная причина"
                        }
                        causes.add(if (likLabel.isNotBlank()) "$text ($likLabel)" else text)
                        causeStatuses.add(if (ru != null) (cc.getString(1) ?: "auto") else "en")
                    }
                }
                val symptoms = ArrayList<String>()
                val symptomStatuses = ArrayList<String>()
                db.rawQuery(
                    "SELECT st.text_ru, st.translation_status, sy.text_en " +
                        "FROM dtc_symptoms sy " +
                        "LEFT JOIN dtc_symptom_translations st ON st.code=sy.code AND st.manufacturer_id=sy.manufacturer_id " +
                        "AND st.standard=sy.standard AND st.ord=sy.ord AND st.lang='ru' " +
                        "WHERE sy.code=? AND sy.manufacturer_id=? AND sy.standard=? " +
                        "ORDER BY sy.ord LIMIT 8",
                    arrayOf(code, maker, standard),
                ).use { cc ->
                    while (cc.moveToNext()) {
                        val ru = if (cc.isNull(0)) null else cc.getString(0)
                        val text = ru ?: cc.getString(2) ?: continue
                        symptoms.add(text)
                        symptomStatuses.add(if (ru != null) (cc.getString(1) ?: "auto") else "en")
                    }
                }
                val checks = ArrayList<String>()
                db.rawQuery(
                    "SELECT text_ru FROM dtc_checks WHERE code=? AND manufacturer_id=? AND standard=? ORDER BY ord",
                    arrayOf(code, maker, standard),
                ).use { cc ->
                    while (cc.moveToNext()) checks.add(cc.getString(0))
                }
                val related = if (c.isNull(13)) emptyList() else {
                    try {
                        val arr = org.json.JSONArray(c.getString(13))
                        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                out.add(
                    DtcLookup(
                        code = code,
                        scope = maker,
                        standard = standard,
                        titleEn = c.getString(3),
                        titleRu = if (c.isNull(9)) null else c.getString(9),
                        translationStatus = if (c.isNull(10)) null else c.getString(10),
                        detailEn = if (c.isNull(4)) null else c.getString(4),
                        detailRu = if (c.isNull(11)) null else c.getString(11),
                        recommendationsRu = if (c.isNull(12)) null else c.getString(12),
                        severity = if (c.isNull(5)) null else c.getString(5),
                        severityReason = if (c.isNull(6)) null else c.getString(6),
                        mil = if (c.isNull(7)) null else c.getInt(7),
                        limpPossible = if (c.isNull(8)) null else c.getInt(8),
                        causes = causes,
                        causeStatuses = causeStatuses,
                        symptoms = symptoms,
                        symptomStatuses = symptomStatuses,
                        checks = checks,
                        relatedCodes = related,
                        source = if (c.isNull(14)) "?" else c.getString(14),
                        groupTag = if (maker in VAG_SCOPES) "VAG" else "",
                    ),
                )
            }
        }
        return out
    }

    suspend fun lookupDtc(code: String): List<DtcLookup> = withContext(Dispatchers.IO) {
        val db = ensure()
        queryDtcs(db, "d.code=?", arrayOf(code.uppercase()), null)
    }

    /** Search by code prefix / description text (RU+EN), limited rows. */
    suspend fun searchDtc(query: String, limit: Int = 100): List<DtcLookup> = withContext(Dispatchers.IO) {
        val db = ensure()
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val like = "%${q.uppercase()}%"
        val likeLower = "%${q.lowercase()}%"
        val likeAsIs = "%$q%"
        // primary search: code / EN title / RU title (casefolded column)
        val out = queryDtcs(
            db,
            "(d.code LIKE ? OR d.title_en LIKE ? OR t.title_ru LIKE ? OR d.code IN " +
                "(SELECT code FROM dtc WHERE description_ru_lower LIKE ?))",
            arrayOf(like, like, likeAsIs, likeLower),
            limit,
        )
        if (out.size >= limit) return@withContext out
        // secondary: search inside Russian details (auto-translated long text)
        val extra = queryDtcs(
            db,
            "d.manufacturer_id='GENERIC' AND t.detail_ru_lower LIKE ? " +
                "AND d.code NOT IN (" + out.joinToString(",") { "'" + it.code + "'" }.ifEmpty { "''" } + ")",
            arrayOf(likeLower),
            limit - out.size,
        )
        out + extra
    }

    data class CategoryInfo(val prefix: String, val systemEn: String, val systemRu: String)

    suspend fun categories(): List<CategoryInfo> = withContext(Dispatchers.IO) {
        val db = ensure()
        val out = ArrayList<CategoryInfo>()
        db.rawQuery("SELECT prefix, system_en, system_ru FROM dtc_categories ORDER BY prefix", null).use { c ->
            while (c.moveToNext()) {
                out.add(CategoryInfo(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            }
        }
        out
    }

    data class ManufacturerInfo(val id: String, val nameRu: String, val nameEn: String, val codes: Int)

    suspend fun manufacturers(): List<ManufacturerInfo> = withContext(Dispatchers.IO) {
        val db = ensure()
        val out = ArrayList<ManufacturerInfo>()
        db.rawQuery(
            "SELECT m.id, m.name_ru, m.name_en, COUNT(d.code) AS cnt " +
                "FROM manufacturers m LEFT JOIN dtc_definitions d ON d.manufacturer_id=m.id " +
                "GROUP BY m.id HAVING cnt > 0 ORDER BY cnt DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ManufacturerInfo(c.getString(0), c.getString(1) ?: "", c.getString(2), c.getInt(3)))
            }
        }
        out
    }

    /** Vehicle profile for the current generation (Skoda Octavia A5). */
    data class VehicleProfile(
        val generationId: String,
        val title: String,
        val years: String,
        val platform: String,
        val engine: String?,
        val engineSpecs: String?,
        val protocol: String?,
        val ecus: List<EcuRow>,
        val supportExpected: Int,
        val supportUnknown: Int,
        val supportConfirmed: Int,
        val adapterNote: String?,
    )

    data class EcuRow(
        val partNumber: String,
        val system: String,
        val systemRu: String,
        val controller: String?,
        val confidence: String,
    )

    suspend fun vehicleProfile(generationId: String): VehicleProfile? = withContext(Dispatchers.IO) {
        val db = ensure()
        val g = db.rawQuery(
            "SELECT g.code, g.name_ru, g.years_start, g.years_end, g.platform, " +
                "e.code, e.name_ru, e.displacement_l, e.power_hp, e.torque_nm, p.name_ru " +
                "FROM vehicle_generations g " +
                "LEFT JOIN engine_fitment ef ON ef.generation_id=g.id " +
                "LEFT JOIN engines e ON e.id=ef.engine_id " +
                "LEFT JOIN ecus ecu ON ecu.id=(SELECT ecu_id FROM vehicle_ecus WHERE generation_id=g.id " +
                "  AND ecu_id IN (SELECT id FROM ecus WHERE system='engine') LIMIT 1) " +
                "LEFT JOIN protocols p ON p.id=ecu.protocol_id " +
                "WHERE g.id=? LIMIT 1",
            arrayOf(generationId),
        ).use { c ->
            if (c.moveToFirst()) {
                val years = if (c.isNull(2) || c.isNull(3)) "" else "${c.getInt(2)}–${c.getInt(3)}"
                val engineSpecs = if (c.isNull(6)) null else {
                    val disp = if (c.isNull(7)) "?" else c.getDouble(7).toString()
                    val hp = if (c.isNull(8)) "?" else c.getInt(8).toString()
                    val nm = if (c.isNull(9)) "?" else c.getInt(9).toString()
                    "${c.getString(6)} · ${disp} л · ${hp} л.с. · ${nm} Н·м"
                }
                arrayOf(
                    c.getString(0) ?: "", c.getString(1) ?: "", years,
                    c.getString(4) ?: "", c.getString(5), engineSpecs,
                    if (c.isNull(10)) null else c.getString(10),
                )
            } else null
        } ?: return@withContext null

        val ecus = ArrayList<EcuRow>()
        db.rawQuery(
            "SELECT e.part_number_display, e.system, e.oem_name_ru, e.controller_family, e.confidence " +
                "FROM vehicle_ecus ve JOIN ecus e ON e.id=ve.ecu_id " +
                "WHERE ve.generation_id=? ORDER BY CASE e.system " +
                "WHEN 'engine' THEN 1 WHEN 'transmission' THEN 2 WHEN 'abs' THEN 3 " +
                "WHEN 'instrument' THEN 4 WHEN 'gateway' THEN 5 ELSE 6 END",
            arrayOf(generationId),
        ).use { c ->
            while (c.moveToNext()) {
                ecus.add(
                    EcuRow(
                        partNumber = c.getString(0) ?: "",
                        system = c.getString(1),
                        systemRu = c.getString(2) ?: c.getString(1),
                        controller = if (c.isNull(3)) null else c.getString(3),
                        confidence = c.getString(4),
                    ),
                )
            }
        }
        var exp = 0; var unk = 0; var conf = 0
        db.rawQuery(
            "SELECT support_level, COUNT(*) FROM vehicle_parameter_support WHERE generation_id=? GROUP BY 1",
            arrayOf(generationId),
        ).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "expected" -> exp = c.getInt(1)
                    "unknown" -> unk = c.getInt(1)
                    "confirmed" -> conf = c.getInt(1)
                }
            }
        }
        val adapterNote = db.rawQuery(
            "SELECT notes FROM adapter_compatibility WHERE generation_id=? LIMIT 1",
            arrayOf(generationId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        VehicleProfile(
            generationId = generationId,
            title = "${g[1]} ${g[0]}".trim(),
            years = g[2] as String,
            platform = g[3] as String,
            engine = g[4] as String?,
            engineSpecs = g[5] as String?,
            protocol = g[6] as String?,
            ecus = ecus,
            supportExpected = exp,
            supportUnknown = unk,
            supportConfirmed = conf,
            adapterNote = adapterNote,
        )
    }

    private companion object {
        const val DB_VERSION = 2
        val VAG_SCOPES = setOf("VOLKSWAGEN", "AUDI", "SKODA", "SEAT")
    }
}
