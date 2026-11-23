package com.obd2.app.core.obd

/**
 * DTC encoding/decoding per SAE J2012 & ISO 15031-5 / ISO 15765-4.
 *
 * Two bytes -> 5-char code:
 *   byte1 bits 7-6: system letter (00 P, 01 C, 10 B, 11 U)
 *   byte1 bits 5-4: first digit (0..3)
 *   byte1 bits 3-0: second digit (hex nibble)
 *   byte2 bits 7-4: third digit (hex nibble)
 *   byte2 bits 3-0: fourth digit (hex nibble)
 *
 * Handles:
 *  - multi-ECU responses separated per ECU instead of flattened blindly
 *  - ISO 14229/15031 Negative Responses (7F <sid> <nrc>) never decoded as DTCs
 *  - ISO-TP single-frame / multi-frame with ATH0 (0:/1:/2: + byte-count) and ATH1 (7E8 10 LL / 21 ...)
 *  - compact hex (ATS0) as well as spaced hex (ATS1)
 *  - CAN count-prefixed format ("43 <count> <DTC pairs> <00 padding>") vs non-CAN ("43 <DTC pairs>")
 */
object DtcParser {

    private val codeRegex = Regex("[PBCU][0-3][0-9A-F]{3}")

    data class NegativeResponse(
        val ecuId: String?,
        val serviceId: Int,
        val nrc: Int,
    ) {
        fun describeRu(): String {
            val nrcText = when (nrc) {
                0x11 -> "сервис не поддерживается ЭБУ"
                0x12 -> "подфункция не поддерживается"
                0x13 -> "некорректная длина/формат сообщения"
                0x22 -> "условия не выполнены"
                0x31 -> "запрос вне диапазона"
                0x33 -> "требуется доступ безопасности"
                0x78 -> "ожидание ответа от ЭБУ"
                else -> "код отказа 0x%02X".format(nrc)
            }
            return "7F %02X %02X (%s%s)".format(
                serviceId,
                nrc,
                ecuId?.let { "ЭБУ $it: " } ?: "",
                nrcText,
            )
        }
    }

    data class DtcParseResult(
        val dtcs: List<DtcEntry>,
        val negativeResponses: List<NegativeResponse> = emptyList(),
        val ecuCount: Int = 0,
        val warnings: List<String> = emptyList(),
        val rawLines: List<String> = emptyList(),
    )

    private data class EcuFrame(
        val ecuId: String?,
        val bytes: List<Int>,
    )

    fun bytesToCode(b1: Int, b2: Int): String? {
        if (b1 == 0 && b2 == 0) return null // padding / no code
        val letters = "PCBU"
        val letterIdx = (b1 shr 6) and 0x03
        val d1 = (b1 shr 4) and 0x03
        val d2 = b1 and 0x0F
        val d3 = (b2 shr 4) and 0x0F
        val d4 = b2 and 0x0F
        val code = "${letters[letterIdx]}$d1${"%X%X%X".format(d2, d3, d4)}"
        return if (codeRegex.matches(code)) code else null
    }

    fun codeToBytes(code: String): Pair<Int, Int> {
        val letters = "PCBU"
        val letter = letters.indexOf(code[0].uppercaseChar())
        val d1 = code[1] - '0'
        val d2 = Character.digit(code[2], 16)
        val d3 = Character.digit(code[3], 16)
        val d4 = Character.digit(code[4], 16)
        val b1 = (letter shl 6) or (d1 shl 4) or d2
        val b2 = (d3 shl 4) or d4
        return b1 to b2
    }

