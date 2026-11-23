package com.obd2.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Regression tests for the persistent crash-report mechanism. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DiagLogCrashPersistenceTest {

    @Test
    fun saveReadClearRoundtrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DiagLog.clearCrash(context)
        assertNull(DiagLog.readCrash(context))

        DiagLog.saveCrash(context, "main", IllegalStateException("тест-сбой"))
        val report = DiagLog.readCrash(context)
        assertNotNull(report)
        assertTrue(report!!.contains("IllegalStateException"))
        assertTrue(report.contains("тест-сбой"))
        assertTrue(report.contains("Поток: main"))

        DiagLog.clearCrash(context)
        assertNull(DiagLog.readCrash(context))
    }

    @Test
    fun freshContainerReadsSavedCrashAndAcknowledgeClearsIt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DiagLog.saveCrash(context, "main", IllegalStateException("сбой прошлого запуска"))

        // New container (same path as Application.onCreate) picks the crash up:
        val container = com.obd2.app.di.AppContainer(context)
        val saved = container.lastCrash.value
        assertNotNull(saved)
        assertTrue(saved!!.contains("IllegalStateException"))
        assertTrue(container.lastCrash.value != null)

        // Acknowledge clears only the in-memory state:
        container.acknowledgeCrash()
        assertEquals(null, container.lastCrash.value)
        DiagLog.clearCrash(context)
    }
}
