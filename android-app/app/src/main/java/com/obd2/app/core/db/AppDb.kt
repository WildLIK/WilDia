package com.obd2.app.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object SessionStatus {
    const val ACTIVE = "ACTIVE"
    const val COMPLETED = "COMPLETED"
    const val INTERRUPTED = "INTERRUPTED"
    const val CONNECTION_LOST = "CONNECTION_LOST"
    const val FAILED = "FAILED"

    fun labelRu(status: String, dtcReadCompleted: Boolean): String = when (status) {
        ACTIVE -> "Активен"
        COMPLETED -> if (dtcReadCompleted) "Завершён" else "Сеанс завершён (ошибки не читались)"
        INTERRUPTED -> "Прерван (восстановлен после сбоя)"
        CONNECTION_LOST -> "Потеря связи"
        FAILED -> "Ошибка проверки"
        else -> status
    }
}

object VehicleIdentitySource {
    const val NONE = "NONE"
    const val AUTO_FULL = "AUTO_FULL"
    const val AUTO_PARTIAL = "AUTO_PARTIAL"
    const val AUTO_VIN = "AUTO_VIN"
    const val AUTO_ECU = "AUTO_ECU"
    const val MANUAL = "MANUAL"
    const val DEMO = "DEMO"

    fun labelRu(source: String): String = when (source) {
        AUTO_FULL, AUTO_VIN -> "Автомобиль определён автоматически (Mode 09)"
        AUTO_PARTIAL, AUTO_ECU -> "Автомобиль определён частично (требует подтверждения модели)"
        MANUAL -> "Автомобиль выбран вручную"
        DEMO -> "ДЕМО — данные симулированы"
        else -> "Автомобиль не определён"
    }
}

@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["startedAtMs"]),
        Index(value = ["status"]),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val updatedAtMs: Long = startedAtMs,
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
    val vehicleIdentitySource: String = VehicleIdentitySource.NONE,
    val storedCount: Int = 0,
    val pendingCount: Int = 0,
    val permanentCount: Int = 0,
    val dtcReadCompleted: Boolean = false,
    val status: String = SessionStatus.ACTIVE,
    val endReason: String? = null,
    val rawDtcResponsesJson: String? = null,
    val legacyUnverified: Boolean = false,
    val freezeFrameJson: String? = null,
    val readinessJson: String? = null,
    val discoveredEcusJson: String? = null,
) {
    val totalDtcCount: Int get() = storedCount + pendingCount + permanentCount

    fun displayVehicleTitle(): String = when {
        demoMode -> "ДЕМО — симулятор"
        !identifiedVin.isNullOrBlank() -> "VIN: ${vinMasked ?: identifiedVin}"
        !ecuIds.isNullOrBlank() -> "ЭБУ: $ecuIds"
        else -> "Автомобиль не определён"
    }
}

@Entity(
    tableName = "dtc_records",
    indices = [
        Index(value = ["sessionId", "mode"]),
        Index(value = ["timestampMs"]),
    ],
)
data class DtcRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val code: String,
    val mode: Int,            // 3 (stored) / 7 (pending) / 10 (0x0A permanent)
    val descriptionRu: String?,
    val descriptionEn: String?,
    val scope: String?,
    val timestampMs: Long,
    val ecuId: String? = null,
    val rawResponse: String? = null,
    val translationStatus: String? = null,
)