    /**
     * Full Mode 03/07/0A parser over raw ELM327 response lines.
     * Isolates per-ECU frames, handles ISO-TP reassembly, filters 7F negative
     * responses, and respects CAN count bytes without shifting or flattening.
     */
    fun parseDtcResponse(
        lines: List<String>,
        mode: Int,
        isCanProtocol: Boolean? = null,
    ): DtcParseResult {
        val positiveSid = 0x40 + mode
        val warnings = ArrayList<String>()
        val negatives = ArrayList<NegativeResponse>()
        val dtcs = ArrayList<DtcEntry>()
        val seenCodes = LinkedHashMap<String, MutableSet<String>>() // code -> reporting ecuIds

        val frames = reassembleFrames(lines, positiveSid, mode, warnings)
        var respondingEcus = 0

        for (frame in frames) {
            val b = frame.bytes
            if (b.isEmpty()) continue
            if (b[0] == 0x7F) {
                val sid = b.getOrNull(1) ?: mode
                val nrc = b.getOrNull(2) ?: 0
                if (nrc != 0x78) { // ignore transient responsePending
                    negatives.add(NegativeResponse(frame.ecuId, sid, nrc))
                }
                continue
            }
            if (b[0] != positiveSid) {
                warnings.add("Неожиданный заголовок 0x%02X вместо 0x%02X${frame.ecuId?.let { " (ЭБУ $it)" } ?: ""}".format(b[0], positiveSid))
                continue
            }
            respondingEcus++
            val payload = b.drop(1).map { it.toByte() }.toByteArray()
            val parsed = parseSingleEcuPayload(payload, mode, isCanProtocol, frame.ecuId, warnings)
            for (entry in parsed) {
                val ecuTag = entry.ecuId ?: "ECU#$respondingEcus"
                val ecus = seenCodes.getOrPut(entry.code) { LinkedHashSet() }
                if (ecus.isEmpty()) {
                    dtcs.add(entry)
                }
                ecus.add(ecuTag)
            }
        }

        // Preserve reporting ECU tags when multiple ECUs reported the same code
        val merged = dtcs.map { d ->
            val ecus = seenCodes[d.code]
            if (ecus != null && ecus.size > 1 && d.ecuId != null) {
                d.copy(ecuId = ecus.joinToString(","))
            } else {
                d
            }
        }

        return DtcParseResult(
            dtcs = merged,
            negativeResponses = negatives,
            ecuCount = respondingEcus,
            warnings = warnings,
            rawLines = lines,
        )
    }

    /**
     * Parses DTC payload bytes (everything AFTER a single ECU's 43/47/4A header byte).
     * Never falls back to decoding padding/00-count frames into phantom DTCs.
     */
    fun parseDtcPayload(data: ByteArray, mode: Int, isCanProtocol: Boolean? = null): List<DtcEntry> {
        val warnings = ArrayList<String>()
        return parseSingleEcuPayload(data, mode, isCanProtocol, null, warnings)
    }

    private fun parseSingleEcuPayload(
        data: ByteArray,
        mode: Int,
        isCanProtocol: Boolean?,
        ecuId: String?,
        warnings: MutableList<String>,
    ): List<DtcEntry> {
        val out = ArrayList<DtcEntry>()
        if (data.isEmpty()) return out
        if (data.all { it == 0.toByte() }) return out

        val ints = IntArray(data.size) { data[it].toInt() and 0xFF }
        val useCountByte: Boolean = when (isCanProtocol) {
            true -> true
            false -> false
            null -> inferHasCountByte(ints)
        }

        if (useCountByte) {
            val count = ints[0]
            if (count == 0) {
                // Explicit 0 DTCs reported by CAN ECU — never brute-force scan padding.
                return out
            }
            var found = 0
            var i = 1
            while (i + 1 < ints.size && found < count) {
                val b1 = ints[i]
                val b2 = ints[i + 1]
                i += 2
                if (b1 == 0 && b2 == 0) continue
                val code = bytesToCode(b1, b2) ?: continue
                out.add(DtcEntry(code, mode, "%02X %02X".format(b1, b2), ecuId))
                found++
            }
            if (found != count) {
                warnings.add("Заявлено ошибок: $count, разобрано: $found${ecuId?.let { " (ЭБУ $it)" } ?: ""}")
            }
        } else {
            var i = 0
            while (i + 1 < ints.size) {
                val b1 = ints[i]
                val b2 = ints[i + 1]
                i += 2
                if (b1 == 0 && b2 == 0) continue
                val code = bytesToCode(b1, b2) ?: continue
                out.add(DtcEntry(code, mode, "%02X %02X".format(b1, b2), ecuId))
            }
        }
        return out
    }

