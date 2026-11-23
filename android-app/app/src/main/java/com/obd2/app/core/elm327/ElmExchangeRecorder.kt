package com.obd2.app.core.elm327

/**
 * Observer of ELM327 exchanges for the technical diagnostics (§7).
 * One record per command: text, write UUID/result, time to first byte,
 * full response in HEX and ASCII, prompt flag, fragments, anomalies.
 */
interface ElmExchangeRecorder {
    fun onCommandStart(cmd: String)
    fun onWriteResult(uuid: String?, ok: Boolean, error: String?)
    fun onChunk(bytes: ByteArray)
    fun onCommandDone(record: ElmExchangeRecord)
}

data class ElmExchangeRecord(
    val ts: Long,
    val cmd: String,
    val writeUuid: String?,
    val writeResult: String,
    val firstByteMs: Long?,
    val responseHex: String,
    val responseAscii: String,
    val durationMs: Long?,
    val promptSeen: Boolean,
    val resultCode: String,
    val errorKind: String?,
    val fragments: Int,
    val fragmentsMergedOk: Boolean,
    val unexpectedChars: List<String>,
    val terminatorMissing: Boolean,
    val bufferOverflow: Boolean,
    val requestCancelled: Boolean,
)

/** Default recorder that forwards finished records into [com.obd2.app.core.diag.DiagSession]. */
class DiagExchangeRecorder(private val session: com.obd2.app.core.diag.DiagSession) : ElmExchangeRecorder {

    private var currentUuid: String? = null
    private var writeResult = ""
    private var startedAt = 0L
    private var firstByteAt = 0L
    private var cmd = ""
    private val collected = java.io.ByteArrayOutputStream()
    private var fragments = 0
    private val unexpected = LinkedHashSet<String>()
    private var overflow = false

    @Synchronized
    override fun onCommandStart(c: String) {
        cmd = c
        startedAt = System.currentTimeMillis()
        firstByteAt = 0
        collected.reset()
        fragments = 0
        unexpected.clear()
        overflow = false
        writeResult = ""
        currentUuid = null
    }

    @Synchronized
    override fun onWriteResult(uuid: String?, ok: Boolean, error: String?) {
        currentUuid = uuid
        writeResult = when {
            error != null -> "ошибка записи: $error"
            ok == true -> "принято транспортом"
            else -> "не принято"
        }
    }

    @Synchronized
    override fun onChunk(bytes: ByteArray) {
        fragments += 1
        if (firstByteAt == 0L) firstByteAt = System.currentTimeMillis()
        if (collected.size() + bytes.size > MAX_CAPTURE) {
            overflow = true
            return
        }
        collected.write(bytes, 0, bytes.size)
        // unexpected (non-ASCII / control) characters
        bytes.forEach { b ->
            if (b < 0x20 && b != 0x0D.toByte() && b != 0x0A.toByte() && b != 0x3E.toByte()) {
                unexpected.add(String.format("0x%02X", b))
            }
        }
    }

    @Synchronized
    override fun onCommandDone(r: ElmExchangeRecord) {
        session.elmRecord(
            com.obd2.app.core.diag.ElmCommandRecord(
                ts = r.ts, cmd = r.cmd, writeUuid = r.writeUuid, writeResult = r.writeResult,
                firstByteMs = r.firstByteMs, responseHex = r.responseHex.take(4000),
                responseAscii = r.responseAscii.take(4000), durationMs = r.durationMs,
                promptSeen = r.promptSeen, resultCode = r.resultCode, errorKind = r.errorKind,
                fragments = r.fragments, fragmentsMergedOk = r.fragmentsMergedOk,
                unexpectedChars = r.unexpectedChars, terminatorMissing = r.terminatorMissing,
                bufferOverflow = r.bufferOverflow, requestCancelled = r.requestCancelled,
            )
        )
    }

    companion object {
        private const val MAX_CAPTURE = 64 * 1024
    }

    // helpers used by Elm327Client to build the final record
    fun capturedHex(): String = collected.toByteArray().joinToString(" ") { "%02X".format(it) }
    fun capturedAscii(): String = collected.toByteArray()
        .joinToString("") { if (it in 0x20..0x7E || it == 0x0A.toByte() || it == 0x0D.toByte()) it.toChar().toString() else "." }
    fun fragmentCount(): Int = fragments
    fun firstByteDelta(): Long? = if (firstByteAt == 0L || startedAt == 0L) null else (firstByteAt - startedAt).coerceAtLeast(0)
    fun unexpectedList(): List<String> = unexpected.toList()
    fun overflowFlag(): Boolean = overflow
}
