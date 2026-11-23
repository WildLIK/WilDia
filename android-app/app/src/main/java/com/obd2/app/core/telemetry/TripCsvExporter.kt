package com.obd2.app.core.telemetry

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.obd2.app.core.db.TripRecordingEntity
import com.obd2.app.core.db.TripSampleEntity
import com.obd2.app.core.obd.PidDefinition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline CSV exporter for saved trip recordings (`filesDir/reports/`).
 */
object TripCsvExporter {

    private val isoDf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    fun exportCsv(
        context: Context,
        recording: TripRecordingEntity,
        samples: List<TripSampleEntity>,
        registry: List<PidDefinition>,
        maxBytes: Long = 10L * 1024L * 1024L,
    ): File {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val file = File(dir, "trip_${recording.id}_${recording.startedAtMs}.csv")
        val sb = StringBuilder()
        sb.appendLine("recording_id,session_id,timestamp_ms,timestamp_iso,pid_hex,name,value,unit,status,error_note")
        for (s in samples) {
            val def = registry.firstOrNull { it.mode == 1 && it.pid == s.pid }
            val name = (def?.displayName ?: "PID %02X".format(s.pid)).replace("\"", "\"\"")
            val unit = (s.unit ?: "").replace("\"", "\"\"")
            val note = (s.errorNote ?: "").replace("\"", "\"\"")
            val valStr = s.value?.let { "%.4f".format(Locale.US, it) } ?: ""
            sb.appendLine(
                "${recording.id},${recording.sessionId ?: ""},${s.timestampMs},${isoDf.format(Date(s.timestampMs))}," +
                    "${"%02X".format(s.pid)},\"$name\",$valStr,\"$unit\",${s.status},\"$note\"",
            )
            if (sb.length >= maxBytes) break
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    fun buildShareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OBD2 Запись поездки ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
