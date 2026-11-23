package com.obd2.app.ui.catalog

import com.obd2.app.core.obd.PidDefinition

/**
 * Human-friendly grouping of the standard PID catalog (§1): driver-oriented
 * categories, short plain-language explanations, and technical details kept
 * behind an expandable section. Decoder types (bitmap/encoded/dtc/...) are
 * NEVER shown as the primary description.
 */
object PidCatalog {

    const val CAT_ENGINE = "Двигатель"
    const val CAT_TEMP = "Температуры"
    const val CAT_FUEL = "Топливо"
    const val CAT_ELECTRIC = "Электрика"
    const val CAT_ECO = "Экология"
    const val CAT_OTHER = "Прочее"

    val CATEGORIES = listOf(CAT_ENGINE, CAT_TEMP, CAT_FUEL, CAT_ELECTRIC, CAT_ECO, CAT_OTHER)

    private fun hexSet(vararg hex: String) = hex.map { it.uppercase() }.toSet()

    private val enginePids = hexSet(
        "04", "0B", "0C", "0E", "10", "11", "1F", "30", "33",
        "11", "45", "47", "48", "49", "4A", "4B", "4C", "5A",
        "61", "62", "63", "64", "8E", "66",
    )
    private val tempPids = hexSet(
        "05", "0F", "46", "5C", "67", "68", "78", "79", "84",
    )
    private val fuelPids = hexSet(
        "03", "06", "07", "08", "09", "0A", "22", "23", "2C", "2D", "2E", "2F",
        "44", "51", "52", "53", "54", "55", "56", "57", "58", "59", "5D", "5E", "9D", "9E", "A2",
    )
    private val electricPids = hexSet("42", "5B", "65", "1E")
    private val ecoPids = hexSet(
        "12", "13", "1D", "14", "15", "16", "17", "18", "19", "1A", "1B",
        "24", "25", "26", "27", "28", "29", "2A", "2B",
        "34", "35", "36", "37", "38", "39", "3A", "3B",
        "3C", "3D", "3E", "3F", "7C", "7D", "7E", "83",
    )

    fun categoryFor(def: PidDefinition): String {
        if (def.mode == 9) return CAT_OTHER
        val h = "%02X".format(def.pid)
        return when {
            h in enginePids -> CAT_ENGINE
            h in tempPids -> CAT_TEMP
            h in fuelPids -> CAT_FUEL
            h in electricPids -> CAT_ELECTRIC
            h in ecoPids -> CAT_ECO
            else -> CAT_OTHER
        }
    }

    /** Technical decoder kinds that must never be shown as the main text. */
    fun isTechnicalDecoder(def: PidDefinition): Boolean =
        def.decoder in setOf("bitmap", "encoded", "dtc", "status", "fuel_status", "air_status",
            "obd_std", "freeze_dtc", "emission_req", "nte_status", "aux", "aux_io_supported")

    /** Short driver-oriented purpose text; falls back to the RU name itself. */
    fun explanation(def: PidDefinition): String {
        if (def.mode == 9) return "Идентификационная информация автомобиля (Mode 09)"
        val h = "%02X".format(def.pid)
        val known = mapOf(
            "04" to "Насколько сильно загружен двигатель прямо сейчас",
            "05" to "Температура охлаждающей жидкости: прогрев и перегрев",
            "0B" to "Разрежение/давление во впускном коллекторе",
            "0C" to "Обороты коленвала — основной показатель работы двигателя",
            "0D" to "Скорость автомобиля по данным ЭБУ",
            "0F" to "Температура воздуха на входе в двигатель",
            "10" to "Расход воздуха (MAF) — основа расчёта смеси",
            "11" to "Насколько открыта дроссельная заслонка",
            "1F" to "Сколько времени работает двигатель с момента пуска",
            "21" to "Пробег, накопленный с включённой лампой «Check Engine»",
            "2C" to "Сколько выхлопных газов возвращается во впуск (EGR)",
            "2E" to "Открытие клапана продувки бака (EVAP)",
            "2F" to "Уровень топлива в баке",
            "31" to "Пробег с момента очистки ошибок",
            "33" to "Атмосферное давление — влияет на расчёт смеси",
            "42" to "Напряжение бортовой сети: состояние генератора и АКБ",
            "43" to "Абсолютная нагрузка двигателя",
            "44" to "Целевое соотношение воздух/топливо (лямбда)",
            "45" to "Относительное положение дросселя",
            "4D" to "Время работы с включённой лампой неисправности",
            "4E" to "Время с момента очистки ошибок",
            "5C" to "Температура моторного масла",
            "5F" to "Экологические требования, под которые сертифицирован автомобиль",
            "5B" to "Остаточный ресурс гибридной батареи",
            "61" to "Момент, запрошенный водителем (педаль газа)",
            "62" to "Фактический момент двигателя",
            "A6" to "Одометр по данным ЭБУ",
        )
        return known[h] ?: def.nameRu ?: def.nameEn
    }

    /** Human title: never a decoder type. */
    fun title(def: PidDefinition): String {
        val name = def.nameRu ?: def.nameEn
        return if (isTechnicalDecoder(def)) "Служебный параметр (${hex(def)})" else name
    }

    fun hex(def: PidDefinition): String = "%02X %02X".format(def.mode, def.pid)

    /** Expandable technical details text (PID, formula, raw input data). */
    fun technicalDetails(def: PidDefinition, rawLast: String?): String = buildString {
        appendLine("PID: ${hex(def)}")
        def.formula?.let { appendLine("Формула: $it") }
        val mult = def.mult
        val off = def.offset
        if (mult != null || off != null) {
            appendLine("Декодирование: значение = данные × ${mult ?: 1} ${if (off != null && off != 0.0) "+ ($off)" else ""}".trim())
        }
        def.weight?.let { appendLine("Байты данных: $it") }
        def.bytes?.let { appendLine("Длина ответа: $it байт") }
        rawLast?.takeIf { it.isNotBlank() }?.let { appendLine("Последние сырые данные: $it") }
    }
}
