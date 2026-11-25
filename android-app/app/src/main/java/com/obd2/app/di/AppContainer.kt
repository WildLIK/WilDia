package com.obd2.app.di

import android.content.Context
import com.obd2.app.DiagLog
import com.obd2.app.core.db.AppDb
import com.obd2.app.core.db.ObdLookupDb
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.repo.DiagnosticRepository
import com.obd2.app.core.repo.FullCheckRunner
import com.obd2.app.core.repo.SettingsRepository
import com.obd2.app.core.report.DiagnosticReportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Simple manual dependency container.
 * Kept dependency-free on purpose (no Hilt/kapt) for the v1 build; layers are
 * constructed once and injected explicitly, which keeps MVVM wiring obvious
 * and unit-testable. A future Rust core (JNI) would plug in behind
 * DiagnosticRepository without touching the UI layer.
 */
class AppContainer(private val context: Context) {
    val log = RawLog()
    val diagSession = com.obd2.app.core.diag.DiagSession(
        baseDir = java.io.File(context.filesDir, "diag"),
        rawLog = log,
    )
    val diagExporter = com.obd2.app.core.diag.DiagExporter(
        context.applicationContext, diagSession,
    ).also { it.rawLogSource = { log.snapshot() } }
    val settings = SettingsRepository(context)
    val lookupDb = ObdLookupDb(context)
    val appDb = AppDb.get(context)
    val repository = DiagnosticRepository(
        context = context.applicationContext,
        lookupDb = lookupDb,
        appDb = appDb,
        settings = settings,
        log = log,
    )
    val reportBuilder = DiagnosticReportBuilder(context.applicationContext)

    /**
     * Saved crash from the previous launch (full trace for developers).
     * Read once at startup, then the file is removed so it never blocks
     * subsequent launches; the UI shows a short notice and the full report
     * lives in the Diagnostics screen until acknowledged.
     */
    val lastCrash = MutableStateFlow<String?>(null)

    init {
        lastCrash.value = DiagLog.readCrash(context)
        DiagLog.clearCrash(context)
        repository.ioScope.launch(Dispatchers.IO) {
            diagSession.journal.preload()
        }
    }

    fun acknowledgeCrash() {
        lastCrash.value = null
    }

    fun fullCheckRunner(): FullCheckRunner = FullCheckRunner(repository, context.applicationContext)
}