@Entity(
    tableName = "session_sensors",
    primaryKeys = ["sessionId", "mode", "pid"],
)
data class SessionSensorEntity(
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

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Query(
        """
        UPDATE sessions
        SET endedAtMs=:ended, updatedAtMs=:ended,
            storedCount=:stored, pendingCount=:pending, permanentCount=:permanent,
            protocol=COALESCE(:protocol, protocol), vinMasked=COALESCE(:vin, vinMasked),
            dtcReadCompleted=1, status='COMPLETED'
        WHERE id=:id
        """,
    )
    suspend fun finishSession(
        id: Long,
        ended: Long,
        stored: Int,
        pending: Int,
        permanent: Int,
        protocol: String?,
        vin: String?,
    )

    @Query(
        """
        UPDATE sessions
        SET updatedAtMs=:updatedAtMs,
            storedCount=:stored, pendingCount=:pending, permanentCount=:permanent,
            dtcReadCompleted=:dtcReadCompleted,
            protocol=COALESCE(:protocol, protocol),
            vinMasked=COALESCE(:vinMasked, vinMasked),
            rawDtcResponsesJson=COALESCE(:rawDtcJson, rawDtcResponsesJson)
        WHERE id=:id
        """,
    )
    suspend fun updateSessionDtcProgress(
        id: Long,
        updatedAtMs: Long,
        stored: Int,
        pending: Int,
        permanent: Int,
        dtcReadCompleted: Boolean,
        protocol: String?,
        vinMasked: String?,
        rawDtcJson: String?,
    )

    @Query(
        """
        UPDATE sessions
        SET updatedAtMs=:updatedAtMs,
            protocol=COALESCE(:protocol, protocol),
            identifiedVin=COALESCE(:identifiedVin, identifiedVin),
            vinMasked=COALESCE(:vinMasked, vinMasked),
            ecuIds=COALESCE(:ecuIds, ecuIds),
            vehicleIdentitySource=CASE WHEN :identitySource != 'NONE' THEN :identitySource ELSE vehicleIdentitySource END
        WHERE id=:id
        """,
    )
    suspend fun updateSessionTelemetryMeta(
        id: Long,
        updatedAtMs: Long,
        protocol: String?,
        identifiedVin: String?,
        vinMasked: String?,
        ecuIds: String?,
        identitySource: String,
    )

    /** Marks session ended without overwriting saved DTC counts. */
    @Query("UPDATE sessions SET endedAtMs=:ended, updatedAtMs=:ended, status=CASE WHEN status='ACTIVE' THEN 'COMPLETED' ELSE status END WHERE id=:id")
    suspend fun markEnded(id: Long, ended: Long)

    @Query(
        """
        UPDATE sessions
        SET endedAtMs=COALESCE(endedAtMs, :ended),
            updatedAtMs=:ended,
            status=:status,
            endReason=COALESCE(:reason, endReason)
        WHERE id=:id
        """,
    )
    suspend fun closeSessionWithStatus(id: Long, ended: Long, status: String, reason: String?)

    /**
     * Startup crash recovery: any session left ACTIVE or without endedAtMs from a
     * killed/frozen process is marked INTERRUPTED while preserving all saved DTCs and sensors.
     */
    @Query(
        """
        UPDATE sessions
        SET status='INTERRUPTED',
            endedAtMs=COALESCE(endedAtMs, updatedAtMs, startedAtMs, :nowMs),
            updatedAtMs=COALESCE(updatedAtMs, :nowMs),
            endReason=COALESCE(endReason, :reason)
        WHERE status='ACTIVE' OR endedAtMs IS NULL
        """,
    )
    suspend fun markOrphanSessionsInterrupted(
        nowMs: Long,
        reason: String = "Процесс приложения был прерван или перезапущен",
    ): Int

    @Query("SELECT * FROM sessions WHERE id=:id LIMIT 1")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun allSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id=:id")
    suspend fun deleteSessionRow(id: Long)

    @Query("SELECT id FROM sessions WHERE demoMode = 1")
    suspend fun demoSessionIds(): List<Long>

    /**
     * Marks historical sessions saved by older decoder versions (without rawDtcResponsesJson)
     * that contain known v0.4.0 phantom DTC artifacts as legacyUnverified without deleting them.
     */
    @Query(
        """
        UPDATE sessions
        SET updatedAtMs=:updatedAtMs,
            freezeFrameJson=COALESCE(:freezeFrameJson, freezeFrameJson),
            readinessJson=COALESCE(:readinessJson, readinessJson),
            discoveredEcusJson=COALESCE(:discoveredEcusJson, discoveredEcusJson)
        WHERE id=:id
        """,
    )
    suspend fun updateSessionExtendedArtifacts(
        id: Long,
        updatedAtMs: Long,
        freezeFrameJson: String?,
        readinessJson: String?,
        discoveredEcusJson: String?,
    )

    @Query(
        """
        UPDATE sessions
        SET legacyUnverified = 1,
            endReason = COALESCE(endReason, 'Неподтверждённые данные (снято старой версией декодера DTC)')
        WHERE legacyUnverified = 0
          AND rawDtcResponsesJson IS NULL
          AND id IN (
              SELECT DISTINCT sessionId FROM dtc_records
              WHERE code IN ('C3F0A', 'P117F', 'P0A11', 'P3503', 'P0047', 'P0043', 'P2200')
          )
        """,
    )
    suspend fun markLegacyUnverifiedSessions(): Int
}

@Dao
interface DtcRecordDao {
    @Insert
    suspend fun insertAll(records: List<DtcRecordEntity>)

    @Query("DELETE FROM dtc_records WHERE sessionId=:sessionId AND mode=:mode")
    suspend fun deleteModeForSession(sessionId: Long, mode: Int)

    @Query("DELETE FROM dtc_records WHERE sessionId=:sessionId")
    suspend fun deleteAllForSession(sessionId: Long)

    @Transaction
    suspend fun replaceModeRecords(sessionId: Long, mode: Int, records: List<DtcRecordEntity>) {
        deleteModeForSession(sessionId, mode)
        if (records.isNotEmpty()) {
            insertAll(records)
        }
    }

