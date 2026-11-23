package com.obd2.app.core.universal

/**
 * Documented ECU definition within a vehicle diagnostic profile.
 */
data class ProfileEcuDefinition(
    val vagAddressCode: String,   // e.g. "01", "02", "03", "17", "19" or "OBD"
    val txAddress: String,
    val rxAddress: String,
    val nameRu: String,
    val protocolKind: DiagnosticProtocolKind,
    val readOnlySupportedOnElm327: Boolean,
    val notesRu: String,
)

/**
 * Documented, strictly read-only command belonging to a vehicle profile.
 */
data class ProfileReadCommand(
    val command: String,
    val targetEcu: String,
    val titleRu: String,
    val unit: String,
    val descriptionRu: String,
    val requiresUds: Boolean = false,
)

/**
 * Extensible profile describing protocols, ECU addressing, VIN/ID probe sequence,
 * safe read-only commands, and honest ELM327 adapter limitations for a vehicle family.
 */
data class VehicleDiagnosticProfile(
    val id: String,
    val brand: String,
    val model: String,
    val generation: String,
    val yearRange: String,
    val engineCode: String,
    val transmissionHint: String,
    val protocols: List<String>,
    val ecuTargets: List<ProfileEcuDefinition>,
    val vinProbeSequence: List<String>,
    val safeReadOnlyCommands: List<ProfileReadCommand>,
    val adapterLimitationsRu: List<String>,
) {
    val displayTitle: String
        get() = listOf(brand, model, generation, yearRange, engineCode)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * Extensible registry of vehicle diagnostic profiles.
 * New brands/models/generations can be registered without modifying core OBD/ELM logic.
 */
object VehicleDiagnosticProfileRegistry {

    val skodaOctavia1zBse: VehicleDiagnosticProfile = VehicleDiagnosticProfile(
        id = "skoda_octavia_1z_2010_2013_bse",
        brand = "Skoda",
        model = "Octavia II",
        generation = "1Z (A5 FL)",
        yearRange = "2010–2013",
        engineCode = "1.6 MPI BSE (Simos 7.1)",
        transmissionHint = "АКПП 09G (Aisin) / МКПП",
        protocols = listOf(
            "ISO 15765-4 CAN 11-bit 500 kbps (ATSP6) — основной OBD-II / UDS шлюз",
            "ISO 14230-4 KWP2000 (ATSP4 / ATSP5) — резервный опрос 1A 90",
            "VAG TP 2.0 (PQ35 CAN) — фирменный протокол блоков ABS/Airbag/Climate",
        ),
        ecuTargets = listOf(
            ProfileEcuDefinition(
                vagAddressCode = "01",
                txAddress = "7E0",
                rxAddress = "7E8",
                nameRu = "Электроника двигателя (ECM · Simos 7.1)",
                protocolKind = DiagnosticProtocolKind.STANDARD_OBD2,
                readOnlySupportedOnElm327 = true,
                notesRu = "Отвечает по OBD-II (01/03/07/09) и адресно 7E0->7E8.",
            ),
            ProfileEcuDefinition(
                vagAddressCode = "02",
                txAddress = "7E1",
                rxAddress = "7E9",
                nameRu = "Электроника АКПП (TCM · 09G)",
                protocolKind = DiagnosticProtocolKind.STANDARD_OBD2,
                readOnlySupportedOnElm327 = true,
                notesRu = "Может отвечать на 0904 (CalID) и 03/07 отдельно от двигателя; на 0902 часто отвечает NRC 7F 09 12.",
            ),
            ProfileEcuDefinition(
                vagAddressCode = "17",
                txAddress = "714",
                rxAddress = "77E",
                nameRu = "Комбинация приборов (Dashboard / Immobilizer)",
                protocolKind = DiagnosticProtocolKind.UDS_ISO14229,
                readOnlySupportedOnElm327 = true,
                notesRu = "На рестайлинге 1Z FL (2010–2013) поддерживает UDS 22 F1 90 (VIN) и 22 F1 87 (номер детали).",
            ),
            ProfileEcuDefinition(
                vagAddressCode = "19",
                txAddress = "710",
                rxAddress = "77A",
                nameRu = "Диагностический интерфейс шин данных (Gateway)",
                protocolKind = DiagnosticProtocolKind.UDS_ISO14229,
                readOnlySupportedOnElm327 = true,
                notesRu = "Возможен опрос UDS 22 F1 90 / 22 F1 87 на рестайлинговой платформе PQ35.",
            ),
            ProfileEcuDefinition(
                vagAddressCode = "03",
                txAddress = "713",
                rxAddress = "77D",
                nameRu = "Электроника тормозной системы (ABS/ESP)",
                protocolKind = DiagnosticProtocolKind.VAG_TP20,
                readOnlySupportedOnElm327 = false,
                notesRu = "Использует VAG TP 2.0; стандартный ELM327 (Vgate iCar Pro) без отдельного стека TP 2.0 блок не читает.",
            ),
        ),
        vinProbeSequence = listOf(
            "1. ATH1 + 0902 (функциональный OBD-II с разделением заголовков 7E8 / 7E9)",
            "2. ATH1 + ATSH 7E0 + ATCRA 7E8 + 0902 (прямой опрос ЭБУ двигателя без помех от АКПП 7E9)",
            "3. UDS 22 F1 90 на 7E0->7E8 (двигатель), 714->77E (панель приборов 17), 710->77A (шлюз 19)",
            "4. KWP2000 1A 90 (если активен протокол KWP2000)",
        ),
        safeReadOnlyCommands = listOf(
            ProfileReadCommand("010C", "7E0", "Обороты двигателя", "об/мин", "Стандартный PID RPM", requiresUds = false),
            ProfileReadCommand("0105", "7E0", "Температура охлаждающей жидкости", "°C", "Стандартный PID ECT", requiresUds = false),
            ProfileReadCommand("010F", "7E0", "Температура впускного воздуха", "°C", "Стандартный PID IAT", requiresUds = false),
            ProfileReadCommand("0111", "7E0", "Положение дроссельной заслонки", "%", "Стандартный PID TPS", requiresUds = false),
            ProfileReadCommand("0104", "7E0", "Расчётная нагрузка двигателя", "%", "Стандартный PID Load", requiresUds = false),
            ProfileReadCommand("0106", "7E0", "Краткосрочная коррекция топлива (STFT B1)", "%", "Стандартный PID STFT", requiresUds = false),
            ProfileReadCommand("0107", "7E0", "Долгосрочная коррекция топлива (LTFT B1)", "%", "Стандартный PID LTFT", requiresUds = false),
            ProfileReadCommand("0142", "7E0", "Напряжение бортовой сети (ECU)", "В", "Модуль контроля напряжения (+ ATRV)", requiresUds = false),
            ProfileReadCommand("22F190", "7E0 / 714 / 710", "Чтение VIN по UDS", "VIN", "ISO 14229 DID F190 (только чтение)", requiresUds = true),
            ProfileReadCommand("22F187", "7E0 / 714 / 710", "Номер детали ЭБУ VAG", "ASCII", "ISO 14229 DID F187 (только чтение)", requiresUds = true),
            ProfileReadCommand("22F189", "7E0 / 714 / 710", "Версия ПО ЭБУ", "ASCII", "ISO 14229 DID F189 (только чтение)", requiresUds = true),
        ),
        adapterLimitationsRu = listOf(
            "Адаптер Vgate iCar Pro (ELM327) не реализует транспортный уровень VAG TP 2.0: блоки ABS (03), Airbag (15), Климат (08) на PQ35 без TP 2.0 недоступны.",
            "На Simos 7.1 + 09G при опросе Mode 09 без заголовков (ATH0) ответы 7E8 (двигатель) и 7E9 (АКПП) смешиваются, а NRC 7F 09 xx может портить ASCII-буфер.",
            "Кодирование, адаптации, базовые установки и сброс сервисных интервалов намеренно не выполняются (строгий режим только чтения).",
        ),
    )

    val genericObd2: VehicleDiagnosticProfile = VehicleDiagnosticProfile(
        id = "generic_obd2",
        brand = "Универсальный",
        model = "OBD-II / EOBD",
        generation = "Стандарт ISO 15031 / 15765",
        yearRange = "1996+",
        engineCode = "Любой совместимый ДВС",
        transmissionHint = "Определяется автоматически",
        protocols = listOf(
            "Автоопределение ELM327 (ATSP0): CAN 11/29-bit, KWP2000, ISO 9141-2, J1850",
        ),
        ecuTargets = listOf(
            ProfileEcuDefinition(
                vagAddressCode = "OBD",
                txAddress = "7DF",
                rxAddress = "7E8",
                nameRu = "Базовый ЭБУ двигателя (OBD-II)",
                protocolKind = DiagnosticProtocolKind.STANDARD_OBD2,
                readOnlySupportedOnElm327 = true,
                notesRu = "Стандартные режимы 01, 02, 03, 06, 07, 09, 0A.",
            ),
        ),
        vinProbeSequence = listOf(
            "1. ATH1 + 0902 (функциональный OBD-II)",
            "2. ATH1 + ATSH 7E0 + ATCRA 7E8 + 0902",
            "3. UDS 22 F1 90 (7E0->7E8)",
            "4. KWP2000 1A 90 (на шине KWP)",
        ),
        safeReadOnlyCommands = listOf(
            ProfileReadCommand("010C", "7DF", "Обороты двигателя", "об/мин", "Стандартный PID RPM"),
            ProfileReadCommand("0105", "7DF", "Температура ОЖ", "°C", "Стандартный PID ECT"),
            ProfileReadCommand("0902", "7DF", "Чтение VIN (Mode 09)", "VIN", "Стандартный запрос VIN"),
        ),
        adapterLimitationsRu = listOf(
            "Доступность нестандартных блоков зависит от марки, протокола шины и возможностей адаптера ELM327.",
        ),
    )

    private val profiles: List<VehicleDiagnosticProfile> = listOf(
        skodaOctavia1zBse,
        genericObd2,
    )

    fun allProfiles(): List<VehicleDiagnosticProfile> = profiles

    fun findById(id: String?): VehicleDiagnosticProfile? =
        profiles.firstOrNull { it.id == id }

    fun matchVehicle(
        maker: String?,
        model: String?,
        year: String?,
        engine: String?,
    ): VehicleDiagnosticProfile {
        val joined = listOfNotNull(maker, model, year, engine).joinToString(" ").lowercase()
        val isSkoda = joined.contains("skoda") || joined.contains("шкода")
        val isOctavia1z = joined.contains("octavia") || joined.contains("октавия") ||
            joined.contains("1z") || joined.contains("a5")
        val isBseOrYear = joined.contains("bse") || joined.contains("1.6") ||
            joined.contains("2010") || joined.contains("2011") ||
            joined.contains("2012") || joined.contains("2013")
        return if ((isSkoda && isOctavia1z) || (isOctavia1z && isBseOrYear)) {
            skodaOctavia1zBse
        } else {
            genericObd2
        }
    }
}