    /**
     * Heuristic when protocol type (CAN vs KWP/ISO-9141) is not explicitly known:
     * - CAN single/multi-frame after 0x43/0x47/0x4A carries <count> followed by count*2 bytes (+ optional 00 padding).
     * - KWP2000 / ISO-9141 carries 3 pairs = 6 bytes with no count byte (even length, non-zero tail bytes).
     */
    private fun inferHasCountByte(ints: IntArray): Boolean {
        if (ints.size == 1) return ints[0] == 0
        val count = ints[0]
        if (count == 0) {
            // Only treat leading 0x00 as count=0 when remaining bytes are padding zeros
            // or size is odd (e.g., 1+6 padding). Non-CAN never encodes P00xx first with 00 xx unless malformed.
            return ints.drop(1).all { it == 0 }
        }
        val neededLen = 1 + count * 2
        if (neededLen > ints.size) return false
        val trailingAllZero = (neededLen until ints.size).all { ints[it] == 0 }
        if (!trailingAllZero) return false
        // Note: CAN payloads have 1 + 2*N data bytes, padded to odd (e.g. 7) or exact (1 + 2*N),
        // whereas KWP payloads have exact even length (2, 4, 6). Having passed neededLen <= ints.size
        // and trailingAllZero above, both odd and even CAN-padded lengths (e.g., 6-byte [01, 01, 33, 00, 00, 00]) are valid.
        return true
    }