    @Query("SELECT * FROM dtc_records WHERE sessionId=:sessionId ORDER BY mode ASC, code ASC")
    suspend fun forSession(sessionId: Long): List<DtcRecordEntity>

    @Query("SELECT * FROM dtc_records WHERE sessionId=:sessionId ORDER BY mode ASC, code ASC")
    fun forSessionFlow(sessionId: Long): Flow<List<DtcRecordEntity>>

    @Query("SELECT * FROM dtc_records ORDER BY timestampMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<DtcRecordEntity>>
}

@Dao
interface SessionSensorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sensors: List<SessionSensorEntity>)

    @Query("SELECT * FROM session_sensors WHERE sessionId=:sessionId ORDER BY pid ASC")
    suspend fun forSession(sessionId: Long): List<SessionSensorEntity>

    @Query("SELECT * FROM session_sensors WHERE sessionId=:sessionId ORDER BY pid ASC")
    fun forSessionFlow(sessionId: Long): Flow<List<SessionSensorEntity>>

    @Query("DELETE FROM session_sensors WHERE sessionId=:sessionId")
    suspend fun deleteAllForSession(sessionId: Long)
}

@Entity(tableName = "trip_recordings")
data class TripRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long = startedAtMs,
    val endedAtMs: Long? = null,
    val status: String = SessionStatus.ACTIVE,
    val selectedPidsCsv: String,
    val sampleCount: Int = 0,
    val errorCount: Int = 0,
    val maxDurationSec: Int = 3600,
    val maxSamples: Int = 20000,
    val vehicleLabel: String = "Автомобиль не определён",
    val notes: String? = null,
)

@Entity(
    tableName = "trip_samples",
    indices = [Index(value = ["recordingId", "timestampMs"])],
)
data class TripSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val timestampMs: Long,
    val pid: Int,
    val value: Double? = null,
    val unit: String? = null,
    val status: String = "OK", // OK | UNSUPPORTED | ERROR
    val errorNote: String? = null,
)

@Dao
interface TripRecordingDao {
    @Insert
    suspend fun insertRecording(rec: TripRecordingEntity): Long

    @Query("UPDATE trip_recordings SET updatedAtMs=:updatedAtMs, sampleCount=:sampleCount, errorCount=:errorCount WHERE id=:id")
    suspend fun updateRecordingProgress(id: Long, updatedAtMs: Long, sampleCount: Int, errorCount: Int)

    @Query("UPDATE trip_recordings SET endedAtMs=:endedAtMs, updatedAtMs=:endedAtMs, status=:status, sampleCount=:sampleCount, errorCount=:errorCount, notes=COALESCE(:notes, notes) WHERE id=:id")
    suspend fun closeRecording(id: Long, endedAtMs: Long, status: String, sampleCount: Int, errorCount: Int, notes: String?)

    @Query(
        """
        UPDATE trip_recordings
        SET status='INTERRUPTED',
            endedAtMs=COALESCE(endedAtMs, updatedAtMs, startedAtMs, :nowMs),
            updatedAtMs=COALESCE(updatedAtMs, :nowMs),
            notes=COALESCE(notes, 'Прервана при аварийном завершении')
        WHERE status='ACTIVE' OR endedAtMs IS NULL
        """,
    )
    suspend fun markOrphanRecordingsInterrupted(nowMs: Long): Int

    @Query("SELECT * FROM trip_recordings ORDER BY startedAtMs DESC")
    fun allRecordings(): Flow<List<TripRecordingEntity>>

    @Query("SELECT * FROM trip_recordings ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recentRecordings(limit: Int): List<TripRecordingEntity>

    @Query("SELECT * FROM trip_recordings WHERE id=:id LIMIT 1")
    suspend fun getRecording(id: Long): TripRecordingEntity?

    @Query("DELETE FROM trip_recordings WHERE id=:id")
    suspend fun deleteRecordingRow(id: Long)

    @Insert
    suspend fun insertSamples(samples: List<TripSampleEntity>)

    @Query("SELECT * FROM trip_samples WHERE recordingId=:recordingId ORDER BY timestampMs ASC, id ASC")
    suspend fun samplesForRecording(recordingId: Long): List<TripSampleEntity>

    @Query("DELETE FROM trip_samples WHERE recordingId=:recordingId")
    suspend fun deleteSamplesForRecording(recordingId: Long)

    @Transaction
    suspend fun deleteRecordingCompletely(recordingId: Long) {
        deleteSamplesForRecording(recordingId)
        deleteRecordingRow(recordingId)
    }
}

@Entity(
    tableName = "dtc_scan_runs",
    indices = [Index(value = ["sessionId", "timestampMs"])],
)
data class DtcScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val sessionId: Long,
    val timestampMs: Long,
    val status: String,
    val storedCodesJson: String,
    val pendingCodesJson: String,
    val permanentCodesJson: String,
    val errorsJson: String,
    val auditJson: String,
)

