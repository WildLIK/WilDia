package com.obd2.app.core.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling diagnostic journal: keeps the last ~120 seconds of events in
 * memory, spills every event to a JSONL file (bounded) so the journal
 * survives an app restart, and writes timestamped error snapshots that are
 * never overwritten by later sessions.
 *
 * Size discipline: in-memory window bounded by time (WINDOW_MS) and count
 * (MAX_EVENTS); the on-disk spill rotates at MAX_SPILL_BYTES; snapshot files
 * accumulate under filesDir/diag/snapshots and are limited by count
 * (MAX_SNAPSHOTS — oldest regular snapshots pruned, crash ones kept).
 */
class DiagJournal(
    private val baseDir: File,
    private val windowMs: Long = 120_000L,
    private val maxEvents: Int = 4000,
    private val maxSpillBytes: Long = 4L * 1024 * 1024,
) {
    private val lock = Any()
    private val mem = ArrayDeque<DiagEvent>()
    private var spillFile: File = File(baseDir, "events_current.jsonl")
    private var spillWriter: java.io.FileWriter? = null
    private var spillSize = 0L
    private var spillSinceFlush = 0
    private var initialized = false

    val snapshotsDir: File by lazy { File(baseDir, "snapshots").apply { mkdirs() } }
    private val crashDir: File by lazy { File(baseDir, "crash").apply { mkdirs() } }

    private fun ensureInitializedLocked() {
        if (initialized) return
        initialized = true
        baseDir.mkdirs()
        restoreTailFromSpill()
    }

    /** Preloads persisted journal tail on a background I/O thread without blocking app startup. */
    fun preload() {
        synchronized(lock) { ensureInitializedLocked() }
    }

    fun add(event: DiagEvent) {
        synchronized(lock) {
            ensureInitializedLocked()
            mem.addLast(event)
            while (mem.size > maxEvents || (mem.size > 1 && event.ts - mem.first().ts > windowMs)) {
                mem.removeFirst()
            }
            spill(event)
            if (event.severity == DiagSeverity.ERROR) {
                runCatching { spillWriter?.flush() }
                spillSinceFlush = 0
                writeSnapshot("error", "событие ERROR: ${event.op}: ${event.details ?: ""}")
            }
        }
    }

    fun snapshot(windowMsOverride: Long? = null): List<DiagEvent> = synchronized(lock) {
        ensureInitializedLocked()
        val cutoff = System.currentTimeMillis() - (windowMsOverride ?: windowMs)
        mem.filter { it.ts >= cutoff }
    }

    fun allInMemory(): List<DiagEvent> = synchronized(lock) {
        ensureInitializedLocked()
        mem.toList()
    }

    /** Writes a snapshot file; returns its path. Existing files are never overwritten. */
    private var snapshotSeq = 0

    fun writeSnapshot(kind: String, description: String): File = synchronized(lock) {
        ensureInitializedLocked()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        snapshotSeq += 1
        val f = File(snapshotsDir, "${kind}_${ts}_${snapshotSeq}.json")
        val events = mem.toList()
        f.writeText(buildSnapshotJson(kind, description, events, f.name))
        pruneRegularSnapshots()
        f
    }

    /** Called from the crash handler: persists events + crash info. */
    fun persistCrash(threadName: String, stackTrace: String) {
        synchronized(lock) {
            ensureInitializedLocked()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val f = File(crashDir, "crash_$ts.jsonl")
            f.bufferedWriter().use { w ->
                w.appendLine(jsonLine(DiagEvent(
                    System.currentTimeMillis(), DiagCategory.SYS, "CRASH",
                    severity = DiagSeverity.ERROR,
                    details = "thread=$threadName",
                )))
                stackTrace.lineSequence().forEach { line ->
                    w.appendLine(jsonLine(DiagEvent(
                        System.currentTimeMillis(), DiagCategory.SYS, "STACK",
                        details = line.take(300),
                    )))
                }
                mem.forEach { w.appendLine(jsonLine(it)) }
            }
            // keep last 5 crash files
            crashDir.listFiles { file -> file.name.startsWith("crash_") }
                ?.sortedBy { it.name }
                ?.dropLast(5)
                ?.forEach { it.delete() }
        }
    }

    fun readLastCrashJsonl(): File? =
        crashDir.listFiles { file -> file.name.startsWith("crash_") }?.maxByOrNull { it.name }

    fun spillFile(): File = synchronized(lock) {
        ensureInitializedLocked()
        runCatching { spillWriter?.flush() }
        spillSinceFlush = 0
        spillFile
    }

    fun close() {
        synchronized(lock) { spillWriter?.flush(); spillWriter?.close(); spillWriter = null; spillSinceFlush = 0 }
    }

    // ------------------------------------------------------------------ //

    private fun spill(event: DiagEvent) {
        try {
            if (spillWriter == null) {
                spillWriter = java.io.FileWriter(spillFile, true)
                spillSize = spillFile.length()
            }
            val line = jsonLine(event) + "\n"
            spillWriter!!.append(line)
            spillSize += line.length
            spillSinceFlush++
            if (spillSinceFlush >= 16) {
                spillWriter!!.flush()
                spillSinceFlush = 0
            }
            if (spillSize > maxSpillBytes) rotateSpill()
        } catch (_: Exception) {
            // diagnostics must never crash the app
        }
    }

    private fun rotateSpill() {
        runCatching { spillWriter?.close() }
        spillWriter = null
        val rotated = File(baseDir, "events_prev.jsonl")
        rotated.delete()
        spillFile.renameTo(rotated)
        spillFile = File(baseDir, "events_current.jsonl")
        spillSize = 0
    }

    private fun restoreTailFromSpill() {
        runCatching {
            val prev = File(baseDir, "events_prev.jsonl")
            val cur = File(baseDir, "events_current.jsonl")
            val lines = (prev.takeIf { it.exists() }?.readLines().orEmpty() +
                cur.takeIf { it.exists() }?.readLines().orEmpty())
            val now = System.currentTimeMillis()
            lines.takeLast(maxEvents).forEach { line ->
                parseLine(line)?.let { if (now - it.ts <= 30 * 60_000L) mem.addLast(it) }
            }
        }
    }

    private fun pruneRegularSnapshots() {
        val files = snapshotsDir.listFiles { file -> file.name.startsWith("error_") }
            ?.sortedBy { it.name } ?: return
        files.dropLast(10).forEach { it.delete() }
    }

    private fun jsonLine(e: DiagEvent): String {
        fun esc(s: String?): String? = s
            ?.replace("\\", "\\\\")
            ?.replace("\"", "\\\"")
            ?.replace("\n", "\\n")
            ?.replace("\r", "")
            ?.replace("\t", " ")
        return buildString {
            append("{\"ts\":${e.ts}")
            append(",\"cat\":\"${e.category}\"")
            append(",\"op\":\"${esc(e.op) ?: ""}\"")
            e.resultCode?.let { append(",\"rc\":\"${esc(it)}\"") }
            e.durationMs?.let { append(",\"ms\":$it") }
            e.prevState?.let { append(",\"prev\":\"${esc(it)}\"") }
            e.newState?.let { append(",\"new\":\"${esc(it)}\"") }
            e.details?.let { append(",\"det\":\"${esc(it.take(2000))}\"") }
            append(",\"sev\":\"${e.severity}\"}")
        }
    }

    private fun buildSnapshotJson(kind: String, description: String, events: List<DiagEvent>, fileName: String): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("{")
            appendLine("  \"kind\": \"${kind.replace("\"", "'")}\",")
            appendLine("  \"written\": \"${df.format(Date())}\",")
            appendLine("  \"file\": \"$fileName\",")
            appendLine("  \"description\": \"${description.replace("\"", "'").take(500)}\",")
            appendLine("  \"window_ms\": $windowMs,")
            appendLine("  \"events\": [")
            events.forEachIndexed { i, e ->
                val comma = if (i < events.size - 1) "," else ""
                appendLine("    ${jsonLine(e)}$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun parseLine(line: String): DiagEvent? = runCatching {
        if (!line.trim().startsWith("{")) return null
        fun str(key: String): String? =
            Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.replace("\\n", "\n")
        fun num(key: String): Long? = Regex("\"$key\":(-?[0-9]+)").find(line)?.groupValues?.get(1)?.toLong()
        DiagEvent(
            ts = num("ts") ?: return null,
            category = DiagCategory.valueOf(str("cat") ?: "SYS"),
            op = str("op") ?: "?",
            resultCode = str("rc"),
            durationMs = num("ms"),
            prevState = str("prev"),
            newState = str("new"),
            details = str("det"),
            severity = DiagSeverity.valueOf(str("sev") ?: "INFO"),
        )
    }.getOrNull()
}
