package com.obd2.app.core.obd

/**
 * Decoders for Mode-01 PID payloads.
 * Pure Kotlin; every branch is covered by unit tests.
 */
object PidDecoder {

    private fun weighted(data: ByteArray, weight: String?): Int {
        if (data.isEmpty()) return 0
        return when (weight) {
            "A" -> data[0].toInt() and 0xFF
            "AB" -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            "ABC" -> ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            "ABCD" -> ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            else -> data[0].toInt() and 0xFF
        }
    }

    private fun signedWeight(data: ByteArray, weight: String?): Int {
        val v = weighted(data, weight)
        return when (weight) {
            "A" -> if (v > 127) v - 256 else v
            "AB" -> if (v > 32767) v - 65536 else v
            else -> v
        }
    }

    /** Numeric decode per DB-stored weight/mult/offset. */
    fun numeric(def: PidDefinition, data: ByteArray, signed: Boolean = false): Double {
        val w = if (signed) signedWeight(data, def.weight) else weighted(data, def.weight)
        val mult = def.mult ?: 1.0
        val off = def.offset ?: 0.0
        return w * mult + off
    }

    /** bitmap decode: PID support masks (0100, 0120, ...). Returns list of supported pid numbers. */
    fun supportMask(basePid: Int, data: ByteArray): List<Int> {
        require(data.size >= 4)
        val mask = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val result = ArrayList<Int>(32)
        for (i in 0 until 32) {
            val bit = (mask shr (31 - i)) and 1
            if (bit == 1) result.add(basePid + 1 + i)
        }
        return result
    }

    fun bitmapBits(data: ByteArray, maxBits: Int = data.size * 8): List<Int> {
        val result = ArrayList<Int>()
        for (byteIdx in data.indices) {
            val b = data[byteIdx].toInt() and 0xFF
            for (bit in 0 until 8) {
                val pos = byteIdx * 8 + bit
                if (pos >= maxBits) break
                if ((b shr (7 - bit)) and 1 == 1) result.add(pos)
            }
        }
        return result
    }

    /**
     * Mode 01 PID 01/41 monitor status.
     * A: bits: 7 MIL, 0..6 DTC count; B..D monitor readiness.
     */
    data class MonitorStatus(
        val milOn: Boolean,
        val dtcCount: Int,
        val readiness: List<String>,
    )

    fun monitorStatus(data: ByteArray): MonitorStatus {
        val a = data[0].toInt() and 0xFF
        val mil = (a and 0x80) != 0
        val cnt = a and 0x7F
        val readiness = ArrayList<String>()
        val secondByte = data.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        val thirdByte = data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
        // SAE J1979 PID 01/41: Byte B bit 3 indicates compression ignition (diesel) vs spark ignition (petrol).
        // Continuous monitors availability in Byte B bits 0..2: MIS (bit0), FUEL (bit1), CCM (bit2).
        // Non-continuous monitors availability in Byte C bits 0..7.
        val compression = (secondByte and 0x08) != 0
        if ((secondByte and 0x01) != 0) readiness.add("MIS")
        if ((secondByte and 0x02) != 0) readiness.add("FUEL")
        if ((secondByte and 0x04) != 0) readiness.add("CCM")
        if (!compression) {
            if ((thirdByte and 0x01) != 0) readiness.add("CAT")
            if ((thirdByte and 0x02) != 0) readiness.add("HCAT")
            if ((thirdByte and 0x04) != 0) readiness.add("EVAP")
            if ((thirdByte and 0x08) != 0) readiness.add("AIR")
            if ((thirdByte and 0x10) != 0) readiness.add("AC")
            if ((thirdByte and 0x20) != 0) readiness.add("O2")
            if ((thirdByte and 0x40) != 0) readiness.add("O2H")
            if ((thirdByte and 0x80) != 0) readiness.add("EGR")
        } else {
            if ((thirdByte and 0x01) != 0) readiness.add("NMHC")
            if ((thirdByte and 0x02) != 0) readiness.add("NOx")
            if ((thirdByte and 0x08) != 0) readiness.add("BP")
            if ((thirdByte and 0x20) != 0) readiness.add("EGS")
            if ((thirdByte and 0x40) != 0) readiness.add("PM")
            if ((thirdByte and 0x80) != 0) readiness.add("EGR")
        }
        return MonitorStatus(mil, cnt, readiness)
    }

    /** Fuel system status (PID 03): 2 bytes, each a status code. */
    fun fuelSystemStatus(data: ByteArray): String {
        val names = mapOf(
            0 to "Не готов",
            1 to "Разомкнутый цикл (open loop)",
            2 to "Замкнутый цикл (closed loop)",
            4 to "Разомкнутый цикл (обогащение при пуске)",
            8 to "Разомкнутый цикл (обогащение при прогреве)",
            16 to "Замкнутый цикл, неисправность — запасная карта",
            32 to "Отключение подачи топлива",
            64 to "Ограниченная подача топлива",
        )
        return data.take(2).mapNotNull {
            val v = it.toInt() and 0xFF
            names[v]?.let { n -> "%02X: %s".format(v, n) }
        }.joinToString("; ").ifEmpty { "Код: " + data.joinToString(" ") { b -> "%02X".format(b) } }
    }