@Dao
interface DtcScanRunDao {
    @Insert
    suspend fun insert(entity: DtcScanRunEntity): Long

    @Query("SELECT * FROM dtc_scan_runs WHERE sessionId=:sessionId ORDER BY timestampMs DESC, id DESC")
    suspend fun forSession(sessionId: Long): List<DtcScanRunEntity>

    @Query("SELECT * FROM dtc_scan_runs ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<DtcScanRunEntity>

    @Query("DELETE FROM dtc_scan_runs WHERE sessionId=:sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Database(
    entities = [
        SessionEntity::class,
        DtcRecordEntity::class,
        SessionSensorEntity::class,
        TripRecordingEntity::class,
        TripSampleEntity::class,
        DtcScanRunEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun dtcRecordDao(): DtcRecordDao
    abstract fun sessionSensorDao(): SessionSensorDao
    abstract fun tripRecordingDao(): TripRecordingDao
    abstract fun dtcScanRunDao(): DtcScanRunDao

    @Transaction
    open suspend fun deleteSessionCompletely(sessionId: Long) {
        dtcRecordDao().deleteAllForSession(sessionId)
        sessionSensorDao().deleteAllForSession(sessionId)
        dtcScanRunDao().deleteForSession(sessionId)
        sessionDao().deleteSessionRow(sessionId)
    }

    @Transaction
    open suspend fun deleteAllDemoSessions(): Int {
        val ids = sessionDao().demoSessionIds()
        for (id in ids) {
            dtcRecordDao().deleteAllForSession(id)
            sessionSensorDao().deleteAllForSession(id)
            dtcScanRunDao().deleteForSession(id)
            sessionDao().deleteSessionRow(id)
        }
        return ids.size
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN updatedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN transportType TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN profileId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN profileName TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN identifiedVin TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN ecuIds TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN vehicleIdentitySource TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN dtcReadCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN endReason TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN rawDtcResponsesJson TEXT")

                db.execSQL("ALTER TABLE dtc_records ADD COLUMN ecuId TEXT")
                db.execSQL("ALTER TABLE dtc_records ADD COLUMN rawResponse TEXT")
                db.execSQL("ALTER TABLE dtc_records ADD COLUMN translationStatus TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_sensors (
                        sessionId INTEGER NOT NULL,
                        mode INTEGER NOT NULL,
                        pid INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        valueText TEXT NOT NULL,
                        numericValue REAL,
                        unit TEXT,
                        rawHex TEXT,
                        timestampMs INTEGER NOT NULL,
                        PRIMARY KEY(sessionId, mode, pid)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN legacyUnverified INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN freezeFrameJson TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN readinessJson TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN discoveredEcusJson TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trip_recordings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER,
                        startedAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL,
                        endedAtMs INTEGER,
                        status TEXT NOT NULL,
                        selectedPidsCsv TEXT NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        errorCount INTEGER NOT NULL,
                        maxDurationSec INTEGER NOT NULL,
                        maxSamples INTEGER NOT NULL,
                        vehicleLabel TEXT NOT NULL,
                        notes TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS trip_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recordingId INTEGER NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        pid INTEGER NOT NULL,
                        value REAL,
                        unit TEXT,
                        status TEXT NOT NULL,
                        errorNote TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_samples_recordingId_timestampMs ON trip_samples(recordingId, timestampMs)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dtc_scan_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId TEXT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        storedCodesJson TEXT NOT NULL,
                        pendingCodesJson TEXT NOT NULL,
                        permanentCodesJson TEXT NOT NULL,
                        errorsJson TEXT NOT NULL,
                        auditJson TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dtc_scan_runs_sessionId_timestampMs ON dtc_scan_runs(sessionId, timestampMs)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_startedAtMs ON sessions(startedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_status ON sessions(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dtc_records_sessionId_mode ON dtc_records(sessionId, mode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dtc_records_timestampMs ON dtc_records(timestampMs)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )

        /**
         * One AppDb per application context. In production the Application is a
         * process-wide singleton, so this yields exactly one DB; in Robolectric
         * tests every test method owns its own Application/environment, and
         * keying by context prevents a static instance from carrying SQLite
         * connection pointers across test environments (Illegal connection
         * pointer / UncaughtExceptionsBeforeTest cascades).
         */
        private val instances = java.util.concurrent.ConcurrentHashMap<Context, AppDb>()

        fun get(context: Context): AppDb {
            val app = context.applicationContext
            return instances.getOrPut(app) {
                Room.databaseBuilder(app, AppDb::class.java, "obd_app.db")
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
            }
        }
    }
}
