package com.obd2.app.core.lpg.omvl

import kotlin.math.roundToInt

/**
 * Strict offline binary decoder for OMVL SAVER SKY 32 (read-only).
 * Never mutates state, never allocates unbounded buffers, and rejects invalid/corrupted frames.
 */
object OmvlSkyCodec {
    const val PREFIX_REQUEST = 0x77
    const val PREFIX_RESPONSE = 0x66
    const val MAX_FRAME_BYTES = 256
    const val MIN_FRAME_BYTES = 5

    /** CRC-16/CCITT-FALSE: poly=0x1021, init=0xFFFF, no reflection, xorout=0x0000. */
    fun crc16CcittFalse(data: ByteArray, length: Int = data.size): Int {
        val safeLen = length.coerceIn(0, data.size)
        var crc = 0xFFFF
        for (i in 0 until safeLen) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    fun verifyFrame(raw: ByteArray, allowedPrefixes: Set<Int> = setOf(PREFIX_REQUEST, PREFIX_RESPONSE)): OmvlFrameDecodeResult {
        if (raw.size < MIN_FRAME_BYTES) {
            return OmvlFrameDecodeResult.Invalid("Кадр слишком короткий (${raw.size} < $MIN_FRAME_BYTES)")
        }
        if (raw.size > MAX_FRAME_BYTES) {
            return OmvlFrameDecodeResult.Invalid("Кадр превышает допустимую длину (${raw.size} > $MAX_FRAME_BYTES)")
        }
        val prefix = raw[0].toInt() and 0xFF
        if (prefix !in allowedPrefixes) {
            return OmvlFrameDecodeResult.Invalid("Неизвестный стартовый байт 0x%02X".format(prefix))
        }
        val declaredLen = raw[1].toInt() and 0xFF
        if (declaredLen != raw.size) {
            return OmvlFrameDecodeResult.Invalid("Несовпадение длины: в заголовке=$declaredLen, фактически=${raw.size}")
        }
        val calcCrc = crc16CcittFalse(raw, raw.size - 2)
        val wireCrc = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        if (calcCrc != wireCrc) {
            return OmvlFrameDecodeResult.Invalid(
                "Ошибка CRC-16: расчёт=0x%04X, в кадре=0x%04X".format(calcCrc, wireCrc),
            )
        }
        val opcode = raw[2].toInt() and 0xFF
        val payload = raw.copyOfRange(3, raw.size - 2)
        return OmvlFrameDecodeResult.Valid(
            OmvlFrame(
                prefix = prefix,
                length = declaredLen,
                opcode = opcode,
                payload = payload,
                crcBe = wireCrc,
                raw = raw.copyOf(),
            ),
        )
    }

    private fun u16Be(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)

    private fun roundScale(v: Double, factor: Int): Double =
        (v * factor).roundToInt() / factor.toDouble()

    private fun decodeU16Scaled(
        raw: ByteArray,
        offset: Int,
        scale: Double,
        roundFactor: Int,
        unit: String,
        offsetLabel: String,
    ): OmvlDecodedValue<Double> {
        val u = u16Be(raw, offset)
        if (u == 0xFFFF) {
            return OmvlDecodedValue(null, unit, OmvlFieldConfidence.UNDECODED, offsetLabel, "Недоступно (0xFFFF)")
        }
        return OmvlDecodedValue(roundScale(u * scale, roundFactor), unit, OmvlFieldConfidence.PROBABLE, offsetLabel)
    }

    /**
     * Decodes probable live telemetry fields from a verified 126-byte RX 0x09 frame.
     * Unconfirmed fields (gas/reducer temperatures, fuel mode, injector state) and 0xFFFF sentinels stay UNDECODED.
     */
    fun decodeTelemetry0x09(frame: OmvlFrame, offsetMs: Long = 0L): OmvlTelemetrySnapshot? {
        if (frame.prefix != PREFIX_RESPONSE || frame.opcode != 0x09 || frame.raw.size < 126) {
            return null
        }
        val r = frame.raw
        val rpmRaw = u16Be(r, 23)
        val rpmVal: OmvlDecodedValue<Int> = if (rpmRaw == 0xFFFF) {
            OmvlDecodedValue(null, "об/мин", OmvlFieldConfidence.UNDECODED, "frame[23..24]", "Недоступно (0xFFFF)")
        } else {
            OmvlDecodedValue(rpmRaw, "об/мин", OmvlFieldConfidence.PROBABLE, "frame[23..24]")
        }
        val petrolRaw = (0 until 4).map { i -> u16Be(r, 3 + 2 * i) }
        val petrolCyl: OmvlDecodedValue<List<Double>> = if (petrolRaw.any { it == 0xFFFF }) {
            OmvlDecodedValue(null, "мс", OmvlFieldConfidence.UNDECODED, "frame[3..10]", "Недоступно (0xFFFF)")
        } else {
            OmvlDecodedValue(petrolRaw.map { roundScale(it * 0.005, 1000) }, "мс", OmvlFieldConfidence.PROBABLE, "frame[3..10]")
        }
        val gasRaw = (0 until 4).map { i -> u16Be(r, 11 + 2 * i) }
        val gasCyl: OmvlDecodedValue<List<Double>> = if (gasRaw.any { it == 0xFFFF }) {
            OmvlDecodedValue(null, "мс", OmvlFieldConfidence.UNDECODED, "frame[11..18]", "Недоступно (0xFFFF)")
        } else {
            OmvlDecodedValue(gasRaw.map { roundScale(it * 0.005, 1000) }, "мс", OmvlFieldConfidence.PROBABLE, "frame[11..18]")
        }
        return OmvlTelemetrySnapshot(
            offsetMs = offsetMs,
            rpm = rpmVal,
            petrolInjCylMs = petrolCyl,
            petrolInjAvgMs = decodeU16Scaled(r, 19, 0.005, 1000, "мс", "frame[19..20]"),
            gasInjCylMs = gasCyl,
            gasInjAvgMs = decodeU16Scaled(r, 21, 0.005, 1000, "мс", "frame[21..22]"),
            mapBar = decodeU16Scaled(r, 27, 0.01, 100, "бар", "frame[27..28]"),
            gasPressureBar = decodeU16Scaled(r, 29, 0.01, 100, "бар", "frame[29..30]"),
            supplyVoltageV = decodeU16Scaled(r, 46, 0.001, 100, "В", "frame[46..47]"),
        )
    }

    /**
     * Decodes the 0x32 historical DTC counter/status table (104 bytes, 2-byte slots starting at payload[5]).
     */
    fun decodeDtcTable0x32(frame: OmvlFrame): List<OmvlDtcEntry> {
        if (frame.prefix != PREFIX_RESPONSE || frame.opcode != 0x32 || frame.payload.size < 7) {
            return emptyList()
        }
        val p = frame.payload
        val maxSlots = (p.size - 5) / 2
        val result = ArrayList<OmvlDtcEntry>()
        for (codeNum in 0 until maxSlots) {
            val off = 5 + codeNum * 2
            val count = p[off].toInt() and 0xFF
            val status = p[off + 1].toInt() and 0xFF
            if (count != 0 || status != 0) {
                result += OmvlDtcEntry(
                    code = "E%03d".format(codeNum),
                    codeNumber = codeNum,
                    descriptionRu = OmvlDtcCatalog.describeRu(codeNum),
                    occurrenceCount = count,
                    statusByteHex = "0x%02X".format(status),
                    hasFreezeFrameRecord = false,
                    isHistorical = true,
                )
            }
        }
        return result
    }

    /**
     * Decodes the 0x35 recent-error freeze-frame list (80 bytes, 5 × 15-byte records).
     * Only the confirmed E*** error code byte is interpreted; inner snapshot bytes remain undecoded.
     */
    fun decodeDtcEvents0x35(frame: OmvlFrame): List<OmvlDtcEntry> {
        if (frame.prefix != PREFIX_RESPONSE || frame.opcode != 0x35 || frame.payload.isEmpty() || frame.payload.size % 15 != 0) {
            return emptyList()
        }
        val p = frame.payload
        val result = ArrayList<OmvlDtcEntry>()
        var i = 0
        while (i + 15 <= p.size) {
            val codeNum = p[i].toInt() and 0xFF
            result += OmvlDtcEntry(
                code = "E%03d".format(codeNum),
                codeNumber = codeNum,
                descriptionRu = OmvlDtcCatalog.describeRu(codeNum),
                occurrenceCount = null,
                statusByteHex = null,
                hasFreezeFrameRecord = true,
                isHistorical = true,
            )
            i += 15
        }
        return result
    }

    fun mergeHistoricalDtcs(table0x32: List<OmvlDtcEntry>, events0x35: List<OmvlDtcEntry>): List<OmvlDtcEntry> {
        val freezeSet = events0x35.map { it.codeNumber }.toSet()
        val byCode = LinkedHashMap<Int, OmvlDtcEntry>()
        for (e in table0x32) {
            byCode[e.codeNumber] = e.copy(hasFreezeFrameRecord = e.codeNumber in freezeSet)
        }
        for (e in events0x35) {
            if (e.codeNumber !in byCode) {
                byCode[e.codeNumber] = e
            }
        }
        return byCode.values.sortedBy { it.codeNumber }
    }
}

/**
 * Bounded stream reassembler for fragmented RFCOMM RX chunks (e.g. 100 + 26 = 126 bytes).
 * Protects against buffer overflow, out-of-bounds reads, and infinite loops on corrupted streams.
 */
class OmvlStreamReassembler(
    private val expectedPrefix: Int = OmvlSkyCodec.PREFIX_RESPONSE,
    private val maxBufferBytes: Int = 4096,
) {
    private val buffer = ArrayList<Byte>(256)
    var droppedBytes: Int = 0
        private set
    var invalidFrameCount: Int = 0
        private set

    fun feed(chunk: ByteArray): List<OmvlFrame> {
        if (chunk.isEmpty()) return emptyList()
        if (buffer.size + chunk.size > maxBufferBytes) {
            // Bounded drop to prevent unbounded allocation on noisy/corrupted link
            val overflow = (buffer.size + chunk.size) - maxBufferBytes
            val drop = overflow.coerceAtMost(buffer.size)
            if (drop > 0) {
                buffer.subList(0, drop).clear()
                droppedBytes += drop
            }
            if (chunk.size > maxBufferBytes) {
                droppedBytes += chunk.size
                return emptyList()
            }
        }
        for (b in chunk) buffer.add(b)

        val out = ArrayList<OmvlFrame>()
        while (buffer.size >= OmvlSkyCodec.MIN_FRAME_BYTES) {
            val first = buffer[0].toInt() and 0xFF
            if (first != expectedPrefix) {
                val idx = buffer.indexOfFirst { (it.toInt() and 0xFF) == expectedPrefix }
                if (idx < 0) {
                    droppedBytes += buffer.size
                    buffer.clear()
                    break
                }
                droppedBytes += idx
                buffer.subList(0, idx).clear()
                if (buffer.size < OmvlSkyCodec.MIN_FRAME_BYTES) break
            }

            val msgLen = buffer[1].toInt() and 0xFF
            if (msgLen < OmvlSkyCodec.MIN_FRAME_BYTES || msgLen > OmvlSkyCodec.MAX_FRAME_BYTES) {
                buffer.removeAt(0)
                droppedBytes++
                invalidFrameCount++
                continue
            }
            if (buffer.size < msgLen) break

            val candidate = ByteArray(msgLen) { i -> buffer[i] }
            when (val res = OmvlSkyCodec.verifyFrame(candidate, setOf(expectedPrefix))) {
                is OmvlFrameDecodeResult.Valid -> {
                    out += res.frame
                    buffer.subList(0, msgLen).clear()
                }
                is OmvlFrameDecodeResult.Invalid -> {
                    buffer.removeAt(0)
                    droppedBytes++
                    invalidFrameCount++
                }
            }
        }
        return out
    }

    fun reset() {
        buffer.clear()
    }
}

object OmvlDtcCatalog {
    fun describeRu(codeNumber: Int): String = when (codeNumber) {
        in 1..8 -> "Отсутствие сигнала впрыска бензиновой форсунки $codeNumber"
        in 9..16 -> "Неисправность газовой форсунки ${codeNumber - 8}"
        17 -> "Датчик температуры редуктора — цепь замкнута на массу"
        18 -> "Датчик температуры редуктора — обрыв цепи"
        19 -> "Датчик температуры газа — цепь замкнута на массу"
        20 -> "Датчик температуры газа — обрыв цепи"
        21 -> "Газовый клапан — цепь замкнута на массу"
        22 -> "Газовый клапан — обрыв цепи"
        23 -> "Пустой баллон (низкое давление газа)"
        24 -> "Неэффективная система подогрева редуктора (температура < 15 °C)"
        25 -> "Бензиновые форсунки постоянно открыты"
        26 -> "Газовые форсунки постоянно открыты"
        28 -> "Неисправность панели управления / переключателя топлива"
        31 -> "Низкое напряжение питания ЭБУ (справочно)"
        33 -> "Неправильный сигнал +12 В (справочно)"
        42 -> "Автоматический переход на бензин (справочно)"
        else -> "Неизвестный код ГБО E%03d".format(codeNumber)
    }
}