    /**
     * Reassembles ELM327 output lines into per-ECU byte messages.
     * Supports:
     *  - ATH1 CAN headers (3-char 11-bit e.g. 7E8 or 8-char 29-bit e.g. 18DAF110) with ISO-TP PCI (0N / 1N LL / 2x)
     *  - ATH0 ISO-TP multi-frame (`0:`, `1:`, `2:` with optional 3-char hex total length line `00E`)
     *  - ATH0 single-frame / multi-ECU lines (each line starting with 43/47/4A or 7F is a distinct ECU)
     */
    private fun reassembleFrames(
        lines: List<String>,
        positiveSid: Int,
        mode: Int,
        warnings: MutableList<String>,
    ): List<EcuFrame> {
        val cleaned = lines.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return emptyList()

        // Detect ATH1 (every data line starts with a 3-char or 8-char CAN ID followed by hex bytes)
        val ath1Regex = Regex("^([0-9A-F]{3}|[0-9A-F]{8})\\s+([0-9A-F\\s]+)$")
        val allAth1 = cleaned.all { line ->
            val m = ath1Regex.matchEntire(line) ?: return@all false
            val header = m.groupValues[1]
            // Avoid mistaking compact 3-char hex length "00E" without payload or mode bytes like "43"
            header.length == 8 || (header.length == 3 && (header.startsWith("7") || m.groupValues[2].trim().length >= 4))
        }
        if (allAth1) {
            return reassembleAth1(cleaned, ath1Regex)
        }

        // ATH0 (possibly with ISO-TP 0:/1:/2: lines or plain multi-ECU lines)
        val out = ArrayList<EcuFrame>()
        var currentIsoTp: MutableList<Int>? = null
        var expectedIsoTpLen: Int? = null
        var pendingLenHint: Int? = null
        var currentPlain: MutableList<Int>? = null

        fun flushIsoTp() {
            val buf = currentIsoTp ?: return
            val trimmed = expectedIsoTpLen?.let { len ->
                if (len in 1..buf.size) buf.subList(0, len).toList() else buf.toList()
            } ?: buf.toList()
            if (trimmed.isNotEmpty()) out.add(EcuFrame(null, trimmed))
            currentIsoTp = null
            expectedIsoTpLen = null
        }

        fun flushPlain() {
            val buf = currentPlain ?: return
            if (buf.isNotEmpty()) out.add(EcuFrame(null, buf.toList()))
            currentPlain = null
        }

        val seqLineRegex = Regex("^([0-9A-F]):\\s*(.*)$")
        val lenLineRegex = Regex("^[0-9A-F]{3}$")

        for (line in cleaned) {
            if (lenLineRegex.matches(line)) {
                flushPlain()
                flushIsoTp()
                pendingLenHint = line.toInt(16)
                continue
            }
            val seqMatch = seqLineRegex.matchEntire(line)
            if (seqMatch != null) {
                flushPlain()
                val seq = seqMatch.groupValues[1].toInt(16)
                val bytes = parseHexTokens(seqMatch.groupValues[2])
                if (seq == 0 || currentIsoTp == null) {
                    flushIsoTp()
                    currentIsoTp = ArrayList(bytes)
                    expectedIsoTpLen = pendingLenHint
                    pendingLenHint = null
                } else {
                    currentIsoTp!!.addAll(bytes)
                }
                continue
            }

            // Plain ATH0 line
            flushIsoTp()
            pendingLenHint = null
            val bytes = parseHexTokens(line)
            if (bytes.isEmpty()) continue
            val startsNewMsg = (bytes[0] == positiveSid) ||
                (bytes[0] == 0x7F && bytes.getOrNull(1) == mode)
            if (startsNewMsg) {
                flushPlain()
                currentPlain = ArrayList(bytes)
            } else if (currentPlain != null) {
                // Wrapped continuation bytes from the same ECU (e.g., ["43 02 01 71 01", "74"])
                currentPlain!!.addAll(bytes)
            } else {
                // Unexpected leading frame (e.g., 7F for another SID or garbage)
                currentPlain = ArrayList(bytes)
            }
        }
        flushIsoTp()
        flushPlain()

        if (out.isEmpty() && cleaned.isNotEmpty()) {
            warnings.add("Не удалось выделить кадры ответа из строк: ${cleaned.joinToString(" | ")}")
        }
        return out
    }

    private fun reassembleAth1(lines: List<String>, ath1Regex: Regex): List<EcuFrame> {
        data class IsoTpAsm(val ecuId: String, val expectedLen: Int, val buf: MutableList<Int>)

        val completed = ArrayList<EcuFrame>()
        val inProgress = LinkedHashMap<String, IsoTpAsm>()

        for (line in lines) {
            val m = ath1Regex.matchEntire(line) ?: continue
            val ecuId = m.groupValues[1]
            val frameBytes = parseHexTokens(m.groupValues[2])
            if (frameBytes.isEmpty()) continue
            val pci = frameBytes[0]
            val frameType = (pci shr 4) and 0x0F
            when (frameType) {
                0x0 -> {
                    val len = pci and 0x0F
                    val payload = frameBytes.drop(1).take(len)
                    inProgress.remove(ecuId)
                    completed.add(EcuFrame(ecuId, payload))
                }
                0x1 -> {
                    if (frameBytes.size >= 2) {
                        val totalLen = ((pci and 0x0F) shl 8) or frameBytes[1]
                        val initial = frameBytes.drop(2).toMutableList()
                        if (initial.size >= totalLen) {
                            completed.add(EcuFrame(ecuId, initial.take(totalLen)))
                        } else {
                            inProgress[ecuId] = IsoTpAsm(ecuId, totalLen, initial)
                        }
                    }
                }
                0x2 -> {
                    val asm = inProgress[ecuId] ?: continue
                    asm.buf.addAll(frameBytes.drop(1))
                    if (asm.buf.size >= asm.expectedLen) {
                        completed.add(EcuFrame(ecuId, asm.buf.take(asm.expectedLen)))
                        inProgress.remove(ecuId)
                    }
                }
                else -> {
                    // Non-ISO-TP headered frame (e.g. KWP/J1850 with headers)
                    completed.add(EcuFrame(ecuId, frameBytes))
                }
            }
        }
        // Flush any partial multi-frame buffers cleanly
        inProgress.values.forEach { asm ->
            completed.add(EcuFrame(asm.ecuId, asm.buf.take(asm.expectedLen)))
        }
        return completed
    }

