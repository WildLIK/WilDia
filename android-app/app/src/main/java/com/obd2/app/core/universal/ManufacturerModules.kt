package com.obd2.app.core.universal

interface ManufacturerModule {
    val id: String
    val manufacturerName: String
    val supportedProtocols: List<DiagnosticProtocolKind>
    val documentedEcus: List<DocumentedEcuTarget>
    val adapterLimitationsRu: String
    val capabilitySpecs: List<DiagnosticFunctionSpec>
}

object UniversalObd2Module : ManufacturerModule {
    override val id = "universal_obd2"
    override val manufacturerName = "Universal"
    override val supportedProtocols = listOf(
        DiagnosticProtocolKind.STANDARD_OBD2,
        DiagnosticProtocolKind.CAN_ISO_TP,
        DiagnosticProtocolKind.KWP2000,
        DiagnosticProtocolKind.J1850_PWM,
    )
    override val documentedEcus = listOf(
        DocumentedEcuTarget("7E0", "7E8", "Двигатель / ECM (OBD-II)", DiagnosticProtocolKind.STANDARD_OBD2, standardObdFallback = true),
        DocumentedEcuTarget("7E1", "7E9", "Трансмиссия / TCM (OBD-II)", DiagnosticProtocolKind.STANDARD_OBD2, standardObdFallback = true),
    )
    override val adapterLimitationsRu = "Работает со стандартными ELM327 (BLE и Classic SPP) без адресных расширений."
    override val capabilitySpecs = listOf(
        DiagnosticFunctionSpec(
            functionId = "obd_dtc_read",
            titleRu = "Чтение стандартных кодов ошибок (Mode 03/07/0A)",
            descriptionRu = "Считывание сохранённых, ожидающих и постоянных кодов OBD-II и расшифровка по офлайн-базе.",
            manufacturer = "Universal",
            compatibleModels = listOf("Любой OBD-II автомобиль"),
            supportedEcuIds = listOf("7E8", "7E9", "OBD"),
            requiredProtocol = DiagnosticProtocolKind.STANDARD_OBD2,
            adapterRequirements = "ELM327 с поддержкой стандартного протокола авто",
            preconditions = listOf("Включено зажигание", "ЭБУ отвечает"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.OBD_SUPPORT_BITMAP,
            confidenceHint = "Подтверждается при ответе ЭБУ на 03/07/0A",
            groupRu = "Ошибки и стоп-кадр",
        ),
        DiagnosticFunctionSpec(
            functionId = "obd_freeze_frame",
            titleRu = "Чтение стоп-кадра (Mode 02 Freeze Frame)",
            descriptionRu = "Снимок параметров двигателя в момент фиксации ошибки.",
            manufacturer = "Universal",
            compatibleModels = listOf("Любой OBD-II автомобиль"),
            supportedEcuIds = listOf("7E8", "OBD"),
            requiredProtocol = DiagnosticProtocolKind.STANDARD_OBD2,
            adapterRequirements = "ELM327 (стандартный OBD-II)",
            preconditions = listOf("Наличие сохранённого стоп-кадра в ЭБУ"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.OBD_SUPPORT_BITMAP,
            confidenceHint = "Подтверждается ответом 02 02 00 / 02 00 00",
            groupRu = "Ошибки и стоп-кадр",
        ),
        DiagnosticFunctionSpec(
            functionId = "obd_im_readiness",
            titleRu = "Проверка готовности мониторов (I/M Readiness)",
            descriptionRu = "Статус диагностических мониторов (катализатор, EVAP, O2, пропуски).",
            manufacturer = "Universal",
            compatibleModels = listOf("Любой OBD-II автомобиль"),
            supportedEcuIds = listOf("7E8", "OBD"),
            requiredProtocol = DiagnosticProtocolKind.STANDARD_OBD2,
            adapterRequirements = "ELM327 (стандартный OBD-II)",
            preconditions = listOf("ЭБУ отвечает на 01 01"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.OBD_SUPPORT_BITMAP,
            confidenceHint = "Подтверждается ответом 41 01",
            groupRu = "Мониторы и параметры",
        ),
        DiagnosticFunctionSpec(
            functionId = "obd_live_pids",
            titleRu = "Чтение и запись параметров двигателя (Mode 01)",
            descriptionRu = "Обороты, температура, коррекции топлива, напряжение, нагрузка.",
            manufacturer = "Universal",
            compatibleModels = listOf("Любой OBD-II автомобиль"),
            supportedEcuIds = listOf("7E8", "OBD"),
            requiredProtocol = DiagnosticProtocolKind.STANDARD_OBD2,
            adapterRequirements = "ELM327 (стандартный OBD-II)",
            preconditions = listOf("Поддержка PID в маске 0100..01C0"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.OBD_SUPPORT_BITMAP,
            confidenceHint = "Подтверждается маской 0100",
            groupRu = "Мониторы и параметры",
        ),
        DiagnosticFunctionSpec(
            functionId = "obd_mode06",
            titleRu = "Результаты бортовых тестов (Mode 06)",
            descriptionRu = "Контроль пропусков по цилиндрам, порогов катализатора и датчиков O2.",
            manufacturer = "Universal",
            compatibleModels = listOf("CAN OBD-II автомобили (ISO 15765-4)"),
            supportedEcuIds = listOf("7E8"),
            requiredProtocol = DiagnosticProtocolKind.CAN_ISO_TP,
            adapterRequirements = "ELM327 + CAN ISO 15765-4",
            preconditions = listOf("Ответ ЭБУ на 06 00"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.OBD_SUPPORT_BITMAP,
            confidenceHint = "Подтверждается маской 0600",
            groupRu = "Мониторы и параметры",
        ),
        DiagnosticFunctionSpec(
            functionId = "obd_mode09_ident",
            titleRu = "Идентификация ЭБУ и калибровок (Mode 09)",
            descriptionRu = "Чтение VIN, Calibration ID, CVN и имени ЭБУ.",
            manufacturer = "Universal",
            compatibleModels = listOf("Автомобили с поддержкой Mode 09"),
            supportedEcuIds = listOf("7E8", "OBD"),
            requiredProtocol = DiagnosticProtocolKind.STANDARD_OBD2,
            adapterRequirements = "ELM327 с поддержкой многофреймовых ответов ISO-TP",
            preconditions = listOf("Поддержка Mode 09 ЭБУ"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.OBD_SUPPORT_BITMAP,
            confidenceHint = "Подтверждается ответом 09 00 / 09 02 / 09 0A",
            groupRu = "Идентификация ЭБУ",
        ),
    )
}

object VagModule : ManufacturerModule {
    override val id = "vag"
    override val manufacturerName = "VAG"
    override val supportedProtocols = listOf(
        DiagnosticProtocolKind.STANDARD_OBD2,
        DiagnosticProtocolKind.UDS_ISO14229,
        DiagnosticProtocolKind.VAG_TP20,
        DiagnosticProtocolKind.KWP2000,
    )
    override val documentedEcus = listOf(
        DocumentedEcuTarget("7E0", "7E8", "01 - Электроника двигателя (ECM)", DiagnosticProtocolKind.STANDARD_OBD2, standardObdFallback = true),
        DocumentedEcuTarget("710", "77A", "19 - Диагностический интерфейс шин (Gateway)", DiagnosticProtocolKind.UDS_ISO14229),
        DocumentedEcuTarget("714", "77E", "17 - Комбинация приборов (Dashboard)", DiagnosticProtocolKind.UDS_ISO14229),
        DocumentedEcuTarget("713", "77D", "03 - Тормозная система ABS/ESP", DiagnosticProtocolKind.VAG_TP20),
    )
    override val adapterLimitationsRu =
        "Платформы PQ35 (например Octavia A5) часто используют TP 2.0 для блоков 01/03/09; Vgate iCar Pro не гарантирует TP 2.0 без полевой проверки, тогда как UDS на Gateway/приборке требует поддержки ATSH/ATCRA."
    override val capabilitySpecs = listOf(
        DiagnosticFunctionSpec(
            functionId = "vag_uds_ident",
            titleRu = "Чтение идентификации UDS-блоков (DID F187 / F190)",
            descriptionRu = "Чтение номера детали VAG и версии ПО для UDS-совместимых блоков.",
            manufacturer = "VAG",
            compatibleModels = listOf("VW / Audi / Škoda / SEAT (CAN UDS)"),
            supportedEcuIds = listOf("77A", "77E", "7E8"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "ELM327 с поддержкой ATSH/ATCRA и ISO-TP CAN",
            preconditions = listOf("Обнаружен UDS-совместимый блок"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.ECU_RESPONDED,
            confidenceHint = "Требует ответа 62 F1 87",
            groupRu = "Фирменное чтение (VAG)",
        ),
        DiagnosticFunctionSpec(
            functionId = "vag_service_reset",
            titleRu = "Сброс сервисного интервала (VAG)",
            descriptionRu = "Сброс счётчиков ТО в комбинации приборов.",
            manufacturer = "VAG",
            compatibleModels = listOf("VW / Audi / Škoda / SEAT"),
            supportedEcuIds = listOf("77E"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "Адаптер с подтверждённой записью UDS/TP 2.0",
            preconditions = listOf("Отдельный этап валидации на стенде"),
            operationType = OperationType.SERVICE,
            riskLevel = RiskLevel.MEDIUM,
            verificationMethod = VerificationMethod.DISABLED_SAFETY_LOCK,
            confidenceHint = "Отключено до стендовой проверки",
            groupRu = "Сервисные операции (отключено)",
        ),
        DiagnosticFunctionSpec(
            functionId = "vag_long_coding",
            titleRu = "Длинное кодирование / настройки комфорта (VAG)",
            descriptionRu = "Изменение кодировки блоков комфорта, освещения и приборной панели.",
            manufacturer = "VAG",
            compatibleModels = listOf("VW / Audi / Škoda / SEAT"),
            supportedEcuIds = listOf("77A", "77E"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "Надёжный адаптер (STN/vLinker) + SecurityAccess",
            preconditions = listOf("Резервная копия кодировки", "Отдельная валидация"),
            operationType = OperationType.MODIFY,
            riskLevel = RiskLevel.HIGH,
            verificationMethod = VerificationMethod.DISABLED_SAFETY_LOCK,
            confidenceHint = "Отключено из соображений безопасности",
            groupRu = "Изменяющие операции (отключено)",
        ),
    )
}

object FordModule : ManufacturerModule {
    override val id = "ford"
    override val manufacturerName = "Ford"
    override val supportedProtocols = listOf(
        DiagnosticProtocolKind.STANDARD_OBD2,
        DiagnosticProtocolKind.J1850_PWM,
        DiagnosticProtocolKind.UDS_ISO14229,
    )
    override val documentedEcus = listOf(
        DocumentedEcuTarget("7E0", "7E8", "PCM — Модуль управления силовым агрегатом", DiagnosticProtocolKind.STANDARD_OBD2, standardObdFallback = true),
        DocumentedEcuTarget("760", "768", "ABS — Антиблокировочная система (HS-CAN)", DiagnosticProtocolKind.UDS_ISO14229),
        DocumentedEcuTarget("720", "728", "IPC — Панель приборов (HS-CAN)", DiagnosticProtocolKind.UDS_ISO14229),
    )
    override val adapterLimitationsRu =
        "Модели Ford 2002–2005 (например Explorer 2004) могут использовать SAE J1850 PWM или требовать переключатель MS-CAN, недоступный на базовом ELM327 без доработки."
    override val capabilitySpecs = listOf(
        DiagnosticFunctionSpec(
            functionId = "ford_uds_ident",
            titleRu = "Идентификация модулей Ford HS-CAN (DID F187/F190)",
            descriptionRu = "Чтение каталожных номеров блоков PCM/ABS/IPC по CAN UDS.",
            manufacturer = "Ford",
            compatibleModels = listOf("Ford CAN (2006+)", "Explorer (при наличии HS-CAN)"),
            supportedEcuIds = listOf("7E8", "768", "728"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "ELM327 HS-CAN",
            preconditions = listOf("Активная шина HS-CAN"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.ECU_RESPONDED,
            confidenceHint = "Требует ответа UDS 62 F1 87",
            groupRu = "Фирменное чтение (Ford)",
        ),
        DiagnosticFunctionSpec(
            functionId = "ford_asbuilt_config",
            titleRu = "Конфигурация модулей As-Built (Ford)",
            descriptionRu = "Изменение конфигурации центрального модуля BCM/IPC.",
            manufacturer = "Ford",
            compatibleModels = listOf("Ford CAN"),
            supportedEcuIds = listOf("726", "720"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "Адаптер с поддержкой MS-CAN/HS-CAN",
            preconditions = listOf("Бэкап As-Built", "Отдельная валидация"),
            operationType = OperationType.MODIFY,
            riskLevel = RiskLevel.HIGH,
            verificationMethod = VerificationMethod.DISABLED_SAFETY_LOCK,
            confidenceHint = "Отключено до отдельной проверки",
            groupRu = "Изменяющие операции (отключено)",
        ),
    )
}

object NissanDatsunModule : ManufacturerModule {
    override val id = "nissan_datsun"
    override val manufacturerName = "Nissan / Datsun"
    override val supportedProtocols = listOf(
        DiagnosticProtocolKind.STANDARD_OBD2,
        DiagnosticProtocolKind.UDS_ISO14229,
        DiagnosticProtocolKind.KWP2000,
    )
    override val documentedEcus = listOf(
        DocumentedEcuTarget("7E0", "7E8", "ECM — Блок управления двигателем", DiagnosticProtocolKind.STANDARD_OBD2, standardObdFallback = true),
        DocumentedEcuTarget("740", "760", "ABS / ESP (CAN)", DiagnosticProtocolKind.UDS_ISO14229),
        DocumentedEcuTarget("745", "765", "BCM / ЦБКЭ", DiagnosticProtocolKind.UDS_ISO14229),
    )
    override val adapterLimitationsRu =
        "Datsun on-DO / mi-DO (платформа ВАЗ-2190) использует стандартный OBD-II по CAN/KWP для ECM, но подушки/ЭУР/ЦБКЭ могут требовать нестандартных KWP/CAN адресов."
    override val capabilitySpecs = listOf(
        DiagnosticFunctionSpec(
            functionId = "nissan_uds_ident",
            titleRu = "Идентификация блоков Nissan / Datsun (UDS F187)",
            descriptionRu = "Безопасное чтение номера блока ECM/ABS/BCM по CAN.",
            manufacturer = "Nissan / Datsun",
            compatibleModels = listOf("Datsun on-DO / mi-DO", "Nissan CAN"),
            supportedEcuIds = listOf("7E8", "760", "765"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "ELM327 CAN ISO 15765-4",
            preconditions = listOf("Шина CAN 500 кбод"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.ECU_RESPONDED,
            confidenceHint = "Требует ответа UDS 62 F1 87",
            groupRu = "Фирменное чтение (Nissan / Datsun)",
        ),
        DiagnosticFunctionSpec(
            functionId = "nissan_idle_relearn",
            titleRu = "Обучение холостого хода / дросселя (Nissan / Datsun)",
            descriptionRu = "Калибровочная процедура адаптации дроссельной заслонки.",
            manufacturer = "Nissan / Datsun",
            compatibleModels = listOf("Nissan / Datsun"),
            supportedEcuIds = listOf("7E8"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "Проверенный адаптер",
            preconditions = listOf("Отдельная валидация на автомобиле"),
            operationType = OperationType.SERVICE,
            riskLevel = RiskLevel.MEDIUM,
            verificationMethod = VerificationMethod.DISABLED_SAFETY_LOCK,
            confidenceHint = "Отключено до проверки",
            groupRu = "Сервисные операции (отключено)",
        ),
    )
}

object HavalGwmModule : ManufacturerModule {
    override val id = "haval_gwm"
    override val manufacturerName = "Haval / Great Wall"
    override val supportedProtocols = listOf(
        DiagnosticProtocolKind.STANDARD_OBD2,
        DiagnosticProtocolKind.KWP2000,
        DiagnosticProtocolKind.UDS_ISO14229,
    )
    override val documentedEcus = listOf(
        DocumentedEcuTarget("7E0", "7E8", "EMS — Контроллер двигателя (Delphi / Bosch)", DiagnosticProtocolKind.STANDARD_OBD2, standardObdFallback = true),
        DocumentedEcuTarget("720", "728", "ABS / ESP", DiagnosticProtocolKind.UDS_ISO14229),
    )
    override val adapterLimitationsRu =
        "Ранние Great Wall / Haval H3 (2010–2014) часто работают по K-Line KWP2000; некоторые клоны ELM327 v2.1 имеют проблемы с таймингами KWP2000 5-baud init."
    override val capabilitySpecs = listOf(
        DiagnosticFunctionSpec(
            functionId = "haval_uds_ident",
            titleRu = "Идентификация блоков Haval / GWM (UDS F187)",
            descriptionRu = "Чтение идентификатора ПО и номера детали при работе по CAN.",
            manufacturer = "Haval / Great Wall",
            compatibleModels = listOf("Haval H3 / H5 / H6", "Great Wall Hover H3"),
            supportedEcuIds = listOf("7E8", "728"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "ELM327 CAN (на K-Line не применяется)",
            preconditions = listOf("Активна шина CAN"),
            operationType = OperationType.READ,
            riskLevel = RiskLevel.NONE,
            verificationMethod = VerificationMethod.ECU_RESPONDED,
            confidenceHint = "Требует ответа UDS 62 F1 87",
            groupRu = "Фирменное чтение (Haval / GWM)",
        ),
        DiagnosticFunctionSpec(
            functionId = "haval_sas_calib",
            titleRu = "Калибровка датчика угла руля / адаптации (Haval)",
            descriptionRu = "Сервисная калибровка SAS после регулировки развал-схождения.",
            manufacturer = "Haval / Great Wall",
            compatibleModels = listOf("Haval / GWM CAN"),
            supportedEcuIds = listOf("728"),
            requiredProtocol = DiagnosticProtocolKind.UDS_ISO14229,
            adapterRequirements = "Подтверждённый UDS-адаптер",
            preconditions = listOf("Отдельная валидация"),
            operationType = OperationType.SERVICE,
            riskLevel = RiskLevel.MEDIUM,
            verificationMethod = VerificationMethod.DISABLED_SAFETY_LOCK,
            confidenceHint = "Отключено до проверки",
            groupRu = "Сервисные операции (отключено)",
        ),
    )
}

object ManufacturerModuleRegistry {
    val allModules: List<ManufacturerModule> = listOf(
        UniversalObd2Module,
        VagModule,
        FordModule,
        NissanDatsunModule,
        HavalGwmModule,
    )

    fun forManufacturer(manufacturer: String?): ManufacturerModule? {
        if (manufacturer.isNullOrBlank()) return null
        return allModules.firstOrNull {
            it.manufacturerName.equals(manufacturer, ignoreCase = true) ||
                it.id.equals(manufacturer, ignoreCase = true)
        }
    }

    fun combinedSpecsFor(manufacturer: String?): List<DiagnosticFunctionSpec> {
        val brand = forManufacturer(manufacturer)
        return if (brand != null && brand != UniversalObd2Module) {
            UniversalObd2Module.capabilitySpecs + brand.capabilitySpecs
        } else {
            UniversalObd2Module.capabilitySpecs
        }
    }
}

data class ExtendedDiagnosticsReport(
    val vehicle: VehicleIdentityResult,
    val adapterCapabilities: AdapterCapabilities,
    val activeModuleName: String,
    val discoveredEcus: List<DiscoveredEcu>,
    val evaluatedCapabilities: List<EvaluatedCapability>,
    val catalogVersion: String,
    val incomplete: Boolean,
    val warnings: List<String>,
    val timestampMs: Long,
) {
    val confirmedForNormalUser: List<EvaluatedCapability>
        get() = evaluatedCapabilities.filter { it.visibleToNormalUser && it.status == CapabilityStatus.CONFIRMED_SUPPORTED }
}