    /** OBD standards (PID 1C). */
    fun obdStandard(v: Int): String = when (v) {
        1 -> "OBD-II (США, CARB)"
        2 -> "OBD (США, EPA)"
        3 -> "OBD и OBD-II"
        4 -> "OBD-I"
        5 -> "Не предназначен для OBD"
        6 -> "EOBD (Европа)"
        7 -> "EOBD и OBD-II"
        8 -> "EOBD и OBD"
        9 -> "EOBD, OBD и OBD-II"
        10 -> "JOBD (Япония)"
        11 -> "JOBD и OBD-II"
        12 -> "JOBD и EOBD"
        13 -> "JOBD, EOBD и OBD-II"
        17 -> "EOBD (Европа, фаза 2)"
        18 -> "EOBD и OBD-II (фаза 2)"
        19 -> "EOBD и OBD (фаза 2)"
        20 -> "EOBD, OBD и OBD-II (фаза 2)"
        21 -> "EOBD (Европа, фаза 3)"
        22 -> "EOBD и OBD-II (фаза 3)"
        23 -> "EOBD и OBD (фаза 3)"
        24 -> "EOBD, OBD и OBD-II (фаза 3)"
        25 -> "EOBD-IV (Европа)"
        28 -> "Китай (HBD)"
        29 -> "Китай (HBD) и OBD-II"
        31 -> "Бразилия OBDBR-1"
        32 -> "Бразилия OBDBR-2"
        33 -> "Бразилия OBDBR-1 и OBD-II"
        34 -> "Бразилия OBDBR-2 и OBD-II"
        38 -> "Корея (KOBD)"
        else -> "Значение 0x%02X".format(v)
    }

    /** Fuel type (PID 51). */
    fun fuelType(v: Int): String = when (v) {
        0 -> "Не указан"
        1 -> "Бензин"
        2 -> "Метанол"
        3 -> "Этанол"
        4 -> "Дизель"
        5 -> "СПГ (LPG/пропан)"
        6 -> "КПГ (CNG/метан)"
        7 -> "Газ (пропан)"
        8 -> "Электрический"
        9 -> "Бензин + гибрид (электро/сжигание)"
        10 -> "Гибрид: бензин + электричество"
        11 -> "Гибрид: дизель + электричество"
        12 -> "Электромобиль"
        13 -> "Гибрид: дизель + электро"
        14 -> "Гибрид: бензин/электро (plug-in)"
        15 -> "Гибрид: дизель/электро (plug-in)"
        16 -> "Гибрид: регенеративный"
        17 -> "Бензин/этанол смешанный"
        18 -> "Этанол/метанол смешанный"
        19 -> "Бензин/этанол (прямая инъекция)"
        else -> "Значение 0x%02X".format(v)
    }

    /** O2 sensor present — 2 banks (PID 13: B1S1..B1S4, B2S1..B2S4). */
    fun o2Present2Banks(data: ByteArray): String {
        val b = data[0].toInt() and 0xFF
        val sensors = listOfNotNull(
            if (b and 0x01 != 0) "B1S1" else null, if (b and 0x02 != 0) "B1S2" else null,
            if (b and 0x04 != 0) "B1S3" else null, if (b and 0x08 != 0) "B1S4" else null,
            if (b and 0x10 != 0) "B2S1" else null, if (b and 0x20 != 0) "B2S2" else null,
            if (b and 0x40 != 0) "B2S3" else null, if (b and 0x80 != 0) "B2S4" else null,
        )
        return if (sensors.isEmpty()) "нет данных" else sensors.joinToString(", ")
    }

    /** O2 sensor present — 4 banks (PID 1D: B1S1..B4S2). */
    fun o2Present4Banks(data: ByteArray): String {
        val b = data[0].toInt() and 0xFF
        val sensors = listOfNotNull(
            if (b and 0x01 != 0) "B1S1" else null, if (b and 0x02 != 0) "B1S2" else null,
            if (b and 0x04 != 0) "B2S1" else null, if (b and 0x08 != 0) "B2S2" else null,
            if (b and 0x10 != 0) "B3S1" else null, if (b and 0x20 != 0) "B3S2" else null,
            if (b and 0x40 != 0) "B4S1" else null, if (b and 0x80 != 0) "B4S2" else null,
        )
        return if (sensors.isEmpty()) "нет данных" else sensors.joinToString(", ")
    }

    /** Kept for backwards compatibility (delegates to 4-bank layout). */
    fun o2Present(data: ByteArray): String = o2Present4Banks(data)

    /** Secondary air status (PID 12). */
    fun airStatus(v: Int): String = when (v and 0x07) {
        1 -> "Насос включён"
        2 -> "Насос выключен"
        4 -> "Клапан в байпасе"
        0 -> "Нет данных"
        else -> "Значение 0x%02X".format(v)
    }
}
