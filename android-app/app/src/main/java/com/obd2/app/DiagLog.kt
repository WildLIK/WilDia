package com.obd2.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Launch diagnostics: logcat tag + persistent last-crash report.
 * The crash file is shown to the user on the next launch by MainActivity so a
 * startup failure is visible instead of a silent crash loop.
 */
object DiagLog {
    private const val TAG = "OBD2_DIAG"
    private const val CRASH_FILE = "last_crash.txt"

    fun d(msg: String) {
        Log.i(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
    }

    /** Persists an uncaught exception for the next launch. Never throws. */
    fun saveCrash(context: Context, thread: String, t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val text = buildString {
                appendLine("Время: ${System.currentTimeMillis()}")
                appendLine("Поток: $thread")
                appendLine("Тип: ${t::class.java.name}")
                appendLine("Сообщение: ${t.message}")
                appendLine(sw.toString())
            }
            File(context.filesDir, CRASH_FILE).writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "saveCrash failed", e)
        }
    }

    fun readCrash(context: Context): String? = try {
        val f = File(context.filesDir, CRASH_FILE)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        null
    }

    fun clearCrash(context: Context) {
        try {
            File(context.filesDir, CRASH_FILE).delete()
        } catch (e: Exception) {
            Log.e(TAG, "clearCrash failed", e)
        }
    }
}
