package com.obd2.app.core.log

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Ring-buffer raw communication log (direction + timestamp + text).
 * Feeds the "Adapter research" screen and the diagnostic report.
 * Pure Kotlin so tests can assert on it.
 */
class RawLog(private val capacity: Int = 4000) {

    enum class Dir { TX, RX, INFO, ERROR }

    data class Entry(
        val timestampMs: Long,
        val dir: Dir,
        val text: String,
        /** Monotonic duration in ms for TX→RX pairs (set on RX of a reply). */
        val durationMs: Long? = null,
    )

    private val entries = ArrayDeque<Entry>(capacity)
    private var lastTxTimestamp = 0L

    @Synchronized
    fun log(dir: Dir, text: String, durationMs: Long? = null) {
        val now = System.currentTimeMillis()
        if (dir == Dir.TX) lastTxTimestamp = now
        entries.addLast(Entry(now, dir, text, durationMs))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    fun toPlainText(maskVins: Boolean = false): String {
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return snapshot().joinToString("\n") { e ->
            val text = if (maskVins) maskVin(e.text) else e.text
            "${df.format(Date(e.timestampMs))} ${dirLabel(e.dir)} $text"
        }
    }

    private fun dirLabel(d: Dir) = when (d) {
        Dir.TX -> ">>"
        Dir.RX -> "<<"
        Dir.INFO -> "--"
        Dir.ERROR -> "!!"
    }

    companion object {
        /** Replaces sequences of 17 VIN-like chars (Mode 09 answers) with XXXX. */
        fun maskVin(text: String): String {
            val vinRegex = Regex("(?<=[^A-Z0-9]|^)([A-HJ-NPR-Z0-9]{17})(?=[^A-Z0-9]|$)")
            return text.replace(vinRegex, "XXXXXXXXXXXXXXXXX")
        }
    }
}