    /** Parses spaced hex ("43 02 01 33") OR compact hex ("43020133") into byte ints. */
    internal fun parseHexTokens(text: String): List<Int> {
        val out = ArrayList<Int>()
        val tokens = text.trim().uppercase().split(' ', '\t').filter { it.isNotBlank() }
        for (t in tokens) {
            if (t.length == 2) {
                t.toIntOrNull(16)?.let { out.add(it) }
            } else if (t.length > 2 && t.length % 2 == 0 && t.all { it in '0'..'9' || it in 'A'..'F' }) {
                var i = 0
                while (i + 1 < t.length) {
                    t.substring(i, i + 2).toIntOrNull(16)?.let { out.add(it) }
                    i += 2
                }
            }
        }
        return out
    }

    /**
     * Collects payload bytes after the first response header token ("43"/"47"/"4A").
     * Kept for direct callers/unit tests on single-ECU wrapped responses; for full
     * Mode 03/07/0A vehicle decoding use [parseDtcResponse].
     */
    fun collectModeResponseBytes(lines: List<String>, responseHeader: Int): ByteArray {
        val tokens = lines.flatMap { it.uppercase().split(' ', '\t').filter { t -> t.isNotBlank() } }
        val header = "%02X".format(responseHeader)
        val idx = tokens.indexOf(header)
        if (idx < 0) return ByteArray(0)
        val bytes = ArrayList<Byte>()
        for (t in tokens.drop(idx + 1)) {
            if (t.length != 2) continue
            val v = t.toIntOrNull(16) ?: continue
            bytes.add(v.toByte())
        }
        return bytes.toByteArray()
    }
}

/** One ECU's Mode 09 ASCII payload (e.g. Calibration ID or ECU Name) with optional CAN header. */
data class EcuMode09Value(
    val ecuHeader: String?,
    val value: String,
    val rawLines: List<String>,
)

/** Decoded ISO 14229 / KWP2000 / OBD-II Negative Response (7F <SID> <NRC>). */
data class NegativeResponse(
    val ecuHeader: String?,
    val serviceId: Int,
    val nrc: Int,
    val descriptionRu: String,
) {
    fun formatShort(): String = buildString {
        if (!ecuHeader.isNullOrBlank()) append("$ecuHeader ")
        append("7F %02X %02X ($descriptionRu)".format(serviceId, nrc))
    }
}

/** Mode 09 / UDS / KWP2000 vehicle identification parsing: VIN, Calibration ID, ECU name, NRC. */
object Mode09Parser {
    private val vinRegex = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private val vinSearchRegex = Regex("[A-HJ-NPR-Z0-9]{17}")
    private val canHeaderPrefix = Regex("^([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\\s+(.+)$")
    private val isoTpIndexPrefix = Regex("^[0-9A-Fa-f]:\\s*(.+)$")
    private val hexLenLineRegex = Regex("^[0-9A-Fa-f]{3}$")

    fun describeNrcRu(nrc: Int): String = when (nrc) {
        0x10 -> "generalReject"
        0x11 -> "сервис не поддерживается"
        0x12 -> "подфункция не поддерживается"
        0x13 -> "неверная длина или формат"
        0x22 -> "условия не выполнены"
        0x31 -> "запрос вне диапазона (DID/PID не поддерживается)"
        0x33 -> "требуется доступ безопасности"
        0x78 -> "ожидание ответа (responsePending)"
        0x7E -> "подфункция не поддерживается в активной сессии"
        0x7F -> "сервис не поддерживается в активной сессии"
        else -> "NRC 0x%02X".format(nrc)
    }

