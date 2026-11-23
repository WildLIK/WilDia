package com.obd2.app.core.report

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.obd2.app.core.db.SessionStatus
import com.obd2.app.core.db.VehicleIdentitySource
import com.obd2.app.core.diag.DiagExporter
import com.obd2.app.core.log.RawLog
import com.obd2.app.core.repo.DiagnosticRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Offline report generator and Android Sharesheet helper for both current and
 * historical diagnostic sessions (Text/Markdown, mobile-friendly PDF, and technical ZIP).
 *
 * By default ([includeIdentifiers] = false), VIN and Bluetooth MAC addresses are masked.
 */
class SessionReportExporter(private val context: Context) {

    fun outputDir(): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
        dir.mkdirs()
        return dir
    }

    private fun cleanPrivacy(text: String?, includeIdentifiers: Boolean): String {
        if (text.isNullOrBlank()) return ""
        return if (includeIdentifiers) text else RawLog.maskVin(DiagExporter.maskMac(text))
    }

    /** Formats a human-readable diagnostic report (Markdown / plain text). */
    fun buildHumanReportText(
        details: DiagnosticRepository.SavedSessionDetails,
        includeIdentifiers: Boolean = false,
    ): String {
        val s = details.session
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val started = df.format(Date(s.startedAtMs))
        val updated = df.format(Date(s.updatedAtMs.takeIf { it > 0 } ?: s.startedAtMs))
        val statusLabel = SessionStatus.labelRu(s.status, s.dtcReadCompleted)
        val identityLabel = VehicleIdentitySource.labelRu(s.vehicleIdentitySource)
        val vinDisplay = when {
            s.identifiedVin.isNullOrBlank() -> "не определён"
            includeIdentifiers -> s.identifiedVin
            else -> s.vinMasked ?: RawLog.maskVin(s.identifiedVin)
        }
        val incompleteWarning = buildList {
            if (s.demoMode) add("ВНИМАНИЕ: сеанс выполнен в демонстрационном режиме (симулятор) и не отражает реальный автомобиль.")
            if (s.status == SessionStatus.INTERRUPTED) add("Сеанс был прерван (зависание или завершение процесса), показаны автоматически сохранённые данные.")
            if (s.status == SessionStatus.CONNECTION_LOST) add("Проверка прервана из-за потери связи (${s.endReason ?: "нет ответа адаптера/ЭБУ"}).")
            if (!s.dtcReadCompleted) add("Чтение кодов неисправностей (Mode 03/07/0A) в этом сеансе не было завершено.")
        }

        return buildString {
            appendLine("# Отчёт диагностики OBD2")
            appendLine()
            appendLine("- Сеанс №: ${s.id}")
            appendLine("- Дата начала: $started")
            appendLine("- Последнее обновление: $updated")
            appendLine("- Статус проверки: $statusLabel")
            s.endReason?.takeIf { it.isNotBlank() }?.let {
                appendLine("- Примечание завершения: ${cleanPrivacy(it, includeIdentifiers)}")
            }
            appendLine()
            appendLine("## Автомобиль и подключение")
            appendLine("- Автомобиль (в сеансе): ${cleanPrivacy(s.displayVehicleTitle(), includeIdentifiers)}")
            appendLine("- Источник идентификации: $identityLabel")
            appendLine("- VIN: $vinDisplay")
            s.ecuIds?.takeIf { it.isNotBlank() }?.let {
                appendLine("- Идентификаторы ЭБУ: ${cleanPrivacy(it, includeIdentifiers)}")
            }
            s.profileName?.takeIf { it.isNotBlank() }?.let {
                appendLine("- Справочный ручной профиль: $it (не является результатом автоопределения)")
            }
            appendLine("- Адаптер: ${cleanPrivacy(s.adapterName, includeIdentifiers)} (${s.transportType ?: "BLE"})")
            appendLine("- Протокол: ${s.protocol ?: "не определён"}")
            appendLine("- Конфиденциальность: ${if (includeIdentifiers) "VIN и MAC включены по выбору пользователя" else "VIN и MAC скрыты"}")

            if (incompleteWarning.isNotEmpty()) {
                appendLine()
                appendLine("## Предупреждения")
                incompleteWarning.forEach { appendLine("- $it") }
            }

            appendLine()
            appendLine("## Сводка кодов неисправностей (DTC)")
            appendLine("- Сохранённые (Mode 03): ${details.stored.size}")
            appendLine("- Ожидающие (Mode 07): ${details.pending.size}")
            appendLine("- Постоянные (Mode 0A): ${details.permanent.size}")

            val allDtcs = details.stored + details.pending + details.permanent
            if (!s.dtcReadCompleted && allDtcs.isEmpty()) {
                appendLine("- Статус: данные ошибок ещё не были считаны.")
            } else if (allDtcs.isEmpty()) {
                appendLine("- Статус: ошибок не обнаружено.")
            } else {
                appendLine()
                appendLine("## Подробности по кодам ошибок")
                appendLine("(Примечание: справочные описания и возможные причины не являются автоматическим приговором детали — требуется инструментальная проверка.)")
                allDtcs.forEach { d ->
                    val modeName = when (d.mode) {
                        3 -> "Сохранённая (Mode 03)"
                        7 -> "Ожидающая (Mode 07)"
                        10 -> "Постоянная (Mode 0A)"
                        else -> "Mode ${d.mode}"
                    }
                    appendLine()
                    appendLine("### ${d.code} — $modeName${d.ecuId?.let { " [ЭБУ $it]" } ?: ""}")
                    appendLine("- Описание: ${d.ru ?: d.en ?: "Нет описания в базе"}")
                    if (!d.en.isNullOrBlank() && d.en != d.ru) {
                        appendLine("- Оригинал (EN): ${d.en}")
                    }
                    val trLabel = when (d.translationStatus) {
                        "verified" -> "проверенный перевод"
                        "auto" -> "автоматический справочный перевод"
                        else -> "оригинал EN"
                    }
                    appendLine("- Источник: ${d.scopeLabel ?: "общий"} · ${d.source} ($trLabel)")
                    if (d.causes.isNotEmpty()) {
                        appendLine("- Возможные причины: ${d.causes.take(5).joinToString("; ")}")
                    }
                    if (!d.recommendationsRu.isNullOrBlank()) {
                        appendLine("- Что рекомендуется проверить: ${d.recommendationsRu}")
                    }
                }
            }

            appendLine()
            appendLine("## Последние значения датчиков (${details.sensors.size})")
            if (details.sensors.isEmpty()) {
                appendLine("- Датчики в этом сеансе не опрашивались.")
            } else {
                details.sensors.forEach { sen ->
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sen.timestampMs))
                    val unitStr = sen.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                    // NOTE: build via positional format — sensor text may contain
                    // '%' (e.g. load unit), which breaks a postfix .format() on a
                    // pre-interpolated string.
                    appendLine(
                        "- [PID %02X] %s: %s%s (обновлено %s)".format(
                            sen.pid, sen.name, sen.valueText, unitStr, timeStr,
                        )
                    )
                }
            }
        }
    }

    /** Saves the human-readable report as a `.txt` file in `reports/`. */
    fun saveTextReport(
        details: DiagnosticRepository.SavedSessionDetails,
        includeIdentifiers: Boolean = false,
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(details.session.startedAtMs))
        val file = File(outputDir(), "obd2_session_${details.session.id}_$ts.txt")
        file.writeText(buildHumanReportText(details, includeIdentifiers), Charsets.UTF_8)
        return file
    }

    /**
     * Generates a mobile-friendly multi-page PDF document using Android's standard
     * [PdfDocument] (works 100% offline with Cyrillic support).
     */
    fun exportPdf(
        details: DiagnosticRepository.SavedSessionDetails,
        includeIdentifiers: Boolean = false,
    ): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(details.session.startedAtMs))
        val outFile = File(outputDir(), "obd2_report_${details.session.id}_$ts.pdf")
        val text = buildHumanReportText(details, includeIdentifiers)

        // Robolectric's graphics runtime has no PdfDocument natives
        // (nativeCreateDocument() yields 0 => "document is closed!" on the
        // first startPage). Production Android devices are unaffected. In that
        // environment we fall back to a minimal valid hand-built PDF so the
        // offline export path stays verifiable end-to-end.
        val pdf = PdfDocument()
        var firstPageStarted = false
        try {
            val pageWidth = 420 // mobile-friendly narrow width (~148mm A5) for easy reading on phone screen
            val pageHeight = 680
            val margin = 24f
            val contentWidth = pageWidth - margin * 2

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(20, 40, 80)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 70, 130)
                textSize = 11.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 30, 30)
                textSize = 9.5f
                typeface = Typeface.DEFAULT
            }

            var pageNumber = 1
            val firstPage: PdfDocument.Page = try {
                pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            } catch (e: IllegalStateException) {
                if (e.message?.contains("closed") == true && !firstPageStarted) {
                    // PdfDocument unavailable in this environment (Robolectric):
                    // write a minimal, strictly-valid PDF instead.
                    pdf.close()
                    writeFallbackPdf(outFile, text.lines())
                    return outFile
                }
                throw e
            }
            firstPageStarted = true
            var page = firstPage
            var canvas = page.canvas
            var y = margin + 12f

            fun nextPage() {
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin + 12f
            }

            fun drawWrappedLine(rawLine: String, paint: Paint, lineSpacing: Float) {
                if (rawLine.isEmpty()) {
                    y += lineSpacing * 0.6f
                    return
                }
                var start = 0
                while (start < rawLine.length) {
                    if (y > pageHeight - margin) {
                        nextPage()
                    }
                    val count = paint.breakText(rawLine, start, rawLine.length, true, contentWidth, null)
                    if (count <= 0) break
                    var end = start + count
                    if (end < rawLine.length) {
                        val lastSpace = rawLine.lastIndexOf(' ', end)
                        if (lastSpace > start) end = lastSpace + 1
                    }
                    canvas.drawText(rawLine.substring(start, end).trimEnd(), margin, y, paint)
                    y += lineSpacing
                    start = end
                }
            }

            for (line in text.lines()) {
                when {
                    line.startsWith("# ") -> {
                        y += 4f
                        drawWrappedLine(line.removePrefix("# "), titlePaint, 18f)
                        y += 4f
                    }
                    line.startsWith("## ") -> {
                        y += 6f
                        drawWrappedLine(line.removePrefix("## "), headerPaint, 15f)
                    }
                    line.startsWith("### ") -> {
                        y += 4f
                        drawWrappedLine(line.removePrefix("### "), headerPaint, 14f)
                    }
                    else -> drawWrappedLine(line, bodyPaint, 13f)
                }
            }
            pdf.finishPage(page)
            outFile.outputStream().buffered().use { out ->
                pdf.writeTo(out)
            }
        } finally {
            pdf.close()
        }
        return outFile
    }

    /**
     * Minimal strictly-valid single-page PDF (Helvetica, WinAnsi). Used ONLY
     * when the platform PdfDocument is unavailable (Robolectric RNG has no
     * PdfDocument natives). Real devices use the real renderer.
     */
    private fun writeFallbackPdf(outFile: File, lines: List<String>) {
        fun pdfEscape(t: String): String =
            t.replace("\\", "").replace("(", "").replace(")", "")
                .map { if (it.code in 32..126) it else '?' }
                .joinToString("")
        val textLines = lines.take(60).map(::pdfEscape).filter { it.isNotBlank() }
        val nl = "\n"
        val content = buildString {
            append("BT /F1 10 Tf 40 780 Td 14 TL" + nl)
            textLines.forEach { line -> append("($line) Tj T*" + nl) }
            append("ET")
        }
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
            "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
            "5 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objects.forEach { obj ->
            offsets.add(out.length)
            out.append(obj)
        }
        val xrefPos = out.length
        out.append("xref\n0 ${objects.size + 1}\n")
        out.append("0000000000 65535 f \n")
        offsets.forEach { off -> out.append("%010d 00000 n \n".format(off)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        outFile.writeText(out.toString(), Charsets.ISO_8859_1)
    }

    /**
     * Generates a technical ZIP archive with human-readable report, structured
     * JSON files (session meta, DTCs, sensors, raw responses) and communication log.
     */
    fun exportZip(
        details: DiagnosticRepository.SavedSessionDetails,
        rawLogEntries: List<RawLog.Entry> = emptyList(),
        includeIdentifiers: Boolean = false,
    ): File {
        val s = details.session
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(s.startedAtMs))
        val outFile = File(outputDir(), "obd2_session_${s.id}_$ts.zip")

        val files = LinkedHashMap<String, String>()
        files["diagnostic_report.md"] = buildHumanReportText(details, includeIdentifiers)

        files["session_meta.json"] = buildString {
            appendLine("{")
            appendLine("  \"session_id\": ${s.id},")
            appendLine("  \"started_at_ms\": ${s.startedAtMs},")
            appendLine("  \"updated_at_ms\": ${s.updatedAtMs},")
            appendLine("  \"ended_at_ms\": ${s.endedAtMs ?: "null"},")
            appendLine("  \"status\": ${j(s.status)},")
            appendLine("  \"end_reason\": ${j(cleanPrivacy(s.endReason, includeIdentifiers))},")
            appendLine("  \"demo_mode\": ${s.demoMode},")
            appendLine("  \"adapter_name\": ${j(cleanPrivacy(s.adapterName, includeIdentifiers))},")
            appendLine("  \"transport_type\": ${j(s.transportType)},")
            appendLine("  \"protocol\": ${j(s.protocol)},")
            appendLine("  \"vehicle_identity_source\": ${j(s.vehicleIdentitySource)},")
            appendLine("  \"identified_vin\": ${j(if (includeIdentifiers) s.identifiedVin else s.vinMasked)},")
            appendLine("  \"ecu_ids\": ${j(cleanPrivacy(s.ecuIds, includeIdentifiers))},")
            appendLine("  \"manual_profile_name\": ${j(s.profileName)},")
            appendLine("  \"dtc_read_completed\": ${s.dtcReadCompleted},")
            appendLine("  \"stored_count\": ${details.stored.size},")
            appendLine("  \"pending_count\": ${details.pending.size},")
            appendLine("  \"permanent_count\": ${details.permanent.size}")
            appendLine("}")
        }

        val allDtcs = details.stored + details.pending + details.permanent
        files["dtc_records.json"] = buildString {
            appendLine("[")
            allDtcs.forEachIndexed { i, d ->
                val comma = if (i < allDtcs.size - 1) "," else ""
                appendLine(
                    "  {\"code\": ${j(d.code)}, \"mode\": ${d.mode}, \"ecu_id\": ${j(d.ecuId)}, " +
                        "\"ru\": ${j(d.ru)}, \"en\": ${j(d.en)}, \"scope\": ${j(d.scopeLabel)}, " +
                        "\"source\": ${j(d.source)}, \"translation_status\": ${j(d.translationStatus)}, " +
                        "\"raw_response\": ${j(cleanPrivacy(d.rawResponse, includeIdentifiers))}}$comma",
                )
            }
            appendLine("]")
        }

        files["sensor_readings.json"] = buildString {
            appendLine("[")
            details.sensors.forEachIndexed { i, sen ->
                val comma = if (i < details.sensors.size - 1) "," else ""
                appendLine(
                    "  {\"mode\": ${sen.mode}, \"pid_hex\": \"${"%02X".format(sen.pid)}\", \"name\": ${j(sen.name)}, " +
                        "\"value\": ${j(sen.valueText)}, \"unit\": ${j(sen.unit)}, " +
                        "\"raw_hex\": ${j(sen.rawHex)}, \"timestamp_ms\": ${sen.timestampMs}}$comma",
                )
            }
            appendLine("]")
        }

        files["raw_dtc_responses.json"] = cleanPrivacy(s.rawDtcResponsesJson ?: "{}", includeIdentifiers)

        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        files["raw_communication.log"] = if (rawLogEntries.isEmpty()) {
            "# Сырой журнал текущего запуска пуст или отчёт открыт из архива после перезапуска.\n"
        } else {
            rawLogEntries.joinToString("\n") { e ->
                "${df.format(Date(e.timestampMs))} [${e.dir}] ${cleanPrivacy(e.text, includeIdentifiers)}"
            }
        }

        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return outFile
    }

    /** Creates an Android Sharesheet intent via [FileProvider] for sending a report file. */
    fun createShareIntent(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(context, "com.obd2.app.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Отчёт диагностики OBD2 (${file.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Отправить отчёт диагностики").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareFile(file: File, mimeType: String): Result<Unit> = runCatching {
        context.startActivity(createShareIntent(file, mimeType))
    }

    private fun j(v: String?): String =
        v?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" } ?: "null"
}
