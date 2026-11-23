package com.obd2.app

import android.app.Application
import com.obd2.app.di.AppContainer

class ObdApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            DiagLog.e("UNCAUGHT on thread ${t.name}", e)
            DiagLog.saveCrash(this, t.name, e)
            runCatching { container.diagSession.journal.persistCrash(t.name, e.stackTraceToString()) }
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