    /** Strips optional CAN header, `0:`/`1:` ISO-TP index, and ISO-TP PCI byte(s), returning clean payload bytes. */
    private data class StrippedFrame(
        val ecuHeader: String?,
        val tokens: List<String>,
        val expectedTotalLen: Int?,
    )

    private fun stripFrame(raw: String): StrippedFrame? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (hexLenLineRegex.matches(trimmed)) return null

        var ecuHeader: String? = null
        var body = trimmed
        val hdrMatch = canHeaderPrefix.matchEntire(trimmed)
        if (hdrMatch != null) {
            val candHdr = hdrMatch.groupValues[1].uppercase()
            val rest = hdrMatch.groupValues[2].trim()
            if (candHdr.length == 8 || (candHdr.startsWith("7") && rest.length >= 4)) {
                ecuHeader = candHdr
                body = rest
            }
        }

        isoTpIndexPrefix.matchEntire(body)?.let { body = it.groupValues[1].trim() }

        var tokens = body.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        var expectedTotalLen: Int? = null
        if (ecuHeader != null && tokens.isNotEmpty()) {
            val pci = tokens[0].toIntOrNull(16)
            if (pci != null && tokens[0].length == 2) {
                val frameType = (pci shr 4) and 0x0F
                when {
                    frameType == 0x1 && tokens.size >= 2 -> {
                        val lenLow = tokens[1].toIntOrNull(16) ?: 0
                        expectedTotalLen = ((pci and 0x0F) shl 8) or lenLow
                        tokens = tokens.drop(2)
                    }
                    frameType == 0x2 -> tokens = tokens.drop(1)
                    frameType == 0x0 && (pci and 0x0F) in 1..7 -> {
                        val sfLen = pci and 0x0F
                        tokens = tokens.drop(1).take(sfLen)
                    }
                }
            }
        }
        return StrippedFrame(ecuHeader, tokens, expectedTotalLen)
    }

    /** Extracts the first Negative Response (7F <sid> <nrc>) found in ELM327 lines, if any. */
    fun extractNrc(lines: List<String>): NegativeResponse? {
        for (raw in lines) {
            val frame = stripFrame(raw) ?: continue
            val t = frame.tokens
            if (t.size >= 3 && t[0] == "7F") {
                val sid = t[1].toIntOrNull(16) ?: continue
                val nrc = t[2].toIntOrNull(16) ?: continue
                return NegativeResponse(frame.ecuHeader, sid, nrc, describeNrcRu(nrc))
            }
        }
        return null
    }

    /**
     * Parses Mode 09 ASCII responses (e.g. PID `02` VIN, `04` CalID, `0A` ECU Name)
     * per ECU without concatenating Engine (`7E8`) and TCM (`7E9`) payloads together,
     * and ignoring Negative Responses (`7F <sid> <nrc>`).
     */
    fun parseMultiEcuAscii(lines: List<String>, pidHex: String): List<EcuMode09Value> {
        val targetPid = pidHex.trim().uppercase()
        data class MutableGroup(
            val header: String?,
            val raw: MutableList<String> = mutableListOf(),
            val bytes: MutableList<Int> = mutableListOf(),
            var sawHeader49: Boolean = false,
            var lastMsgIndex: Int = 0,
            var expectedTotalLen: Int? = null,
            var headerBytesConsumed: Int = 0,
        )

        val groups = mutableListOf<MutableGroup>()
        var currentAnon: MutableGroup? = null
        val byHeader = LinkedHashMap<String, MutableGroup>()

        for (raw in lines) {
            val stripped = stripFrame(raw) ?: continue
            val ecuHeader = stripped.ecuHeader
            val tokens = stripped.tokens
            if (tokens.isEmpty()) continue

            // Never feed Negative Response (7F <sid> <nrc>) bytes into ASCII payloads
            // (e.g. 7F 09 31 has 0x31 == '1' which would otherwise corrupt VIN/CalID).
            if (tokens[0] == "7F") continue

            val startsNew49 = tokens.size >= 2 && tokens[0] == "49" && tokens[1] == targetPid
            val msgIdx = if (startsNew49) tokens.getOrNull(2)?.takeIf { it.length == 2 }?.toIntOrNull(16) else null
            val group = if (ecuHeader != null) {
                val existing = byHeader[ecuHeader]
                if (existing != null && startsNew49 && existing.bytes.isNotEmpty() && (msgIdx == null || msgIdx <= existing.lastMsgIndex)) {
                    val fresh = MutableGroup(ecuHeader)
                    groups.add(fresh)
                    byHeader[ecuHeader] = fresh
                    fresh
                } else {
                    existing ?: MutableGroup(ecuHeader).also {
                        groups.add(it)
                        byHeader[ecuHeader] = it
                    }
                }
            } else {
                val cur = currentAnon
                if (cur == null || (startsNew49 && cur.sawHeader49 && cur.bytes.isNotEmpty() && (msgIdx == null || msgIdx <= cur.lastMsgIndex))) {
                    MutableGroup(null).also {
                        groups.add(it)
                        currentAnon = it
                    }
                } else {
                    cur
                }
            }

            if (stripped.expectedTotalLen != null && group.expectedTotalLen == null) {
                group.expectedTotalLen = stripped.expectedTotalLen
            }

            group.raw.add(raw)
            var i = 0
            while (i < tokens.size) {
                val exp = group.expectedTotalLen
                if (exp != null && group.headerBytesConsumed + group.bytes.size >= exp) {
                    break
                }
                if (tokens[i] == "49" && i + 1 < tokens.size && tokens[i + 1] == targetPid) {
                    group.sawHeader49 = true
                    i += 2
                    group.headerBytesConsumed += 2
                    // Optional Mode 09 item count / sequence byte (01..0F) immediately after 49 <pid>
                    if (i < tokens.size && tokens[i].length == 2) {
                        val seq = tokens[i].toIntOrNull(16)
                        if (seq != null && seq in 1..15) {
                            group.lastMsgIndex = seq
                            i++
                            group.headerBytesConsumed += 1
                        }
                    }
                    continue
                }
                val b = tokens[i].toIntOrNull(16)
                if (b != null && tokens[i].length == 2) {
                    if (b in 0x20..0x7E) {
                        group.bytes.add(b)
                    } else {
                        group.headerBytesConsumed += 1
                    }
                }
                i++
            }
        }

        return groups.mapNotNull { g ->
            val text = g.bytes
                .map { it.toChar() }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isBlank()) null else EcuMode09Value(g.header, text, g.raw.toList())
        }.distinctBy { "${it.ecuHeader}:${it.value}" }
    }

    /** Validates strict 17-character VIN format (ISO 3779, no I/O/Q, rejects placeholder strings). */
    fun isValidVin(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val norm = candidate.trim().uppercase()
        if (!vinRegex.matches(norm)) return false
        val distinct = norm.toSet()
        if (distinct.size < 3) return false // Rejects 00000..., XXXXX..., 11111..., etc.
        return true
    }

    /** Extracts a strictly validated 17-char VIN from mixed hex/ASCII ELM output; null when absent or invalid. */
    fun extractVin(lines: List<String>): String? {
        val perEcu = parseMultiEcuAscii(lines, "02")
        for (item in perEcu) {
            val compact = item.value.replace(" ", "").uppercase()
            if (isValidVin(compact)) return compact
            val m = vinSearchRegex.find(compact)
            if (m != null && compact.length <= 20 && isValidVin(m.value)) return m.value
        }
        for (raw in lines) {
            val stripped = raw.trim().replace(isoTpIndexPrefix, "$1").trim()
            if (stripped.isEmpty()) continue
            val tokens = stripped.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val allHexBytes = tokens.isNotEmpty() && tokens.all { it.length == 2 && it.toIntOrNull(16) != null }
            if (!allHexBytes) {
                val compact = stripped.replace(" ", "").uppercase()
                if (isValidVin(compact)) return compact
            }
        }
        return null
    }

    /** Reassembles per-ECU byte payloads from ISO-TP / ATH1 / ATH0 lines, ignoring 7F negative responses. */
    private fun collectBytesByEcu(lines: List<String>): Map<String, List<Int>> {
        val out = LinkedHashMap<String, MutableList<Int>>()
        val expectedLens = LinkedHashMap<String, Int>()
        for (raw in lines) {
            val frame = stripFrame(raw) ?: continue
            if (frame.tokens.isNotEmpty() && frame.tokens[0] == "7F") continue
            val key = frame.ecuHeader ?: "_ANON_"
            val buf = out.getOrPut(key) { mutableListOf() }
            if (frame.expectedTotalLen != null && key !in expectedLens) {
                expectedLens[key] = frame.expectedTotalLen
            }
            val limit = expectedLens[key]
            for (t in frame.tokens) {
                if (limit != null && buf.size >= limit) break
                val b = t.toIntOrNull(16) ?: continue
                if (t.length == 2) buf.add(b)
            }
        }
        return out
    }

    /** Extracts and validates a 17-char VIN from UDS `22 F1 90` (`62 F1 90 ...`) responses. */
    fun extractVinFromUds22F190(lines: List<String>): String? {
        for ((_, bytes) in collectBytesByEcu(lines)) {
            for (i in 0..bytes.size - 3) {
                if (bytes[i] == 0x62 && bytes[i + 1] == 0xF1 && bytes[i + 2] == 0x90) {
                    val ascii = bytes.drop(i + 3)
                        .take(17)
                        .filter { it in 0x20..0x7E }
                        .map { it.toChar() }
                        .joinToString("")
                        .trim()
                        .uppercase()
                    if (isValidVin(ascii)) return ascii
                }
            }
        }
        return null
    }

    /** Extracts printable ASCII string from UDS `22 <didHi> <didLo>` (`62 <didHi> <didLo> ...`). */
    fun extractAsciiFromUds22(lines: List<String>, didHi: Int, didLo: Int): String? {
        for ((_, bytes) in collectBytesByEcu(lines)) {
            for (i in 0..bytes.size - 3) {
                if (bytes[i] == 0x62 && bytes[i + 1] == didHi && bytes[i + 2] == didLo) {
                    val ascii = bytes.drop(i + 3)
                        .takeWhile { it != 0x00 }
                        .filter { it in 0x20..0x7E }
                        .map { it.toChar() }
                        .joinToString("")
                        .trim()
                    if (ascii.length >= 3) return ascii
                }
            }
        }
        return null
    }

    /** Extracts and validates a 17-char VIN from KWP2000 `1A 90` (`5A 90 ...`) responses. */
    fun extractVinFromKwp1A90(lines: List<String>): String? {
        for ((_, bytes) in collectBytesByEcu(lines)) {
            for (i in 0..bytes.size - 2) {
                if (bytes[i] == 0x5A && bytes[i + 1] == 0x90) {
                    val ascii = bytes.drop(i + 2)
                        .take(17)
                        .filter { it in 0x20..0x7E }
                        .map { it.toChar() }
                        .joinToString("")
                        .trim()
                        .uppercase()
                    if (isValidVin(ascii)) return ascii
                }
            }
        }
        return null
    }

    /** Extracts printable ASCII payloads (Calibration ID / ECU name) after "49 04"/"49 0A". */
    fun extractAscii(lines: List<String>, headerLow: String): String? {
        val items = parseMultiEcuAscii(lines, headerLow)
        if (items.isEmpty()) return null
        return items.joinToString(" | ") { it.value }
    }
}
